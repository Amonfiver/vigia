package com.minda.vigilante.vision

import android.graphics.Color
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.minda.vigilante.Roi
import com.minda.vigilante.telegram.TelegramClient
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

class VigiaVisionAnalyzer(
    private val getRois: () -> List<Roi>,
    private val onStatusText: (String) -> Unit,
    private val telegram: TelegramClient? = null,
    private val faultConfirmMs: Long = 15_000L,
    private val trainingStore: TrainingStore? = null
) : ImageAnalysis.Analyzer {

    // Lo último que “ve” por ROI (para capturar con el botón entrenamiento)
    val lastFeatures: ConcurrentHashMap<String, Features> = ConcurrentHashMap()

    private enum class State { OK, ORANGE, RED_PENDING, RED_CONFIRMED }

    private data class Track(
        var state: State = State.OK,
        var redSinceMs: Long? = null
    )

    private val tracks = ConcurrentHashMap<String, Track>()
    private var lastUiMs = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            val rois = getRois()

            if (rois.isEmpty()) {
                throttledUi(now, "VIGIA: sin ROIs (calibra 100/200/300)")
                return
            }

            // RGBA_8888: un solo plano con bytes RGBA
            val plane = image.planes.firstOrNull()
            val buf = plane?.buffer
            val rowStride = plane?.rowStride ?: 0

            if (buf == null || rowStride <= 0) {
                throttledUi(now, "VIGIA: formato no soportado (usa RGBA_8888)")
                return
            }

            val w = image.width
            val h = image.height

            for (roi in rois) {
                val name = roi.name
                val tr = tracks.getOrPut(name) { Track() }

                val rect = roiToPixels(roi, w, h)
                if (rect.right <= rect.left || rect.bottom <= rect.top) continue

                val orangeRatio = sampleColorRatio(
                    buf, rowStride, w,
                    rect.left, rect.top, rect.right, rect.bottom,
                    grid = 7
                ) { r, g, b -> isOrange(r, g, b) }

                val (topRed, botRed) = redBandRatios(
                    buf, rowStride, w,
                    rect.left, rect.top, rect.right, rect.bottom
                )

                val f = Features(orangeRatio, topRed, botRed)
                lastFeatures[name] = f

                // Umbrales: entrenados si existen, si no default
                val trained = trainingStore?.computeThresholds(name)
                val orangeTh = trained?.orangeThreshold ?: 0.28f
                val redTh = trained?.redThreshold ?: 0.22f

                val seenOrange = orangeRatio > orangeTh
                val seenRedBars = (topRed > redTh && botRed > redTh)

                when {
                    // OBSTÁCULO: naranja
                    seenOrange -> {
                        if (tr.state != State.ORANGE) {
                            tr.state = State.ORANGE
                            tr.redSinceMs = null
                            telegram?.send("🟠 TRANSFER $name PARADO (OBSTÁCULO)")
                        }
                        throttledUi(
                            now,
                            "🟠 Transfer $name: OBSTÁCULO (orange=${fmt(orangeRatio)} th=${fmt(orangeTh)})"
                        )
                    }

                    // FALLO: barras rojas arriba/abajo
                    seenRedBars -> {
                        if (tr.redSinceMs == null) tr.redSinceMs = now
                        val elapsed = now - (tr.redSinceMs ?: now)

                        if (elapsed >= faultConfirmMs) {
                            if (tr.state != State.RED_CONFIRMED) {
                                tr.state = State.RED_CONFIRMED
                                telegram?.send("🟥 TRANSFER $name MAL FUNCIONAMIENTO (>15s)")
                            }
                            throttledUi(
                                now,
                                "🟥 Transfer $name: MAL FUNCIONAMIENTO (top=${fmt(topRed)} bot=${fmt(botRed)} th=${fmt(redTh)})"
                            )
                        } else {
                            if (tr.state != State.RED_PENDING) tr.state = State.RED_PENDING
                            throttledUi(now, "⚠️ Transfer $name: posible fallo (${((faultConfirmMs - elapsed) / 1000)}s)")
                        }
                    }

                    // OK
                    else -> {
                        if (tr.state == State.ORANGE || tr.state == State.RED_PENDING || tr.state == State.RED_CONFIRMED) {
                            telegram?.send("✅ TRANSFER $name REARMADO — TODO OK")
                        }
                        tr.state = State.OK
                        tr.redSinceMs = null
                        throttledUi(now, "✅ Transfer $name: OK")
                    }
                }
            }
        } finally {
            image.close()
        }
    }

    private fun fmt(v: Float) = String.format("%.2f", v)

    private fun throttledUi(now: Long, msg: String) {
        if (now - lastUiMs > 600) {
            lastUiMs = now
            onStatusText(msg)
        }
    }

    // --- Color detectors ---
    private fun isOrange(r: Int, g: Int, b: Int): Boolean {
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val h = hsv[0]
        val s = hsv[1]
        val v = hsv[2]
        return (h in 18f..50f) && s > 0.45f && v > 0.35f
    }

    private fun isRed(r: Int, g: Int, b: Int): Boolean {
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val h = hsv[0]
        val s = hsv[1]
        val v = hsv[2]
        val isHueRed = (h <= 12f) || (h >= 345f)
        return isHueRed && s > 0.45f && v > 0.30f
    }

    // --- ROI -> pixels ---
    private data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun roiToPixels(roi: Roi, w: Int, h: Int): PxRect {
        val l = (roi.leftN * w).toInt().coerceIn(0, w - 1)
        val r = (roi.rightN * w).toInt().coerceIn(0, w - 1)
        val t = (roi.topN * h).toInt().coerceIn(0, h - 1)
        val b = (roi.bottomN * h).toInt().coerceIn(0, h - 1)
        return PxRect(min(l, r), min(t, b), max(l, r), max(t, b))
    }

    // ratios de rojo en banda superior e inferior
    private fun redBandRatios(
        buf: ByteBuffer,
        rowStride: Int,
        imgW: Int,
        l: Int, t: Int, r: Int, b: Int
    ): Pair<Float, Float> {
        val height = b - t
        if (height < 20) return 0f to 0f

        val bandH = max(8, (height * 0.18f).toInt())
        val topRed = sampleColorRatio(buf, rowStride, imgW, l, t, r, t + bandH, 7) { rr, gg, bb -> isRed(rr, gg, bb) }
        val botRed = sampleColorRatio(buf, rowStride, imgW, l, b - bandH, r, b, 7) { rr, gg, bb -> isRed(rr, gg, bb) }
        return topRed to botRed
    }

    // --- Sampling sobre RGBA_8888 ---
    private inline fun sampleColorRatio(
        buf: ByteBuffer,
        rowStride: Int,
        imgW: Int,
        l: Int, t: Int, r: Int, b: Int,
        grid: Int,
        predicate: (Int, Int, Int) -> Boolean
    ): Float {
        val width = max(1, r - l)
        val height = max(1, b - t)

        var hits = 0
        var total = 0

        for (gy in 0 until grid) {
            val y = t + (gy * height) / (grid - 1).coerceAtLeast(1)
            val yy = y.coerceAtLeast(0)
            for (gx in 0 until grid) {
                val x = l + (gx * width) / (grid - 1).coerceAtLeast(1)
                val xx = x.coerceAtLeast(0)

                val idx = yy * rowStride + xx * 4
                if (idx + 2 >= buf.limit()) continue

                val rr = buf.get(idx).toInt() and 0xFF
                val gg = buf.get(idx + 1).toInt() and 0xFF
                val bb = buf.get(idx + 2).toInt() and 0xFF

                total++
                if (predicate(rr, gg, bb)) hits++
            }
        }
        return if (total == 0) 0f else hits.toFloat() / total.toFloat()
    }
}
