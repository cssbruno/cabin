package com.cabin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/** Automotive windshield symbols, inheriting the control's active/disabled tint. */
@Composable
internal fun ClimateDefrostIcon(front: Boolean, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier) {
        val stroke = size.minDimension / 18f
        fun point(x: Float, y: Float) = Offset(size.width * x, size.height * y)
        val outline = Path().apply {
            if (front) {
                moveTo(size.width * .12f, size.height * .7f)
                lineTo(size.width * .04f, size.height * .3f)
                quadraticTo(size.width * .5f, 0f, size.width * .96f, size.height * .3f)
                lineTo(size.width * .88f, size.height * .7f)
            } else {
                moveTo(size.width * .08f, size.height * .18f)
                lineTo(size.width * .92f, size.height * .18f)
                lineTo(size.width * .92f, size.height * .7f)
                lineTo(size.width * .08f, size.height * .7f)
            }
            close()
        }
        drawPath(outline, color, style = Stroke(stroke))
        for (x in listOf(.3f, .5f, .7f)) {
            val heat = Path().apply {
                moveTo(size.width * x, size.height * .94f)
                cubicTo(size.width * (x - .1f), size.height * .72f,
                    size.width * (x + .1f), size.height * .62f,
                    size.width * x, size.height * .38f)
            }
            drawPath(heat, color, style = Stroke(stroke, cap = StrokeCap.Round))
            drawLine(color, point(x, .38f), point(x - .06f, .48f), stroke, StrokeCap.Round)
            drawLine(color, point(x, .38f), point(x + .06f, .48f), stroke, StrokeCap.Round)
        }
    }
}
