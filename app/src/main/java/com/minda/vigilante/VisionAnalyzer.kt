package com.minda.vigilante

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * VisionAnalyzer:
 * - NO define TransferStatus ni TransferState (ya existen en TransferState.kt).
 * - Devuelve por ROI: ratios + flags (obstacle/fault) y el estado lo calcula TransferState.
 *
 * Requiere: ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
 */
class VisionAnalyzer(
    private val roisProvider: () -> List<Roi>,
    private val onDetections: (Map<Int, Detection>) -> Unit
) : ImageAnalysis.Analyzer {

    // Umbrales iniciales (luego se aprenden con tu botón "entrenar")
    private val orangeThreshold = 0.28f
    private val redThreshold = 0.18f

    override fun analyze(image: ImageProxy) {
        try {
            val rois = roisProvider()
            if (rois.isEmpty()) return

            val results = mutableMapOf<Int, Detection>()

            for (roi in rois) {
                val code = roi.name.toIntOrNull() ?: continue
                val det = detectInRoi(image, roi.normalized())
                results[code] = det
            }

            onDetections(results)
        } catch (_: Throwable) {
            // demo silenciosa
        } finally {
            image.close()
        }
    }

    private fun detectInRoi(image: ImageProxy, roi: Roi): Detection {
        val plane = image.planes.firstOrNull() ?: return Detection(0f, 0f, obstacle = false, fault = false)

        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val width = image.width
        val height = image.height

        val left = (roi.leftN * width).roundToInt().coerceIn(0, width - 1)
        val right = (roi.rightN * width).roundToInt().coerceIn(0, width - 1)
        val top = (roi.topN * height).roundToInt().coerceIn(0, height - 1)
        val bottom = (roi.bottomN * height).roundToInt().coerceIn(0, height - 1)

        val x0 = minOf(left, right)
        val x1 = maxOf(left, right)
        val y0 = minOf(top, bottom)
        val y1 = maxOf(top, bottom)

        if (x1 <= x0 || y1 <= y0) {
            return Detection(0f, 0f, obstacle = false, fault = false)
        }

        // Muestreo rápido (rejilla)
        val samplesX = 18
        val samplesY = 18

        var orangeCount = 0
        var redCount = 0
        var total = 0

        for (sy in 0 until samplesY) {
            val y = y0 + ((y1 - y0) * (sy / (samplesY - 1f))).roundToInt()
            for (sx in 0 until samplesX) {
                val x = x0 + ((x1 - x0) * (sx / (samplesX - 1f))).roundToInt()

                val (r, g, b) = readRgb(buffer, rowStride, pixelStride, x, y)

                if (isOrange(r, g, b)) orangeCount++
                if (isRed(r, g, b)) redCount++
                total++
            }
        }

        if (total <= 0) return Detection(0f, 0f, obstacle = false, fault = false)

        val orangeRatio = orangeCount.toFloat() / total.toFloat()
        val redRatio = redCount.toFloat() / total.toFloat()

        val obstacle = orangeRatio >= orangeThreshold
        val fault = redRatio >= redThreshold

        return Detection(orangeRatio, redRatio, obstacle, fault)
    }

    private fun readRgb(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        x: Int,
        y: Int
    ): Triple<Int, Int, Int> {
        val index = y * rowStride + x * pixelStride
        if (index < 0 || index + 2 >= buffer.limit()) return Triple(0, 0, 0)
        val r = buffer.get(index).toInt() and 0xFF
        val g = buffer.get(index + 1).toInt() and 0xFF
        val b = buffer.get(index + 2).toInt() and 0xFF
        return Triple(r, g, b)
    }

    // heurísticas iniciales (se mejoran con tu "entrenamiento")
    private fun isOrange(r: Int, g: Int, b: Int): Boolean {
        return r >= 170 && g in 90..190 && b <= 120 && (r - b) >= 60
    }

    private fun isRed(r: Int, g: Int, b: Int): Boolean {
        return r >= 170 && g <= 90 && b <= 90
    }
}

/**
 * Resultado visual bruto (antes de la máquina de estados).
 */
data class Detection(
    val orangeRatio: Float,
    val redRatio: Float,
    val obstacle: Boolean,
    val fault: Boolean
)
