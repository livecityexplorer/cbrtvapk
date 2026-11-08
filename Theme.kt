package app.iptv.player.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme

private val Bg = Color(0xFF0B0F1A)
private val Surface = Color(0xFF151B2B)
private val Accent = Color(0xFF4F8CFF)
private val OnDark = Color(0xFFEAF0FF)

/** Provides both TV Material (buttons, text) and Material 3 (text fields, dialogs, chips) with the same palette. */
@Composable
fun IptvTheme(amoled: Boolean = false, content: @Composable () -> Unit) {
    val background = if (amoled) Color.Black else Bg
    val surface = if (amoled) Color.Black else Surface
    val tv = darkColorScheme(
        primary = Accent, onPrimary = Color.White, background = background, surface = surface,
        surfaceVariant = Color(0xFF1F2740), onBackground = OnDark, onSurface = OnDark,
        border = Accent, error = Color(0xFFFF5C5C)
    )
    val m3 = m3DarkColorScheme(
        primary = Accent, onPrimary = Color.White, background = background, surface = surface,
        surfaceVariant = Color(0xFF1F2740), onBackground = OnDark, onSurface = OnDark,
        onSurfaceVariant = Color(0xFFB8C3DD), error = Color(0xFFFF5C5C), outline = Color(0xFF55618A),
        secondaryContainer = Color(0xFF26335C), onSecondaryContainer = OnDark
    )
    M3Theme(colorScheme = m3) { MaterialTheme(colorScheme = tv, content = content) }
}
