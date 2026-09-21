package app.watchdatasync

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    private var lowBatteryAlerted = false
    private var batteryRefreshRunnable: Runnable? = null
    private var rssiRefreshRunnable: Runnable? = null
    private var timeSyncRequested = false
    private var lastStreamRecoveryAt = 0L

    private data class BleWriteRequest(
        val gatt: android.bluetooth.BluetoothGatt,
        val payload: ByteArray,
        val onComplete: (Boolean) -> Unit,
    )

    private val bleWriteQueue = ArrayDeque<BleWriteRequest>()
    private var activeBleWrite: BleWriteRequest? = null
    private var activeWriteNoResponse = false
    private var writeTimeoutRunnable: Runnable? = null

    // Bounded in-memory ring buffer: never written to disk/database.
    private val graphPoints = ArrayDeque<HeartRatePoint>(MAX_GRAPH_POINTS)
    private val longGraphPoints = ArrayDeque<HeartRatePoint>(MAX_LONG_GRAPH_POINTS)
    private var sampleCount = 0L
    private var sum = 0L
    private var min = Int.MAX_VALUE
    private var max = Int.MIN_VALUE

    private enum class OverlayDisplayMode {
        NORMAL,
        FULLSCREEN,
    }

    private var overlayView: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayOrientation = Configuration.ORIENTATION_UNDEFINED
    private var overlayDisplayMode = OverlayDisplayMode.NORMAL
    private var lastOverlayValue: String? = null

    private val prefs by lazy {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        migratePreferences()
        createNotificationChannels()
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

            ACTION_SET_NOTIFICATION -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                prefs.edit().putBoolean(KEY_NOTIFICATION_ENABLED, enabled).apply()
                publishNotificationState()

                if (!enabled) {
                    lowBatteryAlerted = false
                    prefs.edit().putBoolean(KEY_LOW_BATTERY_ALERTED, false).apply()
                    getSystemService(NotificationManager::class.java)
                        .cancel(LOW_BATTERY_NOTIFICATION_ID)
                }

                // Recreate the foreground notification so Android drops the old
                // active-channel notification instead of leaving it visible.
                refreshForegroundNotification(forceChannelSwitch = true)

                if (enabled) {
                    val snapshot = LiveHeartRateState.snapshot.value
                    evaluateLowBatteryAlert(
                        snapshot.batteryPercent,
                        snapshot.batteryCharging,
                    )
                }
            }

            ACTION_SET_LOW_BATTERY_ALERT -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                prefs.edit().putBoolean(KEY_LOW_BATTERY_ALERT_ENABLED, enabled).apply()
                publishLowBatteryAlertState()
                if (enabled) {
                    val snapshot = LiveHeartRateState.snapshot.value
                    evaluateLowBatteryAlert(
                        snapshot.batteryPercent,
                        snapshot.batteryCharging,
                    )
                }
            }

            ACTION_SET_MONITORING -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                prefs.edit().putBoolean(KEY_BACKGROUND_MONITORING_ENABLED, enabled).apply()
                publishMonitoringState()
                if (enabled) {
                    if (!runningForeground) startForegroundCompat(buildNotification())
                    loadOverlayFromPrefs()
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
            ACTION_OVERLAY_PRESET -> {
                val preset = overlayPositionPresetFromKey(
                    intent.getStringExtra(EXTRA_POSITION_PRESET),
                )
                prefs.edit().putString(KEY_OVERLAY_PRESET, preset.key).apply()
                applyOverlayPreset(preset)
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
    private fun enqueueLiveCommand(
        current: android.bluetooth.BluetoothGatt,
        payload: ByteArray,
        onComplete: (Boolean) -> Unit = {},
    ): Boolean {
        if (gatt !== current || !monitoringEnabled()) return false

        bleWriteQueue.addLast(
            BleWriteRequest(
                gatt = current,
                payload = payload,
                onComplete = onComplete,
            ),
        )
        pumpBleWriteQueue()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun pumpBleWriteQueue() {
        if (activeBleWrite != null) return

        val current = gatt ?: return
        while (bleWriteQueue.isNotEmpty()) {
            val next = bleWriteQueue.removeFirst()
            if (next.gatt !== current || gatt !== current) {
                next.onComplete(false)
                continue
            }

            val service = current.getService(UUID.fromString(FastrackProtocol.SERVICE_UUID))
            val command = service?.getCharacteristic(
                UUID.fromString(FastrackProtocol.TIME_WRITE_UUID),
            )
            if (command == null) {
                next.onComplete(false)
                continue
            }

            val canWrite = command.properties and
                android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            val canWriteNoResponse = command.properties and
                android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0

            if (!canWrite && !canWriteNoResponse) {
                next.onComplete(false)
                continue
            }

            activeBleWrite = next
            activeWriteNoResponse = !canWrite
            command.writeType = if (canWrite) {
                android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            command.value = next.payload

            val accepted = runCatching {
                current.writeCharacteristic(command)
            }.getOrDefault(false)

            if (!accepted) {
                completeBleWrite(false)
                continue
            }

            val timeout = if (activeWriteNoResponse) {
                NO_RESPONSE_WRITE_GAP_MS
            } else {
                WRITE_RESPONSE_TIMEOUT_MS
            }

            writeTimeoutRunnable = Runnable {
                if (activeBleWrite?.gatt !== current) return@Runnable
                if (activeWriteNoResponse) {
                    completeBleWrite(true)
                } else {
                    completeBleWrite(false)
                    closeGatt()
                    scheduleReconnect()
                }
            }
            handler.postDelayed(writeTimeoutRunnable!!, timeout)
            return
        }
    }

    private fun completeBleWrite(success: Boolean) {
        writeTimeoutRunnable?.let(handler::removeCallbacks)
        writeTimeoutRunnable = null

        val request = activeBleWrite ?: return
        activeBleWrite = null
        activeWriteNoResponse = false
        request.onComplete(success)
        pumpBleWriteQueue()
    }

    private fun cancelBleWrites() {
        writeTimeoutRunnable?.let(handler::removeCallbacks)
        writeTimeoutRunnable = null
        activeBleWrite = null
        activeWriteNoResponse = false
        bleWriteQueue.clear()
    }

    @SuppressLint("MissingPermission")
    private fun startDynamicHeartRateStream() {
        val current = gatt ?: return
        if (!LiveHeartRateState.snapshot.value.connected || !monitoringEnabled()) return

        val queued = enqueueLiveCommand(
            current,
            FastrackProtocol.buildDynamicHeartRateModePacket(),
        ) { success ->
            if (!success) {
                updateStatus("Could not start dynamic heart-rate mode")
                return@enqueueLiveCommand
            }

            handler.postDelayed({
                if (gatt !== current || !monitoringEnabled()) return@postDelayed

                enqueueLiveCommand(
                    current,
                    FastrackProtocol.buildLiveHeartRateStartPacket(),
                ) { started ->
                    if (!started) {
                        updateStatus("Could not start live heart-rate stream")
                        return@enqueueLiveCommand
                    }

                    if (timeSyncRequested) {
                        scheduleRequestedTimeSync()
                    }
                }
            }, DYNAMIC_HR_START_DELAY_MS)
        }

        if (!queued) {
            updateStatus("Could not queue dynamic heart-rate mode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun restartDynamicHeartRateStream() {
        val current = gatt ?: return
        if (!LiveHeartRateState.snapshot.value.connected || !monitoringEnabled()) return

        updateStatus("Re-starting live heart-rate stream…")

        enqueueLiveCommand(
            current,
            FastrackProtocol.buildDynamicHeartRateModePacket(),
        ) { success ->
            if (!success) {
                refreshLiveSubscription()
                return@enqueueLiveCommand
            }

            handler.postDelayed({
                if (gatt !== current || !monitoringEnabled()) return@postDelayed

                enqueueLiveCommand(
                    current,
                    FastrackProtocol.buildLiveHeartRateStartPacket(),
                ) { started ->
                    if (!started) {
                        refreshLiveSubscription()
                    }
                }
            }, DYNAMIC_HR_START_DELAY_MS)
        }
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

                if (
                    stale &&
                    now - lastStreamRecoveryAt >= RECOVERY_COOLDOWN_MS
                ) {
                    lastStreamRecoveryAt = now
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
            rssiRefreshRunnable?.let(handler::removeCallbacks)
            rssiRefreshRunnable = null

            LiveHeartRateState.set(
                LiveHeartRateState.snapshot.value.copy(
                    connected = false,
                    rssi = null,
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
                    startRssiPolling()
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
            if (gatt !== current || activeBleWrite?.gatt !== current) return
            if (activeWriteNoResponse) return

            completeBleWrite(
                status == android.bluetooth.BluetoothGatt.GATT_SUCCESS,
            )
        }

        override fun onReadRemoteRssi(
            current: android.bluetooth.BluetoothGatt,
            rssi: Int,
            status: Int,
        ) {
            if (gatt !== current || status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                return
            }

            val snapshot = LiveHeartRateState.snapshot.value
            LiveHeartRateState.set(
                snapshot.copy(rssi = rssi),
            )
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
                evaluateLowBatteryAlert(battery.percent, battery.charging)
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
        lastStreamRecoveryAt = 0L
        LiveHeartRateState.setLiveBpm(bpm, now)
        sampleCount += 1
        sum += bpm
        min = minOf(min, bpm)
        max = maxOf(max, bpm)

        var graphChanged = false
        if (now - graphLastAt >= GRAPH_SAMPLE_MS || graphPoints.isEmpty()) {
            graphPoints.addLast(HeartRatePoint(now, bpm))
            if (graphPoints.size > MAX_GRAPH_POINTS) {
                graphPoints.removeFirst()
            }
            graphLastAt = now
            graphChanged = true
        }

        val longGraphChanged = longGraphPoints.isEmpty() ||
            now - (longGraphPoints.lastOrNull()?.timestamp ?: 0L) >= LONG_GRAPH_SAMPLE_MS

        if (longGraphChanged) {
            longGraphPoints.addLast(HeartRatePoint(now, bpm))
            if (longGraphPoints.size > MAX_LONG_GRAPH_POINTS) {
                longGraphPoints.removeFirst()
            }
        }

        val average = (sum.toDouble() / sampleCount).roundToInt()
        val current = LiveHeartRateState.snapshot.value

        if (graphChanged || longGraphChanged) {
            LiveHeartRateState.set(
                current.copy(
                    bpm = bpm,
                    averageBpm = average,
                    minimumBpm = min.takeIf { it != Int.MAX_VALUE },
                    maximumBpm = max.takeIf { it != Int.MIN_VALUE },
                    graph = if (graphChanged) graphPoints.toList() else current.graph,
                    longGraph = if (longGraphChanged) {
                        longGraphPoints.toList()
                    } else {
                        current.longGraph
                    },
                    connected = true,
                    status = "Live heart rate active",
                ),
            )
        }

        updateNotification()
        updateOverlay(bpm)
    }

    private fun syncTimeInternal() {
        val current = gatt ?: return
        if (!timeSyncRequested || !monitoringEnabled()) return

        enqueueLiveCommand(
            current,
            FastrackProtocol.buildTimeSyncPacket(Calendar.getInstance()),
        ) { success ->
            if (success) {
                LiveHeartRateState.set(
                    LiveHeartRateState.snapshot.value.copy(
                        status = "Live heart rate active • time synced",
                    ),
                )
                timeSyncRequested = false
                updateNotification(force = true)
            } else if (timeSyncRequested) {
                updateStatus("Time sync failed")
                handler.postDelayed({
                    scheduleRequestedTimeSync()
                }, 1000L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestBatteryLevel() {
        val current = gatt ?: return
        if (!LiveHeartRateState.snapshot.value.connected || !monitoringEnabled()) return

        enqueueLiveCommand(current, FastrackProtocol.buildBatteryRequestPacket())
    }

    private fun startBatteryPolling() {
        batteryRefreshRunnable?.let(handler::removeCallbacks)
        batteryRefreshRunnable = object : Runnable {
            override fun run() {
                val snapshot = LiveHeartRateState.snapshot.value
                if (!monitoringEnabled() || !snapshot.connected) {
                    batteryRefreshRunnable = null
                    return
                }

                requestBatteryLevel()

                val delay = when {
                    snapshot.batteryCharging == true -> BATTERY_REFRESH_CHARGING_MS
                    (snapshot.batteryPercent ?: 100) <= LOW_BATTERY_REARM -> {
                        BATTERY_REFRESH_LOW_MS
                    }
                    else -> BATTERY_REFRESH_NORMAL_MS
                }
                handler.postDelayed(this, delay)
            }
        }
        handler.post(batteryRefreshRunnable!!)
    }

    private fun startRssiPolling() {
        rssiRefreshRunnable?.let(handler::removeCallbacks)
        rssiRefreshRunnable = object : Runnable {
            override fun run() {
                if (!monitoringEnabled() || !LiveHeartRateState.snapshot.value.connected) {
                    rssiRefreshRunnable = null
                    return
                }

                val current = gatt
                if (current == null) {
                    rssiRefreshRunnable = null
                    return
                }

                runCatching {
                    current.readRemoteRssi()
                }
                handler.postDelayed(this, RSSI_REFRESH_MS)
            }
        }
        handler.postDelayed(rssiRefreshRunnable!!, RSSI_INITIAL_DELAY_MS)
    }


    private fun resetLiveSession() {
        batteryRefreshRunnable?.let(handler::removeCallbacks)
        batteryRefreshRunnable = null
        rssiRefreshRunnable?.let(handler::removeCallbacks)
        rssiRefreshRunnable = null
        timeSyncRequested = false
        graphPoints.clear()
        longGraphPoints.clear()
        LiveHeartRateState.setLiveBpm(null)
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
                backgroundMonitoringEnabled = backgroundMonitoringEnabled(),
                notificationEnabled = notificationEnabled(),
                overlayLocked = prefs.getBoolean(KEY_OVERLAY_LOCKED, false),
                overlayScale = prefs.getFloat(KEY_OVERLAY_SCALE, 1f),
                overlayPreset = overlayPositionPreset().key,
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
                backgroundMonitoringEnabled = backgroundMonitoringEnabled(),
                notificationEnabled = notificationEnabled(),
                lowBatteryAlertEnabled = lowBatteryAlertEnabled(),
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
        updateNotification(force = notificationEnabled())
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
        rssiRefreshRunnable?.let(handler::removeCallbacks)
        rssiRefreshRunnable = null
        timeSyncRequested = false
        closeGatt()
        LiveHeartRateState.setLiveBpm(null)
        connectedAt = 0L
        streamRecoveryAttempts = 0
        LiveHeartRateState.set(
            LiveHeartRateState.snapshot.value.copy(
                connected = false,
                rssi = null,
                status = "Disconnected",
            ),
        )
        updateNotification()
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        cancelBleWrites()
        batteryRefreshRunnable?.let(handler::removeCallbacks)
        batteryRefreshRunnable = null
        rssiRefreshRunnable?.let(handler::removeCallbacks)
        rssiRefreshRunnable = null
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
        val liveBpm = LiveHeartRateState.liveBpm.value
        val notificationsOn = notificationEnabled()
        val bpmText = liveBpm?.let { "$it bpm" } ?: "No HR yet"

        val stats = if (!notificationsOn) {
            "Background watch connection active"
        } else {
            buildString {
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
        }

        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingIntentFlags(),
        )

        val builder = Notification.Builder(
            this,
            if (notificationsOn) CHANNEL_ID else QUIET_CHANNEL_ID,
        )
            .setSmallIcon(renderNotificationIcon(if (notificationsOn) liveBpm else null))
            .setContentTitle(if (notificationsOn) bpmText else "Watch connected")
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

    private fun evaluateLowBatteryAlert(percent: Int?, charging: Boolean?) {
        if (percent == null) return

        if (percent > LOW_BATTERY_REARM) {
            if (lowBatteryAlerted) {
                lowBatteryAlerted = false
                prefs.edit().putBoolean(KEY_LOW_BATTERY_ALERTED, false).apply()
            }
            return
        }

        if (
            percent > LOW_BATTERY_THRESHOLD ||
            charging == true ||
            !lowBatteryAlertEnabled() ||
            !notificationEnabled() ||
            lowBatteryAlerted ||
            !androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
        ) {
            return
        }

        lowBatteryAlerted = true
        prefs.edit().putBoolean(KEY_LOW_BATTERY_ALERTED, true).apply()

        val openIntent = PendingIntent.getActivity(
            this,
            LOW_BATTERY_NOTIFICATION_ID,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingIntentFlags(),
        )

        val notification = Notification.Builder(this, LOW_BATTERY_CHANNEL_ID)
            .setSmallIcon(renderNotificationIcon(null))
            .setContentTitle("Watch battery low")
            .setContentText(
                "FT_38093 battery is $percent%. Charge the watch soon.",
            )
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_SYSTEM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(LOW_BATTERY_NOTIFICATION_ID, notification)
    }

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
        if (!notificationEnabled() && !force) return

        val liveBpm = LiveHeartRateState.liveBpm.value
        val now = System.currentTimeMillis()
        if (!force && liveBpm == lastNotifiedBpm) return
        if (!force && now - lastNotificationAt < NOTIFICATION_UPDATE_MS) return

        lastNotificationAt = now
        lastNotifiedBpm = liveBpm

        handler.post {
            if (!runningForeground || !monitoringEnabled()) return@post
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun startForegroundCompat(notification: Notification) {
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

    private fun refreshForegroundNotification(
        forceChannelSwitch: Boolean = false,
    ) {
        if (!runningForeground || !monitoringEnabled()) return

        if (forceChannelSwitch) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            runningForeground = false
        }

        startForegroundCompat(buildNotification())
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        manager.deleteNotificationChannel(LEGACY_QUIET_CHANNEL_ID)

        val activeChannel = NotificationChannel(
            CHANNEL_ID,
            "Live heart rate",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Live heart-rate updates"
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

        val lowBatteryChannel = NotificationChannel(
            LOW_BATTERY_CHANNEL_ID,
            "Low battery alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "One-time alert when watch battery falls to 20% or below"
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

        val quietChannel = NotificationChannel(
            QUIET_CHANNEL_ID,
            "Background connection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Quiet foreground connection required for background BLE monitoring"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }

        manager.createNotificationChannels(
            listOf(activeChannel, quietChannel, lowBatteryChannel),
        )
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
        overlayOrientation = currentOverlayOrientation()
        overlayDisplayMode = OverlayDisplayMode.NORMAL

        val text = TextView(this).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 80f * scale
                setColor(Color.argb(48, 10, 16, 28))
                setStroke(
                    (2f * scale).roundToInt().coerceAtLeast(1),
                    Color.rgb(90, 220, 255),
                )
            }
            setOnTouchListener(OverlayDragListener(manager, this))
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
                handleOverlayWindowInsets(insets)
                insets
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
        }

        overlayView = text
        overlayParams = params
        lastOverlayValue = null
        applyOverlayStyle()

        runCatching {
            manager.addView(text, params)
            ViewCompat.requestApplyInsets(text)
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
            setStroke(
                (2f * scale).roundToInt().coerceAtLeast(1),
                Color.rgb(90, 220, 255),
            )
        }
        updateOverlay()
        view.post {
            overlayParams?.let { current -> applyOverlayPosition(view, current) }
        }
    }

    private fun handleOverlayWindowInsets(insets: WindowInsetsCompat) {
        val fullscreen =
            !insets.isVisible(WindowInsetsCompat.Type.statusBars()) &&
                !insets.isVisible(WindowInsetsCompat.Type.navigationBars())
        val nextMode = if (fullscreen) {
            OverlayDisplayMode.FULLSCREEN
        } else {
            OverlayDisplayMode.NORMAL
        }

        if (nextMode == overlayDisplayMode) return

        val view = overlayView ?: return
        val params = overlayParams ?: return
        val oldMode = overlayDisplayMode
        if (overlayPositionPreset() == OverlayPositionPreset.CUSTOM) {
            saveOverlayPosition(overlayOrientation, oldMode, params.x, params.y)
        }
        overlayDisplayMode = nextMode
        applyOverlayPosition(view, params)
    }

    private fun applyOverlayPreset(preset: OverlayPositionPreset) {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        if (preset == OverlayPositionPreset.CUSTOM) {
            val (x, y) = loadOverlayPosition(overlayOrientation, overlayDisplayMode)
            params.x = x
            params.y = y
        } else {
            val (x, y) = calculatePresetPosition(view, preset)
            params.x = x
            params.y = y
        }
        clampOverlayPosition(view, params)
        if (preset == OverlayPositionPreset.CUSTOM) {
            saveOverlayPosition(overlayOrientation, overlayDisplayMode, params.x, params.y)
        }
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(view, params)
        }
    }

    private fun applyOverlayPosition(view: View, params: WindowManager.LayoutParams) {
        val preset = overlayPositionPreset()
        if (preset == OverlayPositionPreset.CUSTOM) {
            val (x, y) = loadOverlayPosition(overlayOrientation, overlayDisplayMode)
            params.x = x
            params.y = y
        } else {
            val (x, y) = calculatePresetPosition(view, preset)
            params.x = x
            params.y = y
        }
        clampOverlayPosition(view, params)
        if (preset == OverlayPositionPreset.CUSTOM) {
            saveOverlayPosition(overlayOrientation, overlayDisplayMode, params.x, params.y)
        }
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(view, params)
        }
    }

    private fun calculatePresetPosition(view: View, preset: OverlayPositionPreset): Pair<Int, Int> {
        val displayWidth = resources.displayMetrics.widthPixels
        val displayHeight = resources.displayMetrics.heightPixels
        val overlayWidth = view.width.takeIf { it > 0 } ?: view.measuredWidth
        val overlayHeight = view.height.takeIf { it > 0 } ?: view.measuredHeight
        val maxX = (displayWidth - overlayWidth).coerceAtLeast(0)
        val maxY = (displayHeight - overlayHeight).coerceAtLeast(0)
        val margin = (8f * resources.displayMetrics.density).roundToInt()
        val x = when (preset) {
            OverlayPositionPreset.TOP_LEFT, OverlayPositionPreset.BOTTOM_LEFT ->
                (maxX - margin).coerceAtLeast(0)
            OverlayPositionPreset.TOP_CENTER, OverlayPositionPreset.BOTTOM_CENTER -> maxX / 2
            OverlayPositionPreset.TOP_RIGHT, OverlayPositionPreset.BOTTOM_RIGHT -> margin.coerceAtMost(maxX)
            OverlayPositionPreset.CUSTOM -> 0
        }
        val y = when (preset) {
            OverlayPositionPreset.TOP_LEFT, OverlayPositionPreset.TOP_CENTER, OverlayPositionPreset.TOP_RIGHT ->
                margin.coerceAtMost(maxY)
            OverlayPositionPreset.BOTTOM_LEFT, OverlayPositionPreset.BOTTOM_CENTER, OverlayPositionPreset.BOTTOM_RIGHT ->
                (maxY - margin).coerceAtLeast(0)
            OverlayPositionPreset.CUSTOM -> 0
        }
        return x to y
    }

    private fun updateOverlay(bpm: Int? = LiveHeartRateState.snapshot.value.bpm) {
        val value = bpm?.let { "$it bpm" } ?: "— bpm"
        if (value == lastOverlayValue) return
        lastOverlayValue = value
        if (Looper.myLooper() == Looper.getMainLooper()) {
            overlayView?.text = value
        } else {
            handler.post { overlayView?.text = value }
        }
    }

    private fun publishMonitoringState() {
        val current = LiveHeartRateState.snapshot.value
        LiveHeartRateState.set(current.copy(backgroundMonitoringEnabled = backgroundMonitoringEnabled()))
    }

    private fun publishNotificationState() {
        val current = LiveHeartRateState.snapshot.value
        LiveHeartRateState.set(current.copy(notificationEnabled = notificationEnabled()))
    }

    private fun publishLowBatteryAlertState() {
        val current = LiveHeartRateState.snapshot.value
        LiveHeartRateState.set(current.copy(lowBatteryAlertEnabled = lowBatteryAlertEnabled()))
    }

    private fun publishOverlayState() {
        val current = LiveHeartRateState.snapshot.value
        LiveHeartRateState.set(current.copy(
            overlayVisible = prefs.getBoolean(KEY_OVERLAY_VISIBLE, false),
            overlayLocked = prefs.getBoolean(KEY_OVERLAY_LOCKED, false),
            overlayScale = prefs.getFloat(KEY_OVERLAY_SCALE, 1f),
            overlayPreset = overlayPositionPreset().key,
        ))
    }

    private fun loadOverlayFromPrefs() {
        publishOverlayState()
        if (prefs.getBoolean(KEY_OVERLAY_VISIBLE, false)) showOverlay()
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayParams?.let { params ->
            if (overlayOrientation != Configuration.ORIENTATION_UNDEFINED &&
                overlayPositionPreset() == OverlayPositionPreset.CUSTOM
            ) {
                saveOverlayPosition(overlayOrientation, overlayDisplayMode, params.x, params.y)
            }
        }
        runCatching { manager.removeView(view) }
        overlayView = null
        overlayParams = null
        overlayOrientation = Configuration.ORIENTATION_UNDEFINED
        overlayDisplayMode = OverlayDisplayMode.NORMAL
        lastOverlayValue = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val view = overlayView ?: return
        val params = overlayParams ?: return
        val oldOrientation = overlayOrientation
        val newOrientation = if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Configuration.ORIENTATION_LANDSCAPE
        } else {
            Configuration.ORIENTATION_PORTRAIT
        }
        if (oldOrientation == Configuration.ORIENTATION_UNDEFINED) {
            overlayOrientation = newOrientation
            applyOverlayPosition(view, params)
            return
        }
        if (oldOrientation == newOrientation) return
        if (overlayPositionPreset() == OverlayPositionPreset.CUSTOM) {
            saveOverlayPosition(oldOrientation, overlayDisplayMode, params.x, params.y)
        }
        overlayOrientation = newOrientation
        applyOverlayPosition(view, params)
    }

    private fun clampOverlayPosition(view: View, params: WindowManager.LayoutParams) {
        val displayWidth = resources.displayMetrics.widthPixels
        val displayHeight = resources.displayMetrics.heightPixels
        val overlayWidth = view.width.takeIf { it > 0 } ?: view.measuredWidth
        val overlayHeight = view.height.takeIf { it > 0 } ?: view.measuredHeight
        if (overlayWidth <= 0 || overlayHeight <= 0) return
        val maxX = (displayWidth - overlayWidth).coerceAtLeast(0)
        val maxY = (displayHeight - overlayHeight).coerceAtLeast(0)
        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
    }

    private fun currentOverlayOrientation(): Int =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Configuration.ORIENTATION_LANDSCAPE
        } else {
            Configuration.ORIENTATION_PORTRAIT
        }

    private fun loadOverlayPosition(orientation: Int, displayMode: OverlayDisplayMode): Pair<Int, Int> {
        val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val fullscreen = displayMode == OverlayDisplayMode.FULLSCREEN
        val xKey = when {
            landscape && fullscreen -> KEY_OVERLAY_X_LANDSCAPE_FULLSCREEN
            landscape -> KEY_OVERLAY_X_LANDSCAPE
            fullscreen -> KEY_OVERLAY_X_PORTRAIT_FULLSCREEN
            else -> KEY_OVERLAY_X_PORTRAIT,
        }
        val yKey = when {
            landscape && fullscreen -> KEY_OVERLAY_Y_LANDSCAPE_FULLSCREEN
            landscape -> KEY_OVERLAY_Y_LANDSCAPE
            fullscreen -> KEY_OVERLAY_Y_PORTRAIT_FULLSCREEN
            else -> KEY_OVERLAY_Y_PORTRAIT,
        }
        if (prefs.contains(xKey) && prefs.contains(yKey)) {
            return prefs.getInt(xKey, DEFAULT_OVERLAY_X) to prefs.getInt(
                yKey,
                if (landscape) DEFAULT_OVERLAY_Y_LANDSCAPE else DEFAULT_OVERLAY_Y_PORTRAIT,
            )
        }
        if (fullscreen) {
            val normalXKey = if (landscape) KEY_OVERLAY_X_LANDSCAPE else KEY_OVERLAY_X_PORTRAIT
            val normalYKey = if (landscape) KEY_OVERLAY_Y_LANDSCAPE else KEY_OVERLAY_Y_PORTRAIT
            if (prefs.contains(normalXKey) && prefs.contains(normalYKey)) {
                val x = prefs.getInt(normalXKey, DEFAULT_OVERLAY_X)
                val y = prefs.getInt(
                    normalYKey,
                    if (landscape) DEFAULT_OVERLAY_Y_LANDSCAPE else DEFAULT_OVERLAY_Y_PORTRAIT,
                )
                saveOverlayPosition(orientation, displayMode, x, y)
                return x to y
            }
        }
        if (prefs.contains(KEY_OVERLAY_X) || prefs.contains(KEY_OVERLAY_Y)) {
            val legacyX = prefs.getInt(KEY_OVERLAY_X, DEFAULT_OVERLAY_X)
            val legacyY = prefs.getInt(KEY_OVERLAY_Y, DEFAULT_OVERLAY_Y_PORTRAIT)
            saveOverlayPosition(orientation, displayMode, legacyX, legacyY)
            return legacyX to legacyY
        }
        return DEFAULT_OVERLAY_X to if (landscape) DEFAULT_OVERLAY_Y_LANDSCAPE else DEFAULT_OVERLAY_Y_PORTRAIT
    }

    private fun saveOverlayPosition(orientation: Int, displayMode: OverlayDisplayMode, x: Int, y: Int) {
        val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val fullscreen = displayMode == OverlayDisplayMode.FULLSCREEN
        val xKey = when {
            landscape && fullscreen -> KEY_OVERLAY_X_LANDSCAPE_FULLSCREEN
            landscape -> KEY_OVERLAY_X_LANDSCAPE
            fullscreen -> KEY_OVERLAY_X_PORTRAIT_FULLSCREEN
            else -> KEY_OVERLAY_X_PORTRAIT,
        }
        val yKey = when {
            landscape && fullscreen -> KEY_OVERLAY_Y_LANDSCAPE_FULLSCREEN
            landscape -> KEY_OVERLAY_Y_LANDSCAPE
            fullscreen -> KEY_OVERLAY_Y_PORTRAIT_FULLSCREEN
            else -> KEY_OVERLAY_Y_PORTRAIT,
        }
        prefs.edit().putInt(xKey, x).putInt(yKey, y).apply()
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
                backgroundMonitoringEnabled = false,
            ),
        )
        stopSelf()
    }

    private fun monitoringEnabled(): Boolean =
        backgroundMonitoringEnabled()

    private fun backgroundMonitoringEnabled(): Boolean =
        prefs.getBoolean(KEY_BACKGROUND_MONITORING_ENABLED, true)

    private fun notificationEnabled(): Boolean =
        prefs.getBoolean(KEY_NOTIFICATION_ENABLED, true)

    private fun overlayPositionPreset(): OverlayPositionPreset =
        overlayPositionPresetFromKey(prefs.getString(KEY_OVERLAY_PRESET, null))

    private fun lowBatteryAlertEnabled(): Boolean =
        prefs.getBoolean(KEY_LOW_BATTERY_ALERT_ENABLED, true)

    private fun migratePreferences() {
        if (!prefs.contains(KEY_LOW_BATTERY_ALERT_ENABLED)) {
            prefs.edit().putBoolean(KEY_LOW_BATTERY_ALERT_ENABLED, true).apply()
        }

        lowBatteryAlerted = prefs.getBoolean(KEY_LOW_BATTERY_ALERTED, false)

        if (!prefs.contains(KEY_BACKGROUND_MONITORING_ENABLED)) {
            prefs.edit()
                .putBoolean(
                    KEY_BACKGROUND_MONITORING_ENABLED,
                    prefs.getBoolean(KEY_NOTIFICATION_ENABLED, true),
                )
                .apply()
        }
    }

    override fun onDestroy() {
        reconnectRunnable?.let(handler::removeCallbacks)
        watchdogRunnable?.let(handler::removeCallbacks)
        batteryRefreshRunnable?.let(handler::removeCallbacks)
        reconnectRunnable = null
        batteryRefreshRunnable = null
        watchdogRunnable = null
        hideOverlay()
        closeGatt()
        runningForeground = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private inner class OverlayDragListener(
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
                    if (overlayPositionPreset() != OverlayPositionPreset.CUSTOM) {
                        prefs.edit()
                            .putString(KEY_OVERLAY_PRESET, OverlayPositionPreset.CUSTOM.key)
                            .apply()
                        publishOverlayState()
                    }
                    downX = event.rawX.toInt()
                    downY = event.rawY.toInt()
                    startX = params.x
                    startY = params.y
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - downX
                    val dy = event.rawY.toInt() - downY
                    params.x = startX - dx
                    params.y = startY + dy
                    clampOverlayPosition(view, params)
                    runCatching { manager.updateViewLayout(view, params) }
                    return true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    clampOverlayPosition(view, params)
                    val orientation = context.resources.configuration.orientation
                    val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
                    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    prefs.edit()
                        .putInt(
                            if (landscape) KEY_OVERLAY_X_LANDSCAPE else KEY_OVERLAY_X_PORTRAIT,
                            params.x,
                        )
                        .putInt(
                            if (landscape) KEY_OVERLAY_Y_LANDSCAPE else KEY_OVERLAY_Y_PORTRAIT,
                            params.y,
                        )
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
        const val ACTION_OVERLAY_PRESET = "app.watchdatasync.action.OVERLAY_PRESET"
        const val ACTION_SET_MONITORING = "app.watchdatasync.action.SET_MONITORING"
        const val ACTION_SET_NOTIFICATION = "app.watchdatasync.action.SET_NOTIFICATION"
        const val ACTION_SET_LOW_BATTERY_ALERT = "app.watchdatasync.action.SET_LOW_BATTERY_ALERT"
        const val ACTION_SYNC_TIME = "app.watchdatasync.action.SYNC_TIME"

        const val EXTRA_ADDRESS = "extra_address"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_ENABLED = "extra_enabled"
        const val EXTRA_LOCKED = "extra_locked"
        const val EXTRA_SCALE = "extra_scale"
        const val EXTRA_POSITION_PRESET = "extra_position_preset"

        const val PREFS = "watch_preferences"
        const val KEY_ADDRESS = "bound_watch_address"
        const val KEY_NAME = "bound_watch_name"
        const val KEY_BACKGROUND_MONITORING_ENABLED = "background_monitoring_enabled"
        const val KEY_NOTIFICATION_ENABLED = "notification_enabled"
        const val KEY_LOW_BATTERY_ALERT_ENABLED = "low_battery_alert_enabled"
        private const val KEY_LOW_BATTERY_ALERTED = "low_battery_alerted"
        const val KEY_OVERLAY_VISIBLE = "overlay_visible"
        const val KEY_OVERLAY_LOCKED = "overlay_locked"
        const val KEY_OVERLAY_SCALE = "overlay_scale"
        const val KEY_OVERLAY_PRESET = "overlay_preset"
        const val KEY_OVERLAY_X = "overlay_x"
        const val KEY_OVERLAY_Y = "overlay_y"
        private const val KEY_OVERLAY_X_PORTRAIT = "overlay_x_portrait"
        private const val KEY_OVERLAY_Y_PORTRAIT = "overlay_y_portrait"
        private const val KEY_OVERLAY_X_LANDSCAPE = "overlay_x_landscape"
        private const val KEY_OVERLAY_Y_LANDSCAPE = "overlay_y_landscape"
        private const val KEY_OVERLAY_X_PORTRAIT_FULLSCREEN = "overlay_x_portrait_fullscreen"
        private const val KEY_OVERLAY_Y_PORTRAIT_FULLSCREEN = "overlay_y_portrait_fullscreen"
        private const val KEY_OVERLAY_X_LANDSCAPE_FULLSCREEN = "overlay_x_landscape_fullscreen"
        private const val KEY_OVERLAY_Y_LANDSCAPE_FULLSCREEN = "overlay_y_landscape_fullscreen"
        private const val DEFAULT_OVERLAY_X = 24
        private const val DEFAULT_OVERLAY_Y_PORTRAIT = 120
        private const val DEFAULT_OVERLAY_Y_LANDSCAPE = 72

        private const val CHANNEL_ID = "live_heart_rate_v3"
        private const val QUIET_CHANNEL_ID = "live_heart_rate_background_v2"
        private const val LOW_BATTERY_CHANNEL_ID = "watch_battery_alerts_v1"
        private const val LEGACY_CHANNEL_ID = "live_heart_rate_v2"
        private const val LEGACY_QUIET_CHANNEL_ID = "live_heart_rate_background_v1"
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        private const val NOTIFICATION_ID = 4101
        private const val LOW_BATTERY_NOTIFICATION_ID = 4102
        private const val LOW_BATTERY_THRESHOLD = 20
        private const val LOW_BATTERY_REARM = 25

        // 24h × one RAM sample every 2s = 43,200 points.
        // Still bounded and RAM-only; no persistence layer is used.
        private const val MAX_GRAPH_POINTS = 5400
        private const val MAX_LONG_GRAPH_POINTS = 2880
        private const val GRAPH_SAMPLE_MS = 2000L
        private const val LONG_GRAPH_SAMPLE_MS = 30_000L
        private const val LIVE_WATCHDOG_MS = 15000L
        private const val LIVE_STALE_MS = 12000L
        private const val NOTIFICATION_UPDATE_MS = 2000L
        private const val BATTERY_REFRESH_NORMAL_MS = 10 * 60 * 1000L
        private const val BATTERY_REFRESH_LOW_MS = 2 * 60 * 1000L
        private const val BATTERY_REFRESH_CHARGING_MS = 15 * 60 * 1000L
        private const val RSSI_INITIAL_DELAY_MS = 5000L
        private const val RSSI_REFRESH_MS = 30_000L
        private const val DYNAMIC_HR_START_DELAY_MS = 1500L
        private const val NO_RESPONSE_WRITE_GAP_MS = 80L
        private const val WRITE_RESPONSE_TIMEOUT_MS = 1500L
        private const val RECOVERY_COOLDOWN_MS = 20_000L

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

        fun overlayPreset(context: Context, preset: OverlayPositionPreset) {
            context.startService(
                Intent(context, HeartRateService::class.java)
                    .setAction(ACTION_OVERLAY_PRESET)
                    .putExtra(EXTRA_POSITION_PRESET, preset.key),
            )
        }

        fun setLowBatteryAlertEnabled(context: Context, enabled: Boolean) {
            val intent = Intent(context, HeartRateService::class.java)
                .setAction(ACTION_SET_LOW_BATTERY_ALERT)
                .putExtra(EXTRA_ENABLED, enabled)

            context.startService(intent)
        }

        fun setNotificationEnabled(context: Context, enabled: Boolean) {
            val intent = Intent(context, HeartRateService::class.java)
                .setAction(ACTION_SET_NOTIFICATION)
                .putExtra(EXTRA_ENABLED, enabled)

            context.startService(intent)
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
