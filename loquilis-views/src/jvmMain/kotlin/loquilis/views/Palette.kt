package loquilis.views

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class Palette(
    val page: Color,
    val panel: Color,
    val line: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val selection: Color,
    val failed: Color,
    val userBubble: Color,
    val userInk: Color,
    val agentBubble: Color,
    val agentInk: Color,
)

private val Light = Palette(
    page = Color(0xFFFFFFFF),
    panel = Color(0xFFF5F5F7),
    line = Color(0xFFDEDEE3),
    ink = Color(0xFF1C1C1E),
    muted = Color(0xFF6E6E73),
    accent = Color(0xFF0A7CFF),
    selection = Color(0xFFDCEBFF),
    failed = Color(0xFFC62828),
    userBubble = Color(0xFF0A7CFF),
    userInk = Color(0xFFFFFFFF),
    agentBubble = Color(0xFFE9E9EB),
    agentInk = Color(0xFF1C1C1E),
)

private val Dark = Palette(
    page = Color(0xFF1C1C1E),
    panel = Color(0xFF242426),
    line = Color(0xFF3A3A3C),
    ink = Color(0xFFF2F2F7),
    muted = Color(0xFFA1A1A6),
    accent = Color(0xFF4DA3FF),
    selection = Color(0xFF17375C),
    failed = Color(0xFFFF8A80),
    userBubble = Color(0xFF0A7CFF),
    userInk = Color(0xFFFFFFFF),
    agentBubble = Color(0xFF3A3A3C),
    agentInk = Color(0xFFF2F2F7),
)

val LocalPalette = staticCompositionLocalOf { Light }

@Composable
fun ReviewTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (dark) Dark else Light
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        background = palette.page,
        surface = palette.page,
        onBackground = palette.ink,
        onSurface = palette.ink,
        primary = palette.accent,
    )
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
