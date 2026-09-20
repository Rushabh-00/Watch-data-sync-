package app.watchdatasync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var controller: BleController

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasRequiredPermissions()) {
                HeartRateService.start(this)
                refreshDiscovery()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = BleController(this)

        setContent {
            val snapshot by LiveHeartRateState.snapshot.collectAsStateWithLifecycle()
            val devices by controller.devices.collectAsStateWithLifecycle()

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF64E9FF),
                    secondary = Color(0xFFA58BFF),
                    background = Color(0xFF070A12),
                    surface = Color(0xFF0E1321),
                    surfaceVariant = Color(0xFF171D2E),
                ),
            ) {
                Surface(Modifier.fillMaxSize()) {
                    Dashboard(
                        snapshot = snapshot,
                        devices = devices,
                        onScan = { refreshDiscovery() },
                        onConnect = { HeartRateService.connect(this, it.device.address, it.name) },
                        onSync = { HeartRateService.syncTime(this) },
                        onDisconnect = { HeartRateService.disconnect(this) },
                        onOverlay = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                                startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:$packageName"),
                                    ),
                                )
                            } else {
                                HeartRateService.overlayOn(this)
                            }
                        },
                        onOverlayOff = { HeartRateService.overlayOff(this) },
                    )
                }
            }
        }

        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (::controller.isInitialized && hasRequiredPermissions()) {
            HeartRateService.start(this)
        }
    }

    override fun onDestroy() {
        controller.stopScan()
        super.onDestroy()
    }

    private fun requestPermissionsIfNeeded() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            HeartRateService.start(this)
            refreshDiscovery()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
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

    private fun refreshDiscovery() {
        val savedAddress = getSharedPreferences(
            HeartRateService.PREFS,
            MODE_PRIVATE,
        ).getString(HeartRateService.KEY_ADDRESS, null)

        if (savedAddress.isNullOrBlank()) {
            controller.startScan()
        }
    }
}

@androidx.compose.runtime.Composable
private fun Dashboard(
    snapshot: LiveHeartRateSnapshot,
    devices: List<FoundWatch>,
    onScan: () -> Unit,
    onConnect: (FoundWatch) -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
    onOverlay: () -> Unit,
    onOverlayOff: () -> Unit,
) {
    val connectedShape = RoundedCornerShape(28.dp)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070A12))
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "WATCH DATA SYNC",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )

        Text(
            "Fastrack FT_38093",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        Text(
            "Live heart rate • automatic time sync • background connection",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            shape = connectedShape,
            colors = CardDefaults.cardColors(containerColor = Color(0xFF10182A)),
        ) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LIVE HEART RATE", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    snapshot.bpm?.let { "$it" } ?: "—",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("BPM", fontWeight = FontWeight.Bold)
                Text(
                    snapshot.status,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatPill("AVG", snapshot.averageBpm)
                    StatPill("MIN", snapshot.minimumBpm)
                    StatPill("MAX", snapshot.maximumBpm)
                }
            }
        }

        Card(
            shape = connectedShape,
            colors = CardDefaults.cardColors(containerColor = Color(0xFF10182A)),
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("LIVE TREND", fontWeight = FontWeight.Bold)
                HeartGraph(snapshot.graph)
                Text(
                    "Session graph • RAM-only • last 5 minutes",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(
            shape = connectedShape,
            colors = CardDefaults.cardColors(containerColor = Color(0xFF10182A)),
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("WATCH CONNECTION", fontWeight = FontWeight.Bold)
                Text(snapshot.deviceName ?: "No saved watch")
                Text(
                    if (snapshot.connected) "Connected in background" else "Auto-connect enabled for the saved watch",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onSync, enabled = snapshot.connected) {
                        Text("Sync time")
                    }
                    OutlinedButton(onClick = onDisconnect, enabled = snapshot.connected) {
                        Text("Disconnect")
                    }
                }

                OutlinedButton(onClick = onScan, enabled = !snapshot.connected) {
                    Text("Scan for another watch")
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
                        OutlinedButton(onClick = { onConnect(device) }) {
                            Text("Save & connect")
                        }
                    }
                }
            }
        }

        Card(
            shape = connectedShape,
            colors = CardDefaults.cardColors(containerColor = Color(0xFF10182A)),
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("LIVE DISPLAY", fontWeight = FontWeight.Bold)
                Text(
                    "Use a floating BPM pill above other apps.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onOverlay, enabled = snapshot.connected) {
                        Text("Show overlay")
                    }
                    OutlinedButton(onClick = onOverlayOff) {
                        Text("Hide overlay")
                    }
                }
            }
        }

        Text(
            "Time: " + if (snapshot.timeSynced) "synced automatically" else "waiting",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
    }
}

@androidx.compose.runtime.Composable
private fun StatPill(label: String, value: Int?) {
    Box(
        modifier = Modifier
            .border(1.dp, Color(0xFF243A56), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF7F8EA7))
            Text(value?.toString() ?: "—", fontWeight = FontWeight.Bold)
        }
    }
}

@androidx.compose.runtime.Composable
private fun HeartGraph(values: List<Int>) {
    if (values.size < 2) {
        Box(Modifier.fillMaxWidth().height(150.dp)) {
            Text(
                "Waiting for heart-rate samples…",
                modifier = Modifier.padding(top = 58.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val minValue = (values.minOrNull() ?: 60) - 4
        val maxValue = (values.maxOrNull() ?: 100) + 4
        val range = (maxValue - minValue).coerceAtLeast(1)

        val path = Path()
        values.forEachIndexed { index, bpm ->
            val x = if (values.size == 1) 0f else size.width * index / (values.size - 1)
            val y = size.height - ((bpm - minValue).toFloat() / range * size.height)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawLine(
            color = Color(0xFF1F2A3D),
            start = Offset(0f, size.height - 1f),
            end = Offset(size.width, size.height - 1f),
            strokeWidth = 2f,
        )

        drawPath(
            path = path,
            color = Color(0xFF64E9FF),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f),
        )
    }
}
