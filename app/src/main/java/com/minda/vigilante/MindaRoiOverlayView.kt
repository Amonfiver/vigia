package com.minda.vigilante

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class MindaRoiOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ===== PROPIEDADES QUE FALTABAN =====
    var isCalibrating: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var calibrationTarget: String = "100"
        set(value) {
            field = value
            invalidate()
        }

    var rois: List<Roi> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var onRoiCreated: ((Roi) -> Unit)? = null

    private val paintBox = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.YELLOW
        isAntiAlias = true
    }

    private val paintText = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }

    private val paintFill = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(60, 255, 255, 0)
    }

    private var startX = 0f
    private var startY = 0f
    private var currentRect: RectF? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Dibujar ROIs guardadas
        for (r in rois) {
            val rect = RectF(
                r.leftN * width,
                r.topN * height,
                r.rightN * width,
                r.bottomN * height
            )
            canvas.drawRect(rect, paintFill)
            canvas.drawRect(rect, paintBox)
            canvas.drawText(r.name, rect.left + 10, rect.top + 40, paintText)
        }

        // Dibujar ROI en creación
        currentRect?.let { rect ->
            canvas.drawRect(rect, paintFill)
            canvas.drawRect(rect, paintBox)
            canvas.drawText("Set $calibrationTarget", rect.left + 10, rect.top + 40, paintText)
        }

        if (isCalibrating) {
            canvas.drawText(
                "CALIBRANDO: dibuja ROI para $calibrationTarget",
                20f,
                height - 30f,
                paintText
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isCalibrating) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                currentRect = RectF(startX, startY, startX, startY)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                currentRect?.let {
                    it.right = event.x
                    it.bottom = event.y
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val rect = currentRect ?: return true
                currentRect = null
                invalidate()

                val l = (minOf(rect.left, rect.right) / width).coerceIn(0f, 1f)
                val r = (maxOf(rect.left, rect.right) / width).coerceIn(0f, 1f)
                val t = (minOf(rect.top, rect.bottom) / height).coerceIn(0f, 1f)
                val b = (maxOf(rect.top, rect.bottom) / height).coerceIn(0f, 1f)

                onRoiCreated?.invoke(
                    Roi(calibrationTarget, l, t, r, b)
                )

                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
