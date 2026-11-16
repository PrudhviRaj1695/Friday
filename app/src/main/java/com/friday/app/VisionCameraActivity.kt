package com.friday.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FRIDAY Vision v1: local camera preview, still capture and throttled live frame analysis.
 * Frames are processed on-device and are never uploaded by this activity.
 */
class VisionCameraActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var analysisText: TextView
    private lateinit var captureButton: Button
    private lateinit var liveButton: Button
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var analysis: ImageAnalysis? = null
    private var liveEnabled = true
    private var lastAnalysisAt = 0L
    private var latestResult = ""
    private val analyzing = AtomicBoolean(false)
    private val objectDetector by lazy {
        ObjectDetection.getClient(ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableClassification()
            .build())
    }
    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val barcodeScanner by lazy { BarcodeScanning.getClient() }
    private val faceDetector by lazy {
        FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .enableTracking()
            .build())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 7410)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(2, 11, 24))
            setPadding(16, 18, 16, 12)
        }
        val title = TextView(this).apply {
            text = "FRIDAY VISION  •  ON-DEVICE"
            setTextColor(Color.rgb(0, 229, 242))
            textSize = 18f
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Starting camera…"
            setTextColor(Color.LTGRAY)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(4, 8, 4, 8)
        }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
        analysisText = TextView(this).apply {
            text = "Live recognition results will appear here. Point the camera at an object, text or barcode."
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(12, 12, 12, 12)
            setBackgroundColor(Color.rgb(11, 28, 46))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        captureButton = Button(this).apply {
            text = "CAPTURE & ANALYZE"
            setOnClickListener { captureStill() }
        }
        liveButton = Button(this).apply {
            text = "PAUSE LIVE"
            setOnClickListener {
                liveEnabled = !liveEnabled
                text = if (liveEnabled) "PAUSE LIVE" else "RESUME LIVE"
                status.text = if (liveEnabled) "Live analysis active • local processing" else "Live analysis paused • camera preview remains active"
            }
        }
        val useButton = Button(this).apply {
            text = "ASK FRIDAY"
            setOnClickListener { returnResult() }
        }
        val closeButton = Button(this).apply {
            text = "CLOSE"
            setOnClickListener { setResult(Activity.RESULT_CANCELED); finish() }
        }
        controls.addView(captureButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(liveButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        actions.addView(useButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(closeButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(title)
        root.addView(status)
        root.addView(previewView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f))
        root.addView(analysisText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(controls)
        root.addView(actions)
        setContentView(root)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(cameraExecutor, ::analyzeLiveFrame) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
                status.text = "Camera active • live analysis on-device • no recording"
            } catch (error: Exception) {
                status.text = "Camera could not start: ${error.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeLiveFrame(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (!liveEnabled || now - lastAnalysisAt < 850 || !analyzing.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        lastAnalysisAt = now
        val mediaImage = proxy.image
        if (mediaImage == null) {
            analyzing.set(false)
            proxy.close()
            return
        }
        try {
            val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
            val result = recognize(image)
            runOnUiThread {
                latestResult = result
                analysisText.text = result
                status.text = "Live frame analyzed • ${proxy.width}×${proxy.height} • local only"
            }
        } catch (error: Exception) {
            runOnUiThread { status.text = "Vision analysis error: ${error.message}" }
        } finally {
            analyzing.set(false)
            proxy.close()
        }
    }

    private fun recognize(image: InputImage): String {
        val objects: List<DetectedObject> = Tasks.await(objectDetector.process(image), 1800, java.util.concurrent.TimeUnit.MILLISECONDS)
        val text = Tasks.await(textRecognizer.process(image), 1800, java.util.concurrent.TimeUnit.MILLISECONDS).text
        val barcodes = Tasks.await(barcodeScanner.process(image), 1800, java.util.concurrent.TimeUnit.MILLISECONDS)
        val faces = Tasks.await(faceDetector.process(image), 1800, java.util.concurrent.TimeUnit.MILLISECONDS)
        val objectLines = objects.take(12).map { detected ->
            val label = detected.labels.firstOrNull()?.text ?: "Object"
            val confidence = detected.labels.firstOrNull()?.confidence?.let { " (${(it * 100).toInt()}%)" }.orEmpty()
            "• $label$confidence"
        }
        val barcodeLines = barcodes.take(8).map { "• ${it.rawValue ?: it.displayValue ?: "barcode"} [${it.format}]" }
        return buildString {
            append("OBJECTS (generic on-device detector)\n")
            append(if (objectLines.isEmpty()) "No objects confidently labeled\n" else objectLines.joinToString("\n") + "\n")
            append("\nTEXT / OCR\n")
            append(if (text.isBlank()) "No readable text detected\n" else text.take(1800) + "\n")
            append("\nBARCODES\n")
            append(if (barcodeLines.isEmpty()) "No barcode detected\n" else barcodeLines.joinToString("\n") + "\n")
            append("\nFACES\n")
            append("${faces.size} face(s) detected; no identity recognition performed.")
        }.take(3500)
    }

    private fun captureStill() {
        val capture = imageCapture ?: return
        captureButton.isEnabled = false
        status.text = "Capturing photo…"
        val file = File(cacheDir, "friday-vision-${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                status.text = "Photo captured • analyzing locally…"
                cameraExecutor.execute {
                    try {
                        val image = InputImage.fromFilePath(this@VisionCameraActivity, android.net.Uri.fromFile(file))
                        val result = recognize(image)
                        runOnUiThread {
                            latestResult = "CAPTURED PHOTO\n$result"
                            analysisText.text = latestResult
                            status.text = "Photo analysis complete • image remains in app cache"
                            captureButton.isEnabled = true
                        }
                    } catch (error: Exception) {
                        runOnUiThread {
                            status.text = "Photo analysis failed: ${error.message}"
                            captureButton.isEnabled = true
                        }
                    } finally {
                        file.delete()
                    }
                }
            }
            override fun onError(exception: ImageCaptureException) {
                status.text = "Capture failed: ${exception.message}"
                captureButton.isEnabled = true
            }
        })
    }

    private fun returnResult() {
        val value = latestResult
        if (value.isBlank()) {
            status.text = "Wait for a frame or capture a photo first."
            return
        }
        setResult(Activity.RESULT_OK, Intent().putExtra("vision_result", value))
        finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7410 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else if (requestCode == 7410) status.text = "Camera permission denied. Enable it in Android app settings."
    }

    override fun onDestroy() {
        runCatching { ProcessCameraProvider.getInstance(this).get().unbindAll() }
        cameraExecutor.shutdown()
        runCatching { objectDetector.close(); textRecognizer.close(); barcodeScanner.close(); faceDetector.close() }
        super.onDestroy()
    }
}
