package app.watchdatasync

import android.Manifest
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.watchdatasync.model.GattValue
import app.watchdatasync.model.WatchDevice
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                WatchDataSyncApp(viewModel)
            }
        }
    }
}

private enum class AppTab(val label: String, val iconText: String) {
    HOME("Home", "⌂"),
    HISTORY("History", "◷"),
    WATCH("Watch", "⌁"),
    DIAGNOSTICS("Diagnostics", "≡"),
}

@Composable
private fun WatchDataSyncApp(viewModel: MainViewModel) {
    var selectedTab by remember { mutableStateOf(AppTab.HOME) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Text(tab.iconText) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (selectedTab) {
                AppTab.HOME -> HomeScreen(viewModel)
                AppTab.HISTORY -> HistoryScreen(viewModel)
                AppTab.WATCH -> WatchScreen(viewModel)
                AppTab.DIAGNOSTICS -> DiagnosticsScreen(viewModel)
            }
        }
    }
}

@Composable
private fun HomeScreen(viewModel: MainViewModel) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val values by viewModel.values.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val permissions = rememberBluetoothPermissions()
    var hasPermissions by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasPermissions = result.values.all { it }
    }

    LaunchedEffect(Unit) {
        hasPermissions = permissions.all {
            ContextCompat.checkSelfPermission(
                viewModel.getApplication(),
                it,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    val heartRate = if (connected) {
        latestDecoded(values, UUID_HEART_RATE)
            ?: latestDecoded(values, UUID_VENDOR_HEART_RATE)
            ?: "—"
    } else {
        "—"
    }
    val spo2 = if (connected) latestDecoded(values, UUID_SPO2) ?: "—" else "—"
    // The standard Battery Service value is not authoritative for this watch.
    // Keep it hidden until we observe and verify the FT_38093 battery packet.
    val battery = "—"

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Spacer(Modifier.height(12.dp)) }

        item {
            Text(
                text = "Watch Data Sync",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Health and fitness dashboard",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            ConnectionCard(
                connected = connected,
                onScan = {
                    if (!hasPermissions) {
                        permissionLauncher.launch(permissions)
                    } else {
                        viewModel.startScan()
                    }
                },
                onDisconnect = viewModel::disconnect,
            )
        }

        item {
            SyncCard(
                connected = connected,
                syncing = syncing,
                onSync = {
                    syncing = true
                    viewModel.refreshStandardData()
                    syncing = false
                },
            )
        }

        error?.let { message ->
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        item {
            Text(
                text = "Today",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        title = "Heart rate",
                        value = heartRate,
                        helper = "Live BLE",
                    )
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        title = "SpO₂",
                        value = spo2,
                        helper = "Live BLE",
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        title = "Steps",
                        value = "—",
                        helper = "Watch protocol",
                    )
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        title = "Sleep",
                        value = "—",
                        helper = "Watch protocol",
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        title = "Calories",
                        value = "—",
                        helper = "Watch protocol",
                    )
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        title = "Battery",
                        value = battery,
                        helper = "Watch protocol",
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        title = "Workouts",
                        value = "—",
                        helper = "Watch protocol",
                    )
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        title = "Activity",
                        value = "—",
                        helper = "Watch protocol",
                    )
                }
            }
        }

        item {
            Card(
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Data availability", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Heart rate is live from the verified FT_38093 vendor BLE channel. SpO₂ and battery stay hidden until their watch-specific packets are verified.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Steps, sleep, workouts and historical records need a verified model-specific protocol adapter before the app can decode them correctly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Text(
                text = "Recent captured data",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (values.isEmpty()) {
            item {
                EmptyState(
                    title = "Nothing captured yet",
                    message = "Connect the watch and sync available data.",
                )
            }
        } else {
            items(
                items = values.asReversed().take(6),
                key = { it.key },
            ) { value ->
                CapturedValueRow(value)
            }
        }

        item {
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun HistoryScreen(viewModel: MainViewModel) {
    val values by viewModel.values.collectAsStateWithLifecycle()
    var range by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "History",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Captured watch data for this sync session",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = range == 0,
                onClick = { range = 0 },
                label = { Text("All") },
            )
            FilterChip(
                selected = range == 1,
                onClick = { range = 1 },
                label = { Text("Heart rate") },
            )
            FilterChip(
                selected = range == 2,
                onClick = { range = 2 },
                label = { Text("Other") },
            )
        }

        Spacer(Modifier.height(14.dp))

        val filtered = values.asReversed().filter {
            when (range) {
                1 -> it.characteristicUuid.equals(UUID_HEART_RATE, ignoreCase = true) ||
                    it.characteristicUuid.equals(UUID_VENDOR_HEART_RATE, ignoreCase = true)
                2 -> !it.characteristicUuid.equals(UUID_HEART_RATE, ignoreCase = true) &&
                    !it.characteristicUuid.equals(UUID_VENDOR_HEART_RATE, ignoreCase = true)
                else -> true
            }
        }

        if (filtered.isEmpty()) {
            EmptyState(
                title = "No history yet",
                message = "Once the watch exposes data, captured records will appear here.",
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.key }) { value ->
                    CapturedValueRow(value)
                }
            }
        }
    }
}

