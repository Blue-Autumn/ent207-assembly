package com.example.arassemblydemo

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.opencv.objdetect.Objdetect
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Independent, user-triggered same-frame capture; never writes photos, progress, or registrations. */
class JointPhotoCamera(
    private val activity: ComponentActivity,
    private val host: FrameLayout,
    private val onState: (state: String, message: String) -> Unit,
    private val onPhoto: (JointPhotoFrame) -> Unit,
    private val fillStage: Boolean = false
) {
    val preview = PreviewView(activity).apply {
        scaleType = if (fillStage) PreviewView.ScaleType.FILL_CENTER else PreviewView.ScaleType.FIT_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        setBackgroundColor(Color.BLACK)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private data class Capture(val token: String, val generation: Long, val requestedAt: Long)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val pending = AtomicReference<Capture?>(null)
    @Volatile private var generation = 0L
    @Volatile private var released = false
    private var resumed = false
    private var provider: ProcessCameraProvider? = null
    private var providerLoading = false
    private var previewCase: Preview? = null
    private var analysisCase: ImageAnalysis? = null
    private var processor: ArucoProcessor? = null // executor owned
    private var bindingPosted = false
    private var boundWidth = 0
    private var boundHeight = 0
    private var boundRotation = -1
    private var captureInFlight: Capture? = null // main-thread guard, includes encoding/post delivery
    private var framing = CameraFramingPreferences.read(activity)
    private var framingReady = false
    private var currentFramingLabel = "主摄"
    private val layoutListener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        if (r - l != or - ol || b - t != ob - ot) { stopCamera(); scheduleBind() }
    }

    init {
        host.addView(preview, FrameLayout.LayoutParams(-1, -1))
        preview.addOnLayoutChangeListener(layoutListener)
    }

    fun resume() {
        requireMain(); if (released) return
        resumed = true; scheduleBind()
    }

    fun pause() {
        requireMain(); resumed = false; stopCamera()
    }

    fun framingLabel(): String = activity.getString(when(currentFramingLabel) {
        "广角" -> R.string.lens_wide
        "广角·近" -> R.string.lens_wide_near
        else -> R.string.lens_main
    })

    fun cycleFraming() {
        requireMain(); if(released || captureInFlight != null) return
        framing = framing.next(); CameraFramingPreferences.save(activity, framing)
        stopCamera(); onState("switching", activity.getString(R.string.photo_switch)); scheduleBind()
    }

    fun release() {
        requireMain(); if (released) return
        pause(); released = true
        preview.removeOnLayoutChangeListener(layoutListener)
        executor.execute { processor?.close(); processor = null }
        executor.shutdown()
        host.removeView(preview)
    }

    /** Arm exactly the next analyzer frame. Return false when no capture was accepted. */
    fun capture(token: String): Boolean {
        requireMain()
        if (released || !resumed || !framingReady || analysisCase == null || captureInFlight != null || token.length !in 1..200) return false
        val capture = Capture(token, generation, SystemClock.elapsedRealtime())
        captureInFlight = capture; pending.set(capture)
        onState("capturing", activity.getString(R.string.photo_capturing))
        main.postDelayed({
            if (captureInFlight === capture) {
                pending.compareAndSet(capture, null); captureInFlight = null
                if (resumed && !released) onState("error", activity.getString(R.string.photo_missing))
            }
        }, 6000)
        return true
    }

    private fun scheduleBind() {
        if (released || !resumed || bindingPosted) return
        bindingPosted = true
        main.post { bindingPosted = false; bindCamera() }
    }

    private fun bindCamera() {
        if (released || !resumed) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopCamera(); onState("permission", activity.getString(R.string.photo_permission))
            return
        }
        val rotation = preview.display?.rotation ?: Surface.ROTATION_0
        val viewport = preview.viewPort ?: return
        if (preview.width <= 0 || preview.height <= 0) return
        if (analysisCase != null && boundWidth == preview.width && boundHeight == preview.height && boundRotation == rotation) return
        val cameraProvider = provider
        if (cameraProvider == null) {
            if (providerLoading) return
            providerLoading = true
            val future = ProcessCameraProvider.getInstance(activity)
            future.addListener({
                providerLoading = false
                if (!released) try {
                    provider = future.get(); scheduleBind()
                } catch (_: Exception) {
                    if (resumed) onState("error", activity.getString(R.string.photo_start_failed))
                }
            }, ContextCompat.getMainExecutor(activity))
            return
        }
        stopCamera()
        val bindingGeneration = generation
        try {
            framing = CameraFramingPreferences.read(activity)
            val lens = LensChoice.choose(cameraProvider.availableCameraInfos, framing.wide)
            val cameraPreview = Preview.Builder().setTargetRotation(rotation).build()
            val analysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setTargetRotation(rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                ).build()).build()
            analysis.setAnalyzer(executor) { frame ->
                var captured: JointPhotoFrame? = null
                var capture: Capture? = null
                try {
                    if (released || generation != bindingGeneration) return@setAnalyzer
                    capture = pending.getAndSet(null) ?: return@setAnalyzer
                    if (capture.generation != bindingGeneration || SystemClock.elapsedRealtime() - capture.requestedAt !in 0..6000) return@setAnalyzer
                    if (processor == null) { ArucoProcessor.initialize(); processor = ArucoProcessor(Objdetect.DICT_4X4_250) }
                    val detection = processor!!.detect(frame, subpixel = true)
                    captured = JointPhotoFrame.fromProxy(capture.token, frame, detection, lens.cameraId, SystemClock.elapsedRealtime())
                    val result = captured
                    val consumed = capture
                    main.post {
                        if (resumed && !released && generation == bindingGeneration && captureInFlight === consumed) {
                            captureInFlight = null
                            onState("captured", activity.getString(R.string.photo_ready))
                            onPhoto(result)
                        } else result.bitmap.recycle()
                    }
                } catch (_: Exception) {
                    captured?.bitmap?.recycle()
                    val failed = capture
                    main.post {
                        if (failed != null && captureInFlight === failed) {
                            captureInFlight = null
                            if (resumed && !released && generation == bindingGeneration) onState("error", activity.getString(R.string.photo_again))
                        }
                    }
                } finally { frame.close() }
            }
            previewCase = cameraPreview; analysisCase = analysis
            cameraPreview.setSurfaceProvider(preview.surfaceProvider)
            val camera = cameraProvider.bindToLifecycle(activity, lens.selector, UseCaseGroup.Builder()
                .setViewPort(viewport).addUseCase(cameraPreview).addUseCase(analysis).build())
            boundWidth = preview.width; boundHeight = preview.height; boundRotation = rotation
            val zoom = camera.cameraInfo.zoomState.value
            val applied = framing.resolve(lens.isWide, zoom?.minZoomRatio ?: 1f, zoom?.maxZoomRatio ?: 1f)
            val future = camera.cameraControl.setZoomRatio(applied.ratio)
            future.addListener({
                if(generation == bindingGeneration && resumed && !released) {
                    val ok = runCatching { future.get() }.isSuccess
                    currentFramingLabel = if(ok) applied.label else if(lens.isWide) "广角" else "主摄"
                    framingReady = true
                    onState("framing", framingLabel())
                    onState("ready", activity.getString(R.string.photo_overview_hint))
                }
            }, ContextCompat.getMainExecutor(activity))
        } catch (_: Exception) {
            stopCamera(); onState("error", activity.getString(R.string.photo_start_failed))
        }
    }

    private fun stopCamera() {
        framingReady = false
        val wasCapturing = captureInFlight != null
        generation++
        pending.set(null); captureInFlight = null
        analysisCase?.clearAnalyzer()
        val owned = listOfNotNull(previewCase, analysisCase)
        if (owned.isNotEmpty()) provider?.unbind(*owned.toTypedArray())
        previewCase = null; analysisCase = null
        boundWidth = 0; boundHeight = 0; boundRotation = -1
        if (wasCapturing && resumed && !released) onState("error", activity.getString(R.string.photo_frame_changed))
    }

    private fun requireMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "JointPhotoCamera methods require the main thread" }
    }
}
