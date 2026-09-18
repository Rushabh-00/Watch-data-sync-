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
import app.watchdatasync.FastrackFeatureHubScreen
import app.watchdatasync.model.GattValue
import app.watchdatasync.model.WatchDevice
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
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
    FEATURES("More", "✦"),
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
                AppTab.FEATURES -> FeatureHubScreen(viewModel)
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
    val liveHeartRate by viewModel.liveHeartRate.collectAsStateWithLifecycle()
    val heartRateHistory by viewModel.heartRateHistory.collectAsStateWithLifecycle()
    val spo2History by viewModel.spo2History.collectAsStateWithLifecycle()
    val boundWatchName by viewModel.boundWatchName.collectAsStateWithLifecycle()
    val todayStepTotal by viewModel.todayStepTotal.collectAsStateWithLifecycle()
    val sleepHistory by viewModel.sleepHistory.collectAsStateWithLifecycle()
    val batteryPercent by viewModel.batteryPercent.collectAsStateWithLifecycle()
    val lastSyncAt by viewModel.lastSyncAt.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val dailyActivity by viewModel.dailyActivity.collectAsStateWithLifecycle()
    val stepHistory by viewModel.stepHistory.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var clockNow by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            clockNow = System.currentTimeMillis()
            delay(60_000L)
        }
    }

    val todayActivity = dailyActivity?.takeIf { isSameLocalDay(it.epochMillis, clockNow) }
    val dailySteps = buildDailyStepPoints(stepHistory, clockNow)
    val dailySleep = buildDailySleepPoints(sleepHistory, clockNow)

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

    LaunchedEffect(hasPermissions) {
        if (hasPermissions) {
            viewModel.startAutomaticWatchDiscovery()
        }
    }

    val heartRate = if (connected && liveHeartRate != null) {
        liveHeartRate.toString() + " bpm"
    } else {
        "—"
    }
    val spo2 = if (connected) latestDecoded(values, UUID_SPO2) ?: "—" else "—"
    // The standard Battery Service value is not authoritative for this watch.
    // Keep it hidden until we observe and verify the FT_38093 battery packet.
    val battery = batteryPercent?.let { "$it%" } ?: "—"
    val steps = todayStepTotal?.toString()
        ?: todayActivity?.steps?.toString()
        ?: "—"
    val calories = todayActivity?.calories?.toString() ?: "—"
    val distance = todayActivity?.distanceMeters?.let(::formatDistanceMeters) ?: "—"
    val activeMinutes = todayActivity?.activeMinutes?.let { it.toString() + " min" } ?: "—"
    val lastSyncLabel = lastSyncAt?.let(::formatDateTime12h) ?: "Never"

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Spacer(Modifier.height(12.dp)) }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Watch Data Sync", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "FT_38093 • local BLE • automatic history sync",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(formatClockTime(clockNow), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            SimpleDateFormat("EEE, dd MMM", Locale.US).format(Date(clockNow)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
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
                    viewModel.syncNow()
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
                        value = steps,
                        helper = when {
                            todayStepTotal != null -> "Verified B1/B2 history"
                            todayActivity != null -> "Verified activity packet"
                            else -> "Awaiting step packet"
                        },
                    )
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        title = "Sleep",
                        value = sleepSummary(sleepHistory),
                        helper = "Synced history",
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        title = "Calories",
                        value = calories,
                        helper = if (todayActivity != null) "Verified activity packet" else "Awaiting verified packet",
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
                        title = "Distance",
                        value = distance,
                        helper = if (todayActivity != null) "Verified activity packet" else "Awaiting activity packet",
                    )
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        title = "Active time",
                        value = activeMinutes,
                        helper = if (todayActivity != null) "Verified activity packet" else "Awaiting activity packet",
                    )
                }
            }
        }

        item {
            Card(
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sync status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (syncing) "Syncing watch clock, step history, sleep, HR, SpO₂ and verified activity packets…"
                        else "Last sync: $lastSyncLabel",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "The app connects directly to the FT_38093 BLE GATT service. Android system Bluetooth pairing is not required for the app's sync path.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TrendChartCard(
                    modifier = Modifier.weight(1f),
                    title = "HR trend",
                    subtitle = "Latest history",
                    points = heartRateHistory.takeLast(24).map { it.epochMillis to it.bpm.toFloat() },
                    lineColor = MaterialTheme.colorScheme.primary,
                    unit = "bpm",
                    emptyMessage = "No HR history",
                )
                TrendChartCard(
                    modifier = Modifier.weight(1f),
                    title = "SpO₂ trend",
                    subtitle = "Latest history",
                    points = spo2History.takeLast(24).map { it.epochMillis to it.percent.toFloat() },
                    lineColor = MaterialTheme.colorScheme.secondary,
                    unit = "%",
                    emptyMessage = "No SpO₂ history",
                )
            }
        }

        item {
            DailyBarChartCard(
                modifier = Modifier.fillMaxWidth(),
                title = "Steps by day",
                subtitle = "Latest 7 days • verified daily totals",
                points = dailySteps,
                barColor = MaterialTheme.colorScheme.primary,
                valueLabel = { String.format(Locale.US, "%.0f", it) },
                emptyMessage = "No verified daily step history yet.",
            )
        }

        item {
            DailyBarChartCard(
                modifier = Modifier.fillMaxWidth(),
                title = "Sleep by day",
                subtitle = "Latest 7 days • verified sleep-stage duration",
                points = dailySleep,
                barColor = MaterialTheme.colorScheme.secondary,
                valueLabel = ::formatMinutesFloat,
                emptyMessage = "No verified sleep stages yet.",
            )
        }

        item {
            DailyDataSummaryCard(
                stepHistory = stepHistory,
                sleepHistory = sleepHistory,
                nowMillis = clockNow,
            )
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
                    message = "The app will discover the FT_38093 watch and sync automatically when connected.",
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
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val heartRateHistory by viewModel.heartRateHistory.collectAsStateWithLifecycle()
    val spo2History by viewModel.spo2History.collectAsStateWithLifecycle()
    val dailyActivity by viewModel.dailyActivity.collectAsStateWithLifecycle()
    val todayStepTotal by viewModel.todayStepTotal.collectAsStateWithLifecycle()
    val stepHistory by viewModel.stepHistory.collectAsStateWithLifecycle()
    val activityProbeStatus by viewModel.activityProbeStatus.collectAsStateWithLifecycle()
    val sleepHistory by viewModel.sleepHistory.collectAsStateWithLifecycle()
    val values by viewModel.values.collectAsStateWithLifecycle()
    val nowMillis = System.currentTimeMillis()
    val todayActivity = dailyActivity?.takeIf { isSameLocalDay(it.epochMillis, nowMillis) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "History",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Trends and verified watch history",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { viewModel.syncNow() },
                    enabled = connected && !syncing,
                ) {
                    Text(if (syncing) "Syncing…" else "Sync now")
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "Activity sync",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        activityProbeStatus,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Steps use verified B1/B2 records. Calories, distance and active time appear only after a verified activity packet is received.",
                        style = MaterialTheme.typography.labelSmall,
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
                        "Today",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        MetricCard(
                            modifier = Modifier.weight(1f),
                            title = "Steps",
                            value = todayStepTotal?.toString()
                                ?: todayActivity?.steps?.toString()
                                ?: "—",
                            helper = when {
                                todayStepTotal != null -> "Verified B1/B2 history"
                                todayActivity != null -> "Verified activity packet"
                                else -> "Awaiting step packet"
                            },
                        )
                        MetricCard(
                            modifier = Modifier.weight(1f),
                            title = "Calories",
                            value = todayActivity?.calories?.toString() ?: "—",
                            helper = if (todayActivity != null) "Verified activity packet" else "Awaiting activity packet",
                        )
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
                        "Activity details",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        MetricCard(
                            modifier = Modifier.weight(1f),
                            title = "Distance",
                            value = todayActivity?.distanceMeters?.let(::formatDistanceMeters) ?: "—",
                            helper = if (todayActivity != null) "Verified activity packet" else "Awaiting activity packet",
                        )
                        MetricCard(
                            modifier = Modifier.weight(1f),
                            title = "Active time",
                            value = todayActivity?.activeMinutes?.let { it.toString() + " min" } ?: "—",
                            helper = if (todayActivity != null) "Verified activity packet" else "Awaiting activity packet",
                        )
                    }
                    Text(
                        "B2 records contain hourly cumulative steps only. The latest FT_38093 26 01 response is a watch-face configuration packet, not activity data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (stepHistory.isNotEmpty()) {
                        Text(
                            "Latest step-history points",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        stepHistory.asReversed().take(8).forEach { sample ->
                            Text(
                                formatHistoryTime(sample.epochMillis) + " • " + sample.totalSteps + " steps",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        item {
            DailyBarChartCard(
                modifier = Modifier.fillMaxWidth(),
                title = "Steps",
                subtitle = "Latest 7 daily totals",
                points = buildDailyStepPoints(stepHistory, nowMillis),
                barColor = MaterialTheme.colorScheme.primary,
                valueLabel = { String.format(Locale.US, "%.0f", it) },
                emptyMessage = "No step-history days yet.",
            )
        }

        item {
            DailyBarChartCard(
                modifier = Modifier.fillMaxWidth(),
                title = "Sleep",
                subtitle = "Latest 7 verified sleep durations",
                points = buildDailySleepPoints(sleepHistory, nowMillis),
                barColor = MaterialTheme.colorScheme.secondary,
                valueLabel = ::formatMinutesFloat,
                emptyMessage = "No sleep-stage days yet.",
            )
        }

        item {
            SleepTrendCard(sleepHistory)
        }

        item {
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Heart-rate history",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (heartRateHistory.isEmpty()) {
                        Text(
                            "No history synced yet. Keep the watch connected and press Sync now.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        heartRateHistory.asReversed().take(60).forEach { sample ->
                            Text(
                                formatHistoryTime(sample.epochMillis) + " • " + sample.bpm + " bpm",
                                style = MaterialTheme.typography.bodySmall,
                            )
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
                        "SpO₂ history",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (spo2History.isEmpty()) {
                        Text(
                            "No SpO₂ history returned by the watch yet.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        spo2History.asReversed().take(60).forEach { sample ->
                            Text(
                                formatHistoryTime(sample.epochMillis) + " • " + sample.percent + "%",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        item {
            TrendChartCard(
                title = "Heart-rate trend",
                subtitle = "Latest synced history",
                points = heartRateHistory.takeLast(48).map { it.epochMillis to it.bpm.toFloat() },
                lineColor = MaterialTheme.colorScheme.primary,
                unit = "bpm",
                emptyMessage = "No heart-rate history yet.",
            )
        }

        item {
            TrendChartCard(
                title = "SpO₂ trend",
                subtitle = "Latest synced history",
                points = spo2History.takeLast(48).map { it.epochMillis to it.percent.toFloat() },
                lineColor = MaterialTheme.colorScheme.secondary,
                unit = "%",
                emptyMessage = "No SpO₂ history yet.",
            )
        }

        item {
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Sleep history",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (sleepHistory.isEmpty()) {
                        Text(
                            "No sleep stage records returned by the watch yet.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        sleepHistory.asReversed().take(30).forEach { sample ->
                            Text(
                                formatHistoryTime(sample.epochMillis) + " • " +
                                    "Stage " + sample.stage + " • " +
                                    sample.durationMinutes + " min",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        item {
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Protocol discovery",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Unknown FT_38093 vendor packets remain available in Diagnostics for protocol verification.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    values.asReversed()
                        .filterNot(::isHeartRateCapture)
                        .take(20)
                        .forEach { value ->
                            Text(
                                value.timestamp + " • " + value.hex,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                }
            }
        }
    }
}

private fun formatDateTime12h(epochMillis: Long): String =
    SimpleDateFormat("dd MMM, h:mm a", Locale.US).format(Date(epochMillis))

private fun formatClockTime(epochMillis: Long): String =
    SimpleDateFormat("h:mm a", Locale.US).format(Date(epochMillis))

private fun formatHistoryTime(epochMillis: Long): String = formatDateTime12h(epochMillis)

private fun isSameLocalDay(firstMillis: Long, secondMillis: Long): Boolean {
    val first = Calendar.getInstance().apply { timeInMillis = firstMillis }
    val second = Calendar.getInstance().apply { timeInMillis = secondMillis }
    return first.get(Calendar.ERA) == second.get(Calendar.ERA) &&
        first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
        first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
}

private fun localDayStart(epochMillis: Long): Long =
    Calendar.getInstance().apply {
        timeInMillis = epochMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

private fun buildDailyStepPoints(
    history: List<app.watchdatasync.model.StepHistorySample>,
    nowMillis: Long,
): List<Pair<Long, Float>> {
    val today = localDayStart(nowMillis)
    val byDay = history.groupBy { localDayStart(it.epochMillis) }
        .mapValues { (_, samples) -> samples.maxOfOrNull { it.totalSteps } ?: 0 }
    return (0L..6L).mapNotNull { offset ->
        val day = Calendar.getInstance().apply {
            timeInMillis = today
            add(Calendar.DAY_OF_YEAR, -offset.toInt())
        }.timeInMillis
        byDay[day]?.let { day to it.toFloat() }
    }.sortedBy { it.first }
}

private fun buildDailySleepPoints(
    history: List<app.watchdatasync.model.SleepStageSample>,
    nowMillis: Long,
): List<Pair<Long, Float>> {
    val today = localDayStart(nowMillis)
    val byDay = history.groupBy { localDayStart(it.epochMillis) }
        .mapValues { (_, samples) -> samples.filter { it.stage in 1..3 }.sumOf { it.durationMinutes }.toFloat() }
    return (0L..6L).mapNotNull { offset ->
        val day = Calendar.getInstance().apply {
            timeInMillis = today
            add(Calendar.DAY_OF_YEAR, -offset.toInt())
        }.timeInMillis
        byDay[day]?.takeIf { it > 0f }?.let { day to it }
    }.sortedBy { it.first }
}

private fun formatMinutesFloat(value: Float): String {
    val total = value.toInt().coerceAtLeast(0)
    val hours = total / 60
    val mins = total % 60
    return if (hours > 0) hours.toString() + "h " + mins + "m" else mins.toString() + "m"
}

@Composable
private fun DailyDataSummaryCard(
    stepHistory: List<app.watchdatasync.model.StepHistorySample>,
    sleepHistory: List<app.watchdatasync.model.SleepStageSample>,
    nowMillis: Long,
) {
    val days = (0L..6L).map { offset ->
        Calendar.getInstance().apply {
            timeInMillis = localDayStart(nowMillis)
            add(Calendar.DAY_OF_YEAR, -offset.toInt())
        }.timeInMillis
    }.reversed()

    val stepsByDay = stepHistory.groupBy { localDayStart(it.epochMillis) }
        .mapValues { (_, samples) -> samples.maxOfOrNull { it.totalSteps } }
    val sleepByDay = sleepHistory.groupBy { localDayStart(it.epochMillis) }
        .mapValues { (_, samples) -> samples.filter { it.stage in 1..3 }.sumOf { it.durationMinutes } }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Daily data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Latest seven calendar days • verified records only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            days.forEach { day ->
                val steps = stepsByDay[day]
                val sleep = sleepByDay[day]?.takeIf { it > 0 }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        SimpleDateFormat("EEE, dd MMM", Locale.US).format(Date(day)),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        steps?.let { it.toString() + " steps" } ?: "—",
                        modifier = Modifier.width(82.dp),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                    Text(
                        sleep?.let(::formatMinutes) ?: "—",
                        modifier = Modifier.width(70.dp),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
            }
        }
    }
}

private fun formatMinutes(minutes: Int): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return if (hours > 0) hours.toString() + "h " + mins + "m" else mins.toString() + "m"
}

private fun formatDistanceMeters(meters: Int): String =
    String.format(Locale.US, "%.2f km", meters / 1000.0)

private fun sleepSummary(history: List<app.watchdatasync.model.SleepStageSample>): String {
    if (history.isEmpty()) return "—"
    val minutes = history.sumOf { if (it.stage in 1..3) it.durationMinutes else 0 }
    val hours = minutes / 60
    val mins = minutes % 60
    return "%dh %02dm".format(Locale.US, hours, mins)
}

@Composable
private fun WatchScreen(viewModel: MainViewModel) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val boundWatchName by viewModel.boundWatchName.collectAsStateWithLifecycle()

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
                text = "FT_38093 • direct BLE sync • no OS pairing required",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        if (boundWatchName != null) "Bound watch" else "No watch bound",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        boundWatchName ?: "Connect your FT_38093 once and the app will reconnect automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (boundWatchName != null) {
                        Text(
                            if (connected) "Auto-connected • ready for data"
                            else "Auto-connect enabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (boundWatchName != null) {
                            OutlinedButton(onClick = viewModel::forgetBoundWatch) {
                                Text("Forget watch")
                            }
                        }
                        Button(
                            onClick = {
                                if (!hasPermissions) {
                                    permissionLauncher.launch(permissions)
                                } else {
                                    viewModel.startScan()
                                }
                            },
                        ) {
                            Text("Discover & connect")
                        }
                        if (connected) {
                            OutlinedButton(onClick = viewModel::disconnect) {
                                Text("Disconnect")
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                "Nearby compatible watches",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (devices.isEmpty()) {
            item {
                EmptyState(
                    title = "No compatible watch found",
                    message = "Tap Discover watch and keep the Fastrack nearby.",
                )
            }
        } else {
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
                        "Discovery mode keeps only unknown/vendor evidence. " +
                            "Verified heart-rate and untrusted standard battery packets are omitted. " +
                            "The rolling capture is retained for 24 hours for protocol investigation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Feature capture",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Clear the old packets, mark the feature, then use only that watch feature. " +
                            "Heart-rate packets stay excluded automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = false,
                            onClick = {
                                copied = false
                                viewModel.clearCapture()
                                viewModel.markCapture("SpO2")
                            },
                            label = { Text("SpO₂") },
                        )
                        FilterChip(
                            selected = false,
                            onClick = {
                                copied = false
                                viewModel.clearCapture()
                                viewModel.markCapture("Sleep")
                            },
                            label = { Text("Sleep") },
                        )
                        FilterChip(
                            selected = false,
                            onClick = {
                                copied = false
                                viewModel.clearCapture()
                                viewModel.markCapture("Stress") 
                            },
                            label = { Text("Stress") },
                        )
                        FilterChip(
                            selected = false,
                            onClick = {
                                copied = false
                                viewModel.clearCapture()
                                viewModel.markCapture("Steps")
                            },
                            label = { Text("Steps") },
                        )
                        FilterChip(
                            selected = false,
                            onClick = {
                                copied = false
                                viewModel.clearCapture()
                                viewModel.markCapture("Workout")
                            },
                            label = { Text("Workout") },
                        )
                        FilterChip(
                            selected = false,
                            onClick = {
                                copied = false
                                viewModel.clearCapture()
                                viewModel.markCapture("History sync")
                            },
                            label = { Text("History") },
                        )
                    }
                    Text(
                        "Background discovery keeps non-heart-rate packets for the rolling last 24 hours, including after reconnect.",
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
                        "Discovery event log",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "HR and standard battery noise are omitted. " +
                            "Showing latest " + minOf(logs.size, 120) + " of " + logs.size + " events.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        logs.takeLast(120)
                            .filterNot(::isDiscoveryNoiseLog)
                            .map(::humanReadableLogLine)
                            .forEach { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodySmall,
                                )
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
                        "Unknown data capture",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val nonHeartRate = values.filterNot(::isHeartRateCapture)
                    val characteristicCount = nonHeartRate
                        .map { it.characteristicUuid.lowercase(Locale.ROOT) }
                        .distinct()
                        .size
                    Text(
                        "Rolling 24-hour discovery • " + nonHeartRate.size +
                            " packets • " + characteristicCount + " active data channels",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (connected) {
                            "Open one feature at a time: SpO₂, sleep, stress, steps or workout. " +
                                "Clear capture before a test, then leave the watch connected to capture its packets."
                        } else {
                            "The bound watch reconnects automatically. Reconnect it to continue passive capture."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    values.asReversed().filterNot { isHeartRateCapture(it) }.take(60).forEach { value ->
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

private fun isHeartRateLog(line: String): Boolean {
    val lower = line.lowercase(Locale.ROOT)
    return lower.contains(UUID_HEART_RATE) ||
        (lower.contains(UUID_VENDOR_HEART_RATE) && lower.contains("e5 11 00"))
}

private fun isDiscoveryNoiseLog(line: String): Boolean =
    isHeartRateLog(line) || line.startsWith("BATTERY standard packet omitted")

private fun humanReadableLogLine(line: String): String {
    val lower = line.lowercase(Locale.ROOT)

    return when {
        isHeartRateLog(line) -> ""
        line.startsWith("CONNECT ") ->
            "Watch connection started"
        line.startsWith("STATE ") && lower.contains("connected") ->
            "BLE connected • service discovery starting"
        line.startsWith("STATE ") && lower.contains("disconnected") ->
            "BLE disconnected"
        line.startsWith("DISCOVER ") ->
            "GATT service discovery requested"
        line.startsWith("SERVICES status=0") ->
            "GATT services discovered successfully"
        line.startsWith("MTU ") ->
            line.replace("MTU ", "MTU negotiated • ")
        line.startsWith("PROTOCOL ") ->
            "Protocol match • FT_38093 live-data channel detected"
        line.startsWith("RECONNECT ") ->
            "Automatic reconnect • " + line.removePrefix("RECONNECT ")
        line.startsWith("DISCONNECT_REASON ") ->
            "Disconnect • " + line.removePrefix("DISCONNECT_REASON ")
        line.startsWith("ERROR ") ->
            "Error • " + line.removePrefix("ERROR ")
        line.startsWith("CCCD ") -> {
            val status = line.substringAfter("status=", "")
            "Notification setup • " + line.substringBefore(" status=") +
                " • " + if (status == "0") "ready" else "status $status"
        }
        line.startsWith("NOTIFY ") ->
            "Notification channel setup • " +
                line.substringAfter("NOTIFY ").substringBefore(" descriptorWrite=")
        line.startsWith("READ ") ->
            "Read requested • " + line.substringAfter("READ ")
        line.startsWith("READ_RESULT ") ->
            "Read result • " + line.removePrefix("READ_RESULT ")
        line.startsWith("TEST_MARKER ") ->
            "Test started • " + line.removePrefix("TEST_MARKER ")
        else ->
            line
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
    appendLine("Heart-rate packets: OMITTED (already decoded by the app)")
    appendLine("Capture retention: rolling last 24 hours")
    appendLine("Purpose: discover unknown FT_38093 data channels such as SpO₂, sleep, stress, steps, workouts and history")
    appendLine()

    val nonHeartRate = values.filterNot { isHeartRateCapture(it) }
    appendLine("NON-HEART-RATE CAPTURE (" + nonHeartRate.size + " packets)")
    if (nonHeartRate.isEmpty()) {
        appendLine("No non-heart-rate packets captured yet.")
    } else {
        appendGroupedCaptureLines(nonHeartRate, this)
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
    val readableLogs = logs.filterNot(::isDiscoveryNoiseLog)
    appendLine("HUMAN-READABLE EVENT LOG (" + readableLogs.size + " events)")
    readableLogs.map(::humanReadableLogLine).filter { it.isNotBlank() }.forEach(::appendLine)
}

private fun appendGroupedCaptureLines(
    values: List<GattValue>,
    builder: StringBuilder,
) {
    var index = 0
    while (index < values.size) {
        val current = values[index]
        var count = 1
        var last = current

        while (
            index + count < values.size &&
            values[index + count].characteristicUuid.equals(
                current.characteristicUuid,
                ignoreCase = true,
            ) &&
            values[index + count].hex == current.hex
        ) {
            last = values[index + count]
            count++
        }

        val firstTime = current.timestamp
        val lastTime = last.timestamp
        builder.appendLine(
            firstTime + " → " + lastTime +
                " • " + friendlyCharacteristicLabel(current.characteristicUuid) +
                " • x" + count,
        )
        builder.appendLine("  HEX: " + current.hex)
        builder.appendLine("  Decode: " + humanReadableDecode(current))
        builder.appendLine("  Candidates: " + compactCandidates(current.decoded))

        index += count
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
                "BLE compatible • app sync does not require Android pairing",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun isHeartRateCapture(value: GattValue): Boolean =
    value.characteristicUuid.equals(UUID_HEART_RATE, ignoreCase = true) ||
        value.characteristicUuid.equals(UUID_VENDOR_HEART_RATE, ignoreCase = true) ||
        value.hex.uppercase(Locale.ROOT).startsWith("E5 11 00 ")

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
                friendlyCharacteristicLabel(value.characteristicUuid),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                humanReadableDecode(value),
                style = MaterialTheme.typography.bodySmall,
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

private const val UUID_BATTERY = "00002a19-0000-1000-8000-00805f9b34fb"
private const val UUID_HEART_RATE = "00002a37-0000-1000-8000-00805f9b34fb"
private const val UUID_VENDOR_HEART_RATE = "000033f2-0000-1000-8000-00805f9b34fb"
private const val UUID_SPO2 = "00002a5f-0000-1000-8000-00805f9b34fb"
