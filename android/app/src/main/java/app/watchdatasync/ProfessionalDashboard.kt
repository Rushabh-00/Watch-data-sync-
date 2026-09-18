package app.watchdatasync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.watchdatasync.model.GattValue
import app.watchdatasync.protocol.VendorHistoryProtocol
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProfessionalDashboardScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val liveHeartRate by viewModel.liveHeartRate.collectAsStateWithLifecycle()
    val heartRateHistory by viewModel.heartRateHistory.collectAsStateWithLifecycle()
    val spo2History by viewModel.spo2History.collectAsStateWithLifecycle()
    val sleepHistory by viewModel.sleepHistory.collectAsStateWithLifecycle()
    val batteryPercent by viewModel.batteryPercent.collectAsStateWithLifecycle()
    val lastSyncAt by viewModel.lastSyncAt.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val activityProbeStatus by viewModel.activityProbeStatus.collectAsStateWithLifecycle()
    val values by viewModel.values.collectAsStateWithLifecycle()
    val boundWatchName by viewModel.boundWatchName.collectAsStateWithLifecycle()

    val permissions = rememberProfessionalBluetoothPermissions()
    var permissionsGranted by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionsGranted = result.values.all { it }
        if (permissionsGranted) viewModel.startAutomaticWatchDiscovery()
    }

    LaunchedEffect(Unit) {
        permissionsGranted = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    val latestSpO2 = spo2History.maxByOrNull { it.epochMillis }?.percent
    val lastSync = lastSyncAt?.let(::professionalDateTime) ?: "Never"
    val healthHistoryCount = heartRateHistory.size + spo2History.size + sleepHistory.size
    val vendorPackets = values.count { it.hex.isNotBlank() }
    val vendorStructures = remember(values) {
        values.mapNotNull { VendorHistoryProtocol.decode(it.hex) }
    }
    val observedEc = vendorStructures.filterIsInstance<VendorHistoryProtocol.EcBatch>().sumOf { it.records.size } +
        vendorStructures.filterIsInstance<VendorHistoryProtocol.EcDateMarker>().size
    val observedFaPages = vendorStructures.filterIsInstance<VendorHistoryProtocol.FaPage>().size
    val syncReady = connected && !syncing

    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "Watch Data Sync",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "FT_38093 companion",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (connected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Text(
                        if (connected) "CONNECTED" else "OFFLINE",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                boundWatchName ?: "No watch connected",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (connected) "Direct BLE • no vendor app required"
                                else "Connect your watch to sync local history",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (syncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp,
                            )
                        }
                    }

                    if (syncing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            "Synchronizing verified watch data…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = connected && !syncing,
                            onClick = viewModel::syncNow,
                        ) {
                            Text(if (syncing) "Syncing…" else "Sync now")
                        }

                        OutlinedButton(
                            onClick = {
                                if (!permissionsGranted) {
                                    permissionLauncher.launch(permissions)
                                } else if (connected) {
                                    viewModel.disconnect()
                                } else {
                                    viewModel.startScan()
                                }
                            },
                        ) {
                            Text(
                                when {
                                    !permissionsGranted -> "Grant Bluetooth"
                                    connected -> "Disconnect"
                                    else -> "Find watch"
                                },
                            )
                        }
                    }
                }
            }
        }

        error?.let { message ->
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Connection issue", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            Text(
                "Today",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ProfessionalMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Heart rate",
                    value = liveHeartRate?.let { "$it bpm" } ?: "—",
                    detail = if (liveHeartRate != null) "Live" else "Connect to watch",
                )
                ProfessionalMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "SpO₂",
                    value = latestSpO2?.let { "$it%" } ?: "—",
                    detail = if (latestSpO2 != null) "Latest history" else "No verified sample",
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ProfessionalMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Steps",
                    value = "—",
                    detail = "FT_38093 semantics pending",
                )
                ProfessionalMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Calories",
                    value = "—",
                    detail = "FT_38093 semantics pending",
                )
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Sync health", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    StatusLine("Last sync", lastSync)
                    StatusLine("Stored health samples", healthHistoryCount.toString())
                    StatusLine("Captured vendor packets", vendorPackets.toString())
                    StatusLine("Observed EC records/markers", observedEc.toString())
                    StatusLine("Observed 44 FA pages", observedFaPages.toString())
                    HorizontalDivider()
                    Text(
                        activityProbeStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Watch status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    StatusLine("Battery", batteryPercent?.let { "$it%" } ?: "—")
                    StatusLine("BLE", if (connected) "Stable connection" else "Disconnected")
                    StatusLine("Sync engine", if (syncing) "Running" else if (syncReady) "Ready" else "Waiting")
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text("Evidence-aware data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "The app only promotes metrics whose FT_38093 bytes and repeatable captures support the displayed meaning. Unidentified vendor timelines stay visible in Diagnostics without being mislabeled as steps, sleep, stress or workouts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = {}, label = { Text("Wellness only") })
                        AssistChip(onClick = {}, label = { Text("Local-first") })
                        AssistChip(onClick = {}, label = { Text("Direct BLE") })
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfessionalMetricCard(
    modifier: Modifier,
    title: String,
    value: String,
    detail: String,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

private fun professionalDateTime(epochMillis: Long): String =
    SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()).format(Date(epochMillis))

@Composable
private fun rememberProfessionalBluetoothPermissions(): Array<String> =
    remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
