package app.watchdatasync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val prefs = context.getSharedPreferences(
            HeartRateService.PREFS,
            Context.MODE_PRIVATE,
        )

        if (!prefs.getString(HeartRateService.KEY_ADDRESS, null).isNullOrBlank()) {
            HeartRateService.start(context)
        }
    }
}
