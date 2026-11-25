package com.example.arassemblydemo

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.min

/** User-pointed planar reference and a new, same-frame camera observation. No assembly completion writes. */
class JointGuideActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AssemblyLanguage.wrap(base))

    private lateinit var scope: JointContext
    private lateinit var store: JointReferenceStore
    private lateinit var camera: JointPhotoCamera
    private lateinit var cameraHost: FrameLayout
    private lateinit var picture: JointPicture
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var primary: Button
    private lateinit var secondary: Button
    private var saved: JointReferenceStore.Saved?=null
    private var frame: JointPhotoFrame?=null
    private var target: PlanarJointMapper.XY?=null
    private var registering=false
    private var previewing=false
    private var active=false
    private var request: String?=null
    private var writing=false
    private val io=Executors.newSingleThreadExecutor()
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted && active) startPreview() else if(::primary.isInitialized) {
            subtitle.text=getString(R.string.joint_permission);primary.isEnabled=true
            primary.text=getString(R.string.ai_ui_allow_camera);primary.setOnClickListener { startPreview() }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state);LightSystemBars.configure(window)
        try {
            scope=JointContext.load(this,intent.getStringExtra("task").orEmpty(),intent.getStringExtra("part").orEmpty())
            require(intent.getStringExtra("scope")==scope.key) { getString(R.string.joint_registration_changed) }
            store=JointReferenceStore(this,scope);saved=store.load()
            registering=intent.getStringExtra("mode")=="register"
        } catch(e:Exception) {
            android.app.AlertDialog.Builder(this).setTitle(getString(R.string.review_reopen)).setMessage(getString(R.string.review_changed))
                .setPositiveButton(getString(R.string.common_back)) { _,_->finish() }.setOnCancelListener { finish() }.show();return
        }
        buildPage()
        camera=JointPhotoCamera(this,cameraHost,::onCameraState,::onPhoto)
        if(!registering && saved==null) {
            title.text=getString(R.string.joint_remember);subtitle.text=getString(R.string.joint_register_intro, partLabel())
            primary.text=getString(R.string.joint_begin);primary.setOnClickListener { registering=true;startPreview() }
            secondary.visibility=View.GONE
        } else {
            title.text=if(registering)getString(R.string.joint_register_here) else getString(R.string.joint_view_here)
            subtitle.text=getString(R.string.joint_three, partLabel())
            primary.text=getString(R.string.joint_open);primary.setOnClickListener { startPreview() };secondary.visibility=View.GONE
        }
    }
    private fun jointReason(reason: String) = getString(when(reason) {
        "OK_REFERENCE" -> R.string.joint_reason_ok_reference
        "OK" -> R.string.joint_reason_ok
        "INVALID_REFERENCE" -> R.string.joint_reason_invalid_reference
        "INVALID_IMAGE" -> R.string.joint_reason_invalid_image
        "DUPLICATE_ID" -> R.string.joint_reason_duplicate_id
        "WRONG_REFERENCE_PART" -> R.string.joint_reason_wrong_reference_part
        "TOO_FEW_REFERENCE_MARKERS" -> R.string.joint_reason_too_few_reference_markers
        "TOO_MANY_REFERENCE_MARKERS" -> R.string.joint_reason_too_many_reference_markers
        "TOO_FEW_MATCHES" -> R.string.joint_reason_too_few_matches
        "INVALID_MARKER" -> R.string.joint_reason_invalid_marker
        "MARKERS_TOO_SMALL" -> R.string.joint_reason_markers_too_small
        "POOR_LAYOUT" -> R.string.joint_reason_poor_layout
        "TARGET_OUTSIDE_IMAGE" -> R.string.joint_reason_target_outside_image
        "TARGET_TOO_FAR" -> R.string.joint_reason_target_too_far
        "UNSTABLE_TRANSFORM" -> R.string.joint_reason_unstable_transform
        "INCONSISTENT_MARKERS" -> R.string.joint_reason_inconsistent_markers
        else -> R.string.joint_reason_unknown
    })
    private fun partLabel(): String {
        var name=scope.part.replace("左侧板",getString(R.string.part_frame)).replace("右侧板",getString(R.string.part_frame)).replace("背板",getString(R.string.part_back))
        listOf(getString(R.string.part_purple),getString(R.string.part_blue),getString(R.string.part_peach),getString(R.string.part_yellow),getString(R.string.part_green)).forEachIndexed { i,s -> name=name.replace("层${i+1}板",s) }
        return name.replace("-1",getString(R.string.part_first)).replace("-2",getString(R.string.part_second))
    }
    private fun buildPage() {
        val outer=FrameLayout(this).apply { setBackgroundColor(Color.WHITE) };LightSystemBars.insetContent(outer)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),dp(16)) }
        outer.addView(root,FrameLayout.LayoutParams(-1,-1))
        root.addView(Button(this).apply { text=getString(R.string.settings_back);isAllCaps=false;setTextColor(ink);backgroundTintList=ColorStateList.valueOf(Color.WHITE)
            setOnClickListener { if(!writing)finish() } },LinearLayout.LayoutParams(-2,dp(44)))
        title=TextView(this).apply { textSize=29f;setTextColor(ink);setTypeface(typeface,Typeface.BOLD);setPadding(0,dp(12),0,dp(12)) };root.addView(title)
        // Keep the preview viewport identical across register/locate and status text changes.
        subtitle=TextView(this).apply { textSize=17f;setTextColor(Color.rgb(99,123,111));setPadding(0,0,0,dp(10)) }
        root.addView(subtitle,LinearLayout.LayoutParams(-1,dp(100)))
        val stage=FrameLayout(this);root.addView(stage,LinearLayout.LayoutParams(-1,0,1f))
        cameraHost=FrameLayout(this).apply { visibility=View.GONE };stage.addView(cameraHost,FrameLayout.LayoutParams(-1,-1))
        picture=JointPicture(this).apply { onPick={ x,y->selectPoint(x,y) } };stage.addView(picture,FrameLayout.LayoutParams(-1,-1))
        secondary=Button(this).apply { isAllCaps=false;setTextColor(Color.rgb(99,123,111));backgroundTintList=ColorStateList.valueOf(Color.WHITE) }
        root.addView(secondary,LinearLayout.LayoutParams(-1,dp(48)))
        primary=Button(this).apply { isAllCaps=false;textSize=19f;setTextColor(Color.WHITE)
            backgroundTintList=ColorStateList.valueOf(Color.rgb(0,165,109)) }
        root.addView(primary,LinearLayout.LayoutParams(-1,dp(58)));setContentView(outer);outer.requestApplyInsets()
    }
    private fun startPreview() {
        if(!active || writing || !scope.isCurrent(this)) return
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.CAMERA);return
        }
        request=null;clearPhoto();previewing=true
        picture.visibility=View.GONE;cameraHost.visibility=View.VISIBLE
        title.text=if(registering)getString(R.string.joint_take) else getString(R.string.joint_angle)
        subtitle.text=getString(R.string.joint_fixed, partLabel())
        primary.text=getString(R.string.joint_capture_face);primary.isEnabled=false;primary.setOnClickListener { capture() }
        secondary.visibility=View.VISIBLE
        secondary.text=if(saved!=null&&!registering)getString(R.string.joint_remark) else getString(R.string.joint_which)
        secondary.setOnClickListener { if(saved!=null&&!registering)confirmRegister() else
            android.app.AlertDialog.Builder(this).setTitle(partLabel())
                .setMessage(getString(R.string.joint_numbers)+scope.ids.sorted().joinToString(" · ") { "%03d".format(it) }+
                    getString(R.string.joint_face_note))
                .setPositiveButton(getString(R.string.common_ok),null).show() }
        camera.resume()
    }
    private fun capture() {
        if(!active||!previewing||request!=null||writing||!scope.isCurrent(this))return
        request=UUID.randomUUID().toString();primary.isEnabled=false;subtitle.text=getString(R.string.joint_capturing)
        if(!camera.capture(request!!)){request=null;primary.isEnabled=true;subtitle.text=getString(R.string.review_camera_busy)}
    }
    private fun onCameraState(state:String,message:String) {
        if(!active||!previewing||writing)return
        if(state=="ready"||state=="streaming")primary.isEnabled=request==null
        if(state=="unavailable"||state=="error"||state=="timeout") {
            request=null;subtitle.text=message;primary.isEnabled=true
        }
    }
    private fun onPhoto(photo:JointPhotoFrame) {
        if(!active||!previewing||photo.token!=request||!scope.isCurrent(this)) { photo.bitmap.recycle();return }
        request=null;previewing=false;camera.pause();cameraHost.visibility=View.GONE
        clearPhoto();frame=photo;picture.visibility=View.VISIBLE;picture.set(photo.bitmap,null,registering)
        primary.isEnabled=false
        secondary.visibility=View.VISIBLE;secondary.text=getString(R.string.joint_retake);secondary.setOnClickListener { startPreview() }
        if(registering) {
            title.text=getString(R.string.joint_tap);subtitle.text=getString(R.string.joint_tap_hint)
            primary.text=getString(R.string.joint_save);primary.setOnClickListener { saveReference() }
            val tags=JointReferenceStore.tags(photo,scope.ids)
            if(tags.map { it.id }.distinct().size<3) { title.text=getString(R.string.joint_more);subtitle.text=getString(R.string.joint_more_hint);picture.editable=false }
        } else locate(photo)
    }
    private fun reference(point:PlanarJointMapper.XY): PlanarJointMapper.Reference {
        val f=frame!!;val tags=JointReferenceStore.tags(f,scope.ids)
        return PlanarJointMapper.Reference(scope.part,tags.map { it.id }.sorted().joinToString(","),scope.ids,
            f.bitmap.width,f.bitmap.height,tags,point)
    }
    private fun selectPoint(x:Double,y:Double) {
        if(!registering||frame==null||writing||!active)return
        val p=PlanarJointMapper.XY(x,y);val result=PlanarJointMapper.validateReference(reference(p))
        target=if(result.point!=null)p else null;picture.set(frame!!.bitmap,p,true,result.point!=null)
        primary.isEnabled=target!=null
        subtitle.text=if(target!=null)getString(R.string.joint_save_hint) else jointReason(result.reason)
    }
    private fun saveReference() {
        val f=frame?:return;val p=target?:return
        if(writing||!active||!scope.isCurrent(this))return
        val ref=reference(p);writing=true;picture.editable=false;primary.isEnabled=false;secondary.isEnabled=false
        subtitle.text=getString(R.string.joint_saving)
        io.execute {
            val result=runCatching { store.save(f,ref) }
            runOnUiThread {
                writing=false
                if(isDestroyed){f.bitmap.recycle();return@runOnUiThread}
                secondary.isEnabled=true;primary.isEnabled=true
                result.onSuccess { saved=it;registering=false;title.text=getString(R.string.joint_saved)
                    subtitle.text=getString(R.string.joint_saved_hint)
                    primary.text=getString(R.string.joint_try_angle);primary.setOnClickListener { startPreview() }
                    secondary.text=getString(R.string.joint_back);secondary.setOnClickListener { finish() }
                }.onFailure { subtitle.text=getString(R.string.joint_save_failed);picture.editable=true }
            }
        }
    }
    private fun locate(photo:JointPhotoFrame) {
        val ref=saved?:return
        val result=if(JointReferenceStore.profile(photo)!=ref.cameraProfile) null else
            PlanarJointMapper.project(ref.reference,JointReferenceStore.tags(photo,scope.ids),photo.bitmap.width,photo.bitmap.height)
        picture.set(photo.bitmap,result?.point,false)
        primary.isEnabled=true
        if(result?.point!=null) {
            title.text=getString(R.string.joint_here);subtitle.text=getString(R.string.joint_here_hint)
            primary.text=getString(R.string.joint_back);primary.setOnClickListener { finish() }
            secondary.text=getString(R.string.joint_new_angle);secondary.setOnClickListener { startPreview() }
        } else {
            title.text=getString(R.string.joint_unseen)
            subtitle.text=result?.let { jointReason(it.reason) }?:getString(R.string.joint_camera_changed)
            primary.text=getString(R.string.ai_ui_retry_camera);primary.setOnClickListener { startPreview() }
            secondary.text=getString(R.string.joint_remark);secondary.setOnClickListener { confirmRegister() }
        }
    }
    private fun confirmRegister() {
        android.app.AlertDialog.Builder(this).setTitle(getString(R.string.joint_replace_question))
            .setMessage(getString(R.string.joint_replace_note))
            .setNegativeButton(getString(R.string.common_cancel),null).setPositiveButton(getString(R.string.joint_replace)) { _,_->registering=true;startPreview() }.show()
    }
    private fun clearPhoto() { picture.set(null,null,false);frame?.bitmap?.let { if(!it.isRecycled)it.recycle() };frame=null;target=null }
    override fun onResume(){super.onResume();active=true;if(::scope.isInitialized&&!scope.isCurrent(this))finish()
        else if(::camera.isInitialized&&previewing)startPreview()}
    override fun onPause(){active=false;request=null;if(::camera.isInitialized)camera.pause();super.onPause()}
    override fun onDestroy(){if(::camera.isInitialized)camera.release();if(::picture.isInitialized&&!writing)clearPhoto();io.shutdown();super.onDestroy()}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private val ink=Color.rgb(27,53,43)

    internal class JointPicture(context:android.content.Context):View(context) {
        private var bitmap:Bitmap?=null;private var point:PlanarJointMapper.XY?=null
        private var valid=true;var editable=false;var onPick:((Double,Double)->Unit)?=null
        private val rect=RectF();private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        fun set(image:Bitmap?,p:PlanarJointMapper.XY?,edit:Boolean,ok:Boolean=true){bitmap=image;point=p;editable=edit;valid=ok;invalidate()}
        override fun onDraw(canvas:Canvas){super.onDraw(canvas);val b=bitmap?:return;if(b.isRecycled)return
            val scale=min(width.toFloat()/b.width,height.toFloat()/b.height)
            val w=b.width*scale;val h=b.height*scale;rect.set((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)
            paint.style=Paint.Style.FILL;paint.alpha=255;canvas.drawBitmap(b,null,rect,paint)
            point?.let { p ->val x=rect.left+p.x.toFloat()*scale;val y=rect.top+p.y.toFloat()*scale
                paint.style=Paint.Style.STROKE;paint.strokeWidth=5f*resources.displayMetrics.density;paint.color=Color.WHITE;canvas.drawCircle(x,y,17f*resources.displayMetrics.density,paint)
                paint.strokeWidth=3f*resources.displayMetrics.density;paint.color=if(valid)Color.rgb(0,166,109) else Color.rgb(210,110,20);canvas.drawCircle(x,y,17f*resources.displayMetrics.density,paint)
            }
        }
        override fun onTouchEvent(event:MotionEvent):Boolean {
            val b=bitmap?:return false;if(!editable)return false
            if(event.action==MotionEvent.ACTION_UP&&rect.contains(event.x,event.y)) {
                performClick();onPick?.invoke(((event.x-rect.left)/rect.width()*b.width).toDouble(),((event.y-rect.top)/rect.height()*b.height).toDouble())
            };return true
        }
        override fun performClick():Boolean { super.performClick();return true }
    }
}
