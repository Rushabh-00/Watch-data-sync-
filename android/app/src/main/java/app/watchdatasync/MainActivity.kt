package app.watchdatasync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private lateinit var bleController: BleController

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (allBluetoothPermissionsGranted()) {
                bleController.startScan()
            } else {
                bleController.setPermissionError()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleController = BleController(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WatchDataSyncScreen(bleController)
                }
            }
        }

        requestBluetoothPermissions()
    }

    private fun requestBluetoothPermissions() {
        val missing = requiredBluetoothPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            bleController.startScan()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requiredBluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun allBluetoothPermissionsGranted(): Boolean =
        requiredBluetoothPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    override fun onDestroy() {
        bleController.disconnect()
        super.onDestroy()
    }
}

@androidx.compose.runtime.Composable
private fun WatchDataSyncScreen(controller: BleController) {
    val devices by controller.devices.collectAsStateWithLifecycle()
    val connected by controller.connected.collectAsStateWithLifecycle()
    val heartRate by controller.heartRate.collectAsStateWithLifecycle()
    val status by controller.status.collectAsStateWithLifecycle()
    val error by controller.error.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Watch Data Sync",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = "FT_38093 • time sync + live heart rate only",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(status)
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Watch",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                Button(
                    onClick = { controller.startScan() },
                    enabled = !connected,
                ) {
                    Text("Scan")
                }

                devices.forEach { device ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "RSSI " + device.rssi,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        OutlinedButton(
                            onClick = { controller.connect(device.device) },
                        ) {
                            Text("Connect")
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Live heart rate",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                Text(
                    text = heartRate?.let { "$it bpm" } ?: "—",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )

                Text(
                    text = "E5 11 00 BPM from FT_38093 live-data",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (connected) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Watch time",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )

                    Text("The watch clock is synchronized automatically after live heart-rate notifications are enabled.")

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { controller.syncTime() }) {
                            Text("Sync time")
                        }

                        OutlinedButton(onClick = { controller.disconnect() }) {
                            Text("Disconnect")
                        }
                    }
                }
            }
        }
    }
}
