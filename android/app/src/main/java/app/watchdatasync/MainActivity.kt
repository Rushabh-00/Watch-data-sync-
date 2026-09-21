package app.watchdatasync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class GraphWindow(
    val label: String,
    val millis: Long,
) {
    H5("5m", 5 * 60 * 1000L),
    M10("10m", 10 * 60 * 1000L),
    M30("30m", 30 * 60 * 1000L),
    H1("1h", 60 * 60 * 1000L),
    H2("2h", 2 * 60 * 60 * 1000L),
    H3("3h", 3 * 60 * 60 * 1000L),
    H24("24h", 24 * 60 * 60 * 1000L),
}

class MainActivity : ComponentActivity() {
    private lateinit var controller: BleController
    private var timeSyncRequestedForOpen = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasRequiredPermissions()) {
                if (monitoringEnabled()) {
                    HeartRateService.start(this)
                    requestTimeSyncForCurrentOpen()
                }
                refreshDiscovery()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        controller = BleController(this)

        val initialEnabled = monitoringEnabled()
        LiveHeartRateState.set(
            LiveHeartRateState.snapshot.value.copy(
                backgroundMonitoringEnabled = initialEnabled,
                notificationEnabled = notificationEnabled(),
            ),
        )

        setContent {
            val snapshot by LiveHeartRateState.snapshot.collectAsStateWithLifecycle()
            val devices by controller.devices.collectAsStateWithLifecycle()
            var themeMode by remember { mutableStateOf(loadThemeMode()) }

            WatchDataSyncTheme(themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Dashboard(
                        snapshot = snapshot,
                        devices = devices,
                        onScan = { refreshDiscovery(force = true) },
                        onConnect = {
                            HeartRateService.connect(
                                this@MainActivity,
                                it.device.address,
                                it.name,
                            )
                        },
                        onDisconnect = {
                            HeartRateService.disconnect(this@MainActivity)
                        },
                        onOverlay = {
                            if (
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                                !Settings.canDrawOverlays(this@MainActivity)
                            ) {
                                getSharedPreferences(
                                    HeartRateService.PREFS,
                                    MODE_PRIVATE,
                                ).edit()
                                    .putBoolean(HeartRateService.KEY_OVERLAY_VISIBLE, true)
                                    .apply()
                                LiveHeartRateState.set(
                                    LiveHeartRateState.snapshot.value.copy(
                                        overlayVisible = true,
                                    ),
                                )
                                startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse(
                                            "package:$packageName",
                                        ),
                                    ),
                                )
                            } else {
                                HeartRateService.overlayOn(this@MainActivity)
                            }
                        },
                        onOverlayOff = {
                            HeartRateService.overlayOff(this@MainActivity)
                        },
                        onOverlayLock = {
                            HeartRateService.overlayLock(
                                this@MainActivity,
                                it,
                            )
                        },
                        onOverlaySize = {
                            HeartRateService.overlaySize(
                                this@MainActivity,
                                it,
                            )
                        },
                        currentThemeMode = themeMode,
                        onThemeMode = {
                            themeMode = it
                            saveThemeMode(it)
                        },
                        onMonitoringEnabled = {
                            LiveHeartRateState.set(
                                LiveHeartRateState.snapshot.value.copy(
                                    backgroundMonitoringEnabled = it,
                                ),
                            )
                            HeartRateService.setMonitoringEnabled(
                                this@MainActivity,
                                it,
                            )
                            if (it) {
                                requestTimeSyncForCurrentOpen()
                            }
                        },
                        onNotificationEnabled = {
                            LiveHeartRateState.set(
                                LiveHeartRateState.snapshot.value.copy(
                                    notificationEnabled = it,
                                ),
                            )
                            HeartRateService.setNotificationEnabled(
                                this@MainActivity,
                                it,
                            )
                        },
                        onOpenNotificationSettings = {
                            startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(
                                        Settings.EXTRA_APP_PACKAGE,
                                        packageName,
                                    )
                                },
                            )
                        },
                    )
                }
            }
        }

        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (::controller.isInitialized && hasRequiredPermissions()) {
            if (monitoringEnabled()) {
                HeartRateService.start(this)
                requestTimeSyncForCurrentOpen()
            }
        }
    }

    override fun onDestroy() {
        controller.stopScan()
        super.onDestroy()
    }

    private fun requestPermissionsIfNeeded() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(
                this,
                it,
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            if (monitoringEnabled()) {
                HeartRateService.start(this)
                requestTimeSyncForCurrentOpen()
            }
            refreshDiscovery()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requestTimeSyncForCurrentOpen() {
        if (timeSyncRequestedForOpen || !monitoringEnabled()) return
        timeSyncRequestedForOpen = true
        HeartRateService.syncTime(this)
    }

    private fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(
                this,
                it,
            ) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredPermissions(): Array<String> {
        val result = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            result += Manifest.permission.BLUETOOTH_SCAN
            result += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            result += Manifest.permission.ACCESS_FINE_LOCATION
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result += Manifest.permission.POST_NOTIFICATIONS
        }

        return result.toTypedArray()
    }

    private fun loadThemeMode(): ThemeMode =
        ThemeMode.fromKey(
            getSharedPreferences(
                HeartRateService.PREFS,
                MODE_PRIVATE,
            ).getString(
                KEY_THEME_MODE,
                ThemeMode.AUTO.key,
            ),
        )

    private fun saveThemeMode(mode: ThemeMode) {
        getSharedPreferences(
            HeartRateService.PREFS,
            MODE_PRIVATE,
        ).edit().putString(KEY_THEME_MODE, mode.key).apply()
    }

    private fun monitoringEnabled(): Boolean {
        val prefs = getSharedPreferences(HeartRateService.PREFS, MODE_PRIVATE)
        if (!prefs.contains(HeartRateService.KEY_BACKGROUND_MONITORING_ENABLED)) {
            val legacy = prefs.getBoolean(HeartRateService.KEY_NOTIFICATION_ENABLED, true)
            prefs.edit()
                .putBoolean(HeartRateService.KEY_BACKGROUND_MONITORING_ENABLED, legacy)
                .apply()
            return legacy
        }
        return prefs.getBoolean(
            HeartRateService.KEY_BACKGROUND_MONITORING_ENABLED,
            true,
        )
    }

    private fun notificationEnabled(): Boolean =
        getSharedPreferences(
            HeartRateService.PREFS,
            MODE_PRIVATE,
        ).getBoolean(
            HeartRateService.KEY_NOTIFICATION_ENABLED,
            true,
        )

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
    }

    private fun refreshDiscovery(force: Boolean = false) {
        val savedAddress = getSharedPreferences(
            HeartRateService.PREFS,
            MODE_PRIVATE,
        ).getString(HeartRateService.KEY_ADDRESS, null)

        if (force || savedAddress.isNullOrBlank()) {
            controller.startScan()
        }
    }
}

