package loquilis.views

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import plusauri.ui.PanelSeamDivider
import plusauri.ui.PanelSide

/**
 * A side panel the window lays out: whether it shows and how wide it is. [onSettle] hears each fold and the end
 * of each resize, so a host keeps the result once rather than at every frame of a drag.
 */
class PanelState(
    shown: Boolean,
    width: Dp,
    private val minWidth: Dp,
    private val maxWidth: Dp,
    private val onSettle: (PanelState) -> Unit = {},
) {
    var shown by mutableStateOf(shown)
        private set
    var width by mutableStateOf(width.coerceIn(minWidth, maxWidth))
        private set

    fun toggle() {
        shown = !shown
        onSettle(this)
    }

    fun resize(by: Dp) {
        width = (width + by).coerceIn(minWidth, maxWidth)
    }

    fun settle() = onSettle(this)
}

/**
 * The seam beside [panel], the kit's panel seam: a click folds or
 * opens it, a drag sizes it. [name] finishes the hover tip, "Hide the [name]".
 */
@Composable
internal fun PanelSeam(panel: PanelState, side: PanelSide, name: String) {
    val density = LocalDensity.current
    PanelSeamDivider(
        side = side,
        collapsed = !panel.shown,
        onToggle = panel::toggle,
        hideLabel = "Hide the $name",
        showLabel = "Show the $name",
        onResize = { px -> panel.resize(with(density) { px.toDp() }) },
        onResizeStopped = panel::settle,
    )
}

internal val LIST_MIN = 180.dp
internal val LIST_MAX = 480.dp
internal val INSPECTOR_MIN = 280.dp
internal val INSPECTOR_MAX = 760.dp
