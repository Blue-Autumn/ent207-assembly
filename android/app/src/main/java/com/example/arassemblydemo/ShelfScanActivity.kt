package com.example.arassemblydemo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop

/** Identity capture only: no pose, no photo saved, no automatic assembly confirmation. */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class ShelfScanActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private data class Request(val generation: Int, val at: Long)
    private val requested = AtomicReference<Request?>(null)
    private val handler = Handler(Looper.getMainLooper())
    private var currentRequest: Request? = null
    private var processor: ArucoProcessor? = null // executor owned
    private var provider: ProcessCameraProvider? = null
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var scan: Button
    @Volatile private var active = false
    @Volatile private var generation = 0
    private var invalidated = false
    private val purpleFreeze by lazy { intent.getBooleanExtra("purple_freeze",false) }
    private var frozenDialog: android.app.AlertDialog? = null
    private var permissionAsked = false
    private var wide = true
    private var timer: CountDownTimer? = null
    private var preparing = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) bind() else status.text = "相机权限未开启。请在系统应用设置中开启后返回。"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        invalidated = savedInstanceState != null
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(247,249,252)); setPadding(20,20,20,20) }
        root.setOnApplyWindowInsetsListener { v, i ->
            @Suppress("DEPRECATION")
            v.setPadding(20,20+i.systemWindowInsetTop,20,20+i.systemWindowInsetBottom); i
        }
        status = TextView(this).apply { textSize = 18f; text = "将本层标签放入取景框；每次点击读取当前画面。" }
        root.addView(status)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        root.addView(preview, LinearLayout.LayoutParams(-1,0,1f))
        scan = Button(this).apply { text = "读取当前标签"; isEnabled = false; setOnClickListener {
            if (preparing) { timer?.cancel(); preparing = false; text = "读取当前标签"; bind() }
            else {
                val request = Request(generation,SystemClock.elapsedRealtime())
                currentRequest = request; requested.set(request); isEnabled = false; status.text = "正在读取下一帧…"
                handler.postDelayed({
                    if (active && currentRequest == request) {
                        requested.compareAndSet(request,null); currentRequest = null
                        status.text = "读取超时，请持稳重试；无画面时切换镜头。"; isEnabled = true
                    }
                },4000)
            }
        } }
        root.addView(scan)
        root.addView(Button(this).apply { text = "切换广角 / 主摄"; setOnClickListener { wide = !wide; bind() } })
        root.addView(Button(this).apply { text = "相机权限设置"; setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")))
        } })
        root.addView(Button(this).apply { text = "取消扫描"; setOnClickListener { finish() } })
        setContentView(root)
        preparing = intent.getBooleanExtra("prepare_automatic_test",false)
        if (preparing) {
            scan.isEnabled = true
            timer = object: CountDownTimer(20000,1000) {
                override fun onTick(ms: Long) { status.text = "测试准备：摆好标签（${ms/1000+1}秒）"; scan.text = "准备好了，直接继续" }
                override fun onFinish() { preparing = false; scan.text = "读取当前标签"; bind() }
            }.start()
        }
    }

    override fun onResume() { super.onResume(); active = true; if (!preparing) bind() }
    override fun onPause() { active = false; frozenDialog?.dismiss(); frozenDialog=null; invalidated = true; generation++; requested.set(null); currentRequest = null; provider?.unbindAll(); scan.isEnabled = preparing; super.onPause() }

    private fun bind() {
        if (!active || preparing) return
        generation++; val token = generation
        requested.set(null); currentRequest = null; scan.isEnabled = false
        if (ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status.text = "需要相机权限。若未授权，请打开下方权限设置。"
            if (!permissionAsked) { permissionAsked = true; permission.launch(Manifest.permission.CAMERA) }; return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!active || token != generation) return@addListener
            try {
                val p = future.get(); provider = p; p.unbindAll()
                val plan = LensChoice.choose(p.availableCameraInfos,wide)
                val rotation = preview.display?.rotation ?: android.view.Surface.ROTATION_0
                val live = Preview.Builder().setTargetRotation(rotation).build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val builder = ImageAnalysis.Builder().setTargetRotation(rotation).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(1280,720),ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                if (plan.cameraId == "4") {
                    val c = getSystemService(CameraManager::class.java).getCameraCharacteristics(plan.cameraId)
                    if (c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.contains(CaptureRequest.CONTROL_AF_MODE_OFF) == true &&
                        (c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) >= 2f) {
                        Camera2Interop.Extender(builder).setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF)
                            .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE,2f)
                    }
                }
                val analysis = builder.build()
                var firstFrame = true
                analysis.setAnalyzer(executor) { frame ->
                    var processingRequest: Request? = null
                    try {
                        if (firstFrame) {
                            firstFrame = false
                            runOnUiThread { if (active && token == generation && currentRequest == null) scan.isEnabled = true }
                        }
                        val request = requested.get()
                        if (active && token == generation && request != null && request.generation == token && requested.compareAndSet(request,null)) {
                            processingRequest = request
                            if (processor == null) {
                                ArucoProcessor.initialize()
                                processor = ArucoProcessor(ShelfMarkerRegistry.OPENCV_DICTIONARY_ID)
                            }
                            val markers = processor!!.detect(frame).markers
                            val ids = markers.map { it.id }.toIntArray()
                            val snapshot = if (purpleFreeze && ids.size==4 && ids.toSet()==setOf(4,5,6,7)) {
                                val gray=processor!!.copyLastGray()
                                val centers=markers.associate { m -> m.id to PurpleJointLocator.XY(
                                    (0..3).sumOf { m.points[it*2].toDouble() }/4,
                                    (0..3).sumOf { m.points[it*2+1].toDouble() }/4) }
                                PurpleSnapshot.make(gray,frame.cropRect.width(),frame.cropRect.height(),centers,frame.imageInfo.rotationDegrees)
                            } else null
                            runOnUiThread {
                                if (active && token == generation && currentRequest == request && SystemClock.elapsedRealtime()-request.at < 4000) {
                                    currentRequest = null
                                    if (snapshot == null) returnIds(ids) else {
                                        frozenDialog=android.app.AlertDialog.Builder(this)
                                            .setTitle(if(snapshot.second) "冻结画面 · 中间榫与槽" else "四码已识别 · 接头轮廓不清")
                                            .setMessage(if(snapshot.second) "圆圈指示两处要对齐的边。核对后可放下手机装配。" else "请分开两块板，露出中间接头并俯拍。也可以只使用找件结果。")
                                            .setView(ImageView(this).apply { setImageBitmap(snapshot.first); adjustViewBounds=true; scaleType=ImageView.ScaleType.FIT_CENTER })
                                            .setPositiveButton("使用找件结果") { _,_ -> returnIds(ids) }
                                            .setNegativeButton("重新取景",null)
                                            .setOnDismissListener { if(active && token==generation) scan.isEnabled=true }
                                            .show()
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        val failedRequest = processingRequest
                        runOnUiThread { if (active && token == generation && failedRequest != null && currentRequest == failedRequest) {
                            currentRequest = null; status.text = "读取失败，请重试：${e.javaClass.simpleName}"; scan.isEnabled = true
                        } }
                    } finally { frame.close() }
                }
                p.bindToLifecycle(this,plan.selector,live,analysis)
                status.text = "${if(plan.isWide) "广角" else "主摄"} · 对准标签，再点击读取。不会保存或上传照片。"
            } catch (e: Exception) { status.text = "相机未就绪，请切换镜头或返回重试：${e.javaClass.simpleName}" }
        },ContextCompat.getMainExecutor(this))
    }

    private fun returnIds(ids: IntArray) {
        setResult(RESULT_OK,Intent().putExtra("marker_ids",ids).putExtra("observation_invalidated",invalidated))
        finish()
    }

    override fun onDestroy() { timer?.cancel(); handler.removeCallbacksAndMessages(null); executor.execute { processor?.close(); processor = null }; executor.shutdown(); super.onDestroy() }
}