@Composable
private fun WatchScreen(viewModel: MainViewModel) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()

    val permissions = rememberBluetoothPermissions()
    var hasPermissions by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasPermissions = result.values.all { it }
    }

    LaunchedEffect(Unit) {
        hasPermissions = permissions.all {
            ContextCompat.checkSelfPermission(
                viewModel.getApplication(),
                it,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Watch",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Find and connect to your BLE watch",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (!hasPermissions) {
                            permissionLauncher.launch(permissions)
                        } else {
                            viewModel.startScan()
                        }
                    },
                ) {
                    Text("Scan")
                }

                OutlinedButton(
                    onClick = viewModel::stopScan,
                ) {
                    Text("Stop")
                }

                OutlinedButton(
                    onClick = viewModel::disconnect,
                    enabled = connected,
                ) {
                    Text("Disconnect")
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (connected) "Connected to watch" else "No watch connected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Only connect to the watch you want to inspect or sync.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (devices.isEmpty()) {
            item {
                EmptyState(
                    title = "No BLE devices found",
                    message = "Tap Scan and keep the watch nearby.",
                )
            }
        } else {
            item {
                Text(
                    "Nearby devices",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            items(devices, key = { it.address }) { device ->
                DeviceRow(
                    device = device,
                    onConnect = { viewModel.connect(device.address) },
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsScreen(viewModel: MainViewModel) {
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val services by viewModel.services.collectAsStateWithLifecycle()
    val values by viewModel.values.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Diagnostics",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (connected) "GATT connection active" else "Disconnected",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                val clipboard =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText(
                                        "Watch Data Sync diagnostics",
                                        buildDiagnosticsClipboardText(
                                            connected = connected,
                                            services = services,
                                            values = values,
                                            logs = logs,
                                        ),
                                    ),
                                )
                                copied = true
                            },
                        ) {
                            Text(if (copied) "Copied" else "Copy log")
                        }

                        OutlinedButton(
                            onClick = {
                                copied = false
                                viewModel.clearCapture()
                            },
                        ) {
                            Text("Clear capture")
                        }
                    }

                    Text(
                        "Capture keeps up to 5,000 packets and 5,000 log lines. " +
                            "Unknown packets are automatically decoded into numeric/text candidates; " +
                            "metric names are only added when verified.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Services and characteristics",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )

                    if (services.isEmpty()) {
                        Text(
                            "Connect a watch to inspect its GATT layout.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        services.forEach { service ->
                            Text(service.uuid, fontWeight = FontWeight.Medium)
                            service.characteristics.forEach { characteristic ->
                                Text(
                                    friendlyCharacteristicLabel(characteristic.uuid),
                                    modifier = Modifier.padding(
                                        start = 8.dp,
                                        bottom = 4.dp,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    characteristic.uuid + " • " +
                                        characteristic.properties.joinToString(" / "),
                                    modifier = Modifier.padding(
                                        start = 8.dp,
                                        bottom = 6.dp,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Divider(Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }

        item {
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Raw event log",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Showing latest " + minOf(logs.size, 300) + " of " + logs.size +
                            " lines. Copy log exports the full capture.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        logs.takeLast(300).joinToString("\n").ifBlank { "No events yet." },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Decoded capture",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Showing latest " + minOf(values.size, 60) + " of " + values.size +
                            " captured packets.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    values.asReversed().take(60).forEach { value ->
                        Column(
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                friendlyCharacteristicLabel(value.characteristicUuid),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                value.timestamp + " • HEX " + value.hex,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                humanReadableDecode(value),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Divider(Modifier.padding(vertical = 5.dp))
                        }
                    }

                    if (values.isEmpty()) {
                        Text(
                            "No captured packets yet.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun buildDiagnosticsClipboardText(
    connected: Boolean,
    services: List<app.watchdatasync.model.GattService>,
    values: List<GattValue>,
    logs: List<String>,
): String = buildString {
    appendLine("WATCH DATA SYNC DIAGNOSTICS")
    appendLine("Connection: " + if (connected) "CONNECTED" else "DISCONNECTED")
    appendLine()

    appendLine("HUMAN-READABLE PACKETS")
    if (values.isEmpty()) {
        appendLine("No captured packets.")
    } else {
        values.forEach { value ->
            appendLine(humanCaptureLine(value))
            appendLine("  Raw HEX: " + value.hex)
            appendLine("  Candidates: " + compactCandidates(value.decoded))
        }
    }

    appendLine()
    appendLine("GATT SERVICES / CHARACTERISTICS")
    services.forEach { service ->
        appendLine(service.uuid)
        service.characteristics.forEach { characteristic ->
            appendLine(
                "  " + friendlyCharacteristicLabel(characteristic.uuid) +
                    " | " + characteristic.uuid +
                    " | " + characteristic.properties.joinToString(" / "),
            )
        }
    }

    appendLine()
    appendLine("RAW EVENT LOG (" + logs.size + " lines)")
    logs.forEach { appendLine(it) }
}


@Composable
private fun ConnectionCard(
    connected: Boolean,
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (connected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (connected) "Watch connected" else "Watch not connected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (connected) {
                    "BLE is ready for available live data."
                } else {
                    "Connect a compatible watch to start."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onScan) {
                    Text(if (connected) "Scan another" else "Find watch")
                }
                OutlinedButton(
                    onClick = onDisconnect,
                    enabled = connected,
                ) {
                    Text("Disconnect")
                }
            }
        }
    }
}

@Composable
private fun SyncCard(
    connected: Boolean,
    syncing: Boolean,
    onSync: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Sync",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (connected) {
                    "Read standard watch data and listen for supported live measurements."
                } else {
                    "Connect a watch before starting sync."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onSync,
                enabled = connected && !syncing,
            ) {
                Text(if (syncing) "Syncing…" else "Sync available data")
            }
            if (syncing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier,
    title: String,
    value: String,
    helper: String,
) {
    Card(
        modifier = modifier,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = helper,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceRow(
    device: WatchDevice,
    onConnect: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onConnect),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(device.name, fontWeight = FontWeight.SemiBold)
            Text(
                device.address + " • RSSI " + device.rssi,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (device.bonded) "Bonded" else "Not bonded",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun friendlyCharacteristicLabel(uuid: String): String {
    val id = uuid.lowercase(Locale.ROOT)
    return when {
        id == UUID_VENDOR_HEART_RATE -> "FT_38093 live-data channel (33F2)"
        id == "000034f2-0000-1000-8000-00805f9b34fb" -> "FT_38093 vendor channel (34F2)"
        id == "00006002-0000-1000-8000-00805f9b34fb" -> "FT_38093 vendor channel (6002)"
        id == "00006102-0000-1000-8000-00805f9b34fb" -> "FT_38093 vendor channel (6102)"
        id == "00006487-3c17-d293-8e48-14fe2e4da212" -> "FT_38093 vendor channel (6487)"
        id == "0000fd04-0000-1000-8000-00805f9b34fb" -> "FT_38093 vendor channel (FD04)"
        id == UUID_BATTERY -> "Battery Level (standard BLE; unverified for this watch)"
        id == UUID_HEART_RATE -> "Heart Rate Measurement (standard BLE)"
        id == UUID_SPO2 -> "SpO₂ Continuous (standard BLE)"
        id == "00002a5e-0000-1000-8000-00805f9b34fb" -> "SpO₂ Spot Check (standard BLE)"
        else -> "BLE characteristic"
    }
}

private fun humanReadableDecode(value: GattValue): String {
    val decoded = value.decoded.orEmpty()
    return when {
        decoded.startsWith("Heart rate ") ->
            "VERIFIED • " + decoded
        decoded.startsWith("Heart-rate frame ") ->
            "Known FT_38093 heart-rate frame • " + decoded.removePrefix("Heart-rate frame ")
        decoded.startsWith("FT_38093 vendor frame") ->
            "UNIDENTIFIED FT_38093 vendor packet • " + decoded.removePrefix("FT_38093 vendor frame • ")
        decoded.startsWith("SpO₂ ") ->
            "Decoded by standard SpO₂ format • " + decoded
        decoded.isBlank() ->
            "UNIDENTIFIED PACKET"
        decoded.startsWith("RAW bytes") ->
            "UNIDENTIFIED PACKET • " + compactCandidates(decoded)
        else ->
            decoded
    }
}

private fun compactCandidates(decoded: String?): String {
    if (decoded.isNullOrBlank()) return "No automatic decoder output"

    val keys = listOf("U8", "S8", "U16LE", "S16LE", "U16BE", "S16BE", "U32LE", "S32LE", "U32BE", "S32BE")
    val parts = keys.mapNotNull { key ->
        Regex("\\Q$key=\\E\\[([^]]*)\\]").find(decoded)?.groupValues?.get(1)
            ?.takeIf { it != "—" }
            ?.let { key + "=" + it }
    }

    return if (parts.isEmpty()) {
        decoded
    } else {
        parts.take(6).joinToString(" • ")
    }
}

private fun humanCaptureLine(value: GattValue): String {
    val prefix = value.timestamp + " • " + friendlyCharacteristicLabel(value.characteristicUuid)
    val decoded = value.decoded.orEmpty()

    return when {
        decoded.startsWith("Heart rate ") ->
            prefix + " • VERIFIED ❤️ " + decoded
        decoded.startsWith("Heart-rate frame ") ->
            prefix + " • HR FRAME • " + decoded.removePrefix("Heart-rate frame ")
        decoded.startsWith("FT_38093 vendor frame") ->
            prefix + " • UNKNOWN VENDOR FRAME • " +
                decoded.removePrefix("FT_38093 vendor frame • ")
        decoded.startsWith("SpO₂ ") ->
            prefix + " • SPO₂ • " + decoded
        else ->
            prefix + " • UNKNOWN PACKET • candidates: " + compactCandidates(decoded)
    }
}

@Composable
private fun CapturedValueRow(value: GattValue) {
    Card {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                value.decoded ?: value.characteristicUuid,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                value.timestamp + " • " + value.characteristicUuid,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "HEX  " + value.hex,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun EmptyState(title: String, message: String) {
    Card(
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun rememberBluetoothPermissions(): Array<String> = remember {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

private fun latestDecoded(values: List<GattValue>, characteristicUuid: String): String? =
    values.asReversed()
        .firstOrNull { it.characteristicUuid.equals(characteristicUuid, ignoreCase = true) }
        ?.decoded
        ?.let { normalizeMetricLabel(it) }

private fun normalizeMetricLabel(value: String): String =
    value.replace("Heart rate ", "", ignoreCase = true)
        .replace("Battery ", "", ignoreCase = true)
        .replace("bpm", "bpm", ignoreCase = true)
        .trim()

private const val UUID_HEART_RATE = "00002a37-0000-1000-8000-00805f9b34fb"
private const val UUID_VENDOR_HEART_RATE = "000033f2-0000-1000-8000-00805f9b34fb"
private const val UUID_SPO2 = "00002a5f-0000-1000-8000-00805f9b34fb"
