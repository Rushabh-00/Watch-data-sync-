package app.watchdatasync

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import androidx.core.app.NotificationCompat
import java.util.Calendar
import java.util.UUID
import kotlin.math.roundToInt

class HeartRateService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: android.bluetooth.BluetoothGatt? = null
    private var reconnectRunnable: Runnable? = null
    private var reconnectAttempt = 0
    private var graphLastAt = 0L
    private var samples = ArrayList<Int>()
    private var graphPoints = ArrayList<Int>()
    private var sum = 0L
    private var min = Int.MAX_VALUE
    private var max = Int.MIN_VALUE
    private var overlayView: View? = null

    private val prefs by lazy {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat(buildNotification())
        loadSessionDefaults()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification())
        when (intent?.action) {
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                val name = intent.getStringExtra(EXTRA_NAME)
                if (!address.isNullOrBlank()) {
                    saveDevice(address, name)
                    connectSavedDevice()
                }
            }
            ACTION_DISCONNECT -> disconnectUser()
            ACTION_SYNC_TIME -> syncTime()
            ACTION_OVERLAY_ON -> showOverlay()
            ACTION_OVERLAY_OFF -> hideOverlay()
            ACTION_START, null -> connectSavedDevice()
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun connectSavedDevice() {
        val address = prefs.getString(KEY_ADDRESS, null) ?: run {
            updateStatus("Ready • scan to connect")
            return
        }
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
        resetLiveSession()
        updateStatus("Connecting to " + (prefs.getString(KEY_NAME, null) ?: "FT_38093") + "…")
        try {
            gatt = device.connectGatt(this, false, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            updateStatus("Bluetooth permission is required")
            scheduleReconnect()
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

        val notify = live.properties and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val indicate = live.properties and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
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
    private val gattCallback = object : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(
            current: android.bluetooth.BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            if (
                status == android.bluetooth.BluetoothGatt.GATT_SUCCESS &&
                newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED
            ) {
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

            LiveHeartRateState.set(
                LiveHeartRateState.snapshot.value.copy(
                    connected = false,
                    timeSynced = false,
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

            updateStatus("Live heart rate active • syncing time…")
        }

        override fun onDescriptorWrite(
            current: android.bluetooth.BluetoothGatt,
            descriptor: android.bluetooth.BluetoothGattDescriptor,
            status: Int,
        ) {
            if (
                descriptor.characteristic.uuid.toString()
                    .equals(FastrackProtocol.LIVE_DATA_UUID, ignoreCase = true)
            ) {
                if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                    updateStatus("Live heart rate active")
                    syncTime()
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
            handleHeartRate(characteristic.value)
        }

        override fun onCharacteristicChanged(
            current: android.bluetooth.BluetoothGatt,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleHeartRate(value)
        }

        override fun onCharacteristicWrite(
            current: android.bluetooth.BluetoothGatt,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (
                !characteristic.uuid.toString()
                    .equals(FastrackProtocol.TIME_WRITE_UUID, ignoreCase = true)
            ) {
                return
            }

            if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                LiveHeartRateState.set(
                    LiveHeartRateState.snapshot.value.copy(
                        timeSynced = true,
                        status = "Live heart rate active • time synced",
                    ),
                )
                updateNotification()
            } else {
                updateStatus("Time sync failed")
            }
        }
    }

    private fun handleHeartRate(packet: ByteArray) {
        val bpm = FastrackProtocol.decodeLiveHeartRate(packet) ?: return
        sum += bpm
        samples.add(bpm)
        if (samples.size > MAX_SESSION_SAMPLES) {
            val removed = samples.removeAt(0)
            sum -= removed
        }
        min = minOf(min, bpm)
        max = maxOf(max, bpm)

        val now = System.currentTimeMillis()
        if (now - graphLastAt >= GRAPH_SAMPLE_MS || graphPoints.isEmpty()) {
            graphPoints.add(bpm)
            if (graphPoints.size > MAX_GRAPH_POINTS) graphPoints.removeAt(0)
            graphLastAt = now
        }

        val average = (sum.toDouble() / samples.size).roundToInt()
        LiveHeartRateState.set(
            LiveHeartRateState.snapshot.value.copy(
                bpm = bpm,
                averageBpm = average,
                minimumBpm = min.takeIf { it != Int.MAX_VALUE },
                maximumBpm = max.takeIf { it != Int.MIN_VALUE },
                graph = graphPoints.toList(),
                connected = true,
                status = "Live heart rate active",
            ),
        )

        updateNotification()
        updateOverlay(bpm)
    }

    @SuppressLint("MissingPermission")
    private fun syncTime() {
        val current = gatt ?: return
        val service = current.getService(UUID.fromString(FastrackProtocol.SERVICE_UUID)) ?: return
        val characteristic =
            service.getCharacteristic(UUID.fromString(FastrackProtocol.TIME_WRITE_UUID)) ?: return

        characteristic.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = FastrackProtocol.buildTimeSyncPacket(Calendar.getInstance())

        runCatching {
            if (!current.writeCharacteristic(characteristic)) {
                updateStatus("Time write was rejected")
            }
        }.onFailure {
            updateStatus("Time sync failed")
        }
    }

    private fun resetLiveSession() {
        samples = ArrayList()
        graphPoints = ArrayList()
        graphLastAt = 0L
        sum = 0L
        min = Int.MAX_VALUE
        max = Int.MIN_VALUE

        LiveHeartRateState.set(
            LiveHeartRateSnapshot(
                connected = false,
                deviceName = prefs.getString(KEY_NAME, "FT_38093"),
                status = "Connecting…",
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
                    "Saved watch • starting auto-connect"
                },
            ),
        )
    }

    private fun updateStatus(value: String) {
        val current = LiveHeartRateState.snapshot.value
        LiveHeartRateState.set(current.copy(status = value))
        updateNotification()
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
        closeGatt()
        LiveHeartRateState.set(
            LiveHeartRateState.snapshot.value.copy(
                connected = false,
                timeSynced = false,
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
        if (prefs.getString(KEY_ADDRESS, null).isNullOrBlank()) return
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
        val bpmText = snapshot.bpm?.let { it.toString() + " bpm" } ?: "No HR yet"
        val stats = buildString {
            snapshot.averageBpm?.let { append("Avg ").append(it).append(" • ") }
            snapshot.minimumBpm?.let { append("Min ").append(it).append(" • ") }
            snapshot.maximumBpm?.let { append("Max ").append(it) }
            if (isEmpty()) append(snapshot.status)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingIntentFlags(),
        )

        val syncIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, HeartRateService::class.java).setAction(ACTION_SYNC_TIME),
            pendingIntentFlags(),
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_heart_rate)
            .setContentTitle(bpmText + " • FT_38093")
            .setContentText(stats)
            .setContentIntent(openIntent)
            .addAction(0, "Sync time", syncIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(false)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification() {
        if (!runningForeground) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private var runningForeground = false

    private fun startForegroundCompat(notification: Notification) {
        if (runningForeground) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
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
            setSound(null, null)
            enableVibration(false)
            setShowBadge(true)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun pendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private fun showOverlay() {
        if (overlayView != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !Settings.canDrawOverlays(this)) {
            return
        }

        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        val text = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(22, 12, 22, 12)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 80f
                setColor(Color.argb(225, 16, 20, 36))
                setStroke(2, Color.rgb(90, 220, 255))
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
            x = 24
            y = 120
        }

        text.text = LiveHeartRateState.snapshot.value.bpm?.let { "$it bpm" } ?: "— bpm"

        runCatching {
            manager.addView(text, params)
            overlayView = text
        }
    }

    private fun updateOverlay(bpm: Int) {
        (overlayView as? TextView)?.text = bpm.toString() + " bpm"
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching { manager.removeView(view) }
        overlayView = null
    }

    override fun onDestroy() {
        reconnectRunnable?.let(handler::removeCallbacks)
        reconnectRunnable = null
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
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = v.layoutParams as WindowManager.LayoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX.toInt()
                    downY = event.rawY.toInt()
                    startX = params.x
                    startY = params.y
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - downX
                    val dy = event.rawY.toInt() - downY
                    if (dx * dx + dy * dy > 36) moved = true
                    params.x = startX - dx
                    params.y = startY + dy
                    runCatching { manager.updateViewLayout(view, params) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    return true
                }
            }
            return false
        }
    }

    companion object {
        const val ACTION_START = "app.watchdatasync.action.START"
        const val ACTION_CONNECT = "app.watchdatasync.action.CONNECT"
        const val ACTION_DISCONNECT = "app.watchdatasync.action.DISCONNECT"
        const val ACTION_SYNC_TIME = "app.watchdatasync.action.SYNC_TIME"
        const val ACTION_OVERLAY_ON = "app.watchdatasync.action.OVERLAY_ON"
        const val ACTION_OVERLAY_OFF = "app.watchdatasync.action.OVERLAY_OFF"
        const val EXTRA_ADDRESS = "extra_address"
        const val EXTRA_NAME = "extra_name"

        const val PREFS = "watch_preferences"
        const val KEY_ADDRESS = "bound_watch_address"
        const val KEY_NAME = "bound_watch_name"

        private const val CHANNEL_ID = "live_heart_rate"
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        private const val NOTIFICATION_ID = 4101
        private const val MAX_SESSION_SAMPLES = 1200
        private const val MAX_GRAPH_POINTS = 300
        private const val GRAPH_SAMPLE_MS = 1000L
        private const val RECONNECT_BASE_MS = 2000L
        private const val RECONNECT_MAX_MS = 30000L

        fun start(context: Context) {
            val intent = Intent(context, HeartRateService::class.java).setAction(ACTION_START)
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

        fun syncTime(context: Context) {
            context.startService(
                Intent(context, HeartRateService::class.java).setAction(ACTION_SYNC_TIME),
            )
        }

        fun disconnect(context: Context) {
            context.startService(
                Intent(context, HeartRateService::class.java).setAction(ACTION_DISCONNECT),
            )
        }

        fun overlayOn(context: Context) {
            context.startService(
                Intent(context, HeartRateService::class.java).setAction(ACTION_OVERLAY_ON),
            )
        }

        fun overlayOff(context: Context) {
            context.startService(
                Intent(context, HeartRateService::class.java).setAction(ACTION_OVERLAY_OFF),
            )
        }
    }
}
