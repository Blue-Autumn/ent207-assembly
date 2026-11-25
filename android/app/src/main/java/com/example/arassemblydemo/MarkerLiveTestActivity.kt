package com.example.arassemblydemo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import org.opencv.objdetect.Objdetect
import kotlin.math.hypot

/** Read-only optical test. No assembly observations, files, account state, or network calls. */
class MarkerLiveTestActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val assemblyMode by lazy { intent.getBooleanExtra("frame_assembly",false) }
    private val stats = MarkerLiveStats()
    private var processor: ArucoProcessor? = null // executor owned
    private var provider: ProcessCameraProvider? = null
    private lateinit var preview: PreviewView
    private lateinit var info: TextView
    private lateinit var result: TextView
    private lateinit var ready: Button
    @Volatile private var active = false
    @Volatile private var generation = 0
    @Volatile private var statsEpoch = 0
    private data class FrameStamp(val generation: Int, val at: Long)
    @Volatile private var lastFrame: FrameStamp? = null
    private var wide = true
    private var permissionAsked = false
    private var prepareUntil = 0L
    private var cameraInfo = "相机启动中"
    private var detectionError: String? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) bind() else { cameraInfo = "相机权限未开启；请打开权限设置"; render() }
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            if (prepareUntil > 0 && SystemClock.elapsedRealtime() >= prepareUntil) { prepareUntil = 0; bind() }
            render(); handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20,20,20,20) }
        root.setOnApplyWindowInsetsListener { v,i ->
            @Suppress("DEPRECATION")
            v.setPadding(20,20+i.systemWindowInsetTop,20,20+i.systemWindowInsetBottom); i
        }
        info = TextView(this).apply { textSize = 14f }
        root.addView(info)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        root.addView(preview,LinearLayout.LayoutParams(-1,0,1f))
        result = TextView(this).apply { textSize = 16f }
        root.addView(ScrollView(this).apply { addView(result) },LinearLayout.LayoutParams(-1,(180*resources.displayMetrics.density).toInt()))
        ready = Button(this).apply { text = "准备好了，开始"; setOnClickListener { prepareUntil = 0; bind(); render() } }
        root.addView(ready)
        val buttons = LinearLayout(this)
        fun button(title: String, action: () -> Unit) { buttons.addView(Button(this).apply { text=title; setOnClickListener { action() } },LinearLayout.LayoutParams(0,-2,1f)) }
        button("切换镜头") { wide = !wide; bind() }
        button("重置统计") { statsEpoch++; stats.clear(); detectionError=null; render() }
        button(if (assemblyMode) "回到试装" else "返回") { finish() }
        root.addView(buttons)
        root.addView(Button(this).apply { text="相机权限设置"; setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")))
        } })
        setContentView(root)
        if (intent.getBooleanExtra("prepare_automatic_test",false)) prepareUntil=SystemClock.elapsedRealtime()+20000
    }
    override fun onResume() { super.onResume(); active=true; if (prepareUntil==0L) bind(); handler.post(ticker) }
    override fun onPause() {
        active=false; generation++; provider?.unbindAll(); stats.clear(); lastFrame=null; detectionError=null
        handler.removeCallbacks(ticker); super.onPause()
    }
    private fun bind() {
        if (!active) return
        generation++; val token=generation
        provider?.unbindAll(); stats.clear(); lastFrame=null; detectionError=null
        if (prepareUntil>SystemClock.elapsedRealtime()) { render(); return }
        prepareUntil=0; cameraInfo="相机启动中"; render()
        if (ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
            cameraInfo="需要相机权限"
            if (!permissionAsked) { permissionAsked=true; permission.launch(Manifest.permission.CAMERA) }
            render(); return
        }
        val future=ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!active || token!=generation) return@addListener
            try {
                val p=future.get(); provider=p; p.unbindAll()
                val plan=LensChoice.choose(p.availableCameraInfos,wide)
                val rotation=preview.display?.rotation ?: android.view.Surface.ROTATION_0
                val live=Preview.Builder().setTargetRotation(rotation).build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val analysis=ImageAnalysis.Builder().setTargetRotation(rotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(1280,720),ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()).build()
                var nextSample=0L
                analysis.setAnalyzer(executor) { frame ->
                    val epoch=statsEpoch
                    try {
                        val at=SystemClock.elapsedRealtime()
                        if (active && token==generation) {
                            lastFrame=FrameStamp(token,at)
                            if (at>=nextSample) {
                                nextSample=at+200
                                if (processor==null) { ArucoProcessor.initialize(); processor=ArucoProcessor(Objdetect.DICT_4X4_250) }
                                val detection=processor!!.detect(frame)
                                val edges=detection.markers.groupBy { it.id }.mapValues { (_,markers) -> markers.map { marker ->
                                    (0..3).map { j -> val k=(j+1)%4
                                        hypot((marker.points[j*2]-marker.points[k*2]).toDouble(),(marker.points[j*2+1]-marker.points[k*2+1]).toDouble())
                                    }.average()
                                }.average() }
                                val dimensions="${frame.cropRect.width()}×${frame.cropRect.height()}"
                                runOnUiThread { if (active && token==generation && epoch==statsEpoch) {
                                    stats.add(at,edges,detection.milliseconds); detectionError=null
                                    cameraInfo="${if(plan.isWide) "广角" else if(wide) "主摄（未找到独立广角）" else "主摄"} · camera ${plan.cameraId} · 分析 $dimensions"
                                } }
                            }
                        }
                    } catch (e: Exception) { runOnUiThread { if (active && token==generation && epoch==statsEpoch) { detectionError=e.javaClass.simpleName; stats.clear() } } }
                    finally { frame.close() }
                }
                p.bindToLifecycle(this,plan.selector,live,analysis)
                cameraInfo="${if(plan.isWide) "广角" else if(wide) "主摄（未找到独立广角）" else "主摄"} · camera ${plan.cameraId} · 等待图像"
            } catch (e: Exception) { cameraInfo="相机未就绪：${e.javaClass.simpleName}；可切换镜头重试" }
            render()
        },ContextCompat.getMainExecutor(this))
    }
    private fun render() {
        val now=SystemClock.elapsedRealtime(); val s=stats.snapshot(now)
        val frame=lastFrame?.takeIf { it.generation==generation }
        ready.visibility=if(prepareUntil>now) android.view.View.VISIBLE else android.view.View.GONE
        info.text="标签实时测试 · DICT_4X4_250（000–249）\n相机默认对焦策略，未锁定焦距；不拍照、不上传、不写装配进度。\n$cameraInfo"
        if (prepareUntil>now) { result.text="准备摆放标签：${(prepareUntil-now+999)/1000} 秒后开始"; return }
        val state=when {
            detectionError!=null -> "检测异常：$detectionError"
            frame==null || now-frame.at>800 -> "无新相机帧；当前 ID 已失效"
            s.sampleAgeMs==null || s.sampleAgeMs>800 -> "有相机帧，暂无有效的新检测结果"
            s.currentIds.isEmpty() -> "当前 ID：未识别（新采样帧未识别到码）"
            else -> "当前 ID：${s.currentIds.joinToString(transform = MarkerLiveStats::displayId)}"
        }
        if (assemblyMode) {
            info.text="侧框找件 · 先确认两件属于同一侧，再返回看槽位图。"
            val fresh = detectionError==null && frame!=null && now-frame.at<=800 && (s.sampleAgeMs ?: Long.MAX_VALUE)<=800
            result.text=if (fresh) FixedFrameHalf.assemblyHint(s.currentIds) else state
            return
        }
        result.text=buildString {
            append(state)
            if (detectionError==null && frame!=null && now-frame.at<=800 && (s.sampleAgeMs ?: Long.MAX_VALUE)<=800) {
                FixedFrameHalf.visible(s.currentIds).forEach { append("\n${it.label}") }
            }
            append("\n近 3 秒采样 ${s.sampleCount} 帧；命中率按检测采样帧，非视频帧\n")
            append("检测耗时：${s.detectionMs?.let { "%.1f ms".format(it) } ?: "—"}；结果距今：${s.sampleAgeMs?.let { "$it ms" } ?: "—"}\n")
            s.rows.forEach { row -> append("ID ${MarkerLiveStats.displayId(row.id)}：${row.hits}/${row.samples}（${if(row.samples>0) "%.0f%%".format(row.hits*100.0/row.samples) else "—"}）；最近读到 ${"%.1f".format(row.lastSeenAgoMs/1000.0)}s 前；近 3 秒黑码平均边长 ${row.meanEdgePx?.let { "%.1f px".format(it) } ?: "—"}\n") }
        }
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); executor.execute { processor?.close(); processor=null }; executor.shutdown(); super.onDestroy() }
}
