package com.example.arassemblydemo

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.*

/** Manual, frozen observations. No background capture, no implicit assembly acceptance. */
class PartProbeActivity: ComponentActivity() {
    private lateinit var preview:PreviewView
    private lateinit var frozen:ProbeView
    private lateinit var status:TextView
    private lateinit var labels:Spinner
    private lateinit var role:Spinner
    private lateinit var shoot:Button
    private lateinit var learn:Button
    private lateinit var inspect:Button
    private var provider:ProcessCameraProvider?=null
    private var capture:ImageCapture?=null
    private val worker=Executors.newSingleThreadExecutor()
    private var wide=true
    private var cameraId="UNKNOWN"
    private var photo:Bitmap?=null
    private var pixels=IntArray(0)
    private var record:JSONObject?=null
    private var sampleDir:File?=null
    private var generation=0
    private var busy=false
    private val references=ArrayList<PartProbeMath.Reference>()
    private val names=arrayOf("红色","绿色","橘黄色","亮黄色","蓝色","白色","黑色","粉色","紫色","灰色","背景")
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()){if(it)bind() else status.text="需要相机权限才能取景。返回主页后可重试。"}
    private fun button(text:String,run:()->Unit)=Button(this).apply{this.text=text;isAllCaps=false;setOnClickListener{run()}}
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.WHITE)}
        root.setOnApplyWindowInsetsListener{v,i->@Suppress("DEPRECATION") v.setPadding(i.systemWindowInsetLeft,i.systemWindowInsetTop,i.systemWindowInsetRight,i.systemWindowInsetBottom);i}
        status=TextView(this).apply{textSize=14f;setPadding(12,6,12,6);text="实物观察试验：取一帧，再点零件内部。只估计颜色候选和画面长轴，不判断装对或三维朝向。"}
        root.addView(status,LinearLayout.LayoutParams(-1,dp(100)))
        val space=FrameLayout(this)
        preview=PreviewView(this).apply{scaleType=PreviewView.ScaleType.FIT_CENTER;implementationMode=PreviewView.ImplementationMode.COMPATIBLE}
        frozen=ProbeView().apply{visibility=View.GONE}
        space.addView(preview,FrameLayout.LayoutParams(-1,-1));space.addView(frozen,FrameLayout.LayoutParams(-1,-1))
        root.addView(space,LinearLayout.LayoutParams(-1,0,1f))
        val settings=LinearLayout(this)
        labels=Spinner(this).apply{adapter=ArrayAdapter(this@PartProbeActivity,android.R.layout.simple_spinner_dropdown_item,names)}
        role=Spinner(this).apply{adapter=ArrayAdapter(this@PartProbeActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("建库样本","独立验证"))}
        settings.addView(labels,LinearLayout.LayoutParams(0,-2,1f));settings.addView(role,LinearLayout.LayoutParams(0,-2,1f));root.addView(settings)
        val row=LinearLayout(this)
        shoot=button("取一帧并保存"){confirmCapture()};learn=button("记住此颜色"){learnColor()};inspect=button("分析选中区域"){analyze()}
        for(b in listOf(shoot,learn,inspect))row.addView(b,LinearLayout.LayoutParams(0,-2,1f));root.addView(row)
        val last=LinearLayout(this)
        last.addView(button("重新取景"){if(!busy){generation++;photo=null;record=null;sampleDir=null;frozen.bitmap=null;frozen.visibility=View.GONE;preview.visibility=View.VISIBLE;bind()}},LinearLayout.LayoutParams(0,-2,1f))
        last.addView(button("切换主摄/广角"){if(!busy){wide=!wide;generation++;photo=null;record=null;sampleDir=null;frozen.visibility=View.GONE;bind()}},LinearLayout.LayoutParams(0,-2,1f))
        last.addView(button("返回"){finish()},LinearLayout.LayoutParams(0,-2,1f));root.addView(last)
        root.addView(button("新建干净颜色库（保留旧记录）"){newLibrary()})
        root.addView(TextView(this).apply{text="手动取帧保存至本机，可导出电脑；不录像、不上传。先采不同颜色作建库，再换视角独立验证。";textSize=12f})
        setContentView(root)
        loadReferences();controls()
        if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)bind()else permission.launch(Manifest.permission.CAMERA)
    }
    private fun dp(n:Int)=(resources.displayMetrics.density*n).toInt()
    private fun controls(){shoot.isEnabled=!busy&&photo==null&&capture!=null;learn.isEnabled=!busy&&photo!=null&&record?.optString("role")=="建库样本";inspect.isEnabled=!busy&&photo!=null;labels.isEnabled=!busy&&photo==null;role.isEnabled=!busy&&photo==null}
    private fun confirmCapture(){
        if(busy||photo!=null)return
        val label=labels.selectedItem.toString();val mode=role.selectedItem.toString()
        AlertDialog.Builder(this).setTitle("本次拍摄：$label · $mode")
            .setMessage(if(mode=="建库样本")"请确认眼前实物就是 $label。换了零件后要先换颜色；保存后，本张标签固定。" else "请确认眼前实物就是 $label。这张只用于验证，不会写入颜色库。")
            .setNegativeButton("返回修改",null).setPositiveButton("确认并拍摄"){_,_->take()}.show()
    }
    private fun newLibrary(){
        if(busy||photo!=null){status.text="请先点重新取景，再新建颜色库。";return}
        AlertDialog.Builder(this).setTitle("重新建立颜色库？")
            .setMessage("旧颜色库另存备份，照片和结果保留。新库需要重新记录至少两种颜色；以前录错的颜色不会继续参与判断。")
            .setNegativeButton("取消",null).setPositiveButton("备份并新建"){_,_->
                try{val file=referenceFile();if(file.exists())file.copyTo(File(file.parentFile,"references-backup-${UUID.randomUUID()}.json"));file.parentFile!!.mkdirs();file.writeText("[]");references.clear();status.text="新颜色库已开始。先采不同颜色的建库样本，再切独立验证。"}
                catch(e:Exception){status.text="未能新建颜色库：${e.message}"}
            }.show()
    }
    private fun bind(){
        if(photo!=null||isFinishing)return
        val gen=++generation;capture=null;controls()
        val future=ProcessCameraProvider.getInstance(this)
        future.addListener({if(gen!=generation||isFinishing)return@addListener
            try{val p=future.get();provider=p;p.unbindAll();val plan=LensChoice.choose(p.availableCameraInfos,wide);cameraId=plan.cameraId
                val view=Preview.Builder().build().also{it.setSurfaceProvider(preview.surfaceProvider)}
                @Suppress("DEPRECATION") val shot=ImageCapture.Builder().setTargetResolution(android.util.Size(1280,960)).setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                p.bindToLifecycle(this,plan.selector,view,shot);capture=shot;preview.visibility=View.VISIBLE
                status.text="${if(plan.isWide)"广角" else "主摄"} 相机 $cameraId · 颜色参考 ${references.size} 条\n停稳后取一帧，再点实物内部；按钮不会自动拍照。";controls()
            }catch(e:Exception){status.text="相机启动失败：${e.message}";controls()}
        },ContextCompat.getMainExecutor(this))
    }
    private fun take(){
        val cap=capture?:return;if(busy||photo!=null)return
        busy=true;controls();status.text="正在手动取帧…";val gen=generation;val lens=cameraId
        val selectedRole=role.selectedItem.toString();val truth=labels.selectedItem.toString()
        cap.takePicture(worker,object:ImageCapture.OnImageCapturedCallback(){
            override fun onCaptureSuccess(image:ImageProxy){
                try{
                    val rotation=image.imageInfo.rotationDegrees;val timestamp=image.imageInfo.timestamp
                    val raw=image.toBitmap();val cropped=Bitmap.createBitmap(raw,image.cropRect.left,image.cropRect.top,image.cropRect.width(),image.cropRect.height())
                    val upright=Bitmap.createBitmap(cropped,0,0,cropped.width,cropped.height,Matrix().apply{postRotate(rotation.toFloat())},true)
                    val scale=min(1.0,640.0/max(upright.width,upright.height));val bitmap=Bitmap.createScaledBitmap(upright,max(1,(upright.width*scale).toInt()),max(1,(upright.height*scale).toInt()),true)
                    val id=UUID.randomUUID().toString();val dir=File(filesDir,"part-probe/$id").apply{mkdirs()}
                    File(dir,"photo.jpg").outputStream().use{upright.compress(Bitmap.CompressFormat.JPEG,95,it)}
                    val info=JSONObject().put("schema","part-observation-v1").put("observation_id",id).put("camera_id",lens).put("role",selectedRole).put("user_expected_color",truth)
                        .put("timestamp_ns",timestamp).put("timestamp_clock","UNVERIFIED_NOT_LATENCY").put("rotation_applied",rotation).put("photo_width",upright.width).put("photo_height",upright.height)
                        .put("analysis_width",bitmap.width).put("analysis_height",bitmap.height).put("registration","NONE").put("pose_6dof","NOT_ESTIMATED").put("selection","USER_TAP_REQUIRED")
                        .put("pipeline","ImageCapture JPEG; automatic exposure/white balance/focus; NOT the calibrated analysis stream").put("references_at_capture",referenceJson())
                    File(dir,"observation.json").writeText(info.toString(2))
                    runOnUiThread{if(gen!=generation||isDestroyed){busy=false;return@runOnUiThread};photo=bitmap;pixels=IntArray(bitmap.width*bitmap.height).also{bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height)};record=info;sampleDir=dir;frozen.bitmap=bitmap;frozen.selection=null;frozen.axis=null;frozen.visibility=View.VISIBLE;frozen.invalidate();provider?.unbindAll();capture=null;busy=false;controls();status.text="已保存本次取景。点一下零件内部（避开反光、边界、槽口），再记颜色或分析。\n仅用户指定区域，尚未自动定位零件。"}
                }catch(e:Exception){runOnUiThread{busy=false;controls();status.text="采集失败：${e.message}"}}finally{image.close()}
            }
            override fun onError(e:ImageCaptureException){runOnUiThread{busy=false;controls();status.text="取帧失败：${e.message}"}}
        })
    }
    private fun referenceFile()=File(filesDir,"part-probe/references.json")
    private fun referenceJson()=JSONArray().apply{references.forEach{put(JSONObject().put("label",it.label).put("lab",JSONArray(it.lab.toList())))}}
    private fun loadReferences(){try{val a=JSONArray(referenceFile().takeIf{it.exists()}?.readText()?:"[]");for(i in 0 until a.length()){val o=a.getJSONObject(i);references+=PartProbeMath.Reference(o.getString("label"),DoubleArray(3){o.getJSONArray("lab").getDouble(it)})}}catch(_:Exception){status.text="颜色库无法读取；未使用损坏数据。"}}
    private fun writeRecord(){sampleDir?.let{File(it,"observation.json").writeText(record!!.toString(2))}}
    private fun learnColor(){
        val b=photo?:return;val point=frozen.selection?:run{status.text="请先点零件内部。";return}
        if(record?.optString("role")!="建库样本"){status.text="这张是独立验证图，不能用于建库。请重新取景并选择建库样本。";return}
        if(references.size>=200){status.text="参考已到200条，本轮请用独立验证。";return}
        if(record!!.has("training_label")){status.text="这张已记入颜色库，不重复添加。请重新取景。";return}
        val lab=PartProbeMath.patch(pixels,b.width,b.height,point.x.toInt(),point.y.toInt());val label=record!!.getString("user_expected_color")
        val swatch=View(this).apply{setBackgroundColor(pixels[point.y.toInt()*b.width+point.x.toInt()]);minimumHeight=dp(60)}
        AlertDialog.Builder(this).setTitle("将选中区域记为：$label")
            .setMessage("下方是选中位置的照片颜色。请核对实物名称；如果选错，请取消并重新取景。")
            .setView(swatch).setNegativeButton("取消",null).setPositiveButton("确认记录 $label"){_,_->
        if(photo!==b||busy||isFinishing||record!!.has("training_label"))return@setPositiveButton
        references+=PartProbeMath.Reference(label,lab);referenceFile().parentFile!!.mkdirs();referenceFile().writeText(referenceJson().toString(2))
        record!!.put("training_label",label).put("training_point",JSONArray(listOf(point.x,point.y))).put("training_lab",JSONArray(lab.toList()));writeRecord()
        status.text="已记录 $label，第 ${references.count{it.label==label}} 条。请换角度或另一颜色；建库图上的识别不算独立验证。"
        }.show()
    }
    private fun analyze(){
        val b=photo?:return;val p=frozen.selection?:run{status.text="请先点零件内部。";return};if(busy)return
        busy=true;controls();val gen=generation;val rgb=pixels.copyOf();val refs=references.toList()
        worker.execute{try{
            val start=System.nanoTime();val sample=PartProbeMath.patch(rgb,b.width,b.height,p.x.toInt(),p.y.toInt());val match=PartProbeMath.match(sample,refs)
            val area=PartProbeMath.region(rgb,b.width,b.height,p.x.toInt(),p.y.toInt());val fraction=area.pixels.size.toDouble()/rgb.size
            val usable=area.pixels.size>=64&&fraction<.35&&!area.touchesEdge&&area.elongation>=2.0
            val markers=detectMarkers(b)
            val result=JSONObject().put("schema","assisted-color-axis-v1").put("user_tap",JSONArray(listOf(p.x,p.y))).put("sample_lab",JSONArray(sample.toList()))
                .put("color_candidate",match.label?:"UNKNOWN").put("nearest_deltaE76",match.distance).put("label_margin",match.margin)
                .put("axis_status",if(usable)"IMAGE_AXIS_ONLY_180_DEG_AMBIGUOUS" else "UNKNOWN").put("image_axis_deg",if(usable)area.angle else JSONObject.NULL)
                .put("region_pixels",area.pixels.size).put("region_fraction",fraction).put("edge_clipped",area.touchesEdge).put("elongation",area.elongation).put("whole_object_completeness","NOT_ESTIMATED")
                .put("markers",markers).put("references_used",JSONArray().apply{refs.forEach{put(JSONObject().put("label",it.label).put("lab",JSONArray(it.lab.toList())))}})
                .put("elapsed_ms",(System.nanoTime()-start)/1e6).put("pose_6dof","NOT_ESTIMATED").put("part_type","NOT_ESTIMATED").put("assembly_correct","NOT_ESTIMATED")
            runOnUiThread{if(gen!=generation||isDestroyed)return@runOnUiThread
                record!!.put("analysis",result);writeRecord();frozen.axis=if(usable)area else null;frozen.invalidate();busy=false;controls()
                status.text="颜色候选：${match.label?:"不确定"}（不是零件身份）\n${if(usable)"选中色块轴 %.1f°；不代表整件朝向".format(area.angle) else "色块长轴不确定，请换角度或背景"}\nArUco ${markers.length()} 个 · 未估计三维朝向/装配状态 · ${"%.0f".format(result.getDouble("elapsed_ms"))}ms"
            }
        }catch(e:Exception){runOnUiThread{busy=false;controls();status.text="分析失败：${e.message}"}}}
    }
    private fun detectMarkers(b:Bitmap):JSONArray {
        ArucoProcessor.initialize();val rgba=Mat();val gray=Mat();val ids=Mat();val corners=ArrayList<Mat>();val rejects=ArrayList<Mat>()
        val detector=ArucoDetector(Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50),DetectorParameters().apply{set_cornerRefinementMethod(Objdetect.CORNER_REFINE_SUBPIX)})
        try{Utils.bitmapToMat(b,rgba);Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY);detector.detectMarkers(gray,corners,ids,rejects)
            return JSONArray().apply{corners.forEachIndexed{i,c->val pts=FloatArray(8);c.get(0,0,pts);put(JSONObject().put("id",ids.get(i,0)[0].toInt()).put("corners_analysis_px",JSONArray(pts.toList())).put("min_side_px",(0..3).minOf{k->hypot((pts[k*2]-pts[((k+1)%4)*2]).toDouble(),(pts[k*2+1]-pts[((k+1)%4)*2+1]).toDouble())}))}}
        }finally{rgba.release();gray.release();ids.release();corners.forEach{it.release()};rejects.forEach{it.release()};detector.clear()}
    }
    override fun onDestroy(){generation++;provider?.unbindAll();worker.shutdown();super.onDestroy()}
    private inner class ProbeView:View(this@PartProbeActivity){
        var bitmap:Bitmap?=null;var selection:PointF?=null;var axis:PartProbeMath.Region?=null
        private val pen=Paint(Paint.ANTI_ALIAS_FLAG)
        private fun rect(b:Bitmap):RectF{val s=min(width.toFloat()/b.width,height.toFloat()/b.height);val w=b.width*s;val h=b.height*s;return RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)}
        override fun onDraw(c:Canvas){c.drawColor(Color.BLACK);val b=bitmap?:return;val r=rect(b);pen.style=Paint.Style.FILL;c.drawBitmap(b,null,r,pen);val s=r.width()/b.width
            selection?.let{pen.color=Color.CYAN;pen.style=Paint.Style.STROKE;pen.strokeWidth=3f;c.drawCircle(r.left+it.x*s,r.top+it.y*s,12f,pen)}
            axis?.let{val a=it.angle*PI/180;val len=40*s;val x=r.left+it.cx.toFloat()*s;val y=r.top+it.cy.toFloat()*s;pen.color=Color.MAGENTA;pen.strokeWidth=4f;c.drawLine(x-(cos(a)*len).toFloat(),y-(sin(a)*len).toFloat(),x+(cos(a)*len).toFloat(),y+(sin(a)*len).toFloat(),pen)}
        }
        override fun onTouchEvent(e:MotionEvent):Boolean{if(e.action==MotionEvent.ACTION_UP&&!busy){val b=bitmap?:return true;val r=rect(b);if(r.contains(e.x,e.y)){selection=PointF((e.x-r.left)*b.width/r.width(),(e.y-r.top)*b.height/r.height());axis=null;invalidate();status.text="已选区域。建库：选颜色→记住此颜色；验证：分析选中区域。"};performClick()};return true}
        override fun performClick():Boolean{super.performClick();return true}
    }
}
