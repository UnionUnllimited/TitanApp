package com.titanvps.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Crown from the mockup (Material has none); the back chevron of a bar. */
internal object TitanIcons {
    /** Filled, as the iPhone's tab bar draws it in either state. */
    val Crown: ImageVector by lazy { crown() }

    /** «‹» with round ends, as iOS's chevron.backward (Material's is thinner, with square ends). */
    val Back: ImageVector by lazy { back() }

    private fun crown() = ImageVector.Builder(
        name = "Crown",
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.Black),
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

    private fun back() = ImageVector.Builder(
        name = "Back",
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
        autoMirror = true,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(15.5f, 4f)
            lineTo(7.5f, 12f)
            lineTo(15.5f, 20f)
        }
    }.build()
}
