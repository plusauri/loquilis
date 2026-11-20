@file:OptIn(ExperimentalTestApi::class)

package loquilis.views

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import java.awt.image.BufferedImage

// A dialog (the kit's ResizableDialog) opens as a layer of its own, so a window with one showing has two roots.

/** The uppermost layer: the open dialog if there is one, else the window. Keys go here. */
internal fun ComposeUiTest.topRoot(): SemanticsNodeInteraction = onAllNodes(isRoot()).onLast()

/** The window as a person sees it: every layer drawn over the one below, so a dialog shows over the window. */
internal fun ComposeUiTest.windowShot(): BufferedImage {
    val roots = onAllNodes(isRoot())
    val layers = (0 until roots.fetchSemanticsNodes().size).map { roots[it].captureToImage().toAwtImage() }
    val shot = BufferedImage(layers.maxOf { it.width }, layers.maxOf { it.height }, BufferedImage.TYPE_INT_ARGB)
    val pen = shot.createGraphics()
    layers.forEach { pen.drawImage(it, 0, 0, null) }
    pen.dispose()
    return shot
}

/** The platform's command key, as [KeyChord.command] reads it: Command on a Mac, Control elsewhere. */
internal val commandKey: androidx.compose.ui.input.key.Key =
    if (System.getProperty("os.name").orEmpty().startsWith("Mac")) androidx.compose.ui.input.key.Key.MetaLeft
    else androidx.compose.ui.input.key.Key.CtrlLeft
