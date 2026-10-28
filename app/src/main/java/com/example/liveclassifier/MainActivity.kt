package com.example.liveclassifier

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var performance: TextView
    private lateinit var predictionRows: List<TextView>
    private lateinit var pauseButton: Button
    private lateinit var actionButton: Button

    // Initialization, inference and model close are serialized on this executor.
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var classifier: ImageClassifier? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var bindingCamera = false
    private var cameraBound = false
    private var loadingModel = false
    private var frameFailed = false
    private val generation = AtomicInteger(0)
    @Volatile private var modelReady = false
    @Volatile private var analyzing = true
    @Volatile private var screenActive = false
    @Volatile private var disposed = false
    private var lastAnalysisMs = 0L
    private var lastResultMs = 0L
    private var lastResultGeneration = -1

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            if (modelReady) bindCamera()
        } else {
            showPermissionUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        performance = findViewById(R.id.performance)
        pauseButton = findViewById(R.id.pause_button)
        actionButton = findViewById(R.id.action_button)
        predictionRows = listOf(
            findViewById(R.id.prediction_one),
            findViewById(R.id.prediction_two),
            findViewById(R.id.prediction_three),
        )

        val root = findViewById<View>(R.id.root)
        val padding = (20 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        // A square PreviewView and shared ViewPort align what is shown with the analyzed crop.
        preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        preview.scaleType = PreviewView.ScaleType.FILL_CENTER
        findViewById<FrameLayout>(R.id.camera_stage).addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val side = minOf(view.width, view.height)
            if (side > 0 && (preview.layoutParams.width != side || preview.layoutParams.height != side)) {
                preview.layoutParams = FrameLayout.LayoutParams(side, side, Gravity.CENTER)
            }
        }

        pauseButton.setOnClickListener {
            analyzing = !analyzing
            generation.incrementAndGet()
            pauseButton.setText(if (analyzing) R.string.pause else R.string.resume)
            status.setText(if (analyzing) R.string.waiting_camera else R.string.paused_status)
        }
        actionButton.setOnClickListener {
            when {
                !hasCameraPermission() -> requestCameraPermission()
                !modelReady -> loadModel()
                else -> {
                    frameFailed = false
                    analyzing = true
                    generation.incrementAndGet()
                    bindCamera()
                }
            }
        }
        loadModel()
    }

    override fun onStart() {
        super.onStart()
        screenActive = true
    }

    override fun onResume() {
        super.onResume()
        if (!hasCameraPermission()) {
            cameraProvider?.unbindAll()
            cameraBound = false
            showPermissionUi()
        } else if (modelReady && !cameraBound && !bindingCamera) {
            bindCamera()
        }
    }

    override fun onStop() {
        screenActive = false
        generation.incrementAndGet()
        super.onStop()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionUi() {
        status.setText(R.string.permission_needed)
        pauseButton.visibility = View.GONE
        actionButton.visibility = View.VISIBLE
        val previouslyAsked = getPreferences(MODE_PRIVATE).getBoolean("camera_permission_asked", false)
        actionButton.setText(
            if (previouslyAsked && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA))
                R.string.camera_settings else R.string.enable_camera,
        )
    }

    private fun requestCameraPermission() {
        val prefs = getPreferences(MODE_PRIVATE)
        if (prefs.getBoolean("camera_permission_asked", false) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        ) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else {
            prefs.edit().putBoolean("camera_permission_asked", true).apply()
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun loadModel() {
        if (loadingModel || disposed) return
        loadingModel = true
        status.setText(R.string.loading_model)
        actionButton.visibility = View.GONE
        analysisExecutor.execute {
            try {
                classifier?.close()
                classifier = null
                val base = BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .setDelegate(Delegate.CPU)
                    .build()
                val options = ImageClassifier.ImageClassifierOptions.builder()
                    .setBaseOptions(base)
                    // Live camera frames are processed serially off the UI thread. IMAGE mode
                    // makes ownership of each Bitmap explicit; CameraX drops queued old frames.
                    .setRunningMode(RunningMode.IMAGE)
                    .setMaxResults(3)
                    .build()
                classifier = ImageClassifier.createFromOptions(applicationContext, options)
                modelReady = true
                runOnUiThread {
                    if (!disposed) {
                        loadingModel = false
                        if (hasCameraPermission()) bindCamera() else showPermissionUi()
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Could not load bundled model", error)
                modelReady = false
                runOnUiThread {
                    if (!disposed) {
                        loadingModel = false
                        showError(R.string.model_error)
                    }
                }
            }
        }
    }

    private fun bindCamera() {
        if (disposed || bindingCamera || !modelReady || !hasCameraPermission()) return
        bindingCamera = true
        status.setText(R.string.waiting_camera)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            bindingCamera = false
            if (disposed || !hasCameraPermission()) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                provider.unbindAll()
                cameraBound = false
                val rotation = preview.display?.rotation ?: Surface.ROTATION_0
                val resolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
                val livePreview = Preview.Builder().setTargetRotation(rotation)
                    .setResolutionSelector(resolution).build().also {
                        it.setSurfaceProvider(preview.surfaceProvider)
                    }
                val analysis = ImageAnalysis.Builder()
                    .setTargetRotation(rotation)
                    .setResolutionSelector(resolution)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                imageAnalysis?.clearAnalyzer()
                imageAnalysis = analysis
                analysis.setAnalyzer(analysisExecutor, ::analyzeFrame)
                val selector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))
                    CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                val group = UseCaseGroup.Builder()
                    .setViewPort(ViewPort.Builder(Rational(1, 1), rotation).setScaleType(ViewPort.FILL_CENTER).build())
                    .addUseCase(livePreview).addUseCase(analysis).build()
                provider.bindToLifecycle(this, selector, group)
                cameraBound = true
                frameFailed = false
                pauseButton.visibility = View.VISIBLE
                pauseButton.isEnabled = true
                pauseButton.setText(if (analyzing) R.string.pause else R.string.resume)
                actionButton.visibility = View.GONE
                if (!analyzing) status.setText(R.string.paused_status)
            } catch (error: Exception) {
                Log.e(TAG, "Could not start camera", error)
                cameraBound = false
                showError(R.string.camera_error)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        val buffers = mutableListOf<Bitmap>()
        try {
            val now = SystemClock.elapsedRealtime()
            if (disposed || !screenActive || !analyzing || !modelReady || now - lastAnalysisMs < FRAME_INTERVAL_MS) return
            val currentClassifier = classifier ?: return
            lastAnalysisMs = now
            val resultGeneration = generation.get()
            // CameraX's converter handles RGBA row stride, avoiding device-specific color corruption.
            val full = proxy.toBitmap().also(buffers::add)
            val bounds = proxy.cropRect
            val crop = FrameGeometry.centerSquare(full.width, full.height, bounds.left, bounds.top, bounds.right, bounds.bottom)
            val square = Bitmap.createBitmap(full, crop.left, crop.top, crop.size, crop.size).also(buffers::add)
            val upright = if (proxy.imageInfo.rotationDegrees == 0) square else {
                val matrix = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
                Bitmap.createBitmap(square, 0, 0, square.width, square.height, matrix, true).also(buffers::add)
            }
            val image = BitmapImageBuilder(upright).build()
            val result = try {
                // Metadata in the model provides its normalization and English labels.
                currentClassifier.classify(image)
            } finally {
                image.close()
            }
            val categories = result.classificationResult().classifications().firstOrNull()?.categories().orEmpty()
            val rows = categories.take(3).map { category ->
                val name = category.categoryName().ifBlank { getString(R.string.fallback_class, category.index()) }
                String.format(Locale.US, "%s  ·  %.1f%%", name, category.score() * 100f)
            }
            val finished = SystemClock.elapsedRealtime()
            val resultRate = if (lastResultGeneration == resultGeneration && lastResultMs > 0)
                1000f / (finished - lastResultMs).coerceAtLeast(1) else 0f
            lastResultMs = finished
            lastResultGeneration = resultGeneration
            runOnUiThread {
                if (!disposed && screenActive && analyzing && generation.get() == resultGeneration && !frameFailed) {
                    predictionRows.forEachIndexed { index, view ->
                        view.text = rows.getOrNull(index) ?: getString(R.string.empty_prediction)
                    }
                    if (rows.isEmpty()) predictionRows.first().setText(R.string.no_result)
                    performance.text = getString(R.string.performance, finished - now, resultRate)
                    status.setText(R.string.live_status)
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Camera frame classification failed", error)
            analyzing = false
            generation.incrementAndGet()
            runOnUiThread {
                if (!disposed) {
                    frameFailed = true
                    showError(R.string.frame_error)
                }
            }
        } finally {
            // createBitmap can return its input for an unchanged crop; recycle each object once.
            buffers.distinct().forEach { if (!it.isRecycled) it.recycle() }
            proxy.close()
        }
    }

    private fun showError(message: Int) {
        status.setText(message)
        pauseButton.visibility = View.GONE
        actionButton.setText(R.string.retry)
        actionButton.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        disposed = true
        screenActive = false
        generation.incrementAndGet()
        imageAnalysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
        analysisExecutor.execute {
            classifier?.close()
            classifier = null
            modelReady = false
        }
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LiveClassifier"
        private const val MODEL_ASSET = "efficientnet_lite0.tflite"
        // Cap analysis at 5 results/sec to limit heat and allocations; preview remains live.
        private const val FRAME_INTERVAL_MS = 200L
    }
}
