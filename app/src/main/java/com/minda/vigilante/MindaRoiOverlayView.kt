package com.minda.vigilante

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MindaRoiOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ===== PROPIEDADES =====
    var isCalibrating: Boolean = false
        set(value) {
            field = value
            // al salir de calibración, cancelamos cualquier creación/drag
            currentRect = null
            draggingIndex = null
            invalidate()
        }

    var calibrationTarget: String = "100"
        set(value) {
            field = value
            invalidate()
        }

    // Internamente guardamos mutable para poder mover ROIs sin depender del Activity
    private val internalRois: MutableList<Roi> = mutableListOf()

    var rois: List<Roi>
        get() = internalRois
        set(value) {
            internalRois.clear()
            internalRois.addAll(value)
            invalidate()
        }

    var onRoiCreated: ((Roi) -> Unit)? = null
    // opcional: si quieres enterarte en Activity cuando arrastras y sueltas
    var onRoiMoved: ((Roi) -> Unit)? = null

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

    // ===== CREACIÓN ROI (calibración) =====
    private var startX = 0f
    private var startY = 0f
    private var currentRect: RectF? = null

    // ===== DRAG ROI (recolocar) =====
    private var draggingIndex: Int? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var dragStartX = 0f
    private var dragStartY = 0f
    private val dragTouchSlopPx = 8f
    private var isDragging = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Dibujar ROIs guardadas
        for (r in internalRois) {
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

        // Dibujar ROI en creación (solo calibrando)
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
        } else {
            // hint discreto
            if (internalRois.isNotEmpty()) {
                canvas.drawText(
                    "TIP: arrastra un ROI para recolocarlo",
                    20f,
                    height - 30f,
                    paintText
                )
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return false

        return if (isCalibrating) {
            // ===== MODO CALIBRACIÓN (crear ROI nuevo) =====
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    currentRect = RectF(startX, startY, startX, startY)
                    invalidate()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    currentRect?.let {
                        it.right = event.x
                        it.bottom = event.y
                    }
                    invalidate()
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val rect = currentRect ?: return true
                    currentRect = null
                    invalidate()

                    val l = (min(rect.left, rect.right) / width).coerceIn(0f, 1f)
                    val r = (max(rect.left, rect.right) / width).coerceIn(0f, 1f)
                    val t = (min(rect.top, rect.bottom) / height).coerceIn(0f, 1f)
                    val b = (max(rect.top, rect.bottom) / height).coerceIn(0f, 1f)

                    val roi = Roi(calibrationTarget, l, t, r, b)
                    onRoiCreated?.invoke(roi)
                    true
                }

                else -> super.onTouchEvent(event)
            }
        } else {
            // ===== MODO NORMAL (recolocar ROI existente) =====
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val hit = hitTestRoi(event.x, event.y)
                    if (hit != null) {
                        draggingIndex = hit
                        val r = internalRois[hit]
                        val rectPx = roiToRectPx(r)

                        dragOffsetX = event.x - rectPx.left
                        dragOffsetY = event.y - rectPx.top
                        dragStartX = event.x
                        dragStartY = event.y
                        isDragging = false
                        parent?.requestDisallowInterceptTouchEvent(true)
                        invalidate()
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    val idx = draggingIndex ?: return false

                    // activar drag solo si te moviste un poco (evita “saltitos”)
                    if (!isDragging) {
                        val dx = abs(event.x - dragStartX)
                        val dy = abs(event.y - dragStartY)
                        if (dx < dragTouchSlopPx && dy < dragTouchSlopPx) return true
                        isDragging = true
                    }

                    val old = internalRois[idx]
                    val wN = (old.rightN - old.leftN).coerceAtLeast(0.001f)
                    val hN = (old.bottomN - old.topN).coerceAtLeast(0.001f)

                    // nueva esquina superior izq en px
                    val newLeftPx = event.x - dragOffsetX
                    val newTopPx = event.y - dragOffsetY

                    // convertir a normalizado
                    var newLeftN = (newLeftPx / width).coerceIn(0f, 1f)
                    var newTopN = (newTopPx / height).coerceIn(0f, 1f)

                    // clamp para que no se salga (mantiene tamaño)
                    newLeftN = newLeftN.coerceIn(0f, 1f - wN)
                    newTopN = newTopN.coerceIn(0f, 1f - hN)

                    val moved = Roi(old.name, newLeftN, newTopN, newLeftN + wN, newTopN + hN)
                    internalRois[idx] = moved
                    invalidate()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val idx = draggingIndex
                    draggingIndex = null
                    parent?.requestDisallowInterceptTouchEvent(false)

                    if (idx != null) {
                        onRoiMoved?.invoke(internalRois[idx])
                    }
                    invalidate()
                    true
                }

                else -> super.onTouchEvent(event)
            }
        }
    }

    private fun roiToRectPx(r: Roi): RectF {
        return RectF(
            r.leftN * width,
            r.topN * height,
            r.rightN * width,
            r.bottomN * height
        )
    }

    private fun hitTestRoi(x: Float, y: Float): Int? {
        // buscamos desde el final por si hay solapamientos (último “encima”)
        for (i in internalRois.indices.reversed()) {
            val rect = roiToRectPx(internalRois[i])
            if (rect.contains(x, y)) return i
        }
        return null
    }
}