@Composable
private fun Dashboard(
    snapshot: LiveHeartRateSnapshot,
    devices: List<FoundWatch>,
    currentThemeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    onScan: () -> Unit,
    onConnect: (FoundWatch) -> Unit,
    onDisconnect: () -> Unit,
    onOverlay: () -> Unit,
    onOverlayOff: () -> Unit,
    onOverlayLock: (Boolean) -> Unit,
    onOverlaySize: (Float) -> Unit,
    onMonitoringEnabled: (Boolean) -> Unit,
    onNotificationEnabled: (Boolean) -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    var graphWindow by remember { mutableStateOf(GraphWindow.H5) }
    var selectedPoint by remember { mutableStateOf<HeartRatePoint?>(null) }
    val context = LocalContext.current
    val systemNotificationsAllowed = NotificationManagerCompat
        .from(context)
        .areNotificationsEnabled()

    val visiblePoints = remember(snapshot.graph, snapshot.longGraph, graphWindow) {
        val source = if (graphWindow == GraphWindow.H24) {
            snapshot.longGraph
        } else {
            snapshot.graph
        }
        val now = System.currentTimeMillis()
        source.filter {
            it.timestamp >= now - graphWindow.millis
        }
    }

    LaunchedEffect(graphWindow, visiblePoints.size, snapshot.bpm) {
        val selected = selectedPoint
        if (selected != null && visiblePoints.none { it.timestamp == selected.timestamp }) {
            selectedPoint = null
        }
    }

    val connectedShape = RoundedCornerShape(18.dp)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "WATCH DATA SYNC",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Fastrack FT_38093",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Live heart rate • auto time sync • background BLE",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = connectedShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            "LIVE HEART RATE",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            snapshot.bpm?.toString() ?: "—",
                            style = MaterialTheme.typography.displayLarge,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "BPM",
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            if (snapshot.connected) "● LIVE" else "○ OFFLINE",
                            color = if (snapshot.connected) {
                                Color(0xFF5DFFB2)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            snapshot.deviceName ?: "No saved watch",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        val batteryText = buildString {
                            append("Battery ")
                            append(snapshot.batteryPercent?.let { "$it%" } ?: "—")
                            if (snapshot.batteryCharging == true) append(" • Charging")
                        }
                        Text(
                            batteryText,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                }

                Text(
                    snapshot.status,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatPill("AVG", snapshot.averageBpm)
                    StatPill("MIN", snapshot.minimumBpm)
                    StatPill("MAX", snapshot.maximumBpm)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = connectedShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "LIVE TREND",
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        graphWindow.label,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }

                if (selectedPoint != null) {
                    val formatted = remember(selectedPoint!!.timestamp) {
                        SimpleDateFormat(
                            "HH:mm:ss",
                            Locale.getDefault(),
                        ).format(Date(selectedPoint!!.timestamp))
                    }
                    Text(
                        "Selected • " + selectedPoint!!.bpm + " bpm • " + formatted,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                HeartGraph(
                    points = visiblePoints,
                    selectedPoint = selectedPoint,
                    onPointSelected = { selectedPoint = it },
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    GraphWindow.entries.forEach { option ->
                        val selected = graphWindow == option
                        if (selected) {
                            Button(
                                onClick = {
                                    graphWindow = option
                                    selectedPoint = null
                                },
                                modifier = Modifier.width(66.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            ) {
                                Text(
                                    option.label,
                                    maxLines = 1,
                                    softWrap = false,
                                    fontSize = 12.sp,
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    graphWindow = option
                                    selectedPoint = null
                                },
                                modifier = Modifier.width(66.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            ) {
                                Text(
                                    option.label,
                                    maxLines = 1,
                                    softWrap = false,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }

                Text(
                    "Tap the graph to inspect a sample • RAM-only history",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = connectedShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "WATCH CONNECTION",
                    fontWeight = FontWeight.Bold,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(snapshot.deviceName ?: "No saved watch")
                        Text(
                            when {
                                snapshot.connected -> "Connected in background"
                                snapshot.deviceName != null -> "Saved watch • auto-connect enabled"
                                else -> "No saved watch"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Text(
                        "BATTERY " + (snapshot.batteryPercent?.let { "$it%" } ?: "—"),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        enabled = snapshot.connected,
                    ) {
                        Text("Disconnect")
                    }

                    Button(
                        onClick = onScan,
                        enabled = !snapshot.connected,
                    ) {
                        Text("Find watch")
                    }
                }

                devices.forEach { device ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "RSSI " + device.rssi,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        OutlinedButton(
                            onClick = { onConnect(device) },
                        ) {
                            Text("Save & connect")
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = connectedShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("CONTROLS", fontWeight = FontWeight.Bold)

                ControlRow(
                    title = "Background monitoring",
                    subtitle = "Keeps BLE + live HR running. Off disconnects the saved watch.",
                    checked = snapshot.backgroundMonitoringEnabled,
                    onCheckedChange = onMonitoringEnabled,
                )

                ControlRow(
                    title = "Notifications",
                    subtitle = if (systemNotificationsAllowed) {
                        if (snapshot.notificationEnabled) {
                            "Live BPM, stats and battery."
                        } else {
                            "Quiet foreground notification keeps BLE alive."
                        }
                    } else {
                        "Android notification permission is blocked."
                    },
                    checked = snapshot.notificationEnabled,
                    onCheckedChange = onNotificationEnabled,
                )

                if (!systemNotificationsAllowed) {
                    OutlinedButton(
                        onClick = onOpenNotificationSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open system notification settings")
                    }
                }

                ControlRow(
                    title = "Overlay",
                    subtitle = "Remembers ON/OFF across app restarts.",
                    checked = snapshot.overlayVisible,
                    onCheckedChange = { enabled ->
                        if (enabled) onOverlay() else onOverlayOff()
                    },
                )

                ControlRow(
                    title = "Lock overlay",
                    subtitle = "Prevents accidental dragging.",
                    checked = snapshot.overlayLocked,
                    onCheckedChange = onOverlayLock,
                )

                Text(
                    "Overlay size • " +
                        (snapshot.overlayScale * 100).roundToInt() + "%",
                    fontWeight = FontWeight.SemiBold,
                )

                Slider(
                    value = snapshot.overlayScale,
                    onValueChange = onOverlaySize,
                    valueRange = 0.70f..1.60f,
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = connectedShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("APPEARANCE", fontWeight = FontWeight.Bold)
                Text(
                    "Auto follows the phone theme. OLED uses pure black for OLED displays.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    ThemeMode.entries.forEach { option ->
                        if (option == currentThemeMode) {
                            Button(
                                onClick = { onThemeMode(option) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(option.label)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onThemeMode(option) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(option.label)
                            }
                        }
                    }
                }
            }
        }

        Text(
            "Time sync: once when this app session opens.",

            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )

        Spacer(
            Modifier
                .height(8.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
        )
    }
}

@Composable
private fun ControlRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun StatPill(
    label: String,
    value: Int?,
) {
    Box(
        modifier = Modifier
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline,
                RoundedCornerShape(14.dp),
            )
            .padding(
                horizontal = 12.dp,
                vertical = 8.dp,
            ),
    ) {
        Column {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value?.toString() ?: "—",
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private data class GraphMetrics(
    val minTime: Long,
    val timeRange: Long,
    val minValue: Int,
    val valueRange: Int,
)

@Composable
private fun HeartGraph(
    points: List<HeartRatePoint>,
    selectedPoint: HeartRatePoint?,
    onPointSelected: (HeartRatePoint) -> Unit,
) {
    if (points.size < 2) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(112.dp),
        ) {
            Text(
                "Waiting for heart-rate samples…",
                modifier = Modifier.padding(top = 44.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val graphMetrics = remember(points) {
        val minTime = points.minOf { it.timestamp }
        val maxTime = points.maxOf { it.timestamp }
        val minValue = (points.minOf { it.bpm } - 4).coerceAtLeast(20)
        val maxValue = (points.maxOf { it.bpm } + 4).coerceAtMost(220)
        GraphMetrics(
            minTime = minTime,
            timeRange = (maxTime - minTime).coerceAtLeast(1L),
            minValue = minValue,
            valueRange = (maxValue - minValue).coerceAtLeast(1),
        )
    }

    val minTime = graphMetrics.minTime
    val timeRange = graphMetrics.timeRange
    val minValue = graphMetrics.minValue
    val valueRange = graphMetrics.valueRange
    val graphColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val selectedColor = MaterialTheme.colorScheme.onSurface

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .pointerInput(points) {
                detectTapGestures { tap ->
                    val ratio =
                        (tap.x / size.width.toFloat().coerceAtLeast(1f))
                            .coerceIn(0f, 1f)
                    val targetTime =
                        minTime + (ratio * timeRange.toFloat()).toLong()

                    points.minByOrNull {
                        abs(it.timestamp - targetTime)
                    }?.let(onPointSelected)
                }
            },
    ) {
        val path = Path()

        points.forEachIndexed { index, point ->
            val x =
                size.width *
                    ((point.timestamp - minTime).toFloat() / timeRange.toFloat())
            val y =
                size.height -
                    (
                        (point.bpm - minValue).toFloat() /
                            valueRange.toFloat()
                        ) * size.height

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        drawLine(
            color = gridColor,
            start = Offset(0f, size.height - 1f),
            end = Offset(size.width, size.height - 1f),
            strokeWidth = 2f,
        )

        drawPath(
            path = path,
            color = graphColor,
            style = Stroke(width = 4f),
        )

        selectedPoint?.let { selected ->
            val x =
                size.width *
                    ((selected.timestamp - minTime).toFloat() /
                        timeRange.toFloat())

            val y =
                size.height -
                    (
                        (selected.bpm - minValue).toFloat() /
                            valueRange.toFloat()
                        ) * size.height

            drawCircle(
                color = selectedColor,
                radius = 7f,
                center = Offset(x, y),
            )
            drawCircle(
                color = graphColor,
                radius = 4f,
                center = Offset(x, y),
            )
        }
    }
}
