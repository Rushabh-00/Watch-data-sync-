package app.watchdatasync

import android.Manifest
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

    val heartRate = latestDecoded(values, UUID_HEART_RATE) ?: "—"
    val spo2 = latestDecoded(values, UUID_SPO2) ?: "—"
    val battery = latestDecoded(values, UUID_BATTERY) ?: "—"

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
                        helper = "Standard BLE",
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
                        "Heart rate, SpO₂ and battery can use standard BLE characteristics when the watch exposes them.",
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
                1 -> it.characteristicUuid.equals(UUID_HEART_RATE, ignoreCase = true)
                2 -> !it.characteristicUuid.equals(UUID_HEART_RATE, ignoreCase = true)
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
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Services and characteristics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                                    characteristic.uuid + " • " + characteristic.properties.joinToString(" / "),
                                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                                    style = MaterialTheme.typography.bodySmall,
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
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Raw event log", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        logs.takeLast(40).joinToString("\n").ifBlank { "No events yet." },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Captured values", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Total captured: " + values.size,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Raw packets stay visible here so model-specific protocol decoding can be verified from observed bytes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
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
private const val UUID_BATTERY = "00002a19-0000-1000-8000-00805f9b34fb"
private const val UUID_SPO2 = "00002a5f-0000-1000-8000-00805f9b34fb"
