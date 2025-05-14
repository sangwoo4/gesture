package com.square.aircommand.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

@Composable
fun HandLandmarkOverlay(
    landmarks: List<Triple<Double, Double, Double>>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val points = landmarks.map { (x, y, _) ->
            Offset((x * size.width).toFloat(), (y * size.height).toFloat())
        }

        val connections = listOf(
            listOf(0, 1, 2, 3, 4),        // Thumb
            listOf(0, 5, 6, 7, 8),        // Index
            listOf(0, 9, 10, 11, 12),     // Middle
            listOf(0, 13, 14, 15, 16),    // Ring
            listOf(0, 17, 18, 19, 20)     // Pinky
        )

        connections.forEach { finger ->
            for (i in 0 until finger.size - 1) {
                drawLine(
                    color = Color.Red,
                    start = points[finger[i]],
                    end = points[finger[i + 1]],
                    strokeWidth = 4f
                )
            }
        }

        points.forEach { pt ->
            drawCircle(
                color = Color.Red,
                radius = 8f,
                center = pt
            )
        }
    }
}
