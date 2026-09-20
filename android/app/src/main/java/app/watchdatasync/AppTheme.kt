package app.watchdatasync

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class ThemeMode(val key: String, val label: String) {
    AUTO("auto", "Auto"),
    OLED("oled", "OLED"),
    DARK("dark", "Dark"),
    LIGHT("light", "Light");

    companion object {
        fun fromKey(key: String?): ThemeMode =
            entries.firstOrNull { it.key == key } ?: AUTO
    }
}

@Composable
fun WatchDataSyncTheme(
    mode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val useDark = when (mode) {
        ThemeMode.AUTO -> isSystemInDarkTheme()
        ThemeMode.OLED, ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val colors = if (useDark) {
        darkColorScheme(
            primary = Color(0xFF64E9FF),
            secondary = Color(0xFFA58BFF),
            background = if (mode == ThemeMode.OLED) Color.Black else Color(0xFF050811),
            surface = if (mode == ThemeMode.OLED) Color.Black else Color(0xFF10182A),
            surfaceVariant = Color(0xFF172238),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF006D7A),
            secondary = Color(0xFF6750A4),
            background = Color(0xFFF8F9FC),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFE8EDF4),
        )
    }

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
