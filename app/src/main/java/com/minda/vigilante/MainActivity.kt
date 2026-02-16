package com.minda.vigilante

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
import com.minda.vigilante.vision.TrainLabel
import com.minda.vigilante.vision.TrainingStore
import com.minda.vigilante.vision.VigiaVisionAnalyzer
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var roiOverlay: MindaRoiOverlayView
    private lateinit var statusText: TextView
    private lateinit var previewView: PreviewView

    private val roiMap: MutableMap<String, Roi> = mutableMapOf()
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private lateinit var trainingStore: TrainingStore
    private var analyzerRef: VigiaVisionAnalyzer? = null

    // ======= ENTRENAMIENTO =======
    private var trainingOn = false
    private val trainingOrder = listOf("100", "200", "300")
    private var trainingIdx = 0
    private val minSamplesPerLabel = 5

    private fun currentRoiName(): String = trainingOrder[trainingIdx]

    private fun countsStr(roi: String): String {
        val c = trainingStore.counts(roi)
        fun f(l: TrainLabel) = (c[l] ?: 0)
        return "ROI $roi | OK=${f(TrainLabel.OK)}/$minSamplesPerLabel  " +
                "OBS=${f(TrainLabel.OBSTACLE)}/$minSamplesPerLabel  " +
                "FALLO=${f(TrainLabel.FAULT)}/$minSamplesPerLabel"
    }

    private fun roiReady(): Boolean {
        val missing = listOf("100", "200", "300").filter { !roiMap.containsKey(it) }
        return missing.isEmpty()
    }

    private fun trainingReadyAll(): Boolean =
        trainingOrder.all { trainingStore.isTrained(it, minPerLabel = minSamplesPerLabel) }

    // ======= TELEGRAM =======
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

        trainingStore = TrainingStore(this)
        trainingStore.load()

        val root = FrameLayout(this)

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        roiOverlay = MindaRoiOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            rois = emptyList()
            // IMPORTANTE: en el overlay vamos a permitir arrastrar ROIs ya creadas
            // (lo implementamos en MindaRoiOverlayView)
        }
        roiOverlay.onRoiMoved = { moved ->
            roiMap[moved.name] = moved
            // opcional: si quieres que se reordene por 100/200/300 al mover
            roiOverlay.rois = roiMap.values.sortedBy { it.name.toIntOrNull() ?: 999 }
        }
        // ===== PANEL DERECHO SCROLLABLE =====
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(620, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 14
                marginEnd = 14
                bottomMargin = 14
            }
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
            setBackgroundColor(0xAA000000.toInt())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scroll.addView(panel)

        val title = TextView(this).apply {
            text = "V.I.G.I.A."
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
        }

        statusText = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            text = "Estado: listo"
        }

        val calibrateBtn = Button(this).apply {
            text = "CALIBRAR ROIS (100→200→300)"
            setOnClickListener { startCalibration() }
        }

        val resetBtn = Button(this).apply {
            text = "RESET ROIS"
            setOnClickListener { resetRois() }
        }

        val watchBtn = Button(this).apply {
            text = "MODO VIGILANTE ON"
            setOnClickListener {
                // Bloqueos duros para no tener “vigilante sin info”
                if (!roiReady()) {
                    val missing = listOf("100", "200", "300").filter { !roiMap.containsKey(it) }
                    toast("Faltan ROIs: ${missing.joinToString(", ")}")
                    statusText.text = "⚠️ No puedo activar Vigilante: faltan ROIs (${missing.joinToString(", ")})"
                    return@setOnClickListener
                }
                if (!trainingReadyAll()) {
                    val notReady = trainingOrder.filter { !trainingStore.isTrained(it, minPerLabel = minSamplesPerLabel) }
                    toast("Falta entrenamiento: ${notReady.joinToString(", ")}")
                    statusText.text = "⚠️ No puedo activar Vigilante: falta entrenamiento en ${notReady.joinToString(", ")}"
                    return@setOnClickListener
                }

                roiOverlay.isCalibrating = false
                trainingOn = false
                toast("Vigilante ON ✅")
                statusText.text = "Vigilante ON ✅ (detectando en tiempo real)"
            }
        }

        val testTelegramBtn = Button(this).apply {
            text = "TEST TELEGRAM ✅"
            setOnClickListener {
                telegramClient?.send("✅ VIGIA: conexión Telegram OK") { ok, msg ->
                    runOnUiThread {
                        if (ok) {
                            toast("Telegram OK ✅")
                            statusText.text = "Telegram OK ✅"
                        } else {
                            toast("Telegram ERROR")
                            statusText.text = "Telegram ERROR: $msg"
                        }
                    }
                }
            }
        }

        // ===== ENTRENAMIENTO UI =====
        val trainingHeader = TextView(this).apply {
            text = "Entrenamiento (botones rápidos)"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
        }

        val trainingInfo = TextView(this).apply {
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 13f
            text = "Entrenamiento: OFF"
        }

        val trainingStartBtn = Button(this).apply {
            text = "INICIAR ENTRENAMIENTO 🎯"
            setOnClickListener {
                if (!roiReady()) {
                    val missing = listOf("100", "200", "300").filter { !roiMap.containsKey(it) }
                    toast("Primero calibra ROIs: ${missing.joinToString(", ")}")
                    statusText.text = "⚠️ No puedo entrenar sin ROIs"
                    return@setOnClickListener
                }
                trainingOn = true
                trainingIdx = 0
                roiOverlay.isCalibrating = false
                trainingInfo.text = "Entrenamiento: ON | ROI actual: ${currentRoiName()}\n${countsStr(currentRoiName())}"
                statusText.text = "🎯 Entrenamiento ON — usa OK / OBSTÁCULO / FALLO (mín $minSamplesPerLabel cada uno)"
                updateTrainingButtonsVisibility()
            }
        }

        val trainingBtnsRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val btnOk = Button(this).apply {
            text = "OK (normal) ✅"
            setOnClickListener { captureTraining(TrainLabel.OK, trainingInfo) }
        }
        val btnObs = Button(this).apply {
            text = "OBSTÁCULO 🟠"
            setOnClickListener { captureTraining(TrainLabel.OBSTACLE, trainingInfo) }
        }
        val btnFault = Button(this).apply {
            text = "MAL FUNCIONAMIENTO 🟥"
            setOnClickListener { captureTraining(TrainLabel.FAULT, trainingInfo) }
        }

        val btnNext = Button(this).apply {
            text = "SIGUIENTE TRANSFER ▶ (100→200→300)"
            setOnClickListener {
                if (!trainingOn) {
                    toast("Entrenamiento está OFF")
                    return@setOnClickListener
                }
                val roi = currentRoiName()
                if (!trainingStore.isTrained(roi, minPerLabel = minSamplesPerLabel)) {
                    toast("Aún no completo ROI $roi (mín $minSamplesPerLabel por estado)")
                    statusText.text = "⚠️ Completa ROI $roi antes de pasar"
                    trainingInfo.text = "Entrenamiento: ON | ROI actual: $roi\n${countsStr(roi)}"
                    return@setOnClickListener
                }

                if (trainingIdx < trainingOrder.lastIndex) {
                    trainingIdx++
                    val next = currentRoiName()
                    toast("Ahora entrenas ROI $next")
                    trainingInfo.text = "Entrenamiento: ON | ROI actual: $next\n${countsStr(next)}"
                    statusText.text = "🎯 Entrenando ROI $next"
                } else {
                    toast("Ya estás en el último (300). Finaliza.")
                }
            }
        }

        val btnFinish = Button(this).apply {
            text = "FINALIZAR ENTRENAMIENTO ✅"
            setOnClickListener {
                if (!trainingOn) {
                    toast("Entrenamiento está OFF")
                    return@setOnClickListener
                }
                val notReady = trainingOrder.filter { !trainingStore.isTrained(it, minPerLabel = minSamplesPerLabel) }
                if (notReady.isNotEmpty()) {
                    toast("Falta entrenamiento en: ${notReady.joinToString(", ")}")
                    statusText.text = "⚠️ Falta entrenar: ${notReady.joinToString(", ")}"
                    trainingInfo.text = "Entrenamiento: ON | ROI actual: ${currentRoiName()}\n${countsStr(currentRoiName())}"
                    return@setOnClickListener
                }

                trainingOn = false
                updateTrainingButtonsVisibility()
                toast("Entrenamiento finalizado ✅")
                trainingInfo.text = "Entrenamiento: OFF ✅ (listo para Vigilante)"
                statusText.text = "✅ Entrenamiento OK. Ya puedes activar Modo Vigilante ON."
            }
        }

        val btnClear = Button(this).apply {
            text = "BORRAR ENTRENAMIENTO 🧹"
            setOnClickListener {
                trainingStore.clearAll()
                trainingOn = false
                trainingIdx = 0
                updateTrainingButtonsVisibility()
                toast("Entrenamiento borrado")
                trainingInfo.text = "Entrenamiento: OFF (borrado)"
                statusText.text = "Entrenamiento borrado ✅"
            }
        }

        // metemos botones en fila y los escondemos por defecto
        trainingBtnsRow.addView(btnOk)
        trainingBtnsRow.addView(btnObs)
        trainingBtnsRow.addView(btnFault)
        trainingBtnsRow.addView(btnNext)
        trainingBtnsRow.addView(btnFinish)
        trainingBtnsRow.addView(btnClear)

        // ===== ROIs: guardar y permitir recolocar luego =====
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

        // Panel layout
        panel.addView(title)
        panel.addView(space())

        panel.addView(calibrateBtn)
        panel.addView(resetBtn)
        panel.addView(watchBtn)
        panel.addView(testTelegramBtn)

        panel.addView(space())
        panel.addView(trainingHeader)
        panel.addView(trainingInfo)
        panel.addView(trainingStartBtn)
        panel.addView(trainingBtnsRow)

        panel.addView(space())
        panel.addView(statusText)

        root.addView(previewView)
        root.addView(roiOverlay)
        root.addView(scroll)

        setContentView(root)

        // estado inicial botones
        fun hideAllTrainingBtns() {
            trainingBtnsRow.visibility = View.GONE
        }
        hideAllTrainingBtns()
        ensureCameraPermission()

        // helper
        fun updateTrainInfoIfStored() {
            if (roiReady()) {
                trainingInfo.text = if (trainingReadyAll())
                    "Entrenamiento: OFF ✅ (ya entrenado)"
                else
                    "Entrenamiento: OFF (falta entrenar)\n100/200/300 mín $minSamplesPerLabel por estado"
            }
        }
        updateTrainInfoIfStored()

        // función local
        fun updateVisibility() {
            trainingBtnsRow.visibility = if (trainingOn) View.VISIBLE else View.GONE
        }
        // puente a método
        updateTrainingButtonsVisibility = { updateVisibility() }
    }

    // Truco para poder llamar desde varios sitios sin duplicar
    private var updateTrainingButtonsVisibility: () -> Unit = {}

    private fun captureTraining(label: TrainLabel, trainingInfo: TextView) {
        if (!trainingOn) {
            toast("Activa primero: INICIAR ENTRENAMIENTO")
            return
        }
        val roi = currentRoiName()
        val analyzer = analyzerRef
        val f = analyzer?.lastFeatures?.get(roi)

        if (f == null) {
            toast("Aún no tengo lectura para ROI $roi (espera 1s)")
            statusText.text = "⚠️ Sin lectura ROI $roi"
            return
        }

        trainingStore.addSample(roi, label, f)

        val c = trainingStore.counts(roi)
        val n = c[label] ?: 0
        toast("Guardado $label ($n/$minSamplesPerLabel) en ROI $roi")

        val trained = trainingStore.isTrained(roi, minPerLabel = minSamplesPerLabel)
        trainingInfo.text =
            "Entrenamiento: ON | ROI actual: $roi\n${countsStr(roi)}" +
                    if (trained) "\n✅ ROI $roi entrenado" else ""

        statusText.text = "🎯 ROI $roi: capturado ${label.name} ($n/$minSamplesPerLabel)"
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
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            val analyzer = VigiaVisionAnalyzer(
                getRois = { roiOverlay.rois },
                onStatusText = { msg -> runOnUiThread { statusText.text = msg } },
                telegram = telegramClient,
                faultConfirmMs = 15_000L,
                trainingStore = trainingStore
            )
            analyzerRef = analyzer
            analysis.setAnalyzer(cameraExecutor, analyzer)

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)

            statusText.text = "Cámara lista ✅ Calibra ROIs y entrena (OK/OBS/FALLO)"
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

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun space(): Space = Space(this).apply { minimumHeight = 12 }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
