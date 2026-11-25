package com.example.arassemblydemo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import android.widget.EditText
import android.widget.ScrollView
import android.text.InputType
import java.util.Locale
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.CoordinateTransform
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

@androidx.annotation.OptIn(TransformExperimental::class, ExperimentalCamera2Interop::class)
class CameraTestActivity : ComponentActivity() {
    private var preparationPending=false
    private var preparationTimer: android.os.CountDownTimer?=null
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var detectionStatus: TextView
    private lateinit var overlay: MarkerOverlay
    private var samplingMode=false
    private var diagnosticMode=false
    private var sampler: CharucoSampling? = null // analyzer executor only
    private lateinit var calibrationOverlay: CalibrationOverlay
    @Volatile private var samplingRole="train"
    private data class SampleRequest(val role: String,val h: Double,val v: Double,val flat: Boolean,val gray: Boolean)
    private val sampleRequest=AtomicReference<SampleRequest?>(null)
    private var calibratedEnabled=false
    @Volatile private var controlledTrial=false // explicit per-entry opt-in; never silently persists across app starts
    @Volatile private var showPart=false
    private var staticPart: StaticPart?=null // analyzer executor only
    private val importProfile=registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if(uri!=null) try {
            val bytes=contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            require(bytes.size<=65536)
            val profile=JSONObject(String(bytes,Charsets.UTF_8))
            require(profile.getString("status")=="SCREEN_CALIBRATED" && profile.getBoolean("activation_allowed"))
            java.io.File(filesDir,"calibration-profile.json").writeBytes(bytes)
            calibratedEnabled=true
            getPreferences(MODE_PRIVATE).edit().putBoolean("screen_profile_enabled",true).apply()
            releaseCamera();startCamera()
        } catch(e: Exception) {poseStatus.text="配置导入失败，保留原设置：${e.javaClass.simpleName}"}
    }
    private var processor: ArucoProcessor? = null // analysis executor only
    private var openCvVersion = ""
    private val uiPending = AtomicBoolean(false)
    private lateinit var poseStatus: TextView
    private lateinit var debugArea: ScrollView
    private lateinit var lensButton: Button
    private var requestWide = true
    private var activeWide = false
    private var lensNote = ""
    private var fallbackScheduled = false
    private var rulerMeasuredMm = 100.0 // New board: scale ALL coordinates from its 100 mm ruler.
    private var lastDiagnostic = ""
    private var lastLogAt = 0L
    private lateinit var permissionButton: Button
    private lateinit var settingsButton: Button
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastFrameAt = AtomicLong(0)
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var foreground = false
    private var binding = false
    private var generation = 0
    private var askedPermission = false
    private var cameraError: String? = null

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showPermissionState()
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!foreground) return
            val last = lastFrameAt.get()
            if (analysis != null && last > 0 && SystemClock.elapsedRealtime() - last > 400) {
                stats.setText(R.string.analysis_stalled)
                overlay.clear()
                detectionStatus.setText(R.string.aruco_stale)
                poseStatus.text = "图像已过期 · 叠加已隐藏"
            }
            mainHandler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preparationPending=intent.getBooleanExtra("prepare_automatic_test",false)
        samplingMode=intent.getBooleanExtra("calibration_sampling",false)
        diagnosticMode=intent.getBooleanExtra("board_diagnostic",false)
        if(diagnosticMode) {samplingMode=true;samplingRole="board_validation"}
        calibratedEnabled=getPreferences(MODE_PRIVATE).getBoolean("screen_profile_enabled",false)
        requestWide = if(samplingMode) true else getPreferences(MODE_PRIVATE).getBoolean("prefer_wide", true)
        askedPermission = savedInstanceState?.getBoolean("asked_permission") ?: false
        rulerMeasuredMm = getPreferences(MODE_PRIVATE).getFloat("board_ruler_mm", 100f).toDouble().takeIf { it.isFinite() && it in 80.0..120.0 } ?: 100.0
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        overlay = MarkerOverlay(this)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        calibrationOverlay=CalibrationOverlay(this)
        root.addView(calibrationOverlay,FrameLayout.LayoutParams(-1,-1))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xB3000000.toInt())
        }
        poseStatus = textLabel(R.string.aruco_waiting, 15f)
        poseStatus.text = "未定位 · 请用新版 A4-landscape-four-48-v1 板" + "\n近似相机参数 · 未标定"
        panel.addView(poseStatus)
        panel.addView(Button(this).apply {
            if(samplingMode) visibility=View.GONE
            text = "展开 Debug"
            setOnClickListener {
                val expanded = debugArea.visibility != View.VISIBLE
                debugArea.visibility = if (expanded) View.VISIBLE else View.GONE
                this@CameraTestActivity.overlay.debug = expanded
                text = if (expanded) "收起 Debug" else "展开 Debug"
            }
        }, LinearLayout.LayoutParams(-2, dp(42)))
        val debugPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = textLabel(R.string.camera_starting, 12f)
        stats = textLabel(R.string.analysis_waiting, 12f)
        detectionStatus = textLabel(R.string.aruco_waiting, 12f)
        debugPanel.addView(status)
        debugPanel.addView(stats)
        debugPanel.addView(detectionStatus)
        debugPanel.addView(textLabel(R.string.camera_overlay_note, 12f))
        val sizeRow = LinearLayout(this)
        val sizeInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(rulerMeasuredMm.toString()); setTextColor(Color.WHITE); textSize = 13f
            contentDescription = "新版板100毫米校验尺实测长度"
        }
        sizeRow.addView(sizeInput, LinearLayout.LayoutParams(0, -2, 1f))
        sizeRow.addView(Button(this).apply {
            text = "应用校验尺 mm"
            setOnClickListener {
                val value = sizeInput.text.toString().toDoubleOrNull()
                if (value == null || !value.isFinite() || value !in 80.0..120.0) {
                    sizeInput.error = "请输入100mm校验尺实测值（80–120mm）；严重缩放请重印"
                } else {
                    rulerMeasuredMm = value
                    getPreferences(MODE_PRIVATE).edit().putFloat("board_ruler_mm", value.toFloat()).apply()
                    releaseCamera(); startCamera()
                }
            }
        })
        debugPanel.addView(sizeRow)
        debugPanel.addView(TextView(this).apply {
            text = "仅使用新版 A4-landscape-four-48-v1 固定板，旧纸或随意摆放不可用。输入100mm校验尺实测长度，统一缩放标记及间距；非均匀缩放请重印。近似相机参数，未标定。"
            textSize = 12f; setTextColor(Color.LTGRAY)
        })
        if(!samplingMode) {
            debugPanel.addView(Button(this).apply {text="试用新参数＋精细角点";setOnClickListener {
                controlledTrial=!controlledTrial
                if(!controlledTrial) calibratedEnabled=false
                text=if(controlledTrial) "返回旧近似参数" else "试用新参数＋精细角点"
                overlay.clear()
            } })
            debugPanel.addView(Button(this).apply {text="显示灰色插入销（实际尺度）";setOnClickListener {
                showPart=!showPart;overlay.clear()
                text=if(showPart) "返回线框立方体" else "显示灰色插入销（实际尺度）"
            } })
            debugPanel.addView(Button(this).apply {text="导入已验证标定配置";setOnClickListener {importProfile.launch("application/json")} })
            debugPanel.addView(Button(this).apply {text="A/B：切换近似 / 屏幕标定";setOnClickListener {
                calibratedEnabled=!calibratedEnabled
                getPreferences(MODE_PRIVATE).edit().putBoolean("screen_profile_enabled",calibratedEnabled).apply()
                releaseCamera();startCamera()
            } })
        }
        debugArea = ScrollView(this).apply { addView(debugPanel); visibility = View.GONE }
        panel.addView(debugArea, LinearLayout.LayoutParams(-1, dp(250)))
        val samplingBottom=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        if(samplingMode) {
            poseStatus.text="广角标定采样 · 尚未标定"
            poseStatus.textSize=12f
            poseStatus.setLines(4)
            val controls=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
            val measures=LinearLayout(this)
            val hInput=EditText(this).apply {hint="横尺实测mm";setHintTextColor(Color.LTGRAY);setTextColor(Color.WHITE);inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL;textSize=12f}
            val vInput=EditText(this).apply {hint="竖尺实测mm";setHintTextColor(Color.LTGRAY);setTextColor(Color.WHITE);inputType=hInput.inputType;textSize=12f}
            measures.addView(hInput,LinearLayout.LayoutParams(0,dp(44),1f));measures.addView(vInput,LinearLayout.LayoutParams(0,dp(44),1f));controls.addView(measures)
            val flat=CheckBox(this).apply {text="已测两条100mm尺，屏幕平面，缩放已固定";setTextColor(Color.WHITE);textSize=11f};controls.addView(flat)
            val grayOptIn=CheckBox(this).apply {text="保存所选帧灰度供审核（可选，最多8张，不上传）";setTextColor(Color.WHITE);textSize=11f};controls.addView(grayOptIn)
            if(diagnosticMode) {
                hInput.hint="原板100mm尺实测mm";vInput.visibility=View.GONE
                flat.text="原横版四标记板，已量100mm尺，屏幕平面且缩放固定"
                grayOptIn.text="同意手动保存诊断灰度图并导出电脑（最多16张）"
            }
            val previous=getPreferences(MODE_PRIVATE)
            hInput.setText(previous.getString("sample_h_mm",""));vInput.setText(previous.getString("sample_v_mm",""))
            var confirmed=false
            fun showMeasurements() {
                (controls.parent as? android.view.ViewGroup)?.removeView(controls)
                controls.setPadding(dp(16),dp(12),dp(16),dp(12))
                controls.setBackgroundColor(Color.DKGRAY)
                val dialog=android.app.AlertDialog.Builder(this).setTitle("确认本次显示尺度")
                    .setView(controls).setNegativeButton("取消",null).setPositiveButton("确认",null).create()
                dialog.setOnShowListener {
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val h=hInput.text.toString().toDoubleOrNull() ?: Double.NaN
                        val v=if(diagnosticMode) h else vInput.text.toString().toDoubleOrNull() ?: Double.NaN
                        if(!CharucoSampling.validMeasurements(h,v,flat.isChecked) || (diagnosticMode && !grayOptIn.isChecked)) {
                            hInput.error=if(diagnosticMode) "请量原板尺、确认平面并勾选灰度保存授权" else "请实测两条尺并确认平面；横竖差异≤1%"
                        } else {
                            confirmed=true
                            previous.edit().putString("sample_h_mm",hInput.text.toString()).putString("sample_v_mm",vInput.text.toString()).apply()
                            dialog.dismiss()
                        }
                    }
                }
                dialog.show()
            }
            val row=LinearLayout(this)
            row.addView(Button(this).apply {text="训练视角";setOnClickListener {
                val labels=arrayOf("训练视角","独立验证视角","四标记板验证")
                val roles=arrayOf("train","validation","board_validation")
                android.app.AlertDialog.Builder(this@CameraTestActivity).setTitle("选择采样组")
                    .setSingleChoiceItems(labels,roles.indexOf(samplingRole)) {dialog,index ->
                        samplingRole=roles[index];text=labels[index];dialog.dismiss()
                    }.setNegativeButton("取消",null).show()
            };if(diagnosticMode) {text="装配板诊断";isEnabled=false}},LinearLayout.LayoutParams(0,dp(48),1f))
            row.addView(Button(this).apply {text="采集一帧";setOnClickListener {
                if(!confirmed) showMeasurements() else sampleRequest.set(SampleRequest(samplingRole,hInput.text.toString().toDoubleOrNull() ?: Double.NaN,
                    vInput.text.toString().toDoubleOrNull() ?: Double.NaN,flat.isChecked,grayOptIn.isChecked))
            }},LinearLayout.LayoutParams(0,dp(48),1f));samplingBottom.addView(row)
            samplingBottom.addView(Button(this).apply {text="尺寸与保存设置";setOnClickListener {confirmed=false;showMeasurements()} },LinearLayout.LayoutParams(-1,dp(48)))
        }
        overlay.onExpired = { if (foreground) invalidatePoseUi("图像已过期 · 叠加已隐藏") }
        permissionButton = Button(this).apply {
            setText(R.string.camera_request)
            setOnClickListener {
                if (hasPermission()) { releaseCamera(); startCamera() } else {
                    askedPermission = true
                    requestPermission.launch(Manifest.permission.CAMERA)
                }
            }
        }
        settingsButton = Button(this).apply {
            setText(R.string.camera_settings)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        panel.addView(permissionButton)
        panel.addView(settingsButton)
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER }
        if(samplingMode) bottom.orientation=LinearLayout.VERTICAL
        lensButton = Button(this).apply {
            text = "广角试用"
            setOnClickListener {
                requestWide = !requestWide
                getPreferences(MODE_PRIVATE).edit().putBoolean("prefer_wide", requestWide).apply()
                releaseCamera(); startCamera()
            }
        }
        if(samplingMode) bottom.addView(samplingBottom) else bottom.addView(lensButton)
        bottom.addView(Button(this).apply { setText(R.string.camera_home); setOnClickListener { finish() } })
        root.addView(bottom, FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin=dp(12) })
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        previewView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                if (foreground && hasPermission()) { releaseCamera(); startCamera() }
            }
        }
        previewView.previewStreamState.observe(this) { state ->
            if (foreground && hasPermission() && cameraError == null) {
                status.setText(if (state == PreviewView.StreamState.STREAMING) R.string.camera_active else R.string.camera_idle)
                Log.i(TAG, "preview=$state generation=$generation")
            }
        }
        setContentView(root)
        root.requestApplyInsets()
        if(preparationPending) {
            val dialog=android.app.AlertDialog.Builder(this).setTitle("自动测试准备")
                .setMessage("请在电脑显示四标记板，并将手机对准它。20 秒后开始；准备好可立即开始。")
                .setCancelable(false).setPositiveButton("立即开始",null).create()
            fun begin() {
                preparationTimer?.cancel();preparationTimer=null
                preparationPending=false;dialog.dismiss();startCamera()
            }
            dialog.setOnShowListener {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {begin()}
                preparationTimer=object:android.os.CountDownTimer(20000,1000) {
                    override fun onTick(left:Long) {dialog.setMessage("请在电脑显示四标记板，并将手机对准它。\n${(left+999)/1000} 秒后开始；准备好可点立即开始。")}
                    override fun onFinish() {begin()}
                }.start()
            }
            dialog.show()
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        mainHandler.post(watchdog)
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission()) startCamera() else {
            releaseCamera()
            showPermissionState()
            if (!askedPermission) {
                askedPermission = true
                requestPermission.launch(Manifest.permission.CAMERA)
            }
        }
    }

    override fun onStop() {
        foreground = false
        mainHandler.removeCallbacks(watchdog)
        releaseCamera()
        status.setText(R.string.camera_paused)
        stats.setText(R.string.analysis_waiting)
        super.onStop()
    }

    override fun onDestroy() {
        preparationTimer?.cancel()
        releaseCamera()
        analyzerExecutor.execute { processor?.close(); processor = null;sampler?.close();sampler=null;staticPart?.close();staticPart=null }
        analyzerExecutor.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("asked_permission", askedPermission)
        super.onSaveInstanceState(outState)
    }

    private fun hasPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionState() {
        status.setText(if (askedPermission) R.string.camera_denied else R.string.camera_permission)
        stats.setText(R.string.analysis_waiting)
        poseStatus.text = "需要相机权限 · 固定装配板"
        permissionButton.setText(R.string.camera_request)
        permissionButton.visibility = View.VISIBLE
        settingsButton.visibility = View.VISIBLE
        Log.i(TAG, "permission=denied")
    }

    private fun startCamera() {
        if (preparationPending || !foreground || !hasPermission() || binding || analysis != null) return
        permissionButton.visibility = View.GONE
        settingsButton.visibility = View.GONE
        // Layout callback retries once the PreviewView has a real viewport.
        val viewPort = previewView.viewPort ?: return
        binding = true
        cameraError = null
        fallbackScheduled = false
        status.setText(R.string.camera_starting)
        stats.setText(R.string.analysis_waiting)
        val token = ++generation
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!foreground || isDestroyed || token != generation) return@addListener
            try {
                val currentProvider = future.get()
                provider = currentProvider
                val lensPlan = LensChoice.choose(currentProvider.availableCameraInfos, requestWide)
                activeWide = lensPlan.isWide
                lensNote = if (activeWide) "广角试用 · 相机 ${lensPlan.cameraId}" else if (requestWide) "无可用广角 · 使用主摄" else "主摄 · 相机 ${lensPlan.cameraId}"
                lensButton.text = if (activeWide) "切回主摄" else "试用广角"
                lensButton.isEnabled = !requestWide || activeWide
                java.io.File(filesDir,"lens-choice.txt").writeText(lensPlan.report)
                Log.i(TAG,lensPlan.report)
                val currentPreview = Preview.Builder().setTargetRotation(previewView.display.rotation).build()
                val captureAudit=CaptureAudit()
                val analysisBuilder = ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setTargetRotation(previewView.display.rotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    ).build())
                val interop=Camera2Interop.Extender(analysisBuilder).setSessionCaptureCallback(captureAudit)
                var lockFocus=false
                if(lensPlan.cameraId=="4") {
                    val characteristics=getSystemService(CameraManager::class.java).getCameraCharacteristics(lensPlan.cameraId)
                    val modes=characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                    val minimum=characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                    if(modes?.contains(CaptureRequest.CONTROL_AF_MODE_OFF)==true && minimum!=null && minimum>=2f) {
                        lockFocus=true
                        interop.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF)
                        interop.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE,2f)
                        lensNote+=" · 固定对焦试用"
                    }
                }
                val currentAnalysis=analysisBuilder.build()
                val trialProfile=JSONObject(assets.open("controlled-candidate-v1.json").bufferedReader().use {it.readText()})
                val loadedProfile=CalibrationProfile.read(this)
                var windowStart = SystemClock.elapsedRealtimeNanos()
                var frames = 0
                var total = 0L
                var detectionSum = 0.0
                var poseSum = 0.0
                var trackedFrames = 0
                val performanceFile = java.io.File(filesDir,"board-performance.csv")
                performanceFile.writeText("generation,camera,elapsed_s,frames,fps,detect_mean_ms,pose_mean_ms,tracked_frames,last_state,used_ids\n")
                val sessionStart = windowStart
                val transformFactory = ImageProxyTransformFactory().apply {
                    // Detection uses the unrotated cropped Y image, with its origin at (0,0).
                    // PreviewView's output transform supplies screen rotation/FIT_CENTER.
                    isUsingCropRect = true
                    isUsingRotationDegrees = false
                }
                // Each binding has its own temporal pose state; no history crosses lifecycle/camera changes.
                val boardLayout = BoardLayout.load(this, rulerMeasuredMm)
                val poseEstimator = BoardPoseEstimator(boardLayout)
                var parameters: CameraParameters? = null
                var cameraId = "pending"
                var modelSignature = ""
                lastFrameAt.set(0)
                currentAnalysis.setAnalyzer(analyzerExecutor) { image ->
                    val frameAt = SystemClock.elapsedRealtimeNanos()
                    try {
                        if (processor == null) {
                            openCvVersion = ArucoProcessor.initialize()
                            processor = ArucoProcessor()
                        }
                        val crop = image.cropRect
                        val approximate = parameters?.forImage(image.width, image.height, crop.left, crop.top, crop.width(), crop.height())
                            ?: throw IllegalStateException("等待当前相机参数")
                        val audit=captureAudit.snapshot(image,cameraId)
                        val trialForFrame=controlledTrial && !samplingMode
                        val selected=CalibrationProfile.select(approximate,audit,if(trialForFrame) trialProfile else loadedProfile,
                            (calibratedEnabled || trialForFrame) && !samplingMode,trialForFrame)
                        val detection = processor!!.detect(image,selected.status=="CONTROLLED_CANDIDATE")
                        val model=selected.model
                        var sampleFrame: SamplingFrame?=null
                        if(samplingMode) {
                            if(sampler==null) sampler=CharucoSampling(this,diagnosticMode)
                            val request=sampleRequest.getAndSet(null)
                            sampleFrame=sampler!!.process(image,audit,approximate,request?.role ?: samplingRole,
                                request?.h ?: Double.NaN,request?.v ?: Double.NaN,request?.flat ?: false,request!=null,
                                request?.gray ?: false,detection.markers,if(diagnosticMode) request?.h ?: rulerMeasuredMm else rulerMeasuredMm)
                        }
                        val modelKey = "$cameraId:${image.width}x${image.height}:$crop:${model.source}"
                        if (modelSignature.isNotEmpty() && modelSignature != modelKey) poseEstimator.reset()
                        val poseStart = SystemClock.elapsedRealtimeNanos()
                        val poseFrame = if(samplingMode) PoseFrame(detection.markers,emptyList(),emptyList(),null,"SAMPLING","采样模式，不绘制装配物",0) else poseEstimator.process(detection.markers, model, frameAt)
                        val poseMilliseconds = (SystemClock.elapsedRealtimeNanos() - poseStart) / 1_000_000.0
                        val partStart=SystemClock.elapsedRealtimeNanos()
                        val partFrame=if(showPart && !samplingMode && poseFrame.pose!=null) {
                            if(staticPart==null) staticPart=StaticPart(this)
                            staticPart!!.project(poseFrame.pose,model)
                        } else null
                        val partMs=(SystemClock.elapsedRealtimeNanos()-partStart)/1_000_000.0
                        if (modelSignature != modelKey) {
                            modelSignature = modelKey
                            val description = "camera=$cameraId stream=${image.width}x${image.height} crop=$crop " +
                                "K=${model.fx},${model.fy},${model.cx},${model.cy} distortion=${model.distortion.contentToString()} source=${model.source} calibrated=${model.calibrated}"
                            Log.i(TAG, description)
                            java.io.File(filesDir, "active-camera-model.txt").writeText(description)
                        }
                        val sourceTransform = transformFactory.getOutputTransform(image)
                        val now = SystemClock.elapsedRealtimeNanos()
                        lastFrameAt.set(frameAt / 1_000_000L)
                        frames++
                        total++
                        detectionSum += detection.milliseconds
                        poseSum += poseMilliseconds
                        if (poseFrame.pose != null) trackedFrames++
                        val elapsed = now - windowStart
                        val updateStats = elapsed >= 1_000_000_000L
                        val fps = frames * 1_000_000_000.0 / elapsed.coerceAtLeast(1)
                        val width = image.width
                        val height = image.height
                        val rotation = image.imageInfo.rotationDegrees
                        val count = total
                        if (updateStats) {
                            java.io.File(filesDir,"runtime-selection.json").writeText(JSONObject().put("status",selected.status).put("source",model.source).put("reason",selected.reason).put("audit",audit)
                                .put("detector",if(selected.status=="CONTROLLED_CANDIDATE") "SUBPIX" else "NONE").put("frame_timestamp",image.imageInfo.timestamp)
                                .put("part_requested",showPart).put("part_projected",partFrame!=null).put("part_projection_ms",partMs).put("fps",fps).put("pose_state",poseFrame.state).toString(2))
                            java.io.File(filesDir,"capture-audit.json").writeText(audit.toString(2))
                            performanceFile.appendText(String.format(Locale.US,"%d,%s,%.3f,%d,%.3f,%.3f,%.3f,%d,%s,%s\n",
                                token,cameraId,(now-sessionStart)/1e9,frames,fps,detectionSum/frames,poseSum/frames,
                                trackedFrames,poseFrame.state,poseFrame.usedIds.joinToString("|")))
                            detectionSum=0.0; poseSum=0.0; trackedFrames=0
                            frames = 0
                            windowStart = now
                        }
                        if (uiPending.compareAndSet(false, true)) {
                            runOnUiThread {
                                try {
                                    if (foreground && token == generation) {
                                        val target = previewView.outputTransform
                                        val fresh = SystemClock.elapsedRealtimeNanos() - frameAt in 0 until 400_000_000L
                                        if (!fresh || target == null || cameraError != null) {
                                            invalidatePoseUi(cameraError ?: if (fresh) "等待预览坐标变换 · 固定板" else "图像已过期 · 叠加已隐藏")
                                        } else {
                                            val transform = CoordinateTransform(sourceTransform, target)
                                            val mapped = detection.markers.map { marker ->
                                                Marker(marker.id, marker.points.copyOf().also { transform.mapPoints(it) })
                                            }
                                            val projected = poseFrame.pose?.projected?.copyOf()?.also { transform.mapPoints(it) }
                                            val mappedPart=partFrame?.copy(points=partFrame.points.copyOf().also {transform.mapPoints(it)})
                                            overlay.show(mapped, projected, frameAt,mappedPart)
                                            sampleFrame?.let {sf -> calibrationOverlay.show(sf.points.copyOf().also {transform.mapPoints(it)}) }
                                            poseStatus.text = lensNote + "\n" + when {
                                                poseFrame.pose != null -> "${poseFrame.reason} · ID ${poseFrame.usedIds.joinToString()}\n" + "${selected.status} · ${selected.reason}"
                                                poseFrame.state == "NO_ANCHOR" -> "未定位 · 请对准新版固定板\n${selected.status} · ${selected.reason}"
                                                else -> "位姿不可用 · ${poseFrame.reason}"
                                            }
                                            if(samplingMode) poseStatus.text="${sampleFrame?.message}"
                                            val diagnosticKey = "${poseFrame.state}:${poseFrame.reason}:${poseFrame.raw.map { it.id }}"
                                            if (updateStats || diagnosticKey != lastDiagnostic || count == 1L) {
                                                val pose = poseFrame.pose
                                                fun vector(values: DoubleArray?) = values?.joinToString { String.format(Locale.US,"%.2f",it) } ?: "—"
                                                detectionStatus.text = "Raw: ${poseFrame.raw.joinToString { it.id.toString() }.ifEmpty { "无" }}" +
                                                    "\nAccepted 候选: ${poseFrame.accepted.joinToString { it.id.toString() }.ifEmpty { "无" }}（板内候选）" +
                                                    "\n参与定位: ${poseFrame.usedIds.joinToString().ifEmpty { "无" }}" +
                                                    "\n逐标记 RMS px（拒绝时仅诊断）: ${poseFrame.markerErrors.entries.joinToString { "${it.key}:%.2f".format(Locale.US,it.value) }.ifEmpty { "—" }}" +
                                                    "\nIgnored: ${poseFrame.rejected.joinToString { "${it.marker.id}:${it.reason}" }.ifEmpty { "无" }}" +
                                                    String.format(Locale.US,"\n检测 %.1f ms · 位姿 %.1f ms", detection.milliseconds,poseMilliseconds) +
                                                    "\n状态 ${poseFrame.state} · ${poseFrame.reason}" +
                                                    "\nt [mm/配置尺度] ${vector(pose?.tvec)}\nrvec [rad] ${vector(pose?.rvec)}" +
                                                    "\n自重投影 RMS [px] ${pose?.let { String.format(Locale.US,"%.3f",it.reprojectionError) } ?: "—"}（非精度证明）" +
                                                    "\n${model.source}\n标定=${model.calibrated} · 板 ${boardLayout.id} · 尺长 $rulerMeasuredMm mm · 黑边 ${boardLayout.markerLengthMm} mm · 平滑关闭"
                                                if(samplingMode) {
                                                    poseStatus.text="${sampleFrame?.message}"
                                                    detectionStatus.text="${sampleFrame?.message}\n模式 $samplingRole；目标训练24–30、独立验证10–12、四标记验证8–12\n采集间改变角度/距离/画面位置；不自动录像\n元数据精确匹配=${audit.getBoolean("matched_capture_result")}\n${audit.getJSONObject("capture")}"
                                                } else detectionStatus.append("\n${selected.status}: ${selected.reason}")
                                                lastDiagnostic = diagnosticKey
                                            }
                                            if (now - lastLogAt >= 1_000_000_000L) {
                                                Log.i(TAG, "pose=${poseFrame.state} reason=${poseFrame.reason} raw=${poseFrame.raw.map { it.id }} accepted=${poseFrame.accepted.map { it.id }} used=${poseFrame.usedIds} markerRms=${poseFrame.markerErrors} " +
                                                    "fps=$fps detectMs=${detection.milliseconds} poseMs=$poseMilliseconds reproj=${poseFrame.pose?.reprojectionError}")
                                                lastLogAt = now
                                            }
                                        }
                                        if (updateStats) stats.text = "OpenCV $openCvVersion\n" + getString(R.string.analysis_stats, width, height, rotation, fps, count)

                                    }
                                } catch (exception: Exception) {
                                    invalidatePoseUi("坐标映射失败 · ${exception.javaClass.simpleName}")
                                } finally {
                                    uiPending.set(false)
                                }
                            }
                        }
                    } catch (exception: Exception) {
                        postDetectionError(token, exception.javaClass.simpleName + ": " + exception.message)
                    } catch (error: LinkageError) {
                        postDetectionError(token, "OpenCV native: " + error.javaClass.simpleName)
                    } finally {
                        image.close()
                    }
                }
                preview = currentPreview
                analysis = currentAnalysis
                currentPreview.setSurfaceProvider(previewView.surfaceProvider)
                camera = currentProvider.bindToLifecycle(this, lensPlan.selector,
                    UseCaseGroup.Builder().setViewPort(viewPort).addUseCase(currentPreview).addUseCase(currentAnalysis).build())
                cameraId = Camera2CameraInfo.from(camera!!.cameraInfo).cameraId
                if(lockFocus) {
                    // Apply to the camera-wide repeating request after every rebind, including preview.
                    val focusFuture=Camera2CameraControl.from(camera!!.cameraControl).setCaptureRequestOptions(
                        CaptureRequestOptions.Builder()
                            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF)
                            .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE,2f).build())
                    focusFuture.addListener({
                        try {focusFuture.get()} catch(e:Exception) {Log.w(TAG,"Fixed focus request failed; per-frame profile gate remains active",e)}
                    },ContextCompat.getMainExecutor(this))
                }
                // Queue publication on the analysis executor, before the first result can be accepted.
                val currentParameters = CameraParameters.fromCameraId(this, cameraId)
                CameraParameters.saveReport(this)
                analyzerExecutor.execute { parameters = currentParameters }
                camera?.cameraInfo?.cameraState?.observe(this) { state ->
                    if (foreground && token == generation) {
                        cameraError = state.error?.let { "CameraX code ${it.code}" }
                        if (cameraError != null) {
                            if (activeWide) fallbackToMain(cameraError!!) else showError(cameraError!!)
                        }
                        else if (previewView.previewStreamState.value == PreviewView.StreamState.STREAMING) status.setText(R.string.camera_active)
                    }
                }
                Log.i(TAG, "bound camera=$cameraId wide=$activeWide FIT_CENTER preview+analysis generation=$token")
            } catch (exception: Exception) {
                releaseCamera()
                val reason = exception.javaClass.simpleName + ": " + exception.message
                if (activeWide) fallbackToMain(reason) else showError(reason)
                Log.e(TAG, "Camera binding failed", exception)
            } finally {
                binding = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun releaseCamera() {
        generation++
        sampleRequest.set(null)
        if(::calibrationOverlay.isInitialized) calibrationOverlay.clear()
        binding = false
        camera?.cameraInfo?.cameraState?.removeObservers(this)
        camera = null
        analysis?.clearAnalyzer()
        val owned = listOfNotNull(preview, analysis)
        if (owned.isNotEmpty()) provider?.unbind(*owned.toTypedArray())
        preview = null
        analysis = null
        lastFrameAt.set(0)
        if (::overlay.isInitialized) overlay.clear()
        if (::detectionStatus.isInitialized) detectionStatus.setText(R.string.aruco_waiting)
        lastDiagnostic = ""
        if (::poseStatus.isInitialized) poseStatus.text = "未定位 · 新版固定板 · 近似相机参数"
        Log.i(TAG, "released generation=$generation")
    }

    private fun postDetectionError(token: Int, message: String) {
        if (uiPending.compareAndSet(false, true)) runOnUiThread {
            try {
                if (foreground && token == generation) {
                    invalidatePoseUi("位姿不可用 · $message")
                }
            } finally { uiPending.set(false) }
        }
    }

    private fun fallbackToMain(reason: String) {
        if (fallbackScheduled) return
        fallbackScheduled = true
        Log.w(TAG,"Wide camera unavailable; fallback to main: $reason")
        java.io.File(filesDir,"wide-fallback.txt").writeText(reason)
        invalidatePoseUi("广角不可用，正在切回主摄")
        requestWide = false
        getPreferences(MODE_PRIVATE).edit().putBoolean("prefer_wide", false).apply()
        mainHandler.post { if (foreground && !isDestroyed) { releaseCamera(); startCamera() } }
    }

    private fun invalidatePoseUi(reason: String) {
        overlay.clear()
        poseStatus.text = reason
        detectionStatus.text = "$reason\n当前位姿无效 · rvec/tvec/重投影误差：—"
        lastDiagnostic = ""
    }

    private fun showError(message: String) {
        cameraError = message
        status.text = getString(R.string.camera_error, message)
        invalidatePoseUi(getString(R.string.camera_error, message))
        permissionButton.setText(R.string.camera_retry)
        permissionButton.visibility = View.VISIBLE
    }

    private fun textLabel(resource: Int, size: Float) = TextView(this).apply {
        setText(resource)
        textSize = size
        setTextColor(Color.WHITE)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object { private const val TAG = "ARCamera" }
}
