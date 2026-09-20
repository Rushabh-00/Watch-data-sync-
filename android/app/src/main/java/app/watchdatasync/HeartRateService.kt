package app.watchdatasync

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.util.Calendar
import java.util.UUID
import kotlin.math.roundToInt

class HeartRateService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    private var gatt: android.bluetooth.BluetoothGatt? = null
    private var reconnectRunnable: Runnable? = null
    private var watchdogRunnable: Runnable? = null
    private var reconnectAttempt = 0
    private var graphLastAt = 0L
    private var lastHeartRateAt = 0L
    private var connectedAt = 0L
    private var streamRecoveryAttempts = 0
    private var lastNotificationAt = 0L
    private var lastNotifiedBpm: Int? = null
    private var cachedNotificationBpm: Int? = Int.MIN_VALUE
    private var cachedNotificationIcon: Icon? = null
    private var batteryRefreshRunnable: Runnable? = null
    private var pendingTimeSyncWrite = false
    private var timeSyncRequested = false

    private var graphPoints = ArrayList<HeartRatePoint>()
    private var sampleCount = 0L
    private var sum = 0L
    private var min = Int.MAX_VALUE
    private var max = Int.MIN_VALUE

    private var overlayView: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var lastOverlayValue: String? = null

    private val prefs by lazy {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        loadSessionDefaults()
        if (monitoringEnabled()) {
            startForegroundCompat(buildNotification())
            loadOverlayFromPrefs()
            startWatchdog()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SYNC_TIME -> {
                if (monitoringEnabled()) {
                    timeSyncRequested = true
                    if (!runningForeground) startForegroundCompat(buildNotification())
                    startWatchdog()
                    if (gatt == null) {
                        connectSavedDevice()
                    } else {
                        scheduleRequestedTimeSync()
                    }
                }
            }

            ACTION_SET_MONITORING -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                prefs.edit().putBoolean(KEY_NOTIFICATION_ENABLED, enabled).apply()
                if (enabled) {
                    if (!runningForeground) startForegroundCompat(buildNotification())
                    startWatchdog()
                    connectSavedDevice()
                } else {
                    stopMonitoring()
                }
            }

            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                val name = intent.getStringExtra(EXTRA_NAME)
                if (!address.isNullOrBlank()) {
                    val previous = prefs.getString(KEY_ADDRESS, null)
                    saveDevice(address, name)
                    if (previous != address) resetLiveSession()
                    if (monitoringEnabled()) {
                        if (!runningForeground) startForegroundCompat(buildNotification())
                        startWatchdog()
                        connectSavedDevice()
                    }
                }
            }

            ACTION_DISCONNECT -> disconnectUser()
            ACTION_OVERLAY_ON -> {
                prefs.edit().putBoolean(KEY_OVERLAY_VISIBLE, true).apply()
                showOverlay()
                publishOverlayState()
            }
            ACTION_OVERLAY_OFF -> {
                prefs.edit().putBoolean(KEY_OVERLAY_VISIBLE, false).apply()
                hideOverlay()
                publishOverlayState()
            }
            ACTION_OVERLAY_LOCK -> {
                val locked = intent.getBooleanExtra(EXTRA_LOCKED, true)
                prefs.edit().putBoolean(KEY_OVERLAY_LOCKED, locked).apply()
                applyOverlayStyle()
                publishOverlayState()
            }
            ACTION_OVERLAY_SIZE -> {
                val scale = intent
                    .getFloatExtra(EXTRA_SCALE, 1f)
                    .coerceIn(0.70f, 1.60f)
                prefs.edit().putFloat(KEY_OVERLAY_SCALE, scale).apply()
                applyOverlayStyle()
                publishOverlayState()
            }
            ACTION_START, null -> {
                if (monitoringEnabled()) {
                    if (!runningForeground) startForegroundCompat(buildNotification())
                    startWatchdog()

                    // Do not tear down a healthy BLE session every time the app returns
                    // to the foreground. Only connect when this service has no GATT.
                    if (gatt == null) {
                        connectSavedDevice()
                    }
                }
            }
        }

        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun connectSavedDevice() {
        val address = prefs.getString(KEY_ADDRESS, null) ?: run {
            updateStatus("Ready • scan to connect")
            return
        }

        if (!monitoringEnabled()) return

        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            updateStatus("Bluetooth is off")
            scheduleReconnect()
            return
        }

        reconnectRunnable?.let(handler::removeCallbacks)
        reconnectRunnable = null

        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            updateStatus("Saved watch address is unavailable")
            return
        }

        closeGatt()
        updateStatus("Connecting to " + (prefs.getString(KEY_NAME, null) ?: "FT_38093") + "…")

        try {
            gatt = device.connectGatt(
                this,
                false,
                gattCallback,
                android.bluetooth.BluetoothDevice.TRANSPORT_LE,
            )
        } catch (_: SecurityException) {
            updateStatus("Bluetooth permission is required")
            scheduleReconnect()
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendLiveCommand(
        current: android.bluetooth.BluetoothGatt,
        payload: ByteArray,
    ): Boolean {
        val service = current.getService(UUID.fromString(FastrackProtocol.SERVICE_UUID))
            ?: return false
        val command = service.getCharacteristic(
            UUID.fromString(FastrackProtocol.TIME_WRITE_UUID),
        ) ?: return false

        val noResponse =
            command.properties and
                android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0

        command.writeType =
            if (noResponse) {
                android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }

        command.value = payload

        return runCatching {
            current.writeCharacteristic(command)
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun startDynamicHeartRateStream() {
        val current = gatt ?: return
        if (!LiveHeartRateState.snapshot.value.connected || !monitoringEnabled()) return

        // Verified UTE/GloryFit-family sequence: D6 02 selects dynamic HR mode,
        // then E5 11 starts the live E5 11 00 <bpm> stream.
        if (!sendLiveCommand(current, FastrackProtocol.buildDynamicHeartRateModePacket())) {
            updateStatus("Could not start dynamic heart-rate mode")
            return
        }

        handler.postDelayed({
            if (gatt !== current || !monitoringEnabled()) return@postDelayed

            if (!sendLiveCommand(current, FastrackProtocol.buildLiveHeartRateStartPacket())) {
                updateStatus("Could not start live heart-rate stream")
                return@postDelayed
            }

            handler.postDelayed({
                if (
                    gatt === current &&
                    monitoringEnabled() &&
                    timeSyncRequested
                ) {
                    syncTimeInternal()
                }
            }, 350L)
        }, DYNAMIC_HR_START_DELAY_MS)
    }

    @SuppressLint("MissingPermission")
    private fun restartDynamicHeartRateStream() {
        val current = gatt ?: return
        if (!LiveHeartRateState.snapshot.value.connected || !monitoringEnabled()) return

        updateStatus("Re-starting live heart-rate stream…")

        if (!sendLiveCommand(current, FastrackProtocol.buildDynamicHeartRateModePacket())) {
            refreshLiveSubscription()
            return
        }

        handler.postDelayed({
            if (gatt !== current || !monitoringEnabled()) return@postDelayed

            if (!sendLiveCommand(current, FastrackProtocol.buildLiveHeartRateStartPacket())) {
                refreshLiveSubscription()
            }
        }, DYNAMIC_HR_START_DELAY_MS)
    }

    @SuppressLint("MissingPermission")
    private fun configureNotifications(current: android.bluetooth.BluetoothGatt): Boolean {
        val service = current.getService(UUID.fromString(FastrackProtocol.SERVICE_UUID))
        val live = service?.getCharacteristic(UUID.fromString(FastrackProtocol.LIVE_DATA_UUID))
        val time = service?.getCharacteristic(UUID.fromString(FastrackProtocol.TIME_WRITE_UUID))

        if (service == null || live == null || time == null) {
            updateStatus("FT_38093 service not available")
            return false
        }

        val notify =
            live.properties and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val indicate =
            live.properties and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE != 0

        if (!notify && !indicate) {
            updateStatus("Live heart-rate channel is not notifiable")
            return false
        }

        if (!current.setCharacteristicNotification(live, true)) {
            updateStatus("Could not enable live heart rate")
            return false
        }

        val descriptor = live.getDescriptor(UUID.fromString(CCCD_UUID))
            ?: run {
                updateStatus("Live heart-rate CCCD not found")
                return false
            }

        descriptor.value =
            if (notify) {
                android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                android.bluetooth.BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }

        return try {
            current.writeDescriptor(descriptor)
        } catch (_: SecurityException) {
            updateStatus("Bluetooth permission is required")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshLiveSubscription() {
        val current = gatt ?: return
        if (!LiveHeartRateState.snapshot.value.connected) return
        if (!configureNotifications(current)) {
            closeGatt()
            scheduleReconnect()
            return
        }
        updateStatus("Refreshing live heart-rate stream…")
    }

    private fun startWatchdog() {
        watchdogRunnable?.let(handler::removeCallbacks)
        watchdogRunnable = null

        if (!monitoringEnabled()) return

        val task = object : Runnable {
            override fun run() {
                if (!monitoringEnabled()) {
                    watchdogRunnable = null
                    return
                }

                val now = System.currentTimeMillis()
                val snapshot = LiveHeartRateState.snapshot.value
                val baseline = maxOf(lastHeartRateAt, connectedAt)
                val stale = snapshot.connected &&
                    baseline > 0L &&
                    now - baseline >= LIVE_STALE_MS

                if (stale) {
                    streamRecoveryAttempts += 1
                    when (streamRecoveryAttempts) {
                        1 -> restartDynamicHeartRateStream()
                        2 -> {
                            updateStatus("Refreshing live heart-rate subscription…")
                            refreshLiveSubscription()
                        }
                        else -> {
                            streamRecoveryAttempts = 0
                            updateStatus("Live stream unavailable • reconnecting…")
                            LiveHeartRateState.set(
                                snapshot.copy(
                                    connected = false,
                                ),
                            )
                            closeGatt()
                            scheduleReconnect()
                        }
                    }
                }

                handler.postDelayed(this, LIVE_WATCHDOG_MS)
            }
        }

        watchdogRunnable = task
        handler.postDelayed(task, LIVE_WATCHDOG_MS)
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(
            current: android.bluetooth.BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            if (gatt !== current) {
                runCatching { current.close() }
                return
            }

            if (
                status == android.bluetooth.BluetoothGatt.GATT_SUCCESS &&
                newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED
            ) {
                connectedAt = System.currentTimeMillis()
                lastHeartRateAt = 0L
                streamRecoveryAttempts = 0

                LiveHeartRateState.set(
                    LiveHeartRateState.snapshot.value.copy(
                        connected = true,
                        status = "Connected • discovering services…",
                    ),
                )
                if (!current.discoverServices()) {
                    updateStatus("Service discovery could not start")
                    scheduleReconnect()
                }
                return
            }

            connectedAt = 0L

            LiveHeartRateState.set(
                LiveHeartRateState.snapshot.value.copy(
                    connected = false,
                    status = "Disconnected • reconnecting…",
                ),
            )

            if (gatt === current) {
                gatt = null
            }
            runCatching { current.close() }
            scheduleReconnect()
        }

        override fun onServicesDiscovered(
            current: android.bluetooth.BluetoothGatt,
            status: Int,
        ) {
            if (gatt !== current) return

            if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                updateStatus("Service discovery failed")
                scheduleReconnect()
                return
            }

            reconnectAttempt = 0
            if (!configureNotifications(current)) {
                scheduleReconnect()
                return
            }

            updateStatus("Live heart rate active")
        }

        override fun onDescriptorWrite(
            current: android.bluetooth.BluetoothGatt,
            descriptor: android.bluetooth.BluetoothGattDescriptor,
            status: Int,
        ) {
            if (gatt !== current) return

            if (
                descriptor.characteristic.uuid.toString()
                    .equals(FastrackProtocol.LIVE_DATA_UUID, ignoreCase = true)
            ) {
                if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                    updateStatus("Live heart rate active")
                    requestBatteryLevel()
                    startBatteryPolling()
                    startDynamicHeartRateStream()
                } else {
                    updateStatus("Live heart-rate setup failed")
                    scheduleReconnect()
                }
            }
        }

        override fun onCharacteristicChanged(
            current: android.bluetooth.BluetoothGatt,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
        ) {
            if (gatt !== current) return
            handleWatchData(characteristic.value)
        }

        override fun onCharacteristicChanged(
            current: android.bluetooth.BluetoothGatt,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (gatt !== current) return
            handleWatchData(value)
        }

        override fun onCharacteristicWrite(
            current: android.bluetooth.BluetoothGatt,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (gatt !== current) return

            if (
                !characteristic.uuid.toString()
                    .equals(FastrackProtocol.TIME_WRITE_UUID, ignoreCase = true)
            ) {
                return
            }

            if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                if (pendingTimeSyncWrite) {
                    pendingTimeSyncWrite = false
                    LiveHeartRateState.set(
                        LiveHeartRateState.snapshot.value.copy(
                            status = "Live heart rate active • time synced",
                        ),
                    )
                    timeSyncRequested = false
                    updateNotification(force = true)
                }
            } else {
                pendingTimeSyncWrite = false
                updateStatus("Time sync failed")
                if (timeSyncRequested) {
                    handler.postDelayed({
                        scheduleRequestedTimeSync()
                    }, 1000L)
                }
            }
        }
    }

    private fun handleWatchData(packet: ByteArray) {
        FastrackProtocol.decodeBattery(packet)?.let { battery ->
            val current = LiveHeartRateState.snapshot.value
            if (
                current.batteryPercent != battery.percent ||
                current.batteryCharging != battery.charging
            ) {
                LiveHeartRateState.set(
                    current.copy(
                        batteryPercent = battery.percent,
                        batteryCharging = battery.charging,
                    ),
                )
                updateNotification(force = true)
            }
        }

        handleHeartRate(packet)
    }

    private fun handleHeartRate(packet: ByteArray) {
        val bpm = FastrackProtocol.decodeLiveHeartRate(packet) ?: return

        val now = System.currentTimeMillis()
        lastHeartRateAt = now
        streamRecoveryAttempts = 0
        sampleCount += 1
        sum += bpm
        min = minOf(min, bpm)
        max = maxOf(max, bpm)

        var graphChanged = false
        if (now - graphLastAt >= GRAPH_SAMPLE_MS || graphPoints.isEmpty()) {
            graphPoints.add(HeartRatePoint(now, bpm))
            if (graphPoints.size > MAX_GRAPH_POINTS) {
                graphPoints.removeAt(0)
            }
            graphLastAt = now
            graphChanged = true
        }

        val average = (sum.toDouble() / sampleCount).roundToInt()
        val current = LiveHeartRateState.snapshot.value

        LiveHeartRateState.set(
            current.copy(
                bpm = bpm,
                averageBpm = average,
                minimumBpm = min.takeIf { it != Int.MAX_VALUE },
                maximumBpm = max.takeIf { it != Int.MIN_VALUE },
                graph = if (graphChanged) graphPoints.toList() else current.graph,
                connected = true,
                status = "Live heart rate active",
            ),
        )

        updateNotification()
        updateOverlay(bpm)
    }

    @SuppressLint("MissingPermission")
    private fun syncTimeInternal() {
        val current = gatt ?: return
        val service = current.getService(UUID.fromString(FastrackProtocol.SERVICE_UUID)) ?: return
        val characteristic =
            service.getCharacteristic(UUID.fromString(FastrackProtocol.TIME_WRITE_UUID)) ?: return

        characteristic.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = FastrackProtocol.buildTimeSyncPacket(Calendar.getInstance())
        pendingTimeSyncWrite = true

        runCatching {
            if (!current.writeCharacteristic(characteristic)) {
                pendingTimeSyncWrite = false
                updateStatus("Time write was rejected")
            }
        }.onFailure {
            pendingTimeSyncWrite = false
            updateStatus("Time sync failed")
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestBatteryLevel() {
        val current = gatt ?: return
        if (!LiveHeartRateState.snapshot.value.connected || !monitoringEnabled()) return

        sendLiveCommand(current, FastrackProtocol.buildBatteryRequestPacket())
    }

    private fun startBatteryPolling() {
        batteryRefreshRunnable?.let(handler::removeCallbacks)
        batteryRefreshRunnable = object : Runnable {
            override fun run() {
                if (!monitoringEnabled() || !LiveHeartRateState.snapshot.value.connected) {
                    batteryRefreshRunnable = null
                    return
                }

                requestBatteryLevel()
                handler.postDelayed(this, BATTERY_REFRESH_MS)
            }
        }
        handler.postDelayed(batteryRefreshRunnable!!, BATTERY_REFRESH_MS)
    }


    private fun resetLiveSession() {
        batteryRefreshRunnable?.let(handler::removeCallbacks)
        batteryRefreshRunnable = null
        pendingTimeSyncWrite = false
        timeSyncRequested = false
        graphPoints = ArrayList()
        graphLastAt = 0L
        lastHeartRateAt = 0L
        connectedAt = 0L
        streamRecoveryAttempts = 0
        sampleCount = 0L
        sum = 0L
        min = Int.MAX_VALUE
        max = Int.MIN_VALUE

        LiveHeartRateState.set(
            LiveHeartRateSnapshot(
                connected = false,
                deviceName = prefs.getString(KEY_NAME, "FT_38093"),
                status = "Connecting…",
                notificationEnabled = monitoringEnabled(),
                overlayLocked = prefs.getBoolean(KEY_OVERLAY_LOCKED, false),
                overlayScale = prefs.getFloat(KEY_OVERLAY_SCALE, 1f),
            ),
        )
    }

    private fun loadSessionDefaults() {
        LiveHeartRateState.set(
            LiveHeartRateSnapshot(
                deviceName = prefs.getString(KEY_NAME, null),
                status = if (prefs.getString(KEY_ADDRESS, null).isNullOrBlank()) {
                    "Ready • scan to connect"
                } else {
                    "Saved watch • auto-connect enabled"
                },
                overlayVisible = prefs.getBoolean(KEY_OVERLAY_VISIBLE, false),
                overlayLocked = prefs.getBoolean(KEY_OVERLAY_LOCKED, false),
                overlayScale = prefs.getFloat(KEY_OVERLAY_SCALE, 1f),
                notificationEnabled = monitoringEnabled(),
            ),
        )
    }

    private fun scheduleRequestedTimeSync() {
        val current = gatt ?: return
        if (!timeSyncRequested || !monitoringEnabled()) return

        handler.postDelayed({
            if (
                gatt !== current ||
                !timeSyncRequested ||
                !monitoringEnabled()
            ) {
                return@postDelayed
            }

            val service = current.getService(
                UUID.fromString(FastrackProtocol.SERVICE_UUID),
            )
            val characteristic = service?.getCharacteristic(
                UUID.fromString(FastrackProtocol.TIME_WRITE_UUID),
            )

            if (characteristic != null) {
                syncTimeInternal()
            } else {
                scheduleRequestedTimeSync()
            }
        }, 350L)
    }

    private fun updateStatus(value: String) {
        val current = LiveHeartRateState.snapshot.value
        LiveHeartRateState.set(current.copy(status = value))
        updateNotification(force = true)
    }

    private fun saveDevice(address: String, name: String?) {
        prefs.edit()
            .putString(KEY_ADDRESS, address)
            .putString(KEY_NAME, name?.takeIf { it.isNotBlank() } ?: "FT_38093")
            .apply()
    }

    private fun disconnectUser() {
        reconnectRunnable?.let(handler::removeCallbacks)
        reconnectRunnable = null
        batteryRefreshRunnable?.let(handler::removeCallbacks)
        batteryRefreshRunnable = null
        pendingTimeSyncWrite = false
        timeSyncRequested = false
        closeGatt()
        connectedAt = 0L
        streamRecoveryAttempts = 0
        LiveHeartRateState.set(
            LiveHeartRateState.snapshot.value.copy(
                connected = false,
                status = "Disconnected",
            ),
        )
        updateNotification()
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val current = gatt
        gatt = null
        runCatching { current?.disconnect() }
        runCatching { current?.close() }
    }

    private fun scheduleReconnect() {
        if (!monitoringEnabled() || prefs.getString(KEY_ADDRESS, null).isNullOrBlank()) return

        reconnectRunnable?.let(handler::removeCallbacks)
        reconnectAttempt = minOf(reconnectAttempt + 1, 6)

        val delay = minOf(
            RECONNECT_BASE_MS * (1L shl (reconnectAttempt - 1)),
            RECONNECT_MAX_MS,
        )

        reconnectRunnable = Runnable { connectSavedDevice() }
        handler.postDelayed(reconnectRunnable!!, delay)

        LiveHeartRateState.set(
            LiveHeartRateState.snapshot.value.copy(
                status = "Disconnected • reconnecting in " + (delay / 1000L) + "s",
            ),
        )
        updateNotification()
    }

    private fun buildNotification(): Notification {
        val snapshot = LiveHeartRateState.snapshot.value
        val bpmText = snapshot.bpm?.let { "$it bpm" } ?: "No HR yet"

        val stats = buildString {
            snapshot.averageBpm?.let { append("Avg ").append(it).append(" • ") }
            snapshot.minimumBpm?.let { append("Min ").append(it).append(" • ") }
            snapshot.maximumBpm?.let { append("Max ").append(it) }

            snapshot.batteryPercent?.let {
                if (isNotEmpty()) append(" • ")
                append("Battery ").append(it).append("%")
                if (snapshot.batteryCharging == true) append(" • Charging")
            }

            if (isEmpty()) append(snapshot.status)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingIntentFlags(),
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(renderNotificationIcon(snapshot.bpm))
            .setContentTitle(bpmText)
            .setContentText(stats)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        return builder.build()
    }

    private var runningForeground = false

    private fun renderNotificationIcon(bpm: Int?): Icon {
        if (cachedNotificationBpm == bpm && cachedNotificationIcon != null) {
            return cachedNotificationIcon!!
        }

        val density = resources.displayMetrics.density
        val size = (48f * density).toInt().coerceAtLeast(48)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = 40f * density
        }

        val value = bpm?.toString() ?: "♥"
        val maxWidth = size * 0.92f
        val measured = paint.measureText(value)
        if (measured > maxWidth) {
            paint.textSize *= maxWidth / measured
        }

        canvas.drawText(value, size / 2f, size * 0.64f, paint)

        return Icon.createWithBitmap(bitmap).also {
            cachedNotificationBpm = bpm
            cachedNotificationIcon = it
        }
    }

    private fun updateNotification(force: Boolean = false) {
        if (!runningForeground || !monitoringEnabled()) return

        val snapshot = LiveHeartRateState.snapshot.value
        val now = System.currentTimeMillis()
        if (!force && snapshot.bpm == lastNotifiedBpm) return
        if (!force && now - lastNotificationAt < NOTIFICATION_UPDATE_MS) return

        lastNotificationAt = now
        lastNotifiedBpm = snapshot.bpm

        handler.post {
            if (!runningForeground || !monitoringEnabled()) return@post
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (runningForeground) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        runningForeground = true
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Live heart rate",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Ongoing live FT_38093 heart-rate connection"
            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            setSound(
                sound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(false)
            setShowBadge(true)
        }

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun pendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }

    private fun showOverlay() {
        if (
            overlayView != null ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            !Settings.canDrawOverlays(this)
        ) {
            return
        }

        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        val scale = prefs.getFloat(KEY_OVERLAY_SCALE, 1f)
        val text = TextView(this).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 80f * scale
                setColor(Color.argb(48, 10, 16, 28))
                setStroke((2f * scale).roundToInt().coerceAtLeast(1), Color.rgb(90, 220, 255))
            }
            setOnTouchListener(OverlayDragListener(manager, this))
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = prefs.getInt(KEY_OVERLAY_X, 24)
            y = prefs.getInt(KEY_OVERLAY_Y, 120)
        }

        overlayView = text
        overlayParams = params
        lastOverlayValue = null
        applyOverlayStyle()

        runCatching {
            manager.addView(text, params)
        }.onFailure {
            overlayView = null
            overlayParams = null
        }
    }

    private fun applyOverlayStyle() {
        val view = overlayView ?: return
        val scale = prefs.getFloat(KEY_OVERLAY_SCALE, 1f)
        view.textSize = (17f * scale).coerceIn(12f, 28f)
        view.setPadding(
            (20f * scale).roundToInt(),
            (10f * scale).roundToInt(),
            (20f * scale).roundToInt(),
            (10f * scale).roundToInt(),
        )
        (view.background as? GradientDrawable)?.apply {
            cornerRadius = 80f * scale
            setStroke((2f * scale).roundToInt().coerceAtLeast(1), Color.rgb(90, 220, 255))
        }
        updateOverlay()

        val params = overlayParams ?: return
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(view, params)
        }
    }

    private fun updateOverlay(bpm: Int? = LiveHeartRateState.snapshot.value.bpm) {
        val value = bpm?.let { "$it bpm" } ?: "— bpm"
        if (value == lastOverlayValue) return
        lastOverlayValue = value

        if (Looper.myLooper() == Looper.getMainLooper()) {
            overlayView?.text = value
        } else {
            handler.post {
                overlayView?.text = value
            }
        }
    }

    private fun publishOverlayState() {
        val current = LiveHeartRateState.snapshot.value
        LiveHeartRateState.set(
            current.copy(
                overlayVisible = prefs.getBoolean(KEY_OVERLAY_VISIBLE, false),
                overlayLocked = prefs.getBoolean(KEY_OVERLAY_LOCKED, false),
                overlayScale = prefs.getFloat(KEY_OVERLAY_SCALE, 1f),
            ),
        )
    }

    private fun loadOverlayFromPrefs() {
        publishOverlayState()
        if (prefs.getBoolean(KEY_OVERLAY_VISIBLE, false)) {
            showOverlay()
        }
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching { manager.removeView(view) }
        overlayView = null
        overlayParams = null
        lastOverlayValue = null
    }

    private fun stopMonitoring() {
        reconnectRunnable?.let(handler::removeCallbacks)
        reconnectRunnable = null
        watchdogRunnable?.let(handler::removeCallbacks)
        watchdogRunnable = null

        closeGatt()
        hideOverlay()
        if (runningForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            runningForeground = false
        }

        LiveHeartRateState.set(
            LiveHeartRateState.snapshot.value.copy(
                connected = false,
                status = "Monitoring off",
                notificationEnabled = false,
                overlayVisible = false,
            ),
        )
        stopSelf()
    }

    private fun monitoringEnabled(): Boolean =
        prefs.getBoolean(KEY_NOTIFICATION_ENABLED, true)

    override fun onDestroy() {
        reconnectRunnable?.let(handler::removeCallbacks)
        watchdogRunnable?.let(handler::removeCallbacks)
        batteryRefreshRunnable?.let(handler::removeCallbacks)
        reconnectRunnable = null
        batteryRefreshRunnable = null
        pendingTimeSyncWrite = false
        watchdogRunnable = null
        hideOverlay()
        closeGatt()
        runningForeground = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private class OverlayDragListener(
        private val manager: WindowManager,
        private val view: View,
    ) : View.OnTouchListener {
        private var downX = 0
        private var downY = 0
        private var startX = 0
        private var startY = 0

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val context = v.context
            val locked = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_OVERLAY_LOCKED, false)

            if (locked) return true

            val params = v.layoutParams as WindowManager.LayoutParams

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX.toInt()
                    downY = event.rawY.toInt()
                    startX = params.x
                    startY = params.y
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - downX
                    val dy = event.rawY.toInt() - downY
                    params.x = (startX - dx).coerceAtLeast(0)
                    params.y = (startY + dy).coerceAtLeast(0)
                    runCatching { manager.updateViewLayout(view, params) }
                    return true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putInt(KEY_OVERLAY_X, params.x)
                        .putInt(KEY_OVERLAY_Y, params.y)
                        .apply()
                    return true
                }
            }
            return true
        }
    }

    companion object {
        const val ACTION_START = "app.watchdatasync.action.START"
        const val ACTION_CONNECT = "app.watchdatasync.action.CONNECT"
        const val ACTION_DISCONNECT = "app.watchdatasync.action.DISCONNECT"
        const val ACTION_OVERLAY_ON = "app.watchdatasync.action.OVERLAY_ON"
        const val ACTION_OVERLAY_OFF = "app.watchdatasync.action.OVERLAY_OFF"
        const val ACTION_OVERLAY_LOCK = "app.watchdatasync.action.OVERLAY_LOCK"
        const val ACTION_OVERLAY_SIZE = "app.watchdatasync.action.OVERLAY_SIZE"
        const val ACTION_SET_MONITORING = "app.watchdatasync.action.SET_MONITORING"
        const val ACTION_SYNC_TIME = "app.watchdatasync.action.SYNC_TIME"

        const val EXTRA_ADDRESS = "extra_address"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_ENABLED = "extra_enabled"
        const val EXTRA_LOCKED = "extra_locked"
        const val EXTRA_SCALE = "extra_scale"

        const val PREFS = "watch_preferences"
        const val KEY_ADDRESS = "bound_watch_address"
        const val KEY_NAME = "bound_watch_name"
        const val KEY_NOTIFICATION_ENABLED = "notification_enabled"
        const val KEY_OVERLAY_VISIBLE = "overlay_visible"
        const val KEY_OVERLAY_LOCKED = "overlay_locked"
        const val KEY_OVERLAY_SCALE = "overlay_scale"
        const val KEY_OVERLAY_X = "overlay_x"
        const val KEY_OVERLAY_Y = "overlay_y"

        private const val CHANNEL_ID = "live_heart_rate_v2"
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        private const val NOTIFICATION_ID = 4101

        private const val MAX_GRAPH_POINTS = 5400
        private const val GRAPH_SAMPLE_MS = 2000L
        private const val LIVE_WATCHDOG_MS = 15000L
        private const val LIVE_STALE_MS = 12000L
        private const val NOTIFICATION_UPDATE_MS = 2000L
        private const val BATTERY_REFRESH_MS = 5 * 60 * 1000L
        private const val DYNAMIC_HR_START_DELAY_MS = 1500L

        private const val RECONNECT_BASE_MS = 2000L
        private const val RECONNECT_MAX_MS = 30000L

        fun start(context: Context) {
            val intent = Intent(context, HeartRateService::class.java)
                .setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun connect(context: Context, address: String, name: String?) {
            val intent = Intent(context, HeartRateService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_ADDRESS, address)
                .putExtra(EXTRA_NAME, name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun disconnect(context: Context) {
            context.startService(
                Intent(context, HeartRateService::class.java)
                    .setAction(ACTION_DISCONNECT),
            )
        }

        fun syncTime(context: Context) {
            context.startService(
                Intent(context, HeartRateService::class.java)
                    .setAction(ACTION_SYNC_TIME),
            )
        }

        fun overlayOn(context: Context) {
            context.startService(
                Intent(context, HeartRateService::class.java)
                    .setAction(ACTION_OVERLAY_ON),
            )
        }

        fun overlayOff(context: Context) {
            context.startService(
                Intent(context, HeartRateService::class.java)
                    .setAction(ACTION_OVERLAY_OFF),
            )
        }

        fun overlayLock(context: Context, locked: Boolean) {
            context.startService(
                Intent(context, HeartRateService::class.java)
                    .setAction(ACTION_OVERLAY_LOCK)
                    .putExtra(EXTRA_LOCKED, locked),
            )
        }

        fun overlaySize(context: Context, scale: Float) {
            context.startService(
                Intent(context, HeartRateService::class.java)
                    .setAction(ACTION_OVERLAY_SIZE)
                    .putExtra(EXTRA_SCALE, scale),
            )
        }

        fun setMonitoringEnabled(context: Context, enabled: Boolean) {
            val intent = Intent(context, HeartRateService::class.java)
                .setAction(ACTION_SET_MONITORING)
                .putExtra(EXTRA_ENABLED, enabled)

            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
