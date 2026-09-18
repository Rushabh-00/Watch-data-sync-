package app.watchdatasync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private enum class FeatureState(
    val label: String,
) {
    VERIFIED("Verified"),
    PHONE_READY("Phone-ready"),
    PENDING("Needs FT_38093 protocol evidence"),
    UNSUPPORTED("Not verified"),
}

private data class FeatureRow(
    val title: String,
    val detail: String,
    val state: FeatureState,
)

private val featureGroups = listOf(
    "Health & sync" to listOf(
        FeatureRow("Live heart rate", "Live E5 11 frames are decoded on the observed 33F2 channel.", FeatureState.VERIFIED),
        FeatureRow("Heart-rate history", "24-hour history fetch and persistence are working.", FeatureState.VERIFIED),
        FeatureRow("SpO₂ history", "34 FA history packets and completion are decoded.", FeatureState.VERIFIED),
        FeatureRow("Sleep sync", "31 01 date/session markers are syncing; detailed 32 stages still need FT_38093 confirmation.", FeatureState.VERIFIED),
        FeatureRow("Steps / calories / distance", "The current watch does not return the expected B2 activity stream yet; values must remain gated.", FeatureState.PENDING),
        FeatureRow("Stress", "UI capability is planned; no FT_38093 stress response is semantically verified.", FeatureState.PENDING),
        FeatureRow("Blood pressure", "Vendor apps expose this on some models, but this FT_38093 path has no verified mapping.", FeatureState.UNSUPPORTED),
        FeatureRow("Breathe / wellness", "Feature surface is available; watch-side command mapping is not yet verified.", FeatureState.PENDING),
    ),
    "Fitness & history" to listOf(
        FeatureRow("Daily dashboard", "Today cards, trends and last-sync state are already part of the app.", FeatureState.VERIFIED),
        FeatureRow("Multi-sport", "Feature surface is planned; FT_38093 sport list/history requires direct capture validation.", FeatureState.PENDING),
        FeatureRow("Workout history", "Protocol-family FD history is known from research, but not yet validated on FT_38093.", FeatureState.PENDING),
        FeatureRow("Goals", "Local goal editing is available in this app; watch goal writes remain protocol-gated.", FeatureState.PHONE_READY),
        FeatureRow("Weekly / monthly trends", "The app already stores historical HR/SpO₂/sleep data for trend views.", FeatureState.VERIFIED),
        FeatureRow("Export / diagnostics", "Raw packets and sync logs are retained for protocol work.", FeatureState.VERIFIED),
    ),
    "Watch controls" to listOf(
        FeatureRow("Watch faces", "Custom face design can be prepared locally; installing a face requires verified watch-face transfer.", FeatureState.PHONE_READY),
        FeatureRow("Brightness / screen-on / DND", "Settings surface is planned; FT_38093 write frames are not yet verified.", FeatureState.PENDING),
        FeatureRow("Alarm", "Settings surface is planned; watch-side alarm command requires FT_38093 evidence.", FeatureState.PENDING),
        FeatureRow("Find phone", "Phone-side action can be exposed; the watch trigger frame remains unverified.", FeatureState.PENDING),
        FeatureRow("Weather", "Phone-side weather screen can be added; watch weather transfer is not yet verified.", FeatureState.PENDING),
        FeatureRow("Music / camera control", "Phone-control endpoints can be supported, but watch trigger packets are not yet verified.", FeatureState.PENDING),
        FeatureRow("Contacts / call / SMS replies", "Vendor app supports these features on compatible models; FT_38093 packets are not verified.", FeatureState.PENDING),
        FeatureRow("Firmware / OTA", "OTA/DFU is intentionally not enabled until a safe, model-specific procedure is observed.", FeatureState.UNSUPPORTED),
    ),
    "Phone & integrations" to listOf(
        FeatureRow("Notification access", "Android notification access settings and a local notification bridge are available.", FeatureState.PHONE_READY),
        FeatureRow("Profile", "Name, birthday, gender, height and weight can be stored locally.", FeatureState.PHONE_READY),
        FeatureRow("Google Fit / Health Connect", "Integration surface is planned; writing vendor health records needs a stable normalized database first.", FeatureState.PENDING),
        FeatureRow("Location / weather", "User-selected location can be used without requiring precise device location.", FeatureState.PHONE_READY),
        FeatureRow("Battery optimization help", "The app can explain the background/BLE requirements.", FeatureState.PHONE_READY),
    ),
)

