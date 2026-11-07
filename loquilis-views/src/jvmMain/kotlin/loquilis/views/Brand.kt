package loquilis.views

import androidx.compose.ui.graphics.painter.Painter

/** Who this console is. [about] is what hovering over the name shows, as label and value, one to a line. */
class Brand(val name: String, val mark: Painter, val about: List<Pair<String, String>> = emptyList())
