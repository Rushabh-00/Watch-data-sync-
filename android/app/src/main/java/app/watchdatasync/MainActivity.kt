package app.watchdatasync

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                WatchDataSyncScreen(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchDataSyncScreen(viewModel: MainViewModel) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val services by viewModel.services.collectAsStateWithLifecycle()
    val values by viewModel.values.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var hasPermissions by remember { mutableStateOf(false) }

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

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
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Watch Data Sync") })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = when {
                        !connected -> "Disconnected"
                        services.isEmpty() -> "Connected — discovering GATT"
                        else -> "Connected — GATT ready"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )

                error?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

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

                    Button(
                        onClick = { viewModel.disconnect() },
                        enabled = connected,
                    ) {
                        Text("Disconnect")
                    }

                    Button(
                        onClick = { viewModel.refreshStandardData() },
                        enabled = connected && services.isNotEmpty(),
                    ) {
                        Text("Refresh data")
                    }
                }
            }

            item {
                Text("Devices", style = MaterialTheme.typography.titleLarge)
            }

            if (devices.isEmpty()) {
                item {
                    Text(
                        "No BLE devices discovered yet. Tap Scan.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                items(devices, key = { it.address }) { device ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.connect(device.address) }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                        Text(device.address + " • RSSI " + device.rssi)
                        Text(if (device.bonded) "Bonded" else "Not bonded")
                    }
                    HorizontalDivider()
                }
            }

            item {
                Text("GATT services", style = MaterialTheme.typography.titleLarge)
            }

            if (services.isEmpty()) {
                item {
                    Text(
                        "Connect to the watch to discover services and characteristics.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                items(services, key = { it.uuid }) { service ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Text("Service " + service.uuid, style = MaterialTheme.typography.titleMedium)

                        service.characteristics.forEach { characteristic ->
                            val canRead = characteristic.properties.contains("READ")
                            val canNotify =
                                characteristic.properties.contains("NOTIFY") ||
                                    characteristic.properties.contains("INDICATE")

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 8.dp, top = 6.dp, bottom = 4.dp),
                            ) {
                                Text(
                                    characteristic.uuid,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    characteristic.properties.joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (canRead) {
                                        Button(
                                            onClick = {
                                                viewModel.readCharacteristic(
                                                    service.uuid,
                                                    characteristic.uuid,
                                                )
                                            },
                                        ) {
                                            Text("Read")
                                        }
                                    }

                                    if (canNotify) {
                                        Button(
                                            onClick = {
                                                viewModel.enableNotifications(
                                                    service.uuid,
                                                    characteristic.uuid,
                                                )
                                            },
                                        ) {
                                            Text("Listen")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }

            item {
                Text("Watch data", style = MaterialTheme.typography.titleLarge)
            }

            if (values.isEmpty()) {
                item {
                    Text(
                        "No characteristic values captured yet. Read a characteristic or tap Listen on a notification characteristic.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                items(values.asReversed(), key = { it.key }) { value ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Text(
                            value.timestamp + " • " + value.characteristicUuid,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        value.decoded?.let {
                            Text(it, style = MaterialTheme.typography.titleMedium)
                        }
                        Text("HEX  " + value.hex, style = MaterialTheme.typography.bodySmall)
                        Text("TEXT " + value.ascii, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }

            item {
                Text("Event log", style = MaterialTheme.typography.titleLarge)
                Text(
                    logs.takeLast(20).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.padding(bottom = 16.dp))
            }
        }
    }
}
