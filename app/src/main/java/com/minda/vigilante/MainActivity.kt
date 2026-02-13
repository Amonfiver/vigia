package com.minda.vigilante

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.minda.vigilante.telegram.TelegramClient
import com.minda.vigilante.vision.VigiaVisionAnalyzer
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var roiOverlay: MindaRoiOverlayView
    private lateinit var statusText: TextView
    private lateinit var previewView: PreviewView

    // ROIs guardadas en memoria
    private val roiMap: MutableMap<String, Roi> = mutableMapOf()

    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // 🔧 PON AQUÍ TU BOT TOKEN y CHAT ID (demo)
    // Nota: luego lo pasamos a Settings/SharedPreferences.
    private val telegramClient: TelegramClient? = TelegramClient(
        botToken = "8258985373:AAGtf6pibwQGMNT6GDsTTSguh6e_2_beC2g",
        chatId = "7781152307"
    )

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else toast("Sin permiso de cámara no puedo vigilar")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Mejor para análisis (reduce latencia)
            scaleType = PreviewView.ScaleType.FILL_CENTER
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
            text = "V.I.G.I.A."
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
                    statusText.text = "⚠️ Falta calibrar: ${missing.joinToString(", ")}"
                } else {
                    toast("Vigilante ON")
                    statusText.text = "Vigilante ON ✅"
                }
            }
        }

        // ✅ NUEVO: Botón de test Telegram
        val telegramTestBtn = Button(this).apply {
            text = "Test Telegram ✅"
            setOnClickListener { sendTelegramTest() }
        }

        // Guardar ROI y avanzar
        roiOverlay.onRoiCreated = { roi ->
            roiMap[roi.name] = roi
            roiOverlay.rois = roiMap.values.sortedBy { it.name.toIntOrNull() ?: 999 }

            when (roi.name) {
                "100" -> nextTarget("200")
                "200" -> nextTarget("300")
                "300" -> finishCalibration()
                else -> statusText.text = "ROI guardada para ${roi.name}"
            }
        }

        panel.addView(title)
        panel.addView(space())
        panel.addView(calibrateBtn)
        panel.addView(resetBtn)
        panel.addView(watchBtn)
        panel.addView(telegramTestBtn) // ✅ añadido aquí
        panel.addView(space())
        panel.addView(statusText)

        root.addView(previewView)
        root.addView(roiOverlay)
        root.addView(panel)

        setContentView(root)

        ensureCameraPermission()
    }

    private fun ensureCameraPermission() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) startCamera() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // 👇 CLAVE: nos facilita RGBA directo para muestrear píxeles
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            val analyzer = VigiaVisionAnalyzer(
                getRois = { roiOverlay.rois },
                onStatusText = { msg -> runOnUiThread { statusText.text = msg } },
                telegram = telegramClient,
                faultConfirmMs = 15_000L
            )

            analysis.setAnalyzer(cameraExecutor, analyzer)

            val selector = CameraSelector.DEFAULT_BACK_CAMERA

            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, analysis)

            statusText.text = "Cámara lista ✅ Calibra ROIs y activa Vigilante"
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startCalibration() {
        roiOverlay.isCalibrating = true
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
        statusText.text = "Reset ✅ Pulsa Calibrar"
    }

    // ✅ NUEVO: helper para test Telegram (sin crasheos)
    private fun sendTelegramTest() {

        val client = telegramClient ?: run {
            statusText.text = "Telegram ❌ Cliente null"
            return
        }

        statusText.text = "Enviando mensaje..."

        client.send("✅ VIGIA: conexión Telegram OK") { success, error ->

            runOnUiThread {
                if (success) {
                    statusText.text = "Telegram ✅ Conectado correctamente"
                    toast("Telegram OK")
                } else {
                    statusText.text = "Telegram ❌ $error"
                    toast("Telegram ERROR")
                }
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun space(): Space = Space(this).apply { minimumHeight = 10 }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
