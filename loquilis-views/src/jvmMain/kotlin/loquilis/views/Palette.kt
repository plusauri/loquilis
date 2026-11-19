package loquilis.views

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import plusauri.ui.CopyWords
import plusauri.ui.LocalCopyWords
import plusauri.ui.LocalScrollCueWords
import plusauri.ui.LocalYearInputWords
import plusauri.ui.PlusauriDarkColorScheme
import plusauri.ui.PlusauriLightColorScheme
import plusauri.ui.PlusauriTheme
import plusauri.ui.ScrollCueWords
import plusauri.ui.YearInputWords

/**
 * The console's colors by role. Views read these and never a literal, so a client can retheme the whole
 * console by passing its own (`doc/decisions/0002-bespoke-consoles.md`). The defaults come from the ui-kit's
 * Plusauri theme, so the plain console and the kit's controls inside it read as one family.
 */
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
    /** Behind the mark for data that stays on this computer. Data sent away sits on [accent]. */
    val local: Color,
) {
    companion object {
        /** The console's colors taken from a Material [scheme], with [local] as the one color of its own. */
        fun from(scheme: ColorScheme, local: Color): Palette = Palette(
            page = scheme.background,
            // A warm step off the page, so the side panels read as furniture rather than as content.
            panel = lerp(scheme.background, scheme.surfaceVariant, 0.45f),
            line = scheme.outlineVariant,
            ink = scheme.onSurface,
            muted = scheme.onSurfaceVariant,
            accent = scheme.primary,
            selection = scheme.primaryContainer,
            failed = scheme.error,
            userBubble = scheme.primary,
            userInk = scheme.onPrimary,
            agentBubble = scheme.surfaceVariant,
            agentInk = scheme.onSurface,
            local = local,
        )

        val Light = from(PlusauriLightColorScheme, local = Color(0xFFD6F0DC))
        val Dark = from(PlusauriDarkColorScheme, local = Color(0xFF1F4A2B))
    }
}

val LocalPalette = staticCompositionLocalOf { Palette.Light }

/** What the kit's controls say, given once here as the kit asks of its host (`plusauri.ui.KitWords`). */
private val KitCopyWords = CopyWords(copy = "Copy", copied = "Copied", hint = "Click to copy")
private val KitScrollCueWords = ScrollCueWords(down = "More below", up = "More above")
private val KitYearInputWords = YearInputWords(placeholder = "Year")

/**
 * The console's theme: the kit's Plusauri theme underneath, so kit controls look as they do in Tutilis, and
 * [palette] over it for the console's own views. A client passes its own [palette] to retheme.
 */
@Composable
fun ReviewTheme(
    dark: Boolean = isSystemInDarkTheme(),
    palette: Palette = if (dark) Palette.Dark else Palette.Light,
    content: @Composable () -> Unit,
) {
    PlusauriTheme(isDarkTheme = dark) {
        val scheme = MaterialTheme.colorScheme.copy(
            background = palette.page,
            surface = palette.page,
            onBackground = palette.ink,
            onSurface = palette.ink,
            primary = palette.accent,
        )
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography) {
            CompositionLocalProvider(
                LocalPalette provides palette,
                LocalCopyWords provides KitCopyWords,
                LocalScrollCueWords provides KitScrollCueWords,
                LocalYearInputWords provides KitYearInputWords,
                content = content,
            )
        }
    }
}
