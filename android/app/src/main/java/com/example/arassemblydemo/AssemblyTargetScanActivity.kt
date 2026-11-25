package com.example.arassemblydemo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.opencv.objdetect.Objdetect
import java.util.concurrent.Executors

/**
 * Finds one requested physical entity through any of its registered markers.
 * RESULT_OK means that an expected marker was observed and the operator returned;
 * it does not mean the entity was picked up or that assembly was completed.
 */
class AssemblyTargetScanActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AssemblyLanguage.wrap(base))
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var processor: ArucoProcessor? = null // analysisExecutor owned
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null

    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var lensStatus: TextView
    private lateinit var returnButton: Button
    private var targetIds: Set<Int> = emptySet()
    private lateinit var targetName: String
    private var requestToken = ""
    private var validRequest = false
    private var wide = true
    private var permissionAsked = false
    private var cameraMessage = ""

    @Volatile private var active = false
    @Volatile private var generation = 0
    private var candidateId: Int? = null // UI thread owned; never restored after pause
    private var candidateGeneration = -1
    private var latestResultAt = 0L
    private var targetSeenAt = 0L
    private var sawOtherIds = false
    private var detectionFailed = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!validRequest || isFinishing || isDestroyed) return@registerForActivityResult
        if (granted) bindCamera() else {
            cameraMessage = getString(R.string.scan_permission)
            renderStatus()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!active || !validRequest) return
            renderStatus()
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val suppliedIds = intent.getIntArrayExtra("target_ids")
        requestToken = intent.getStringExtra("request_token").orEmpty()
        if (suppliedIds == null || suppliedIds.isEmpty() || suppliedIds.any { it !in 0..249 } || requestToken.isBlank()) {
            setResult(RESULT_CANCELED, Intent()
                .putExtra("request_token", requestToken)
                .putExtra("scan_error", "invalid_target_request"))
            Toast.makeText(this, getString(R.string.scan_unregistered), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        targetIds = suppliedIds.toSet()
        val part = intent.getStringExtra("target_name").orEmpty()
        targetName = when {
            part.isBlank() -> getString(R.string.scan_part)
            AssemblyLanguage.english(this) -> AiReplyText.userReference(part, emptyList(), true)
            else -> part
        }
        validRequest = true

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val pad = dp(18)
        root.setPadding(pad, pad, pad, pad)
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(pad + insets.systemWindowInsetLeft, pad + insets.systemWindowInsetTop,
                pad + insets.systemWindowInsetRight, pad + insets.systemWindowInsetBottom)
            insets
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.scan_find, targetName)
            textSize = 22f
            setTextColor(Color.rgb(28, 62, 46))
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.scan_ids, targetIds.sorted().joinToString(" / ") { displayId(it) })
            textSize = 15f
            setTextColor(Color.rgb(72, 98, 82))
            setPadding(0, dp(8), 0, dp(10))
        })
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            setBackgroundColor(Color.rgb(239, 244, 240))
            contentDescription = getString(R.string.scan_preview)
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        lensStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(103, 122, 109))
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(lensStatus)
        status = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.rgb(26, 93, 58))
            setPadding(0, dp(10), 0, dp(10))
            minLines = 2
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status)
        returnButton = Button(this).apply {
            text = getString(R.string.scan_return)
            isAllCaps = false
            isEnabled = false
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(0, 153, 88))
            setOnClickListener {
                val id = candidateId
                if (!active || isFinishing || id == null || id !in targetIds || candidateGeneration != generation) return@setOnClickListener
                setResult(RESULT_OK, Intent().putExtra("marker_id", id).putExtra("request_token", requestToken))
                finish()
            }
        }
        root.addView(returnButton)
        val row = LinearLayout(this)
        fun addButton(label: String, action: () -> Unit) {
            row.addView(Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } },
                LinearLayout.LayoutParams(0, -2, 1f))
        }
        addButton(getString(R.string.scan_switch)) { wide = !wide; bindCamera() }
        addButton(getString(R.string.scan_retry)) { bindCamera() }
        addButton(getString(R.string.scan_cancel)) { finish() }
        root.addView(row)
        root.addView(Button(this).apply {
            text = getString(R.string.scan_settings)
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName")))
            }
        })
        setContentView(root)
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        if (!validRequest || isFinishing) return
        active = true
        bindCamera()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        active = false
        generation++
        clearObservation()
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        handler.removeCallbacks(ticker)
        if (validRequest) renderStatus()
        super.onPause()
    }

    private fun clearObservation() {
        candidateId = null
        candidateGeneration = -1
        latestResultAt = 0L
        targetSeenAt = 0L
        sawOtherIds = false
        detectionFailed = false
    }

    private fun bindCamera() {
        if (!active || !validRequest || isFinishing) return
        generation++
        val token = generation
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        clearObservation()
        cameraMessage = getString(R.string.scan_startup)
        renderStatus()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraMessage = getString(R.string.scan_need_camera)
            renderStatus()
            if (!permissionAsked) {
                permissionAsked = true
                permission.launch(Manifest.permission.CAMERA)
            }
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!active || token != generation || isFinishing || isDestroyed) return@addListener
            try {
                val currentProvider = future.get()
                provider = currentProvider
                currentProvider.unbindAll()
                val plan = LensChoice.choose(currentProvider.availableCameraInfos, wide)
                val rotation = preview.display?.rotation ?: android.view.Surface.ROTATION_0
                val live = Preview.Builder().setTargetRotation(rotation).build().also {
                    it.setSurfaceProvider(preview.surfaceProvider)
                }
                val detectorStream = ImageAnalysis.Builder()
                    .setTargetRotation(rotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    ).build()).build()
                analysis = detectorStream
                var nextDetectionAt = 0L
                detectorStream.setAnalyzer(analysisExecutor) { frame ->
                    try {
                        val at = SystemClock.elapsedRealtime()
                        if (active && token == generation && at >= nextDetectionAt) {
                            nextDetectionAt = at + 200
                            if (processor == null) {
                                ArucoProcessor.initialize()
                                processor = ArucoProcessor(Objdetect.DICT_4X4_250)
                            }
                            val ids = processor!!.detect(frame).markers.map { it.id }
                            val matching = ids.filter { it in targetIds }.minOrNull()
                            runOnUiThread {
                                if (active && token == generation && !isFinishing) {
                                    latestResultAt = at
                                    sawOtherIds = ids.isNotEmpty() && matching == null
                                    detectionFailed = false
                                    if (matching != null) {
                                        targetSeenAt = at
                                        // All requested codes belong to this one entity. Keep the first hit.
                                        if (candidateId == null) {
                                            candidateId = matching
                                            candidateGeneration = token
                                        }
                                    }
                                    renderStatus()
                                }
                            }
                        }
                    } catch (_: Exception) {
                        runOnUiThread {
                            if (active && token == generation && !isFinishing) {
                                clearObservation()
                                detectionFailed = true
                                renderStatus()
                            }
                        }
                    } finally {
                        frame.close()
                    }
                }
                currentProvider.bindToLifecycle(this, plan.selector, live, detectorStream)
                cameraMessage = if (plan.isWide) getString(R.string.scan_wide) else if (wide) getString(R.string.scan_no_wide) else getString(R.string.scan_main)
            } catch (_: Exception) {
                clearObservation()
                cameraMessage = getString(R.string.scan_error)
            }
            renderStatus()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun renderStatus() {
        if (!validRequest || !::status.isInitialized) return
        val now = SystemClock.elapsedRealtime()
        val hasCandidate = active && candidateId != null && candidateGeneration == generation
        lensStatus.text = cameraMessage
        returnButton.isEnabled = hasCandidate
        status.text = when {
            !active -> getString(R.string.scan_resume)
            hasCandidate && now - targetSeenAt <= 800 -> getString(R.string.scan_found, targetName, displayId(candidateId!!))
            hasCandidate -> getString(R.string.scan_held)
            detectionFailed -> getString(R.string.scan_unclear)
            latestResultAt == 0L || now - latestResultAt > 800 -> getString(R.string.scan_aim)
            sawOtherIds -> getString(R.string.scan_other)
            else -> getString(R.string.scan_looking, targetName)
        }
    }

    override fun onDestroy() {
        active = false
        generation++
        handler.removeCallbacksAndMessages(null)
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        analysisExecutor.execute { processor?.close(); processor = null }
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun displayId(value: Int) = value.toString().padStart(3, '0')
}
