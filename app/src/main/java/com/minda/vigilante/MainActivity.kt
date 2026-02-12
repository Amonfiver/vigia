package com.minda.vigilante

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var roiOverlay: MindaRoiOverlayView
    private lateinit var statusText: TextView

    // Guardamos ROIs aquí (estado de la app)
    private val roiMap: MutableMap<String, Roi> = mutableMapOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)

        val fakeBackground = TextView(this).apply {
            text = "Preview (aquí irá la cámara)"
            textSize = 20f
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF111111.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        roiOverlay = MindaRoiOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            rois = emptyList()
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
            setBackgroundColor(0xAA000000.toInt())
            layoutParams = FrameLayout.LayoutParams(560, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 20
                marginEnd = 20
            }
        }

        val title = TextView(this).apply {
            text = "Simulación Almacén Inteligente – Minda"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
        }

        statusText = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            text = "Estado: listo"
        }

        val calibrateBtn = Button(this).apply {
            text = "Calibrar ROIs (100→200→300)"
            setOnClickListener { startCalibration() }
        }

        val resetBtn = Button(this).apply {
            text = "Reset ROIs"
            setOnClickListener { resetRois() }
        }

        val watchBtn = Button(this).apply {
            text = "Modo Vigilante ON"
            setOnClickListener {
                roiOverlay.isCalibrating = false

                val missing = listOf("100", "200", "300").filter { !roiMap.containsKey(it) }
                if (missing.isNotEmpty()) {
                    toast("Faltan ROIs: ${missing.joinToString(", ")}")
                    statusText.text = "⚠️ Vigilante ON, pero faltan ROIs: ${missing.joinToString(", ")}"
                } else {
                    toast("Vigilante ON")
                    statusText.text = "Vigilante ON ✅ (ROIs ok)"
                }
            }
        }

        // Cuando se crea una ROI (al soltar el dedo)
        roiOverlay.onRoiCreated = { roi ->
            roiMap[roi.name] = roi
            roiOverlay.rois = roiMap.values.sortedBy { it.name.toIntOrNull() ?: 999 }

            when (roi.name) {
                "100" -> nextTarget("200")
                "200" -> nextTarget("300")
                "300" -> finishCalibration()
                else -> {
                    // por si algún día metemos otros
                    statusText.text = "ROI guardada para ${roi.name}"
                }
            }
        }

        panel.addView(title)
        panel.addView(space())
        panel.addView(calibrateBtn)
        panel.addView(resetBtn)
        panel.addView(watchBtn)
        panel.addView(space())
        panel.addView(statusText)

        root.addView(fakeBackground)
        root.addView(roiOverlay)
        root.addView(panel)

        setContentView(root)
    }

    private fun startCalibration() {
        roiOverlay.isCalibrating = true

        // Si ya existe 100, saltamos al siguiente que falte
        val next = listOf("100", "200", "300").firstOrNull { !roiMap.containsKey(it) } ?: "100"
        roiOverlay.calibrationTarget = next

        toast("Dibuja ROI para $next")
        statusText.text = "Calibrando: dibuja ROI para $next"
    }

    private fun nextTarget(target: String) {
        roiOverlay.isCalibrating = true
        roiOverlay.calibrationTarget = target
        toast("Ahora dibuja ROI para $target")
        statusText.text = "Calibrando: dibuja ROI para $target"
    }

    private fun finishCalibration() {
        roiOverlay.isCalibrating = false
        toast("Calibración completa ✅")
        statusText.text = "Calibración completa ✅ (100/200/300)"
    }

    private fun resetRois() {
        roiMap.clear()
        roiOverlay.rois = emptyList()
        roiOverlay.isCalibrating = false
        roiOverlay.calibrationTarget = "100"

        toast("ROIs borradas")
        statusText.text = "Reset ✅ Pulsa Calibrar para empezar"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun space(): Space = Space(this).apply { minimumHeight = 10 }
}