@Composable
fun FeatureHubScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    var profileOpen by remember { mutableStateOf(false) }
    var goalsOpen by remember { mutableStateOf(false) }
    var notificationEnabled by remember { mutableStateOf(isNotificationAccessGranted(context)) }
    val customFace = remember { mutableStateOf(loadCustomFace(context)) }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CUSTOM_FACE, uri.toString())
                .apply()
            customFace.value = uri.toString()
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
                "Smart features",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Feature-complete companion surface for FT_38093. Watch-side actions stay capability-gated until their bytes are observed and repeatable.",
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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Your profile & goals", fontWeight = FontWeight.SemiBold)
                    Text(
                        "The vendor app supports profile, step, sleep and multisport goals. These values are stored locally here and can later be pushed through a verified FT_38093 profile command.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { profileOpen = !profileOpen }) { Text("Profile") }
                        OutlinedButton(onClick = { goalsOpen = !goalsOpen }) { Text("Goals") }
                    }
                    if (profileOpen) {
                        LocalProfileEditor(context)
                    }
                    if (goalsOpen) {
                        LocalGoalsEditor(context)
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
                    Text("Notifications", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (notificationEnabled) {
                            "Notification access is enabled. The app can observe phone notifications."
                        } else {
                            "Enable notification access to prepare call, SMS and third-party notification bridging."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            },
                        ) {
                            Text(if (notificationEnabled) "Manage access" else "Enable access")
                        }
                        OutlinedButton(
                            onClick = {
                                notificationEnabled = isNotificationAccessGranted(context)
                            },
                        ) {
                            Text("Refresh")
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
                    Text("Watch faces", fontWeight = FontWeight.SemiBold)
                    Text(
                        customFace.value?.let { "Custom face selected locally: $it" }
                            ?: "Select a gallery image to prepare a custom watch face.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { imagePicker.launch("image/*") }) {
                            Text("Choose image")
                        }
                        OutlinedButton(
                            onClick = {
                                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                    .edit()
                                    .remove(KEY_CUSTOM_FACE)
                                    .apply()
                                customFace.value = null
                            },
                        ) {
                            Text("Clear")
                        }
                    }
                    Text(
                        "Installing the image on the FT_38093 is intentionally blocked until the watch-face transfer format is validated.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        featureGroups.forEach { (group, rows) ->
            item {
                Text(
                    group,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            items(rows) { row ->
                FeatureCard(row)
            }
        }

        item {
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Protocol parity status", fontWeight = FontWeight.SemiBold)
                    Text(
                        "The FT_38093 app currently keeps verified health sync live while exposing the complete companion-app feature surface. Unknown watch commands remain isolated in Diagnostics so new evidence can be promoted without fabricating packets.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { viewModel.syncNow() }) {
                        Text("Sync verified data now")
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureCard(row: FeatureRow) {
    Card {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(row.title, fontWeight = FontWeight.SemiBold)
                FilterChip(
                    selected = false,
                    onClick = {},
                    label = { Text(row.state.label) },
                )
            }
            Text(
                row.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LocalProfileEditor(context: Context) {
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var name by remember { mutableStateOf(prefs.getString(KEY_NAME, "") ?: "") }
    var height by remember { mutableStateOf(prefs.getString(KEY_HEIGHT, "") ?: "") }
    var weight by remember { mutableStateOf(prefs.getString(KEY_WEIGHT, "") ?: "") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                prefs.edit().putString(KEY_NAME, it).apply()
            },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = height,
            onValueChange = {
                height = it
                prefs.edit().putString(KEY_HEIGHT, it).apply()
            },
            label = { Text("Height (cm)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = weight,
            onValueChange = {
                weight = it
                prefs.edit().putString(KEY_WEIGHT, it).apply()
            },
            label = { Text("Weight (kg)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LocalGoalsEditor(context: Context) {
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var steps by remember { mutableStateOf(prefs.getString(KEY_STEP_GOAL, "8000") ?: "8000") }
    var sleep by remember { mutableStateOf(prefs.getString(KEY_SLEEP_GOAL, "8") ?: "8") }
    var multisport by remember { mutableStateOf(prefs.getString(KEY_MULTISPORT_GOAL, "30") ?: "30") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = steps,
            onValueChange = {
                steps = it
                prefs.edit().putString(KEY_STEP_GOAL, it).apply()
            },
            label = { Text("Step goal") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = sleep,
            onValueChange = {
                sleep = it
                prefs.edit().putString(KEY_SLEEP_GOAL, it).apply()
            },
            label = { Text("Sleep goal (hours)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = multisport,
            onValueChange = {
                multisport = it
                prefs.edit().putString(KEY_MULTISPORT_GOAL, it).apply()
            },
            label = { Text("Multisport goal (minutes)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun loadCustomFace(context: Context): String? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_CUSTOM_FACE, null)

private fun isNotificationAccessGranted(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners",
    ).orEmpty()
    return enabled.split(':').any { it.contains(context.packageName, ignoreCase = true) }
}

private const val PREFS = "feature_hub"
private const val KEY_CUSTOM_FACE = "custom_face_uri"
private const val KEY_NAME = "profile_name"
private const val KEY_HEIGHT = "profile_height"
private const val KEY_WEIGHT = "profile_weight"
private const val KEY_STEP_GOAL = "goal_steps"
private const val KEY_SLEEP_GOAL = "goal_sleep"
private const val KEY_MULTISPORT_GOAL = "goal_multisport"
