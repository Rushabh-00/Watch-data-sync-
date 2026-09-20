package app.watchdatasync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (
            intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val prefs = context.getSharedPreferences(
            HeartRateService.PREFS,
            Context.MODE_PRIVATE,
        )

        val savedWatch = !prefs
            .getString(HeartRateService.KEY_ADDRESS, null)
            .isNullOrBlank()

        val enabled = if (prefs.contains(HeartRateService.KEY_BACKGROUND_MONITORING_ENABLED)) {
            prefs.getBoolean(
                HeartRateService.KEY_BACKGROUND_MONITORING_ENABLED,
                true,
            )
        } else {
            val legacy = prefs.getBoolean(
                HeartRateService.KEY_NOTIFICATION_ENABLED,
                true,
            )
            prefs.edit()
                .putBoolean(
                    HeartRateService.KEY_BACKGROUND_MONITORING_ENABLED,
                    legacy,
                )
                .apply()
            legacy
        }

        if (savedWatch && enabled) {
            HeartRateService.start(context)
        }
    }
}
