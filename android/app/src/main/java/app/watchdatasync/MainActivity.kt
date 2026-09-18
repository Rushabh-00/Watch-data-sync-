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
import androidx.compose.foundation.layout.height
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
            TopAppBar(title = { Text("Watch Data Sync • BLE lab") })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (connected) "Connected — GATT discovered" else "Disconnected",
                style = MaterialTheme.typography.titleMedium,
            )

            error?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
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
            }

            Text("Devices", style = MaterialTheme.typography.titleLarge)

            LazyColumn(
                modifier = Modifier.weight(0.9f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(devices, key = { it.address }) { device ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.connect(device.address) }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                        Text(device.address + "  RSSI " + device.rssi)
                        Text(if (device.bonded) "Bonded" else "Not bonded")
                    }
                    HorizontalDivider()
                }
            }

            Text("GATT services", style = MaterialTheme.typography.titleLarge)

            LazyColumn(
                modifier = Modifier.weight(0.8f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(services, key = { it.uuid }) { service ->
                    Text("Service " + service.uuid)
                    service.characteristics.forEach { characteristic ->
                        Text(
                            "  " + characteristic.uuid +
                                " • " + characteristic.properties.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }

            Text("Event log", style = MaterialTheme.typography.titleLarge)

            Text(
                logs.takeLast(12).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
