package com.tannistha.livingcanvas

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

private const val GRID_COLUMNS = 64
private const val GRID_ROWS = 40

// Start here when adjusting the effect.
// 0f = still image, 1f = normal movement.
private const val MOTION_STRENGTH = 1f

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            LivingCanvasScreen()
        }
    }
}

// Smoothly changes from 0 to 1 between two values.
private fun smoothStep(
    start: Float,
    end: Float,
    value: Float
): Float {
    val t = ((value - start) / (end - start))
        .coerceIn(0f, 1f)

    return t * t * (3f - 2f * t)
}

@Composable
fun LivingCanvasScreen() {
    val context = LocalContext.current

    val painting = remember(context) {
        val options = BitmapFactory.Options().apply {
            inScaled = false
        }

        requireNotNull(
            BitmapFactory.decodeResource(
                context.resources,
                R.drawable.starry_night,
                options
            )
        ) {
            "Could not load starry_night."
        }
    }

    val paint = remember {
        Paint(Paint.FILTER_BITMAP_FLAG)
    }

    // Every grid point needs an x and a y coordinate.
    val vertices = remember {
        FloatArray(
            (GRID_COLUMNS + 1) * (GRID_ROWS + 1) * 2
        )
    }

    val transition = rememberInfiniteTransition()

    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 12000,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        )
    )

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101528))
    ) {
        if (size.width <= 0f || size.height <= 0f) {
            return@Canvas
        }

        val time = phase

        val scale = minOf(
            size.width / painting.width,
            size.height / painting.height
        )

        val imageWidth = painting.width * scale
        val imageHeight = painting.height * scale

        val left = (size.width - imageWidth) / 2f
        val top = (size.height - imageHeight) / 2f

        val aspect = imageWidth / imageHeight

        var index = 0

        for (row in 0..GRID_ROWS) {
            val v = row.toFloat() / GRID_ROWS

            for (column in 0..GRID_COLUMNS) {
                val u = column.toFloat() / GRID_COLUMNS

                // Lock the outer edge to prevent exposed gaps.
                val edgeDistance = minOf(
                    u,
                    1f - u,
                    v,
                    1f - v
                )

                val edgeMask =
                    smoothStep(0f, 0.08f, edgeDistance)

                // Approximate protection around the tree
                // in your current cropped image.
                val treeWidth = 0.025f + v * 0.055f

                val treeHorizontal =
                    1f - smoothStep(
                        treeWidth,
                        treeWidth + 0.045f,
                        abs(u - 0.26f)
                    )

                val treeVertical =
                    smoothStep(0.08f, 0.22f, v)

                val treeMask =
                    treeHorizontal * treeVertical

                // Reduce movement near the bottom.
                val skyMask =
                    1f - smoothStep(0.78f, 0.98f, v)

                val motionMask =
                    edgeMask *
                            (1f - treeMask) *
                            skyMask *
                            MOTION_STRENGTH

                // Shared waves: nearby grid points move together.
                var moveX =
                    0.010f * sin(v * 10f - time) +
                            0.004f * sin(
                        u * 13f + v * 8f - 2f * time
                    )

                var moveY =
                    0.006f * sin(u * 9f - time) +
                            0.003f * cos(
                        u * 12f - v * 7f + 2f * time
                    )

                // Main sky swirl, in equal x/y units.
                val dx1 = (u - 0.55f) * aspect
                val dy1 = v - 0.46f

                val weight1 = exp(
                    -(dx1 * dx1 + dy1 * dy1) / 0.10f
                )

                val rotation1 =
                    0.18f * sin(time) * weight1

                val rotatedX1 =
                    dx1 * cos(rotation1) -
                            dy1 * sin(rotation1)

                val rotatedY1 =
                    dx1 * sin(rotation1) +
                            dy1 * cos(rotation1)

                moveX += (rotatedX1 - dx1) / aspect
                moveY += rotatedY1 - dy1

                // A smaller swirl with a different phase.
                val dx2 = (u - 0.77f) * aspect
                val dy2 = v - 0.66f

                val weight2 = exp(
                    -(dx2 * dx2 + dy2 * dy2) / 0.035f
                )

                val rotation2 =
                    -0.15f * sin(time + 1.2f) * weight2

                val rotatedX2 =
                    dx2 * cos(rotation2) -
                            dy2 * sin(rotation2)

                val rotatedY2 =
                    dx2 * sin(rotation2) +
                            dy2 * cos(rotation2)

                moveX += (rotatedX2 - dx2) / aspect
                moveY += rotatedY2 - dy2

                // Move the image grid point.
                vertices[index++] =
                    left + (
                            u + moveX * motionMask
                            ) * imageWidth

                vertices[index++] =
                    top + (
                            v + moveY * motionMask
                            ) * imageHeight
            }
        }

        // Draw the painting itself through the moving grid.
        drawContext.canvas.nativeCanvas.drawBitmapMesh(
            painting,
            GRID_COLUMNS,
            GRID_ROWS,
            vertices,
            0,
            null,
            0,
            paint
        )
    }
}