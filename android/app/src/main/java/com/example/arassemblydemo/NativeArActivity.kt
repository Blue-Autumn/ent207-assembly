package com.example.arassemblydemo

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.opengl.GLES20 as G
import android.opengl.GLES11Ext
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.widget.*
import com.huawei.hiar.*
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.Executors
import java.nio.*
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Separate SDK-owned camera pipeline. No CameraX, marker calibration or CPU face sorting. */
class NativeArActivity: Activity(), GLSurfaceView.Renderer {
    private lateinit var surface: GLSurfaceView
    private lateinit var status: TextView
    @Volatile private var session: ARSession?=null
    private var ready=false
    private var timer: CountDownTimer?=null
    private var anchor: ARAnchor?=null // GL thread, or after it is paused
    private val tap=AtomicReference<Pair<Float,Float>?>(null)
    private var texture=0; private var background=0;private var meshProgram=0
    private var width=1;private var height=1
    private var count=0;private var since=0L;private var previousTimestamp=0L
    private var placed=0
    private var selectedPlane: ARPlane?=null
    private var captureStart=0L
    private var lastSample=0L
    private var lastSave=0L
    private var captureComplete=false
    private val samples=JSONArray()
    private val disk=Executors.newSingleThreadExecutor()
    private val traceName="native-ar-trace-${System.currentTimeMillis()}.json"
    private val quad=buffer(floatArrayOf(-1f,1f,-1f,-1f,1f,1f,1f,-1f))
    private val uv=buffer(floatArrayOf(0f,0f,0f,1f,1f,0f,1f,1f))
    private val mapped=buffer(FloatArray(8))
    private lateinit var mesh:FloatBuffer
    private var vertexCount=0
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(0xff000000.toInt())}
        root.setOnApplyWindowInsetsListener {v,i -> v.setPadding(i.systemWindowInsetLeft,i.systemWindowInsetTop,i.systemWindowInsetRight,i.systemWindowInsetBottom);i}
        status=TextView(this).apply {text="原生空间跟踪试验 · 准备中";setTextColor(-1);textSize=16f;setLines(4)}
        root.addView(status)
        surface=GLSurfaceView(this).apply {setEGLContextClientVersion(2);setEGLConfigChooser(8,8,8,8,16,0);preserveEGLContextOnPause=true;setRenderer(this@NativeArActivity)}
        surface.setOnTouchListener {_,e -> if(e.action==MotionEvent.ACTION_UP) tap.set(e.x to e.y);true}
        root.addView(surface,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(Button(this).apply {text="重新放置：轻点桌面 · 返回主页";setOnClickListener {finish()}})
        setContentView(root);root.requestApplyInsets()
        val dialog=AlertDialog.Builder(this).setTitle("准备自然场景")
            .setMessage("无需标记板。对准真实桌面；轻点一次放置后记录60秒数值诊断，不保存图像。先静止10秒，再缓慢左右移动、返回原位。20秒后开始。")
            .setCancelable(false).setPositiveButton("立即开始",null).setNegativeButton("返回") {_,_->finish()}.create()
        dialog.setOnShowListener {
            fun begin() {timer?.cancel();dialog.dismiss();ready=true;startSession()}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {begin()}
            timer=object:CountDownTimer(20000,1000) {
                override fun onTick(ms:Long) {dialog.setMessage("无需标记板。对准真实桌面。\n${(ms+999)/1000}秒后开始，可立即开始。\n轻点一次放置后记录60秒数值（无图像）。\n先静止10秒，再缓慢左右移动、返回原位。")}
                override fun onFinish() {begin()}
            }.start()
        };dialog.show()
    }
    private fun startSession() {
        if(!ready || isFinishing)return
        if(checkSelfPermission(android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {requestPermissions(arrayOf(android.Manifest.permission.CAMERA),77);return}
        try {
            if(session==null) {
                val created=ARSession(applicationContext)
                val config=ARWorldTrackingConfig(created)
                config.focusMode=ARConfigBase.FocusMode.AUTO_FOCUS
                try {created.configure(config);created.resume()} catch(e:Exception) {created.stop();throw e}
                session=created
            } else {
                session!!.resume()
            }
            surface.onResume()
        } catch(e:Exception) {reportError(e)}
    }
    override fun onRequestPermissionsResult(r:Int,p:Array<out String>,v:IntArray) {super.onRequestPermissionsResult(r,p,v);if(r==77 && v.firstOrNull()==0)startSession()}
    override fun onResume() {super.onResume();if(ready)startSession()}
    override fun onPause() {surface.onPause();session?.pause();super.onPause()}
    override fun onDestroy() {timer?.cancel();anchor?.detach();session?.stop();session=null;disk.shutdown();super.onDestroy()}
    private fun reportError(e:Exception) {
        val text="原生AR未就绪：${e.javaClass.simpleName}: ${e.message}"
        java.io.File(filesDir,"native-ar-status.json").writeText(JSONObject().put("status","ERROR").put("error",text).toString(2))
        runOnUiThread {status.text=text}
    }
    override fun onSurfaceCreated(gl:GL10?,config:EGLConfig?) {
        try {
            val names=IntArray(1);G.glGenTextures(1,names,0);texture=names[0]
            G.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
            G.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,G.GL_TEXTURE_MIN_FILTER,G.GL_LINEAR)
            G.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,G.GL_TEXTURE_MAG_FILTER,G.GL_LINEAR)
            G.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,G.GL_TEXTURE_WRAP_S,G.GL_CLAMP_TO_EDGE)
            G.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,G.GL_TEXTURE_WRAP_T,G.GL_CLAMP_TO_EDGE)
            background=program("attribute vec2 p;attribute vec2 uv;varying vec2 t;void main(){gl_Position=vec4(p,0.,1.);t=uv;}","#extension GL_OES_EGL_image_external : require\nprecision mediump float;varying vec2 t;uniform samplerExternalOES tex;void main(){gl_FragColor=texture2D(tex,t);}")
            meshProgram=program("attribute vec3 p;attribute float shade;uniform mat4 mvp;varying float s;void main(){gl_Position=mvp*vec4(p,1.);s=shade;}","precision mediump float;varying float s;void main(){gl_FragColor=vec4(vec3(s),1.);}")
            loadMesh();since=SystemClock.elapsedRealtimeNanos()
        }catch(e:Exception){reportError(e)}
    }
    override fun onSurfaceChanged(gl:GL10?,w:Int,h:Int) {width=w;height=h;G.glViewport(0,0,w,h)}
    override fun onDrawFrame(gl:GL10?) {
        G.glClear(G.GL_COLOR_BUFFER_BIT or G.GL_DEPTH_BUFFER_BIT)
        val s=session?:return
        try {
            s.setDisplayGeometry(windowManager.defaultDisplay.rotation,width,height)
            s.setCameraTextureName(texture)
            val frame=s.update();val camera=frame.camera
            uv.position(0);mapped.position(0);frame.transformDisplayUvCoords(uv,mapped);mapped.position(0)
            G.glDisable(G.GL_DEPTH_TEST);G.glDepthMask(false);G.glUseProgram(background)
            G.glActiveTexture(G.GL_TEXTURE0);G.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
            attribute(background,"p",quad,2,0);attribute(background,"uv",mapped,2,0)
            G.glUniform1i(G.glGetUniformLocation(background,"tex"),0);G.glDrawArrays(G.GL_TRIANGLE_STRIP,0,4)
            G.glDisableVertexAttribArray(G.glGetAttribLocation(background,"p"));G.glDisableVertexAttribArray(G.glGetAttribLocation(background,"uv"))
            val tracking=camera.trackingState==ARTrackable.TrackingState.TRACKING
            tap.getAndSet(null)?.let {xy -> if(tracking && anchor==null) {
                val hit=frame.hitTest(xy.first,xy.second).firstOrNull {it.trackable is ARPlane && (it.trackable as ARPlane).isPoseInPolygon(it.hitPose)}
                if(hit!=null) {anchor=hit.createAnchor();selectedPlane=hit.trackable as ARPlane;placed++;captureStart=SystemClock.elapsedRealtimeNanos()}
            } }
            val a=anchor
            if(tracking && a!=null && a.trackingState==ARTrackable.TrackingState.TRACKING && ::mesh.isInitialized) {
                val view=FloatArray(16);val projection=FloatArray(16);val model=FloatArray(16);val vp=FloatArray(16);val mvp=FloatArray(16)
                camera.getViewMatrix(view,0);camera.getProjectionMatrix(projection,0,0.01f,100f);a.pose.toMatrix(model,0)
                Matrix.multiplyMM(vp,0,projection,0,view,0);Matrix.multiplyMM(mvp,0,vp,0,model,0)
                G.glEnable(G.GL_DEPTH_TEST);G.glDepthMask(true);G.glUseProgram(meshProgram)
                mesh.position(0);attribute(meshProgram,"p",mesh,3,16)
                mesh.position(3);attribute(meshProgram,"shade",mesh,1,16)
                G.glUniformMatrix4fv(G.glGetUniformLocation(meshProgram,"mvp"),1,false,mvp,0)
                G.glDrawArrays(G.GL_TRIANGLES,0,vertexCount)
                G.glDisableVertexAttribArray(G.glGetAttribLocation(meshProgram,"p"));G.glDisableVertexAttribArray(G.glGetAttribLocation(meshProgram,"shade"))
            }
            G.glDepthMask(true)
            if(frame.timestampNs!=previousTimestamp) {count++;previousTimestamp=frame.timestampNs}
            val now=SystemClock.elapsedRealtimeNanos()
            if(captureStart>0L && !captureComplete && now-lastSample>=100_000_000L) {
                fun matrix(pose: ARPose): JSONArray {val m=FloatArray(16);pose.toMatrix(m,0);return JSONArray(m.toList())}
                val v=FloatArray(16);val p=FloatArray(16)
                camera.getViewMatrix(v,0);camera.getProjectionMatrix(p,0,0.01f,100f)
                val coords=FloatArray(8);mapped.position(0);mapped.get(coords);mapped.position(0)
                val row=JSONObject().put("elapsed_ns",now-captureStart).put("host_monotonic_ns",now)
                    .put("camera_timestamp_ns",frame.timestampNs).put("camera_state",camera.trackingState.toString())
                    .put("camera_pose",matrix(camera.pose)).put("view",JSONArray(v.toList())).put("projection",JSONArray(p.toList()))
                    .put("anchor_state",a?.trackingState?.toString()).put("anchor_pose",a?.let {matrix(it.pose)})
                    .put("plane_state",selectedPlane?.trackingState?.toString()).put("plane_pose",selectedPlane?.let {matrix(it.centerPose)})
                    .put("viewport",JSONArray(listOf(width,height))).put("rotation",windowManager.defaultDisplay.rotation)
                    .put("background_uv",JSONArray(coords.toList())).put("placements",placed).put("gl_error",G.glGetError())
                samples.put(row);lastSample=now
                captureComplete=now-captureStart>=60_000_000_000L
                if(captureComplete || now-lastSave>=5_000_000_000L) {
                    val snapshot=JSONObject().put("schema",1).put("complete",captureComplete)
                        .put("timestamp_alignment","UNKNOWN_NOT_LATENCY").put("matrix_order","column_major; pose translations metres")
                        .put("samples",samples).toString()
                    disk.execute {java.io.File(filesDir,traceName).writeText(snapshot)}
                    lastSave=now
                }
            }
            if(now-since>1_000_000_000L) {
                val fps=count*1e9/(now-since);val planes=s.getAllTrackables(ARPlane::class.java).count {it.trackingState==ARTrackable.TrackingState.TRACKING}
                val info=JSONObject().put("status",camera.trackingState.toString()).put("timestamp",frame.timestampNs).put("unique_camera_fps",fps).put("planes",planes).put("placements",placed).put("anchor",a?.trackingState?.toString()?:"NONE").put("model_triangles",vertexCount/3).put("lens","SDK_DEFAULT_UNVERIFIED").put("trace",traceName).put("trace_samples",samples.length()).put("trace_complete",captureComplete)
                java.io.File(filesDir,"native-ar-status.json").writeText(info.toString(2))
                val diagnostic=if(captureComplete) "60秒记录完成，可返回" else if(captureStart>0) "数值诊断 ${(now-captureStart)/1_000_000_000}/60秒 · 不保存图像" else "轻点一次开始；返回后可重新放置"
                runOnUiThread {status.text="原生AR · ${if(tracking) "跟踪中" else "寻找环境特征"} · %.1f fps\n平面 %d · %s\n%s".format(fps,planes,if(a==null) "缓慢移动寻找桌面" else "已锁定放置，重复触摸无效",diagnostic)}
                count=0;since=now
            }
        }catch(e:Exception){reportError(e)}
    }
    private fun loadMesh() {
        val b=ByteBuffer.wrap(assets.open("models/pin.glb").use {it.readBytes()}).order(ByteOrder.LITTLE_ENDIAN)
        require(b.int==0x46546c67 && b.int==2);b.int;val n=b.int;b.int;val bytes=ByteArray(n);b.get(bytes);val j=JSONObject(String(bytes));b.int;b.int;val base=b.position()
        val access=j.getJSONArray("accessors");val views=j.getJSONArray("bufferViews")
        val p=access.getJSONObject(0);b.position(base+views.getJSONObject(p.getInt("bufferView")).getInt("byteOffset"));val vertices=FloatArray(p.getInt("count")*3){b.float}
        val indices=access.getJSONObject(1);b.position(base+views.getJSONObject(indices.getInt("bufferView")).getInt("byteOffset"));val idx=IntArray(indices.getInt("count")){b.int}
        vertexCount=idx.size;val data=FloatArray(vertexCount*4)
        for(f in idx.indices step 3) {
            val a=idx[f]*3;val c=idx[f+1]*3;val d=idx[f+2]*3
            val ux=vertices[c]-vertices[a];val uy=vertices[c+1]-vertices[a+1];val uz=vertices[c+2]-vertices[a+2]
            val vx=vertices[d]-vertices[a];val vy=vertices[d+1]-vertices[a+1];val vz=vertices[d+2]-vertices[a+2]
            val nx=uy*vz-uz*vy;val ny=uz*vx-ux*vz;val nz=ux*vy-uy*vx
            val shade=.35f+.35f*kotlin.math.abs(ny)/kotlin.math.sqrt(nx*nx+ny*ny+nz*nz).coerceAtLeast(1e-9f)
            for(k in 0..2) {val v=idx[f+k]*3;val o=(f+k)*4;for(q in 0..2)data[o+q]=vertices[v+q];data[o+3]=shade}
        };mesh=buffer(data)
    }
    private fun attribute(program:Int,name:String,data:FloatBuffer,size:Int,stride:Int) {val loc=G.glGetAttribLocation(program,name);G.glEnableVertexAttribArray(loc);G.glVertexAttribPointer(loc,size,G.GL_FLOAT,false,stride,data)}
    private fun program(v:String,f:String):Int {
        fun shader(type:Int,source:String):Int {val id=G.glCreateShader(type);G.glShaderSource(id,source);G.glCompileShader(id);val ok=IntArray(1);G.glGetShaderiv(id,G.GL_COMPILE_STATUS,ok,0);check(ok[0]!=0){G.glGetShaderInfoLog(id)};return id}
        val vs=shader(G.GL_VERTEX_SHADER,v);val fs=shader(G.GL_FRAGMENT_SHADER,f);val p=G.glCreateProgram();G.glAttachShader(p,vs);G.glAttachShader(p,fs);G.glLinkProgram(p);val ok=IntArray(1);G.glGetProgramiv(p,G.GL_LINK_STATUS,ok,0);check(ok[0]!=0){G.glGetProgramInfoLog(p)};G.glDeleteShader(vs);G.glDeleteShader(fs);return p
    }
    companion object {fun buffer(a:FloatArray):FloatBuffer=ByteBuffer.allocateDirect(a.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {put(a);position(0)}}
}
