package com.example.arassemblydemo

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Bundled trusted content only. Imported JSON supplies identity bindings, never executable HTML. */
class AssemblyGuideActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AssemblyLanguage.wrap(base))

    private lateinit var web: WebView
    private val accounts by lazy { LocalAccounts(this) }
    private var owner: String? = null
    private val storage by lazy { getSharedPreferences("assembly-bindings-v1",MODE_PRIVATE) }
    private val io=Executors.newSingleThreadExecutor()
    private lateinit var content: JSONObject
    private lateinit var knownParts: Set<String>
    private var bindings: Map<String,List<Int>> = emptyMap()
    private var revision="unregistered"
    private var feedbackToken:String?=null
    private var aiRequest:Pair<String,String>?=null
    private var aiReturnPending:JSONObject?=null
    private var workPageReady=false
    private data class Scan(val part:String,val token:String,val parts:List<String> = listOf(part))
    private var pending: Scan?=null
    private lateinit var cameraHost: FrameLayout
    private lateinit var liveCamera: AssemblyLiveCamera
    private var liveRequest: Scan? = null
    private data class GuideScan(val task:BoardLocalGuidance.Task,val token:String,val faces:BoardFaceRegistry,
        val measured:MeasuredBoardProjection.Catalog, val slotHistory:FinalAssemblyGuidance.History = FinalAssemblyGuidance.History())
    private var guideRequest:GuideScan?=null
    private var geometryCache: Pair<BoardFaceRegistry, MeasuredBoardProjection.Catalog>? = null
    private fun registrationGeometry(): Pair<BoardFaceRegistry, MeasuredBoardProjection.Catalog> {
        geometryCache?.let { return it }
        val faces = BoardFaceRegistry.load(this, bindings)
        val catalog = runCatching {
            val text = assets.open("shelf-phone/measured-board-planes.json").bufferedReader().use { it.readText() }
            MeasuredBoardProjection.parse(text, faces.assembly, faces.sites)
        }.getOrElse { MeasuredBoardProjection.unavailable("measured_asset_unavailable") }
        return (faces to catalog).also { geometryCache = it }
    }
    private val finderHighlight by lazy {
        FinderHighlight.load(assets.open("shelf-phone/finder-cad-rings.json").bufferedReader().use { it.readText() })
    }
    private val guideStability=BoardLocalGuidance.Stabilizer()
    private var guideLastFrame=0L
    private var guideExpired=false
    private val guideMain=Handler(Looper.getMainLooper())
    private val guideExpiry=object:Runnable{
        override fun run(){
            if(guideRequest==null||!liveResumed||isDestroyed)return
            if(guideLastFrame>0 && SystemClock.elapsedRealtime()-guideLastFrame>850 && !guideExpired){
                guideExpired=true;guideRequest?.slotHistory?.reset();guideStability.reset()
                reportGuideState("need_view","标签暂时离开画面","把本步两件与分散的标签放进画面，继续查看摆向。")
            }
            guideMain.postDelayed(this,200)
        }
    }
    private var liveResumed = false
    private var liveViewportReady = false
    private var permissionInFlight = false
    private var importInProgress = false
    // These fields and all TextToSpeech calls are owned by the main thread.
    private val speechMain = Handler(Looper.getMainLooper())
    private var speechEngine: TextToSpeech? = null
    private var speechVoice: Voice? = null
    private var speechReady = false
    private var speechActive = false
    private var speechGeneration = 0L
    private var speechSerial = 0L
    private var speechUtterance: String? = null
    private var speechRequestToken: String? = null
    private var pendingSpeech: Pair<String,String>? = null
    private var speechUnavailableMessage = ""
    private var speechInitTimeout: Runnable? = null
    private val cameraPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionInFlight = false
        if (!isFinishing && !isDestroyed && sameOwner()) {
            if (granted) configureLiveCamera() else reportLiveState("permission", "允许相机后，就能直接寻找这件零件")
        }
    }
    private val scanner=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request=pending;pending=null
        if(request==null||!sameOwner()) return@registerForActivityResult
        val data=result.data;val marker=data?.getIntExtra("marker_id",-1)?:-1
        if(result.resultCode==RESULT_OK && data?.getStringExtra("request_token")==request.token && marker in bindings[request.part].orEmpty()) {
            callback("onNativeCandidate",JSONObject().put("part",request.part).put("token",request.token).put("marker",marker))
        } else callback("onNativeScanCancelled",JSONObject().put("token",request.token))
    }
    private val aiLauncher=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request=aiRequest;aiRequest=null
        if(request!=null && sameOwner()){
            val data=result.data
            val needsCheck=result.resultCode==RESULT_OK && data?.getStringExtra("aiTask")==request.first &&
                data.getStringExtra("aiScope")==request.second && data.getStringExtra("aiAssessment")=="needs_adjustment" &&
                runCatching { AiReviewContext.load(this,request.first).key==request.second }.getOrDefault(false)
            // The photograph and its teaching animation belong to the review activity.
            aiReturnPending=JSONObject().put("task",request.first).put("needsCheck",needsCheck)
        }
        notifyNativeResume()
    }
    private val importer=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri==null){finishImport();return@registerForActivityResult}
        io.execute {
            try {
                val bytes=contentResolver.openInputStream(uri)?.use { it.readBytesLimited(2*1024*1024) } ?: error(getString(R.string.guide_open_file))
                val project=JSONObject(bytes.toString(Charsets.UTF_8))
                val parsed=parseBindings(project)
                val faceSites=BoardFaceRegistry.serializeSites(project)
                val canonical=JSONObject().apply { parsed.toSortedMap().forEach { (part,ids)->put(part,org.json.JSONArray(ids)) } }.toString()
                val hash=sha(canonical)
                runOnUiThread { if(!isFinishing&&!isDestroyed&&sameOwner()) {
                    var applied = false
                    AlertDialog.Builder(this).setTitle(getString(R.string.guide_import_title))
                        .setMessage(getString(R.string.guide_import_summary, parsed.size, parsed.values.sumOf { it.size }))
                        .setNegativeButton(getString(R.string.common_cancel),null).setPositiveButton(getString(R.string.guide_use)) { _,_->
                            if(storage.edit().putString("bindings",canonical).putString("revision",hash).putString(BoardFaceRegistry.PREF_SITES,faceSites).commit()) {
                                stopLiveCamera();bindings=parsed;geometryCache=null;revision=hash;pending=null;applied=true;web.reload()
                            } else showError(getString(R.string.guide_import_failed))
                        }.setOnDismissListener { finishImport(notify = !applied) }.show()
                } }
            } catch(e:Exception) { runOnUiThread { if(!isFinishing&&!isDestroyed) {
                showError(e.message?:getString(R.string.guide_read_failed));finishImport()
            } } }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);owner=accounts.current()?.id
        if(savedInstanceState!=null && savedInstanceState.getString("reviewOwner")==owner){
            val task=savedInstanceState.getString("reviewTask");val scope=savedInstanceState.getString("reviewScope")
            if(task!=null && scope!=null)aiRequest=task to scope
            savedInstanceState.getString("pendingReviewReturn")?.let { aiReturnPending=JSONObject(it) }
        }
        LightSystemBars.configure(window)
        ImmersiveAssembly.configure(window)
        content=JSONObject(assets.open("shelf-phone/workflow.json").bufferedReader().use { it.readText() })
        knownParts=buildSet { val ts=content.getJSONArray("tasks");for(i in 0 until ts.length()){
            val ids=ts.getJSONObject(i).getJSONArray("context");for(j in 0 until ids.length())add(ids.getString(j)) } }
        runCatching {
            val saved=JSONObject(storage.getString("bindings","{}")!!)
            val rows=mutableListOf<AssemblyBinding>();saved.keys().forEach { part->val ids=saved.getJSONArray(part);for(i in 0 until ids.length())rows+=AssemblyBinding(part,i,ids.getInt(i)) }
            bindings=AssemblyMarkerBindings.validate(rows,knownParts);revision=storage.getString("revision","unregistered")!!
        }.onFailure { bindings=emptyMap();revision="unregistered" }
        val root=FrameLayout(this).apply { setBackgroundColor(Color.rgb(250,249,243)) }
        // Camera and web share the full-screen origin. Only web controls avoid the cutout.
        val stage=FrameLayout(this)
        cameraHost=FrameLayout(this).apply {
            visibility=View.INVISIBLE
            AssemblyMediaFrame.clip(this)
        }
        stage.addView(cameraHost,FrameLayout.LayoutParams(1,1))
        liveCamera=AssemblyLiveCamera(this,cameraHost,::onLiveObservation,::onGuideFrame) { label ->
            callback("onNativeFraming",JSONObject().put("label",label))
        }
        web=WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled=true;settings.domStorageEnabled=true;settings.allowFileAccess=false
            settings.allowContentAccess=false;settings.blockNetworkLoads=true
            addJavascriptInterface(Bridge(),"AssemblyNative")
            webViewClient=object:WebViewClient(){
                override fun shouldOverrideUrlLoading(view:WebView?,request:WebResourceRequest?)=request?.url?.toString()!=PAGE
                override fun onPageFinished(view: WebView?, url: String?) {
                    root.requestApplyInsets()
                    workPageReady=url==PAGE
                    if(workPageReady)notifyNativeResume()
                }
            }
            loadUrl(PAGE)
        }
        stage.addView(web,FrameLayout.LayoutParams(-1,-1))
        root.addView(stage,FrameLayout.LayoutParams(-1,-1));setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val cutout=insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val density=resources.displayMetrics.density
            callback("onNativeSafeInsets",JSONObject().put("top",cutout.top/density)
                .put("bottom",cutout.bottom/density).put("left",cutout.left/density).put("right",cutout.right/density))
            insets
        }
        root.requestApplyInsets()
    }
    private fun parseBindings(p:JSONObject):Map<String,List<Int>> {
        require(p.optString("format")=="shelf-tutorial-3"&&p.optString("assembly")==content.getString("assembly")) { getString(R.string.guide_project_select) }
        val sites=p.getJSONObject("sites");val rows=mutableListOf<AssemblyBinding>()
        sites.keys().forEach { key->val entry=sites.getJSONObject(key);if(!entry.isNull("tag")){
            val split=key.split('|');require(split.size==2) {getString(R.string.guide_invalid_site)}
            val slot=split[1].toIntOrNull()?:error(getString(R.string.guide_invalid_site))
            require(split[1]==slot.toString()) {getString(R.string.guide_invalid_site)}
            val value=entry.get("tag");require(value is Number&&value.toDouble()==value.toInt().toDouble()) {getString(R.string.guide_invalid_number)}
            rows+=AssemblyBinding(split[0],slot,value.toInt())
        } }
        require(rows.isNotEmpty()) { getString(R.string.guide_missing_ids) }
        return AssemblyMarkerBindings.validate(rows,knownParts)
    }
    private fun sameOwner()=accounts.current()?.id==owner
    private fun callback(name:String,payload:JSONObject){if(::web.isInitialized&&!isDestroyed)web.evaluateJavascript("window.$name && window.$name(${payload});",null)}
    private fun showError(text:String){AlertDialog.Builder(this).setTitle(getString(R.string.guide_import_unchanged)).setMessage(text).setPositiveButton(getString(R.string.common_ok),null).show()}
    private fun notifyNativeResume() {
        if (workPageReady && liveResumed && aiRequest==null && !importInProgress &&
            ::web.isInitialized && !isFinishing && !isDestroyed && sameOwner()) {
            val returned=aiReturnPending
            aiReturnPending=null
            web.evaluateJavascript("window.onNativeResume && window.onNativeResume(${returned ?: "null"});", null)
        }
    }

    private fun beginImport() {
        if (!liveResumed || !sameOwner() || importInProgress || isFinishing || isDestroyed) return
        importInProgress = true
        stopLiveCamera()
        importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
    }

    private fun finishImport(notify: Boolean = true) {
        importInProgress = false
        if (notify) notifyNativeResume()
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission(explicit: Boolean) {
        if (!liveResumed || importInProgress || isFinishing || isDestroyed || !sameOwner() || permissionInFlight) return
        if (hasCameraPermission()) { configureLiveCamera(); return }
        val prompted = storage.getBoolean("camera_permission_prompted", false)
        if (prompted && !explicit) {
            reportLiveState("permission", "允许相机后，就能直接寻找这件零件")
            return
        }
        if (prompted && explicit && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            stopLiveCamera()
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            return
        }
        storage.edit().putBoolean("camera_permission_prompted", true).apply()
        permissionInFlight = true
        cameraPermissionRequest.launch(Manifest.permission.CAMERA)
    }

    private val LAYERS=(1..5).map { "层${it}板" }.toSet()
    private val BASE_HALVES=setOf("左侧板-1","左侧板-2")
    private fun configureLiveCamera() {
        if (!::liveCamera.isInitialized) return
        val request = liveRequest
        val guide=guideRequest
        val ids = if(guide!=null)guide.task.parts.flatMap { bindings[it].orEmpty() }.toSet()
                  else request?.parts.orEmpty().flatMap { bindings[it].orEmpty() }.toSet()
        val enabled = (request != null||guide!=null) && ids.isNotEmpty() && liveResumed && !importInProgress && liveViewportReady &&
            !isFinishing && !isDestroyed && sameOwner() && hasCameraPermission()
        cameraHost.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
        val slotIds = if (guide?.task?.kind=="install" && guide.task.group in LAYERS) {
            guide.faces.sites.filter { it.face=="A" && it.part in BASE_HALVES }.map { it.id }.toSet()
        } else emptySet()
        liveCamera.configure(ids, guide?.token?:request?.token.orEmpty(), enabled, finderDot=guide==null, slotMarkerIds=slotIds)
    }

    private fun stopLiveCamera() {
        // Invalidate first: configure/pause can emit synchronously and old queued frames must be ignored.
        liveRequest = null
        guideRequest=null;guideStability.reset();guideLastFrame=0;guideExpired=false
        guideMain.removeCallbacks(guideExpiry)
        liveViewportReady = false
        if (::cameraHost.isInitialized) cameraHost.visibility = View.INVISIBLE
        if (::liveCamera.isInitialized) liveCamera.configure(emptySet(), "", false)
    }

    private fun reportLiveState(state: String, message: String) {
        if(guideRequest!=null){reportGuideState(state,if(state=="permission")"让相机看到本步零件" else "正在看本步摆向",message);return}
        val request = liveRequest ?: return
        if (!liveResumed || importInProgress || !sameOwner() || isFinishing || isDestroyed) return
        callback("onNativeLive", JSONObject().put("part", request.part).put("token", request.token)
            .put("marker", JSONObject.NULL).put("visible", false).put("state", state).put("message", message))
    }

    private fun onLiveObservation(observation: AssemblyLiveCamera.Observation) {
        if(guideRequest?.token==observation.token){
            if(observation.state!="tracking"){
                guideRequest?.slotHistory?.reset();guideStability.reset();guideLastFrame=0
                reportGuideState(observation.state,"正在看本步摆向",observation.message)
            }
            return
        }
        val request = liveRequest ?: return
        if (observation.token != request.token || !liveResumed || importInProgress || !sameOwner() || isFinishing || isDestroyed) return
        if(observation.visible&&request.parts.any { observation.marker in bindings[it].orEmpty() }&&feedbackToken!=request.token){
            feedbackToken=request.token;AssemblyFeedback.confirm(web)
        }
        callback("onNativeLive", JSONObject().put("part", request.part).put("token", observation.token)
            .put("marker", observation.marker ?: JSONObject.NULL).put("visible", observation.visible)
            .put("state", observation.state).put("message", observation.message)
            .put("x", observation.x ?: JSONObject.NULL).put("y", observation.y ?: JSONObject.NULL)
            .put("viewWidth", observation.viewWidth).put("viewHeight", observation.viewHeight)
            .put("frameWidth", observation.frameWidth ?: JSONObject.NULL).put("frameHeight", observation.frameHeight ?: JSONObject.NULL)
            .put("cropLeft", observation.cropLeft ?: JSONObject.NULL).put("cropTop", observation.cropTop ?: JSONObject.NULL)
            .put("cropWidth", observation.cropWidth ?: JSONObject.NULL).put("cropHeight", observation.cropHeight ?: JSONObject.NULL)
            .put("rotationDegrees", observation.rotationDegrees ?: JSONObject.NULL))
    }

    private fun reportGuideState(state:String,title:String,message:String){
        val request=guideRequest?:return
        if(!liveResumed||importInProgress||!sameOwner()||isFinishing||isDestroyed)return
        liveCamera.highlight(emptyList(), android.os.SystemClock.elapsedRealtime())
        callback("onNativeGuidance",JSONObject().put("task",request.task.id).put("token",request.token)
            .put("state",state).put("title",title).put("message",message).put("visible",false)
            .put("stable",false).put("notify",false).put("markerIds",org.json.JSONArray()))
    }

    private fun onGuideFrame(frame:AssemblyLiveCamera.MappedFrame){
        val finder = liveRequest
        if (finder != null && finder.token == frame.token && liveResumed && liveViewportReady &&
            frame.width == cameraHost.width && frame.height == cameraHost.height) {
            val (faces, catalog) = registrationGeometry()
            val views = finderHighlight.update(frame.token,frame.cameraGeneration,finder.parts,faces.sites,frame.markers,catalog,frame.atMs)
            liveCamera.highlight(views, frame.atMs)
            return
        }
        val request=guideRequest?:return
        if(request.token!=frame.token||!liveResumed||importInProgress||!sameOwner()||isFinishing||isDestroyed||
            frame.width<=0||frame.height<=0||frame.width!=cameraHost.width||frame.height!=cameraHost.height)return
        guideLastFrame=frame.atMs;guideExpired=false
        val detections=frame.markers.map { marker->BoardFaceRules.Detection(marker.id,
            (0..3).map { k->BoardFaceRules.Point(marker.points[2*k].toDouble(),marker.points[2*k+1].toDouble()) }) }
        val evaluation=BoardLocalGuidance.evaluate(request.faces.sites,detections,request.task,request.measured,frame.width,frame.height,request.slotHistory,frame.atMs,
            frame.slotQuads.map { q -> (0..3).map { k -> BoardFaceRules.Point(q[2*k].toDouble(),q[2*k+1].toDouble()) } })
        val update=guideStability.update(evaluation,frame.atMs)
        val value=update.result
        if (request.task.kind == "prepare") {
            liveCamera.highlight(finderHighlight.alignedPair(value), frame.atMs)
        }
        val stable=value.status in setOf("direction_ok","rotate","flip","wrong_part","install_base_flip","install_base_ready","install_turn","install_stand","install_oriented","install_row_ok","install_wrong_row","install_wrong_part","install_base_heading","install_notch_hint")
        val neutralHint = value.status=="install_base_heading" || (value.status=="install_notch_hint" && value.installMotion==null)
        if(update.notify && !neutralHint){
            if(value.status in setOf("direction_ok","install_base_ready","install_row_ok","install_oriented"))AssemblyFeedback.confirm(web) else AssemblyFeedback.attention(web)
        }
        fun point(p:BoardFaceRules.Point)=JSONObject().put("x",p.x/frame.width).put("y",p.y/frame.height)
        // Detailed face polygons are consumed by the flat preassembly animation only.
        // Assembly uses the projected axis endpoints and slot centres on each live frame.
        val install=request.task.kind=="install"
        val cue=value.cueGeometry?.takeUnless { install }?.let { p->JSONObject().put("center",point(p.center))
            .put("axes",org.json.JSONArray(p.axes.map { a->JSONObject().put("axis",a.axis)
                .put("start",point(a.start)).put("end",point(a.end)) }))
            .put("footprint",org.json.JSONArray(p.footprint.map(::point)))
            .put("localCenter",org.json.JSONArray(p.localCenter)).put("localHalfExtent",org.json.JSONArray(p.localHalfExtent))
            .put("supportCount",p.supportCount).put("method",p.method).put("face",p.face)
            .put("planeToImage",org.json.JSONArray(p.planeToImage.mapIndexed { i,v->
                when(i){in 0..2->v/frame.width;in 3..5->v/frame.height;else->v} }))
            .put("outlineLocal",org.json.JSONArray(p.outlineLocal.map { q->JSONObject().put("x",q.x).put("y",q.y) }))
            .put("holesLocal",org.json.JSONArray(p.holesLocal.map { ring->org.json.JSONArray(ring.map { q->JSONObject().put("x",q.x).put("y",q.y) }) })) }
        callback("onNativeGuidance",JSONObject().put("task",request.task.id).put("token",request.token)
            .put("state",value.status).put("title",value.title).put("message",value.message)
            .apply {
                if (install && spokenLanguage.language == "en") {
                    put("titleEn", FinalGuideEnglish.text(value.title))
                    put("messageEn", FinalGuideEnglish.text(value.message))
                }
            }
            .put("part",value.part?:JSONObject.NULL).put("angle",value.angleDegrees?:JSONObject.NULL)
            .put("installMotion",value.installMotion?:JSONObject.NULL)
            .put("observedRow",value.observedRow).put("targetRow",value.targetRow)
            .put("installAxis",if(install)value.cueGeometry?.axes?.firstOrNull { it.axis==2 }?.let {
                org.json.JSONArray(listOf(point(it.start),point(it.end))) } else null)
            .put("inferredSlotTargets",org.json.JSONArray(value.inferredSlotTargets.map(::point)))
            .put("notchCue",org.json.JSONArray(value.notchCue.map(::point)))
            .put("backCue",org.json.JSONArray(value.backCue.map(::point)))
            .put("slotTargets",org.json.JSONArray(value.slotTargets.map(::point)))
            .put("targetLabel",value.targetLabel?:JSONObject.NULL)
            .put("cueGeometry",cue?:JSONObject.NULL).put("flipAxis",value.flipAxis?:JSONObject.NULL)
            .put("placement",value.placement?.let { p->JSONObject()
                .put("method",p.method).put("fixedPart",p.fixedPart).put("distanceMm",p.distanceMm)
                .put("deltaLocal",JSONObject().put("x",p.deltaLocal.x).put("y",p.deltaLocal.y)) }?:JSONObject.NULL)
            .put("anchorX",value.anchor?.let{it.x/frame.width}?:JSONObject.NULL)
            .put("anchorY",value.anchor?.let{it.y/frame.height}?:JSONObject.NULL)
            .put("markerIds",org.json.JSONArray(value.markerIds)).put("visible",value.markerIds.isNotEmpty())
            .put("stable",stable).put("notify",update.notify))
    }

    private fun updateLiveViewport(json: String) {
        if (!liveResumed || importInProgress || !sameOwner() || isFinishing || isDestroyed || json.length > 1024) return
        val rect = runCatching { JSONObject(json) }.getOrNull() ?: return
        val x = rect.optDouble("x", Double.NaN)
        val y = rect.optDouble("y", Double.NaN)
        val width = rect.optDouble("width", Double.NaN)
        val height = rect.optDouble("height", Double.NaN)
        val viewportWidth = rect.optDouble("viewportWidth", Double.NaN)
        if (!listOf(x, y, width, height, viewportWidth).all { it.isFinite() } || viewportWidth <= 0 || web.width <= 0) return
        val scale = web.width / viewportWidth
        val pixelWidth = width * scale
        val pixelHeight = height * scale
        // Keep the full rectangle, even while it scrolls partly offscreen; clipping must not stretch the preview.
        if (pixelWidth < 1 || pixelHeight < 1 || pixelWidth > web.width * 2.0 || pixelHeight > web.height * 2.0 ||
            kotlin.math.abs(x * scale) > web.width * 2.0 || kotlin.math.abs(y * scale) > web.height * 2.0) {
            liveViewportReady = false
            configureLiveCamera()
            return
        }
        val w = pixelWidth.roundToInt().coerceAtLeast(1)
        val h = pixelHeight.roundToInt().coerceAtLeast(1)
        val left = (x * scale).roundToInt()
        val top = (y * scale).roundToInt()
        val current = cameraHost.layoutParams as FrameLayout.LayoutParams
        val changed = current.width != w || current.height != h || current.leftMargin != left || current.topMargin != top
        if (!changed && liveViewportReady) return
        if (changed) {
            guideRequest?.let { request->
                request.slotHistory.reset()
                guideLastFrame=0;guideExpired=false
                // Do not replace heading text here: a line-wrap/layout feedback loop would repeatedly
                // invalidate otherwise good frames. This event only withdraws old spatial geometry.
                callback("onNativeGuidanceViewportChanged",JSONObject().put("token",request.token))
            }
            cameraHost.layoutParams = FrameLayout.LayoutParams(w, h).apply { leftMargin = left; topMargin = top }
        }
        liveViewportReady = true
        configureLiveCamera()
    }

    private fun notifySpeech(state: String, requestToken: String, message: String? = null) {
        if (!::web.isInitialized || isDestroyed || !sameOwner()) return
        callback("onNativeSpeechState", JSONObject().put("state", state).put("token", requestToken).apply {
            if (message != null) put("message", message)
        })
    }

    private val spokenLanguage by lazy { AssemblyLanguage.locale(this) }
    private var speechChoice = AssemblyVoicePreferences.Selection()
    private fun isLocalVoice(voice: Voice?): Boolean = AssemblyLanguage.usableVoice(voice, spokenLanguage)

    private fun initializeSpeech() {
        val currentChoice = AssemblyVoicePreferences.selection(this, spokenLanguage)
        if (speechChoice != currentChoice) {
            releaseSpeechEngine()
            speechChoice = currentChoice
        }
        if (!speechActive || isFinishing || isDestroyed || !sameOwner() || speechEngine != null) return
        val token = ++speechGeneration
        speechReady = false
        speechVoice = null
        speechUnavailableMessage = getString(R.string.voice_starting)
        val timeout = Runnable {
            if (speechActive && speechGeneration == token && !speechReady) {
                releaseSpeechEngine()
                speechUnavailableMessage = getString(R.string.voice_timeout)
            }
        }
        speechInitTimeout = timeout
        speechMain.postDelayed(timeout, 10000)
        try {
            // Post even if initialization fails synchronously, so assignment finishes first.
            speechEngine = TextToSpeech(this, TextToSpeech.OnInitListener { result ->
                speechMain.post {
                    if (speechActive && speechGeneration == token && !isFinishing && !isDestroyed && sameOwner()) {
                        finishSpeechInitialization(token, result)
                    }
                }
            }, speechChoice.engine)
        } catch (_: Exception) {
            releaseSpeechEngine()
            speechUnavailableMessage = getString(R.string.voice_unavailable)
        }
    }

    private fun finishSpeechInitialization(token: Long, result: Int) {
        speechInitTimeout?.let(speechMain::removeCallbacks)
        speechInitTimeout = null
        if (result != TextToSpeech.SUCCESS) {
            releaseSpeechEngine()
            speechUnavailableMessage = getString(R.string.voice_retry)
            return
        }
        val engine = speechEngine ?: return
        try {
            val selected = AssemblyLanguage.chooseVoice(engine, spokenLanguage, speechChoice.voice)
            if (selected == null) {
                releaseSpeechEngine()
                speechUnavailableMessage = getString(R.string.voice_no_pack)
                return
            }
            speechVoice = selected
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                private fun report(id: String?, state: String, message: String? = null, terminal: Boolean = false) {
                    speechMain.post {
                        if (!speechActive || speechGeneration != token || id == null || speechUtterance != id ||
                            isFinishing || isDestroyed || !sameOwner()) return@post
                        val requestToken = speechRequestToken ?: return@post
                        if (terminal) { speechUtterance = null; speechRequestToken = null }
                        notifySpeech(state, requestToken, message)
                    }
                }
                override fun onStart(utteranceId: String?) { report(utteranceId, "speaking") }
                override fun onDone(utteranceId: String?) { report(utteranceId, "finished", terminal = true) }
                @Deprecated("Legacy callback")
                override fun onError(utteranceId: String?) {
                    report(utteranceId, "error", getString(R.string.voice_error), true)
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    report(utteranceId, "error", getString(R.string.voice_error), true)
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    report(utteranceId, "stopped", terminal = true)
                }
            })
            engine.setSpeechRate(0.95f)
            speechReady = true
            val waiting=pendingSpeech;pendingSpeech=null
            if(waiting!=null)speakText(waiting.first,waiting.second)
        } catch (_: Exception) {
            releaseSpeechEngine()
            speechUnavailableMessage = getString(R.string.voice_not_ready)
        }
    }

    private fun speakText(text: String, requestToken: String) {
        if (!speechActive || isFinishing || isDestroyed || !sameOwner() || !AssemblyVoicePreferences.enabled(this)) return
        if (requestToken.isBlank() || requestToken.length > 200) return
        val sentence = text.trim()
        if (sentence.isEmpty() || sentence.length > 500) {
            notifySpeech("error", requestToken, getString(R.string.voice_length))
            return
        }
        // The web narration dispatcher owns sequencing. A stray frame/request must never
        // truncate the playing sentence, even before the local TTS engine has initialized.
        val existingToken = speechRequestToken ?: pendingSpeech?.second
        if (existingToken != null) {
            if (existingToken != requestToken) notifySpeech("busy", requestToken, getString(R.string.voice_busy))
            return
        }
        val engine = speechEngine
        if (!speechReady || engine == null || speechVoice == null) {
            pendingSpeech=sentence to requestToken
            initializeSpeech()
            return
        }
        try {
            // Never let the engine silently switch this request to a network/default voice.
            val actual = engine.voice
            val installed = engine.voices.orEmpty().any { it.name == speechVoice?.name && isLocalVoice(it) }
            if (!installed || !isLocalVoice(actual) || actual?.name != speechVoice?.name) {
                releaseSpeechEngine()
                speechUnavailableMessage = getString(R.string.voice_offline)
                notifySpeech("unavailable", requestToken, speechUnavailableMessage)
                return
            }
            val id = "assembly-guide-$speechGeneration-${++speechSerial}"
            speechUtterance = id
            speechRequestToken = requestToken
            if (engine.speak(sentence, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) {
                speechUtterance = null
                speechRequestToken = null
                notifySpeech("error", requestToken, getString(R.string.voice_request))
            }
        } catch (_: Exception) {
            speechUtterance = null
            speechRequestToken = null
            runCatching { engine.stop() }
            notifySpeech("error", requestToken, getString(R.string.voice_error))
        }
    }

    private fun stopCurrentSpeech(notify: Boolean) {
        val stoppedToken = speechRequestToken ?: pendingSpeech?.second
        pendingSpeech=null
        speechUtterance = null
        speechRequestToken = null
        runCatching { speechEngine?.stop() }
        if (notify && stoppedToken != null) notifySpeech("stopped", stoppedToken)
    }

    private fun releaseSpeechEngine() {
        speechGeneration++
        speechReady = false
        speechVoice = null
        speechUtterance = null
        speechRequestToken = null
        speechInitTimeout = null
        val waiting=pendingSpeech;pendingSpeech=null
        if(waiting!=null)notifySpeech("unavailable",waiting.second,getString(R.string.voice_show_text))
        speechMain.removeCallbacksAndMessages(null)
        val engine = speechEngine
        speechEngine = null
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
    }

    private inner class Bridge {
        @JavascriptInterface fun languageTag(): String = AssemblyLanguage.locale(this@AssemblyGuideActivity).toLanguageTag()
        /** One local preview still for the teaching inset. Detection keeps using the full camera view. */
        @JavascriptInterface fun rowLessonPreview(task:String,token:String,requestId:Int){runOnUiThread {
            val request=guideRequest ?: return@runOnUiThread
            if(request.task.id!=task || request.token!=token || !liveResumed || !liveViewportReady ||
                isFinishing || isDestroyed || !sameOwner())return@runOnUiThread
            val image=liveCamera.preview.bitmap ?: return@runOnUiThread
            io.execute {
                val data=try{
                    val scale=minOf(1.0,288.0/maxOf(image.width,image.height))
                    val small=android.graphics.Bitmap.createScaledBitmap(image,maxOf(1,(image.width*scale).toInt()),
                        maxOf(1,(image.height*scale).toInt()),true)
                    try{
                        val bytes=java.io.ByteArrayOutputStream()
                        if(small.compress(android.graphics.Bitmap.CompressFormat.JPEG,75,bytes))
                            android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP)
                        else null
                    }finally{if(small!==image)small.recycle()}
                }finally{image.recycle()}
                if(data!=null)runOnUiThread {
                    if(liveResumed && guideRequest===request && !isFinishing && !isDestroyed)
                        callback("onRowLessonPreview",JSONObject().put("token",token).put("request",requestId)
                            .put("image","data:image/jpeg;base64,$data"))
                }
            }
        }}
        @JavascriptInterface fun canWriteProgress():Boolean = sameOwner() &&
            storage.getString("revision","unregistered")==revision
        @JavascriptInterface fun publishProgress(raw:String):Boolean =
            sameOwner() && AssemblyProgressSummary.publish(this@AssemblyGuideActivity,raw)
        @JavascriptInterface fun launchPage():String = if(intent.getBooleanExtra("open_progress",false)) "progress" else if(intent.getBooleanExtra("resume_work",false)) "work" else "home"
        @JavascriptInterface fun confirmationFeedback() { runOnUiThread { if(liveResumed&&sameOwner()&&!isFinishing) AssemblyFeedback.confirm(web) } }
        @JavascriptInterface fun feedbackStyle():String = AssemblyColorFeedback.read(this@AssemblyGuideActivity)
        @JavascriptInterface fun setFeedbackStyle(style:String) { AssemblyColorFeedback.save(this@AssemblyGuideActivity,style) }
        @JavascriptInterface fun voiceEnabled()=AssemblyVoicePreferences.enabled(this@AssemblyGuideActivity)
        @JavascriptInterface fun setVoiceEnabled(enabled:Boolean) { runOnUiThread {
            AssemblyVoicePreferences.setEnabled(this@AssemblyGuideActivity,enabled)
            if(!enabled)stopCurrentSpeech(notify=true)
        } }
        @JavascriptInterface fun framingLabel()=if(::liveCamera.isInitialized)liveCamera.framingLabel() else "主摄"
        @JavascriptInterface fun scope()=(owner?:"guest")+":"+revision
        @JavascriptInterface fun registered():String=JSONObject().apply { bindings.forEach { (p,ids)->put(p,org.json.JSONArray(ids)) } }.toString()
        @JavascriptInterface fun importRegistration(){runOnUiThread { beginImport() }}
        @JavascriptInterface fun aiSettings(){runOnUiThread { if(sameOwner())startActivity(Intent(this@AssemblyGuideActivity, AiSettingsActivity::class.java)) }}
        @JavascriptInterface fun aiReview(task: String) { runOnUiThread {
            if (!liveResumed || importInProgress || !sameOwner() || isFinishing || isDestroyed || pending!=null || aiRequest!=null ||
                task.length !in 1..100) { notifyNativeResume();return@runOnUiThread }
            runCatching {
                val context=AiReviewContext.load(this@AssemblyGuideActivity,task)
                stopLiveCamera();stopCurrentSpeech(notify=true)
                aiRequest=task to context.key;aiReturnPending=null
                aiLauncher.launch(Intent(this@AssemblyGuideActivity,AiReviewActivity::class.java)
                    .putExtra("task",task).putExtra("scope",context.key))
                @Suppress("DEPRECATION")
                overridePendingTransition(android.R.anim.fade_in,android.R.anim.fade_out)
            }.onFailure { aiRequest=null;AlertDialog.Builder(this@AssemblyGuideActivity).setTitle(getString(R.string.guide_ai_open))
                .setMessage(getString(R.string.guide_ai_retry)) .setPositiveButton(getString(R.string.common_ok),null)
                .setOnDismissListener { notifyNativeResume() }.show() }
        } }
        @JavascriptInterface fun jointPhoto(task: String, part: String, mode: String) { runOnUiThread {
            if (!liveResumed || importInProgress || !sameOwner() || isFinishing || isDestroyed || pending!=null ||
                mode !in setOf("locate","register") || task.length !in 1..100 || part !in knownParts) return@runOnUiThread
            runCatching {
                val context=JointContext.load(this@AssemblyGuideActivity,task,part)
                stopLiveCamera();stopCurrentSpeech(notify=true)
                startActivity(Intent(this@AssemblyGuideActivity,JointGuideActivity::class.java)
                    .putExtra("task",task).putExtra("part",part).putExtra("mode",mode).putExtra("scope",context.key))
            }.onFailure { AlertDialog.Builder(this@AssemblyGuideActivity).setTitle(getString(R.string.guide_joint_open))
                .setMessage(it.message?:getString(R.string.guide_try_step)).setPositiveButton(getString(R.string.common_ok),null)
                .setOnDismissListener { notifyNativeResume() }.show() }
        } }
        @JavascriptInterface fun liveTarget(part: String, token: String) = startFinder(part, token, null)
        @JavascriptInterface fun liveTargetGroup(part: String, taskId: String, token: String) = startFinder(part, token, taskId)
        private fun startFinder(part: String, token: String, taskId: String?) { runOnUiThread {
            if (!liveResumed || importInProgress || !sameOwner() || isFinishing || isDestroyed || pending != null ||
                part !in knownParts || token.isBlank() || token.length > 200) return@runOnUiThread
            if(guideRequest!=null)stopLiveCamera()
            val group = if (taskId == null) listOf(part) else {
                val tasks = content.getJSONArray("tasks")
                val task = (0 until tasks.length()).map { tasks.getJSONObject(it) }
                    .singleOrNull { it.getString("id") == taskId && it.getString("kind") == "install" } ?: return@runOnUiThread
                val members = task.getJSONArray("members")
                (0 until members.length()).map { members.getString(it) }.takeIf { it.firstOrNull() == part } ?: return@runOnUiThread
            }
            liveRequest = Scan(part, token, group)
            configureLiveCamera()
            if (group.all { bindings[it].isNullOrEmpty() }) reportLiveState("unavailable", "这件零件还没有登记标签")
            else if (!hasCameraPermission()) requestCameraPermission(explicit = false)
        } }
        @JavascriptInterface fun liveGuide(task:String,token:String){runOnUiThread {
            if(!liveResumed||importInProgress||!sameOwner()||isFinishing||isDestroyed||pending!=null||aiRequest!=null||
                task.length !in 1..100||token.length !in 1..200)return@runOnUiThread
            val tasks=content.getJSONArray("tasks")
            val row=(0 until tasks.length()).map{tasks.getJSONObject(it)}.singleOrNull { it.optString("id")==task }?:return@runOnUiThread
            fun ids(name:String)=row.optJSONArray(name)?.let { a->(0 until a.length()).map { a.getString(it) } }.orEmpty()
            stopLiveCamera()
            val (faces,measured)=registrationGeometry()
            guideRequest=GuideScan(BoardLocalGuidance.Task(task,row.getString("kind"),row.getString("group"),
                ids("context"),ids("stationary"),ids("members")),token,faces,measured)
            guideStability.reset();guideMain.post(guideExpiry);configureLiveCamera()
            if(!hasCameraPermission())requestCameraPermission(explicit=false)
        }}
        @JavascriptInterface fun liveViewport(json: String) { runOnUiThread { updateLiveViewport(json) } }
        @JavascriptInterface fun stopLive() { runOnUiThread { stopLiveCamera() } }
        @JavascriptInterface fun switchLens() { runOnUiThread {
            if ((liveRequest != null||guideRequest!=null) && liveResumed && !importInProgress && sameOwner() && !isDestroyed) {
                guideRequest?.slotHistory?.reset();guideStability.reset();guideLastFrame=0;guideExpired=false
                reportGuideState("searching","正在切换镜头","把本步零件放进画面。")
                liveCamera.switchLens()
            }
        } }
        @JavascriptInterface fun cameraPermission() { runOnUiThread { requestCameraPermission(explicit = true) } }
        @JavascriptInterface fun exitGuide() { runOnUiThread {
            stopLiveCamera()
            if (!isDestroyed) finish()
        } }
        @JavascriptInterface fun speak(text: String, token: String) { runOnUiThread { speakText(text, token) } }
        @JavascriptInterface fun stopSpeech() { runOnUiThread {
            if (!isDestroyed) stopCurrentSpeech(notify = true)
        } }
        @JavascriptInterface fun scan(part:String,token:String){runOnUiThread {
            if(!sameOwner()||pending!=null||part !in knownParts||token.length !in 1..200)return@runOnUiThread
            val ids=bindings[part].orEmpty();if(ids.isEmpty()){callback("onNativeScanCancelled",JSONObject().put("token",token));return@runOnUiThread}
            stopLiveCamera()
            pending=Scan(part,token);scanner.launch(Intent(this@AssemblyGuideActivity,AssemblyTargetScanActivity::class.java)
                .putExtra("target_name",part).putExtra("target_ids",ids.toIntArray()).putExtra("request_token",token))
        }}
    }
    override fun onSaveInstanceState(outState:Bundle){
        super.onSaveInstanceState(outState)
        outState.putString("reviewOwner",owner)
        aiRequest?.let { (task,scope)->outState.putString("reviewTask",task);outState.putString("reviewScope",scope) }
        aiReturnPending?.let { outState.putString("pendingReviewReturn",it.toString()) }
    }
    override fun onPause(){
        liveResumed = false
        stopLiveCamera()
        if (::liveCamera.isInitialized) liveCamera.pause()
        stopCurrentSpeech(notify = true);speechActive = false
        // Initialization is not resumed later with an old callback. Ready engines can be reused.
        if (!speechReady) releaseSpeechEngine()
        if(::web.isInitialized){web.evaluateJavascript("window.onNativePause && window.onNativePause();",null);web.onPause()};super.onPause()
    }
    override fun onResume(){
        super.onResume()
        ImmersiveAssembly.hide(window)
        if(::web.isInitialized){if(!sameOwner())finish() else {
            liveResumed=true;liveCamera.resume();web.onResume();speechActive=true;initializeSpeech();notifyNativeResume()
        }}
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus) ImmersiveAssembly.hide(window)
    }
    override fun onDestroy(){
        liveResumed=false;stopLiveCamera()
        if (::liveCamera.isInitialized) liveCamera.release()
        speechActive=false;releaseSpeechEngine();pending=null;io.shutdown()
        if(::web.isInitialized){web.removeJavascriptInterface("AssemblyNative");web.destroy()};super.onDestroy()
    }
    private fun java.io.InputStream.readBytesLimited(limit:Int):ByteArray { val result=java.io.ByteArrayOutputStream();val block=ByteArray(8192);while(true){val n=read(block);if(n<0)break;require(result.size()+n<=limit){getString(R.string.guide_too_large)};result.write(block,0,n)};return result.toByteArray() }
    private fun sha(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it)}
    companion object { private const val PAGE="file:///android_asset/shelf-phone/index.html" }
}
