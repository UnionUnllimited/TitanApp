package com.titanvps.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Crown from the mockup (Material has none). */
internal object TitanIcons {
    val CrownOutlined: ImageVector by lazy { crown(filled = false) }
    val Crown: ImageVector by lazy { crown(filled = true) }

    private fun crown(filled: Boolean) = ImageVector.Builder(
        name = if (filled) "Crown" else "CrownOutlined",
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(3f, 7.5f)
            lineTo(7.5f, 11.5f)
            lineTo(12f, 5f)
            lineTo(16.5f, 11.5f)
            lineTo(21f, 7.5f)
            lineTo(19f, 17.5f)
            lineTo(5f, 17.5f)
            close()
        }
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            moveTo(5f, 20.5f)
            lineTo(19f, 20.5f)
        }
    }.build()
}
