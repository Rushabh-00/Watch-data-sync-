package app.watchdatasync

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Phone-side notification bridge.
 *
 * The service intentionally stores only aggregate metadata. Notification bodies are not persisted.
 * Watch delivery is kept separate until the FT_38093 notification packet format is verified.
 */
class WatchNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        recordNotification(sbn.packageName)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = Unit

    private fun recordNotification(packageName: String) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_TOTAL, prefs.getInt(KEY_TOTAL, 0) + 1)
            .putString(KEY_LAST_PACKAGE, packageName)
            .putLong(KEY_LAST_TIME, System.currentTimeMillis())
            .apply()
    }

    private companion object {
        const val PREFS = "notification_bridge"
        const val KEY_TOTAL = "total"
        const val KEY_LAST_PACKAGE = "last_package"
        const val KEY_LAST_TIME = "last_time"
    }
}
