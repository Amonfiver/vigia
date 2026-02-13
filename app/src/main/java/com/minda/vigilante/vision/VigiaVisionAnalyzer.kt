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
    private val faultConfirmMs: Long = 15_000L
) : ImageAnalysis.Analyzer {

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

                val redBars = isRedBars(
                    buf, rowStride, w,
                    rect.left, rect.top, rect.right, rect.bottom
                )

                // Estado target según visión
                val seenOrange = orangeRatio > 0.28f     // ajustable
                val seenRedBars = redBars                // ya es boolean

                // Lógica de estados
                when {
                    seenOrange -> {
                        if (tr.state != State.ORANGE) {
                            tr.state = State.ORANGE
                            tr.redSinceMs = null
                            telegram?.send("🟠 TRANSFER $name PARADO (OBSTÁCULO)")
                            throttledUi(now, "🟠 Transfer $name: OBSTÁCULO (alerta enviada)")
                        }
                    }

                    seenRedBars -> {
                        // Si veníamos de ORANGE, cambiamos a RED_PENDING (fallo) igualmente
                        if (tr.redSinceMs == null) tr.redSinceMs = now

                        val elapsed = now - (tr.redSinceMs ?: now)
                        if (elapsed >= faultConfirmMs) {
                            if (tr.state != State.RED_CONFIRMED) {
                                tr.state = State.RED_CONFIRMED
                                telegram?.send("🟥 TRANSFER $name MAL FUNCIONAMIENTO (>15s)")
                                throttledUi(now, "🟥 Transfer $name: MAL FUNCIONAMIENTO (enviado)")
                            }
                        } else {
                            if (tr.state != State.RED_PENDING) {
                                tr.state = State.RED_PENDING
                                throttledUi(now, "⚠️ Transfer $name: posible fallo (${((faultConfirmMs - elapsed)/1000)}s)")
                            }
                        }
                    }

                    else -> {
                        // Vuelve a OK
                        if (tr.state == State.ORANGE) {
                            telegram?.send("✅ TRANSFER $name REARMADO — TODO OK")
                        } else if (tr.state == State.RED_CONFIRMED || tr.state == State.RED_PENDING) {
                            telegram?.send("✅ TRANSFER $name ACTIVO — TODO OK")
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

    private fun throttledUi(now: Long, msg: String) {
        if (now - lastUiMs > 600) { // evita spam de UI
            lastUiMs = now
            onStatusText(msg)
        }
    }

    // --- Detección por color (HSV) ---
    private fun isOrange(r: Int, g: Int, b: Int): Boolean {
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val h = hsv[0]      // 0..360
        val s = hsv[1]      // 0..1
        val v = hsv[2]      // 0..1
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

    // --- Red bars: rojo arriba y abajo dentro de ROI ---
    private fun isRedBars(
        buf: ByteBuffer,
        rowStride: Int,
        imgW: Int,
        l: Int, t: Int, r: Int, b: Int
    ): Boolean {
        val height = b - t
        if (height < 20) return false

        val bandH = max(8, (height * 0.18f).toInt()) // 18% arriba/abajo
        val topRed = sampleColorRatio(buf, rowStride, imgW, l, t, r, t + bandH, 7) { rr, gg, bb -> isRed(rr, gg, bb) }
        val botRed = sampleColorRatio(buf, rowStride, imgW, l, b - bandH, r, b, 7) { rr, gg, bb -> isRed(rr, gg, bb) }

        // umbrales: barras bastante rojas
        return topRed > 0.22f && botRed > 0.22f
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

        // Rejilla uniforme
        for (gy in 0 until grid) {
            val y = t + (gy * height) / (grid - 1).coerceAtLeast(1)
            val yy = y.coerceAtLeast(0)
            for (gx in 0 until grid) {
                val x = l + (gx * width) / (grid - 1).coerceAtLeast(1)
                val xx = x.coerceAtLeast(0)

                val idx = yy * rowStride + xx * 4 // RGBA
                if (idx + 2 >= buf.limit()) continue

                val rr = buf.get(idx).toInt() and 0xFF
                val gg = buf.get(idx + 1).toInt() and 0xFF
                val bb = buf.get(idx + 2).toInt() and 0xFF
                total++
                if (predicate(rr, gg, bb)) hits++
            }
        }
        if (total == 0) return 0f
        return hits.toFloat() / total.toFloat()
    }
}
