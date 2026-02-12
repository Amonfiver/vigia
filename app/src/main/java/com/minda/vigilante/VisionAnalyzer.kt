package com.minda.vigilante

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.roundToInt

data class Detection(
    val obstacle: Boolean,
    val fault: Boolean
)

class VisionAnalyzer(
    private val roisProvider: () -> List<Roi>,
    private val onDetections: (Map<Int, Detection>) -> Unit
) : ImageAnalysis.Analyzer {

    // Umbrales (ajustables luego)
    private val orangeThreshold = 0.28f   // % de muestras naranja para obstáculo
    private val redThreshold = 0.18f      // % de muestras rojas para fallo

    override fun analyze(image: ImageProxy) {
        try {
            val rois = roisProvider()
            if (rois.size < 3) return

            val results = mutableMapOf<Int, Detection>()
            for (roi in rois) {
                val code = roi.name.toIntOrNull() ?: continue
                val det = detectInRoi(image, roi.normalized())
                results[code] = det
            }
            onDetections(results)
        } finally {
            image.close()
        }
    }

    private fun detectInRoi(img: ImageProxy, roi: Roi): Detection {
        val w = img.width
        val h = img.height

        // ROI en coords de imagen (asumimos rotationDegrees ~0 porque preview en landscape; lo afinaremos si hace falta)
        val left = (roi.leftN * w).roundToInt().coerceIn(0, w - 1)
        val right = (roi.rightN * w).roundToInt().coerceIn(0, w - 1)
        val top = (roi.topN * h).roundToInt().coerceIn(0, h - 1)
        val bottom = (roi.bottomN * h).roundToInt().coerceIn(0, h - 1)

        val sampleCols = 18
        val sampleRows = 10

        var orangeCount = 0
        var redCount = 0
        var total = 0

        for (ry in 0 until sampleRows) {
            val y = top + ((bottom - top) * (ry + 0.5f) / sampleRows).roundToInt().coerceIn(0, h - 1)
            for (rx in 0 until sampleCols) {
                val x = left + ((right - left) * (rx + 0.5f) / sampleCols).roundToInt().coerceIn(0, w - 1)

                val (r, g, b) = yuvToRgbAt(img, x, y)

                // Clasificación rápida
                if (isOrange(r, g, b)) orangeCount++
                if (isRed(r, g, b)) redCount++
                total++
            }
        }

        val orangePct = orangeCount.toFloat() / total.coerceAtLeast(1)
        val redPct = redCount.toFloat() / total.coerceAtLeast(1)

        val obstacle = orangePct >= orangeThreshold
        val fault = redPct >= redThreshold

        return Detection(obstacle = obstacle, fault = fault)
    }

    // Conversión YUV420 (ImageProxy planes) -> RGB por píxel
    private fun yuvToRgbAt(img: ImageProxy, x: Int, y: Int): Triple<Int, Int, Int> {
        val yPlane = img.planes[0]
        val uPlane = img.planes[1]
        val vPlane = img.planes[2]

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val yIndex = yRowStride * y + x
        val uvX = x / 2
        val uvY = y / 2
        val uvIndex = uvRowStride * uvY + uvPixelStride * uvX

        val Y = (yPlane.buffer.get(yIndex).toInt() and 0xFF)
        val U = (uPlane.buffer.get(uvIndex).toInt() and 0xFF)
        val V = (vPlane.buffer.get(uvIndex).toInt() and 0xFF)

        // Fórmula estándar
        val yf = Y - 16
        val uf = U - 128
        val vf = V - 128

        var r = (1.164f * yf + 1.596f * vf)
        var g = (1.164f * yf - 0.392f * uf - 0.813f * vf)
        var b = (1.164f * yf + 2.017f * uf)

        r = r.coerceIn(0f, 255f)
        g = g.coerceIn(0f, 255f)
        b = b.coerceIn(0f, 255f)

        return Triple(r.toInt(), g.toInt(), b.toInt())
    }

    private fun isOrange(r: Int, g: Int, b: Int): Boolean {
        // naranja: R alto, G medio, B bajo
        return r > 160 && g > 80 && g < 190 && b < 120
    }

    private fun isRed(r: Int, g: Int, b: Int): Boolean {
        // rojo: R alto, G bajo-medio, B bajo
        return r > 160 && g < 110 && b < 110
    }
}
