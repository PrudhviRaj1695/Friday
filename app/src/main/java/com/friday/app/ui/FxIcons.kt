package com.friday.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** FRIDAY's own 24×24 line-icon set: one stroke weight, rounded caps, tinted by the caller. */
object FxIcons {
    private fun icon(name: String, vararg paths: String, filled: Set<Int> = emptySet(), stroke: Float = 1.8f): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEachIndexed { i, d ->
            val fill = i in filled
            b.addPath(
                pathData = addPathNodes(d),
                fill = if (fill) SolidColor(Color.White) else null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
        return b.build()
    }

    val Mic = icon("mic", "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3z", "M18.5 11a6.5 6.5 0 0 1-13 0", "M12 17.5V21", "M8.5 21h7")
    val Send = icon("send", "M12 19V5", "M6 11l6-6 6 6", stroke = 2.2f)
    val Stop = icon("stop", "M8.5 7h7a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1-1.5 1.5h-7A1.5 1.5 0 0 1 7 15.5v-7A1.5 1.5 0 0 1 8.5 7z", filled = setOf(0))
    val Pause = icon("pause", "M9 6.5v11", "M15 6.5v11", stroke = 2.4f)
    val Play = icon("play", "M8.5 6v12l9.5-6z", filled = setOf(0))
    val Copy = icon("copy", "M10 8.5h8a1.5 1.5 0 0 1 1.5 1.5v8a1.5 1.5 0 0 1-1.5 1.5h-8A1.5 1.5 0 0 1 8.5 18v-8A1.5 1.5 0 0 1 10 8.5z", "M15.5 8.5V6A1.5 1.5 0 0 0 14 4.5H6A1.5 1.5 0 0 0 4.5 6v8A1.5 1.5 0 0 0 6 15.5h2.5")
    val Speaker = icon("speaker", "M4 9.5h3.5L12 6v12l-4.5-3.5H4z", "M15.5 9.2a4 4 0 0 1 0 5.6", "M18.2 6.6a7.6 7.6 0 0 1 0 10.8")
    val Camera = icon("camera", "M4 8.5A2 2 0 0 1 6 6.5h2L9.5 4.5h5L16 6.5h2a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z", "M15.5 12.5a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0")
    val Tune = icon("tune", "M4 7h9", "M19 7h1", "M18.5 7a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0", "M4 17h2", "M12 17h8", "M11.5 17a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0")
    val Back = icon("back", "M15 5l-7 7 7 7", stroke = 2f)
    val ChevronRight = icon("chevron", "M9.5 5.5l6.5 6.5-6.5 6.5")
    val Close = icon("close", "M6.5 6.5l11 11", "M17.5 6.5l-11 11", stroke = 2f)
    val Plus = icon("plus", "M12 5v14", "M5 12h14", stroke = 2f)
    val Check = icon("check", "M5 12.5l4.5 4.5L19 7.5", stroke = 2.2f)
    val Globe = icon("globe", "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0", "M3.5 12h17", "M12 3c2.6 2.8 2.6 15.2 0 18", "M12 3c-2.6 2.8-2.6 15.2 0 18")
    val Sparkle = icon("sparkle", "M11 3.5l1.8 5.2 5.2 1.8-5.2 1.8L11 17.5l-1.8-5.2L4 10.5l5.2-1.8z", "M18.5 15.5l.8 1.9 1.9.8-1.9.8-.8 1.9-.8-1.9-1.9-.8 1.9-.8z")
    val Chip = icon("chip", "M7.5 5.5h9a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2z", "M10 10h4v4h-4z", "M9.5 2.5v3", "M14.5 2.5v3", "M9.5 18.5v3", "M14.5 18.5v3", "M2.5 9.5h3", "M2.5 14.5h3", "M18.5 9.5h3", "M18.5 14.5h3")
    val Monitor = icon("monitor", "M4.5 4.5h15a1.5 1.5 0 0 1 1.5 1.5v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 15V6a1.5 1.5 0 0 1 1.5-1.5z", "M9 20.5h6", "M12 16.5v4")
    val Phone = icon("phone", "M8 2.5h8a1.5 1.5 0 0 1 1.5 1.5v16a1.5 1.5 0 0 1-1.5 1.5H8A1.5 1.5 0 0 1 6.5 20V4A1.5 1.5 0 0 1 8 2.5z", "M11 18.5h2")
    val Shield = icon("shield", "M12 3l7 3v5.2c0 4.4-2.9 8.3-7 9.8-4.1-1.5-7-5.4-7-9.8V6z", "M9 12l2 2 4-4.2")
    val Terminal = icon("terminal", "M5 4.5h14A1.5 1.5 0 0 1 20.5 6v12a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 18V6A1.5 1.5 0 0 1 5 4.5z", "M7.5 9.5l3 2.5-3 2.5", "M12.5 15h4")
    val Palette = icon("palette", "M12 3a9 9 0 1 0 0 18c1 0 1.6-.7 1.6-1.5 0-.9-.7-1.4-.7-2.3 0-1 .8-1.8 1.8-1.8H17a4 4 0 0 0 4-4C21 6.7 17 3 12 3z", "M8 11.2a.6.6 0 1 1-.01 0", "M11.5 7.7a.6.6 0 1 1-.01 0", "M15.5 9.2a.6.6 0 1 1-.01 0", stroke = 1.8f)
    val Wave = icon("wave", "M4 10v4", "M8 7v10", "M12 4v16", "M16 7v10", "M20 10v4")
    val Bell = icon("bell", "M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 1.5h-15z", "M10 20.5a2 2 0 0 0 4 0")
    val Refresh = icon("refresh", "M19.5 12a7.5 7.5 0 1 1-2.2-5.3", "M19.5 4.5v3.8h-3.8")
    val Trash = icon("trash", "M4.5 7h15", "M10 3.5h4", "M6.5 7l1 13h9l1-13", "M10 11v5", "M14 11v5")
    val Edit = icon("edit", "M4 20h4L19 9l-4-4L4 16z", "M13.5 6.5l4 4")
    val User = icon("user", "M16 8a4 4 0 1 1-8 0a4 4 0 1 1 8 0", "M4.5 20c1.4-3.7 4.3-5.5 7.5-5.5s6.1 1.8 7.5 5.5")
    val Info = icon("info", "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0", "M12 11v5.5", "M12 7.8v.01", stroke = 1.9f)
    val Summary = icon("summary", "M5 6h14", "M5 10h14", "M5 14h9", "M5 18h6")
    val Repeat = icon("repeat", "M4.5 12a6.5 6.5 0 0 1 11.3-4.4", "M16.5 3.5v4.3h-4.3", "M19.5 12a6.5 6.5 0 0 1-11.3 4.4", "M7.5 20.5v-4.3h4.3")
    val Keyboard = icon("keyboard", "M4 6.5h16a1.5 1.5 0 0 1 1.5 1.5v8A1.5 1.5 0 0 1 20 17.5H4A1.5 1.5 0 0 1 2.5 16V8A1.5 1.5 0 0 1 4 6.5z", "M6.5 10h.01", "M10 10h.01", "M13.5 10h.01", "M17 10h.01", "M8 14h8")
}
