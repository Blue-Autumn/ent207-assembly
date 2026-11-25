package com.example.arassemblydemo

import android.Manifest
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/** Current-step capture and advice. No access to pickup/completion writers. */
class AiReviewActivity:ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AssemblyLanguage.wrap(base))

    private lateinit var scope:AiReviewContext
    private lateinit var camera:JointPhotoCamera
    private lateinit var speech:AiReplySpeech
    private lateinit var outer:FrameLayout
    private lateinit var root:LinearLayout
    private lateinit var title:TextView
    private lateinit var subtitle:TextView
    private lateinit var cameraHost:FrameLayout
    private lateinit var image:ImageView
    private lateinit var photoCue:AiPhotoCueView
    private var resultCue:AiPhotoCue.Lesson?=null
    private var resumeCuePhoto=false
    private lateinit var imageToggle:Button
    private lateinit var thumbnailTray:HorizontalScrollView
    private lateinit var thumbnailStrip:LinearLayout
    private lateinit var primary:Button
    private lateinit var secondary:Button
    private lateinit var speaker:ImageButton
    private val session=AiReviewSession()
    private var frame:JointPhotoFrame?=null
    private var annotated:Bitmap?=null
    private data class Shot(val frame:JointPhotoFrame,val annotated:Bitmap){
        var localCheck:JSONObject?=null
    }
    private val shots=mutableListOf<Shot>()
    private var selectedShot=0
    private lateinit var lensButton:Button
    private var previewSpoken=false
    private var showAnnotation=true
    private var captureToken:String?=null
    private var captureTicket=0L
    private var active=false
    private var previewing=false
    private var sending=false
    private var client:AiReviewClient?=null
    private var result:AiReviewResult?=null
    private var resultOrientation:String?=null
    private var bgAnimation:ValueAnimator?=null
    private var bgColor=Color.rgb(250,249,243)
    private var permissionPrompt=false
    private var permissionDenied=false
    private val annotations=Executors.newSingleThreadExecutor()
    private val io=Executors.newSingleThreadExecutor()
    private val layerInstall get()=LayerPhotoReview.applies(scope.task)
    private val automaticQuestion get()=if(layerInstall) "请先判断当前层板缺口方向；灰框槽处若有清楚可见的抬起或离缝才指出。方向清楚就可以直接回答。"
        else "只看当前目标及它正要连接的接口。彩色层板A/B面可混搭，保留缺口方向；侧框按相对接口判断，不能单凭A/B字母报错。连续闭合的细缝不算未拼好；已接好就说明可见处接合，不必再叫用户平移。指出当前最有价值的一项操作，旧组件不要盖过当前目标。"
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        permissionPrompt=false;permissionDenied=!granted
        if(granted&&active)startPreview() else if(!granted&&::primary.isInitialized)showPermission()
    }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);LightSystemBars.configure(window);ImmersiveAssembly.configure(window)
        try{scope=AiReviewContext.load(this,intent.getStringExtra("task").orEmpty());require(scope.key==intent.getStringExtra("scope"))}
        catch(_:Exception){AlertDialog.Builder(this).setTitle(getString(R.string.review_reopen)).setMessage(getString(R.string.review_changed))
            .setPositiveButton(getString(R.string.common_back)){_,_->finish()}.setOnCancelListener{finish()}.show();return}
        buildPage();camera=JointPhotoCamera(this,cameraHost,::onCameraState,::onPhoto,fillStage=true)
        speech=AiReplySpeech(this){_,_->refreshSpeaker()}
        speaker.setOnClickListener{
            val enabled=!AssemblyVoicePreferences.enabled(this)
            AssemblyVoicePreferences.setEnabled(this,enabled);refreshSpeaker()
            if(enabled)speech.speak(result?.let(::spokenAdvice)?:subtitle.text.toString()) else speech.stop()
        }
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){returnToAssembly()}})
    }

    private fun buildPage(){
        outer=FrameLayout(this).apply{setBackgroundColor(paper)};ImmersiveAssembly.safeControls(outer)
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        outer.addView(root,FrameLayout.LayoutParams(-1,-1))
        val heightDp=resources.configuration.screenHeightDp
        val topHeight=when { heightDp<=500 -> 44; heightDp<=620 -> 48; else -> 56 }
        val headerHeight=when { heightDp<=500 -> 98; heightDp<=620 -> 122; else -> 142 }
        val compact=heightDp<=620
        val footerHeight=if(compact)112 else 132
        val optionsHeight=if(compact)60 else 72
        root.addView(quiet(getString(R.string.ai_ui_back)){returnToAssembly()},
            LinearLayout.LayoutParams(-2,dp(topHeight)).apply {leftMargin=dp(16)})
        val header=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),dp(6))}
        root.addView(header,LinearLayout.LayoutParams(-1,dp(headerHeight)))
        title=TextView(this).apply{textSize=if(heightDp<=500)22f else 27f;maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END
            setTypeface(typeface,Typeface.BOLD);setTextColor(ink);setPadding(0,0,0,dp(5))}
        header.addView(title,LinearLayout.LayoutParams(-1,-2))
        val subtitleRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.TOP}
        subtitle=TextView(this).apply{textSize=15f;setTextColor(muted);setPadding(0,0,0,dp(5))}
        subtitleRow.addView(ScrollView(this).apply{addView(subtitle)},LinearLayout.LayoutParams(0,-1,1f))
        speaker=ImageButton(this).apply{background=ColorDrawable(Color.TRANSPARENT);setImageDrawable(SpeakerIcon())
            setPadding(dp(10),dp(10),dp(10),dp(10));contentDescription=getString(R.string.ai_ui_speak);visibility=View.GONE}
        subtitleRow.addView(speaker,LinearLayout.LayoutParams(dp(40),dp(40)));header.addView(subtitleRow,LinearLayout.LayoutParams(-1,0,1f))
        val stage=FrameLayout(this).apply { AssemblyMediaFrame.clip(this) }
        root.addView(stage,LinearLayout.LayoutParams(-1,0,1f).apply {leftMargin=dp(8);rightMargin=dp(8)})
        cameraHost=FrameLayout(this).apply{visibility=View.GONE};stage.addView(cameraHost,FrameLayout.LayoutParams(-1,-1))
        image=ImageView(this).apply{scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription=getString(R.string.ai_ui_photo);visibility=View.GONE}
        stage.addView(image,FrameLayout.LayoutParams(-1,-1))
        photoCue=AiPhotoCueView(this);stage.addView(photoCue,FrameLayout.LayoutParams(-1,-1))
        installPhotoSwipe(image)
        installPhotoSwipe(photoCue)
        lensButton=quiet(getString(R.string.lens_main)){
            if(previewing&&captureToken==null){primary.isEnabled=false;camera.cycleFraming()}
        }.apply{setTextColor(ink);background=android.graphics.drawable.GradientDrawable().apply{setColor(Color.argb(235,255,255,255));cornerRadius=dp(24).toFloat()};visibility=View.GONE}
        stage.addView(lensButton,FrameLayout.LayoutParams(dp(94),dp(44),Gravity.BOTTOM or Gravity.END).apply{setMargins(dp(8),dp(8),dp(10),dp(12))})
        // Reserve the same media rectangle during preview, analysis and review.
        val footer=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),0)
        }
        root.addView(footer,LinearLayout.LayoutParams(-1,dp(footerHeight)))
        val options=LinearLayout(this).apply{gravity=Gravity.CENTER;orientation=LinearLayout.HORIZONTAL}
        footer.addView(options,LinearLayout.LayoutParams(-1,dp(optionsHeight)))
        thumbnailStrip=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        thumbnailTray=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;visibility=View.GONE;addView(thumbnailStrip)}
        thumbnailTray.addOnLayoutChangeListener { _,left,_,right,_,oldLeft,_,oldRight,_ ->
            if(right-left!=oldRight-oldLeft)thumbnailTray.post { fitThumbnails() }
        }
        options.addView(thumbnailTray,LinearLayout.LayoutParams(0,-1,1f))
        fun option(label:String,action:()->Unit)=quiet(label,action).apply{
            textSize=13f;minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0
            isSingleLine=true;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(dp(6),0,dp(6),0)
        }
        imageToggle=option(getString(R.string.ai_ui_marker_boxes)){
            showAnnotation=!showAnnotation
            paintImage()
            photoCue.showPhoto()
        }.apply{visibility=View.GONE}
        options.addView(imageToggle,LinearLayout.LayoutParams(-2,dp(44)))
        secondary=option(getString(R.string.review_add_short)){startPreview()}
        options.addView(secondary,LinearLayout.LayoutParams(-2,dp(44)))
        val action=FrameLayout(this)
        footer.addView(action,LinearLayout.LayoutParams(-1,0,1f))
        primary=Button(this).apply{isAllCaps=false;textSize=18f;minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;stateListAnimator=null;isEnabled=false}
        action.addView(primary)
        stylePrimary(getString(R.string.ai_ui_camera_preparing));setContentView(outer);outer.requestApplyInsets()
    }
    private fun quiet(text:String,action:()->Unit)=Button(this).apply{this.text=text;isAllCaps=false;textSize=14f;setTextColor(muted)
        stateListAnimator=null;background=rounded(Color.TRANSPARENT,22);setOnClickListener{action()}}
    private fun rounded(color:Int,radius:Int)=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat()}

    private fun browseTo(index:Int){
        if(index !in shots.indices || previewing)return
        selectShot(index)
        photoCue.showPhoto()
    }

    private fun installPhotoSwipe(surface:View){
        val slop=ViewConfiguration.get(this).scaledTouchSlop
        val minimum=maxOf(dp(32),slop*2)
        surface.setOnTouchListener(object:View.OnTouchListener{
            var startX=0f
            var startY=0f
            var cancelled=false
            var dragged=false
            override fun onTouch(v:View,event:MotionEvent):Boolean{
                if(previewing || shots.size<2)return false
                val dx=event.x-startX
                val dy=event.y-startY
                when(event.actionMasked){
                    MotionEvent.ACTION_DOWN -> {
                        startX=event.x;startY=event.y;cancelled=false;dragged=false
                    }
                    MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_CANCEL -> cancelled=true
                    MotionEvent.ACTION_MOVE -> {
                        if(!dragged && maxOf(abs(dx),abs(dy))>slop){
                            dragged=true
                            if(abs(dx)<1.4f*abs(dy))cancelled=true
                        }
                    }
                    MotionEvent.ACTION_UP -> if(!cancelled){
                        if(abs(dx)>=minimum && abs(dx)>1.4f*abs(dy)){
                            browseTo(selectedShot+if(dx<0)1 else -1)
                        }else if(!dragged && maxOf(abs(dx),abs(dy))<=slop && v.isClickable){
                            v.performClick()
                        }
                    }
                }
                return true
            }
        })
    }

    private fun refreshMarkerSwitch(){
        imageToggle.visibility=if(!previewing && frame!=null && image.visibility==View.VISIBLE)View.VISIBLE else View.GONE
        imageToggle.isSelected=showAnnotation
        imageToggle.background=rounded(if(showAnnotation)Color.rgb(221,239,227) else soft,12)
        imageToggle.setTextColor(if(showAnnotation)Color.rgb(27,81,57) else ink)
        imageToggle.contentDescription=getString(if(showAnnotation)R.string.ai_ui_hide_boxes else R.string.ai_ui_show_boxes)
    }
    /** Keep a shutter a shutter: retries and other commands always return to a labeled button. */
    private fun stylePrimary(label:String,capture:Boolean=false,color:Int=yellow){
        primary.contentDescription=label;primary.text=if(capture)"" else label
        primary.backgroundTintList=null;primary.setTextColor(ink);primary.setPadding(0,0,0,0)
        val compact=resources.configuration.screenHeightDp<=620
        val size=if(capture){if(compact)52 else 60}else{if(compact)48 else 56}
        primary.layoutParams=FrameLayout.LayoutParams(if(capture)dp(size) else -1,dp(size),Gravity.CENTER)
        if(capture){
            fun shutter(enabled:Boolean):Drawable{
                val ring=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(if(enabled)paper else soft);setStroke(dp(2),if(enabled)ink else muted)}
                val disk=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(if(enabled)ink else Color.rgb(177,181,165))}
                return LayerDrawable(arrayOf(ring,disk)).apply{setLayerInset(1,dp(7),dp(7),dp(7),dp(7))}
            }
            primary.background=StateListDrawable().apply{addState(intArrayOf(-android.R.attr.state_enabled),shutter(false));addState(intArrayOf(),shutter(true))}
        }else primary.background=StateListDrawable().apply{
            addState(intArrayOf(-android.R.attr.state_enabled),rounded(soft,26));addState(intArrayOf(),rounded(color,26))
        }
    }
    private fun clearThumbnails(){
        if(!::thumbnailStrip.isInitialized)return
        for(i in 0 until thumbnailStrip.childCount){
            val tile=thumbnailStrip.getChildAt(i) as? FrameLayout?:continue
            (tile.getChildAt(0) as? ImageView)?.setImageDrawable(null)
        }
        thumbnailStrip.removeAllViews();thumbnailTray.visibility=View.GONE
    }
    private fun refreshThumbnails(){
        clearThumbnails()
        if(shots.isEmpty()||previewing)return
        thumbnailTray.visibility=View.VISIBLE
        shots.forEachIndexed{index,shot->
            val selected=index==selectedShot
            val tile=FrameLayout(this).apply{
                val border=rounded(if(selected)ink else soft,8)
                background=border;setPadding(dp(if(selected)3 else 1),dp(if(selected)3 else 1),dp(if(selected)3 else 1),dp(if(selected)3 else 1))
                isClickable=true;isFocusable=true;isSelected=selected
                contentDescription=getString(if(selected)R.string.ai_ui_photo_selected else R.string.ai_ui_photo_select,index+1,shots.size)
                setOnClickListener{browseTo(index)}
            }
            tile.addView(ImageView(this).apply{
                scaleType=ImageView.ScaleType.CENTER_CROP;setImageBitmap(shot.frame.bitmap)
                background=rounded(paper,6);clipToOutline=true;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },FrameLayout.LayoutParams(-1,-1))
            val side=dp(if(resources.configuration.screenHeightDp<=620)56 else 72)
            thumbnailStrip.addView(tile,LinearLayout.LayoutParams(side,side).apply{rightMargin=if(index<shots.lastIndex)dp(4) else 0})
            if(selected)tile.post{if(tile.isAttachedToWindow)tile.requestRectangleOnScreen(Rect(0,0,tile.width,tile.height),true)}
        }
        fitThumbnails()
    }
    private fun fitThumbnails(){
        val count=thumbnailStrip.childCount
        if(count==0 || thumbnailTray.width==0)return
        val preferred=dp(if(resources.configuration.screenHeightDp<=620)56 else 72)
        val side=((thumbnailTray.width-dp(4)*(count-1))/count).coerceIn(dp(48),preferred)
        for(index in 0 until count){
            val tile=thumbnailStrip.getChildAt(index)
            val params=tile.layoutParams
            if(params.width!=side || params.height!=side){
                params.width=side;params.height=side
                tile.layoutParams=params
            }
        }
    }
    private fun tint(color:Int, accent:Int?=null, announce:Boolean=false){
        bgAnimation?.cancel()
        val foreground=if(accent!=null) Color.rgb(56,76,64) else muted
        subtitle.setTextColor(foreground);secondary.setTextColor(foreground)
        val style=AssemblyColorFeedback.read(this)
        val pulse=style=="pulse" && accent!=null && announce && android.animation.ValueAnimator.areAnimatorsEnabled()
        val colors=when {
            pulse -> intArrayOf(bgColor,requireNotNull(accent),accent,accent,color)
            style=="strong" && accent!=null -> intArrayOf(bgColor,accent)
            else -> intArrayOf(bgColor,color)
        }
        bgAnimation=ValueAnimator.ofArgb(*colors).apply{duration=if(pulse) 2100L else 240L
            addUpdateListener{val value=it.animatedValue as Int;bgColor=value;outer.setBackgroundColor(value);window.setBackgroundDrawable(ColorDrawable(value))};start()}
    }
    private fun startPreview(){
        if(!currentStep())return
        photoCue.stop();resultCue=null;title.contentDescription=null
        val requestedView=result?.takeIf { it.assessment=="need_view" }?.viewRequest?.takeIf { it.isNotBlank() }
        cancelNetwork();speech.stop();session.preview();result=null;resultOrientation=null;captureToken=null;previewing=true;previewSpoken=false
        if(shots.size>=3){clearPhoto()}
        clearThumbnails();imageToggle.visibility=View.GONE
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            previewing=false;showPermission()
            if(!permissionPrompt){permissionPrompt=true;permissionDenied=false;permission.launch(Manifest.permission.CAMERA)}
            return
        }
        tint(paper);cameraHost.visibility=View.VISIBLE;cameraHost.alpha=0f;image.visibility=View.GONE
        title.text=if(layerInstall) getString(R.string.review_direction) else if(shots.isEmpty())getString(R.string.review_overview) else getString(R.string.review_closeup)
        subtitle.text=requestedView ?: if(layerInstall)getString(R.string.review_direction_hint) else if(shots.isEmpty())getString(R.string.review_overview_hint) else getString(R.string.review_closeup_hint)
        lensButton.visibility=View.VISIBLE;lensButton.text=camera.framingLabel()
        speaker.visibility=View.VISIBLE;refreshSpeaker();imageToggle.visibility=View.GONE;secondary.visibility=if(shots.isEmpty())View.INVISIBLE else View.VISIBLE
        secondary.text=getString(R.string.review_analyze_existing, shots.size);secondary.setOnClickListener{camera.pause();previewing=false;cameraHost.visibility=View.GONE;lensButton.visibility=View.GONE;session.pause();session.resumeReady();showBatch()}
        stylePrimary(getString(if(shots.isEmpty())R.string.ai_ui_capture else R.string.ai_ui_capture_joint),capture=true);primary.isEnabled=false
        primary.setOnClickListener{capture()};camera.resume()
    }
    private fun showPermission(){
        title.text=getString(R.string.review_camera_title);subtitle.text=getString(R.string.review_allow_hint)
        cameraHost.visibility=View.GONE;lensButton.visibility=View.GONE;secondary.visibility=View.INVISIBLE;speaker.visibility=View.VISIBLE;refreshSpeaker()
        stylePrimary(getString(R.string.ai_ui_allow_camera));primary.isEnabled=true;primary.setOnClickListener{startPreview()};speech.speak(subtitle.text.toString())
    }
    private fun capture(){
        if(!currentStep()||!previewing||captureToken!=null)return
        val ticket=session.capture()?:return;captureTicket=ticket;captureToken=UUID.randomUUID().toString();primary.isEnabled=false
        AssemblyFeedback.capture(primary)
        if(!camera.capture(captureToken!!)){
            captureToken=null;session.preview();primary.isEnabled=true;subtitle.text=getString(R.string.review_camera_busy)
        }
    }
    private fun onCameraState(state:String,message:String){
        if(!active||!previewing)return
        if(state=="capturing"){subtitle.text=message;lensButton.isEnabled=false;secondary.visibility=View.INVISIBLE}
        if(state=="framing")lensButton.text=message
        if(state=="switching"){primary.isEnabled=false;lensButton.isEnabled=false}
        if(state=="ready"){
            primary.isEnabled=captureToken==null;lensButton.isEnabled=true;cameraHost.animate().alpha(1f).setDuration(220).start()
            if(!previewSpoken){previewSpoken=true;speech.speak(subtitle.text.toString())}
        }
        if(state in setOf("error","unavailable","timeout","permission")){
            captureToken=null;session.preview();subtitle.text=message;primary.isEnabled=true;lensButton.isEnabled=true
            stylePrimary(getString(R.string.ai_ui_retry_camera));primary.setOnClickListener{startPreview()}
        }
    }
    private fun onPhoto(photo:JointPhotoFrame){
        if(!active||!previewing||photo.token!=captureToken||!currentStep()||!session.frame(captureTicket,autoAnalyze=false)){photo.bitmap.recycle();return}
        previewing=false
        image.setImageBitmap(photo.bitmap)
        image.alpha=1f
        image.visibility=View.VISIBLE
        lensButton.visibility=View.GONE
        cameraHost.visibility=View.GONE
        camera.pause()
        annotations.execute {
            val prepared=runCatching { AiPhotoAnnotation.make(photo,scope,this) }
            runOnUiThread {
                if(!active || captureToken!=photo.token || !currentStep()){
                    if((image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap===photo.bitmap) image.setImageDrawable(null)
                    prepared.getOrNull()?.recycle()
                    photo.bitmap.recycle()
                    return@runOnUiThread
                }
                captureToken=null
                val marked=prepared.getOrElse {
                    image.setImageDrawable(null)
                    photo.bitmap.recycle()
                    session.pause()
                    session.resumeRetry()
                    showFailure(AiReviewException("PHOTO_UNAVAILABLE",getString(R.string.review_photo_retry)))
                    return@runOnUiThread
                }
                shots.add(Shot(photo,marked))
                selectedShot=shots.lastIndex
                frame=photo
                annotated=marked
                showBatch()
                speech.speak(if(layerInstall) getString(R.string.review_capture_direction) else if(shots.size==1)getString(R.string.review_capture_one) else getString(R.string.review_captured_count, shots.size))
            }
        }
    }
    private fun selectShot(index:Int){
        if(shots.isEmpty())return
        selectedShot=index.coerceIn(0,shots.lastIndex);frame=shots[selectedShot].frame;annotated=shots[selectedShot].annotated
        paintImage();refreshThumbnails()
    }
    private fun paintImage(){
        image.setImageBitmap(if(showAnnotation)annotated else frame?.bitmap)
        image.contentDescription=getString(R.string.ai_ui_photo_selected,selectedShot+1,shots.size)
        refreshMarkerSwitch()
        refreshPhotoCue()
    }
    private fun refreshPhotoCue(){
        if(!::photoCue.isInitialized)return
        val plan=resultCue;val photo=frame
        if(!AiPhotoCue.mayPresent(plan,photo?.token,shots.lastOrNull()?.frame?.token,active,previewing,sending,result!=null)||photo==null||plan==null){photoCue.stop();return}
        photoCue.play(plan,if(showAnnotation)annotated?:photo.bitmap else photo.bitmap)
    }
    private fun showBatch(){
        photoCue.stop()
        title.contentDescription=null
        tint(paper);cameraHost.visibility=View.GONE;lensButton.visibility=View.GONE;image.visibility=View.VISIBLE;paintImage();refreshThumbnails()
        title.text=getString(R.string.review_count_title, shots.size);subtitle.text=if(layerInstall)getString(R.string.review_batch_direction) else if(shots.size==1)getString(R.string.review_batch_one) else getString(R.string.review_batch_many)
        speaker.visibility=View.VISIBLE;refreshSpeaker()
        refreshMarkerSwitch()
        primary.isEnabled=true;stylePrimary(getString(R.string.ai_ui_analyze_count,shots.size))
        primary.setOnClickListener{session.analyze()?.let{submit(it)}}
        secondary.visibility=View.VISIBLE;secondary.text=if(shots.size>=3)getString(R.string.review_retake_batch) else getString(R.string.review_add)
        secondary.setOnClickListener{if(shots.size>=3)clearPhoto();startPreview()}
    }
    private data class UploadShot(val raw:Bitmap,val marked:Bitmap,val width:Int,val height:Int,val observation:JSONObject,val viewContext:JSONObject)
    private fun submit(ticket:Long){
        if(shots.isEmpty()||!currentStep()||sending||!session.accepts(ticket))return
        photoCue.stop();resultCue=null
        val requestId=UUID.randomUUID().toString();val taskId=scope.task.getString("id")
        val context:JSONObject
        val uploads=mutableListOf<UploadShot>()
        try{
            context=scope.build(shots.first().frame,automaticQuestion)
            for(shot in shots){
                val photo=shot.frame;val factor=minOf(1.0,1600.0/maxOf(photo.bitmap.width,photo.bitmap.height))
                val viewContext=scope.build(photo,automaticQuestion)
                shot.localCheck=viewContext.optJSONObject("framePairCheck")
                val width=(photo.bitmap.width*factor).roundToInt();val height=(photo.bitmap.height*factor).roundToInt()
                val raw=copyForUpload(photo.bitmap,width,height)
                val marked=try{copyForUpload(shot.annotated,width,height)}catch(e:Exception){raw.recycle();throw e}
                uploads.add(UploadShot(raw,marked,width,height,scope.observation(photo,width,height),viewContext))
            }
        }catch(_:Exception){uploads.forEach{it.raw.recycle();it.marked.recycle()};session.fail(ticket);showFailure(AiReviewException("PHOTO_UNAVAILABLE",getString(R.string.review_incomplete)));return}
        // Multi-view advice cannot animate from the stale overview's pixel direction.
        resultOrientation=if(uploads.size==1)context.optJSONObject("localOrientation")?.toString() else null
        if(uploads.size>1){
            context.put("localOrientation",JSONObject().put("policy","multi_view").put("status","per_view_only"))
            // Do not leave the first photo's frame verdict in the shared context of newer views.
            context.put("framePairCheck",JSONObject().put("status","per_view_only").put("instruction","逐张使用views中的framePairCheck；不能合并不同拍摄时刻的方向。"))
        }
        // Identity references may come from a later closeup. Merge identities only; keep geometry per view.
        val observed=linkedMapOf<String,JSONObject>()
        for(shot in uploads){val rows=shot.viewContext.optJSONArray("observedParts")?:continue
            for(i in 0 until rows.length()){val row=rows.getJSONObject(i);observed[row.getString("id")]=row}}
        context.put("observedParts",JSONArray(observed.values.toList()))
        context.put("photoCount",uploads.size).put("multiView",uploads.size>1)
        val call=AiReviewClient(this);client=call;sending=true;result=null;speech.stop()
        lensButton.visibility=View.GONE;image.visibility=View.VISIBLE
        title.text=getString(R.string.review_analyzing_count, uploads.size);subtitle.text=if(layerInstall)getString(R.string.review_analyzing_direction) else getString(R.string.review_analyzing_views)
        primary.isEnabled=false;stylePrimary(getString(R.string.ai_ui_analyzing));speaker.visibility=View.VISIBLE;refreshSpeaker();refreshMarkerSwitch();refreshThumbnails()
        secondary.visibility=View.VISIBLE;secondary.text=getString(R.string.review_cancel);secondary.setOnClickListener{cancelNetwork();session.pause();session.resumeReady();showBatch()}
        io.execute{
            val response=runCatching{
                val views=JSONArray()
                for((index,shot) in uploads.withIndex())views.put(JSONObject().put("viewId","view-${index+1}")
                    .put("purpose",if(index==0)"overview" else "detail").put("observation",shot.observation)
                    .put("localOrientation",shot.viewContext.optJSONObject("localOrientation"))
                    .put("framePairCheck",shot.viewContext.optJSONObject("framePairCheck"))
                    .put("observedParts",shot.viewContext.optJSONArray("observedParts")?:JSONArray())
                    .put("quality",shot.viewContext.optJSONObject("quality")?:JSONObject())
                    .put("parts",shot.viewContext.getJSONArray("parts"))
                    .put("images",JSONArray().put(encode(shot.raw,shot.width,shot.height,"original")).put(encode(shot.marked,shot.width,shot.height,"annotated"))))
                val images=JSONArray(views.getJSONObject(0).getJSONArray("images").toString())
                val reference=AiModelReference.load(applicationContext,taskId)
                if(reference!=null)images.put(reference)
                context.put("modelReference",if(reference!=null)AiModelReference.metadata(applicationContext,taskId)
                    else JSONObject().put("available",false))
                val payload=JSONObject().put("schema","assembly-ai-review-1").put("requestId",requestId)
                    .put("context",context).put("observation",uploads.first().observation).put("images",images).put("views",views).put("question",automaticQuestion)
                call.review(payload,requestId,taskId)
            }
            uploads.forEach{it.raw.recycle();it.marked.recycle()}
            runOnUiThread{
                if(!active||isDestroyed||client!==call||!session.accepts(ticket))return@runOnUiThread
                if(!currentStep())return@runOnUiThread
                client=null;sending=false
                response.onSuccess{if(session.finish(ticket))showResult(it,true)}.onFailure{if(session.fail(ticket))showFailure(it)}
            }
        }
    }
    private fun copyForUpload(source:Bitmap,width:Int,height:Int):Bitmap =
        if(source.width==width&&source.height==height)requireNotNull(source.copy(Bitmap.Config.ARGB_8888,false))
        else Bitmap.createScaledBitmap(source,width,height,true)
    private fun encode(bitmap:Bitmap,width:Int,height:Int,role:String):JSONObject{
        val scaled=if(bitmap.width==width&&bitmap.height==height)bitmap else Bitmap.createScaledBitmap(bitmap,width,height,true)
        try{var bytes=ByteArray(0)
            for(q in listOf(88,78,68,55)){val out=ByteArrayOutputStream();check(scaled.compress(Bitmap.CompressFormat.JPEG,q,out));bytes=out.toByteArray();if(bytes.size<=1200*1024)break}
            if(bytes.size>1200*1024)throw AiReviewException("IMAGE_TOO_LARGE",getString(R.string.review_large))
            return JSONObject().put("role",role).put("mime","image/jpeg").put("base64",Base64.encodeToString(bytes,Base64.NO_WRAP))
        }finally{if(scaled!==bitmap)scaled.recycle()}
    }
    private fun showResult(value:AiReviewResult,announce:Boolean){
        result=value
        val mark=when(value.assessment){
            "looks_ok"->R.drawable.ai_status_ok
            "needs_adjustment"->R.drawable.ai_status_adjust
            else->R.drawable.ai_status_uncertain
        }
        val badge=requireNotNull(getDrawable(mark))
        val size=dp(22)
        badge.setBounds(0,0,size,size)
        title.text=SpannableString(value.headline+" \uFFFC").apply{
            setSpan(ImageSpan(badge,ImageSpan.ALIGN_BASELINE),length-1,length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        title.contentDescription=value.headline
        if(announce){
            resultCue=shots.lastOrNull()?.let { runCatching { scope.photoCue(it.frame,value.assessment,it.localCheck) }.getOrNull() }
            if(resultCue!=null && selectedShot!=shots.lastIndex)selectShot(shots.lastIndex)
        }
        subtitle.text=displayAdvice(value);speaker.visibility=View.VISIBLE;refreshSpeaker();refreshMarkerSwitch();lensButton.visibility=View.GONE
        tint(when(value.assessment){"looks_ok"->Color.rgb(226,246,234);"needs_adjustment"->Color.rgb(255,237,215);else->paper},
            when(value.assessment){"looks_ok"->Color.rgb(116,224,163);"needs_adjustment"->Color.rgb(255,184,101);else->null},announce)
        primary.isEnabled=true;stylePrimary(getString(R.string.ai_ui_return));primary.setOnClickListener{returnToAssembly()};refreshThumbnails()
        secondary.visibility=if(layerInstall && (shots.size>=3 || value.viewRequest.isBlank())) View.GONE else View.VISIBLE;secondary.text=if(shots.size<3)getString(R.string.review_add) else getString(R.string.review_retake_batch)
        secondary.setOnClickListener{if(shots.size>=3)clearPhoto();startPreview()}
        refreshPhotoCue()
        if(announce){
            if(value.assessment=="looks_ok")AssemblyFeedback.confirm(primary) else AssemblyFeedback.attention(primary)
            speech.speak(spokenAdvice(value))
        }
    }
    private fun displayAdvice(value:AiReviewResult)=AiReplyText.advice(value)
    private fun refreshSpeaker(){
        if(!::speaker.isInitialized)return
        val enabled=AssemblyVoicePreferences.enabled(this)
        speaker.contentDescription=if(enabled)getString(R.string.speech_disable) else getString(R.string.speech_enable)
        speaker.isSelected=enabled;speaker.alpha=if(enabled)0.85f else 0.4f
        speaker.imageTintList=ColorStateList.valueOf(if(enabled)purple else muted)
    }
    private fun spokenAdvice(value:AiReviewResult)=AiReplyText.spoken(value,AssemblyLanguage.english(this))
    private fun showFailure(error:Throwable){
        photoCue.stop();resultCue=null
        title.contentDescription=null
        val code=(error as? AiReviewException)?.code.orEmpty()
        title.text=when(code){"UNREACHABLE","TIMEOUT"->getString(R.string.review_connection_problem);"NOT_CONFIGURED","AUTH_FAILED"->getString(R.string.review_check_connection);else->getString(R.string.review_failed)}
        subtitle.text=if(error is AiReviewException && AssemblyLanguage.english(this)) AiReviewEnglish.error(code) else if(error is AiReviewException)error.message else getString(R.string.review_retry)
        tint(paper);lensButton.visibility=View.GONE;speaker.visibility=View.VISIBLE;refreshSpeaker();refreshMarkerSwitch();primary.isEnabled=true;refreshThumbnails()
        stylePrimary(getString(if(frame!=null&&annotated!=null)R.string.ai_ui_retry_analysis else R.string.ai_ui_retry_camera))
        primary.setOnClickListener{if(frame!=null&&annotated!=null){session.retry()?.let{submit(it)}}else startPreview()}
        if(code in setOf("NOT_CONFIGURED","AUTH_FAILED")){
            stylePrimary(getString(R.string.ai_ui_network_settings));primary.setOnClickListener{startActivity(Intent(this,AiSettingsActivity::class.java))}
        }
        secondary.visibility=View.VISIBLE;secondary.text=getString(R.string.review_return);secondary.setOnClickListener{returnToAssembly()};speech.speak(subtitle.text.toString())
    }
    private fun returnToAssembly(){
        if(::photoCue.isInitialized)photoCue.stop()
        val value=result
        if(value!=null&&::scope.isInitialized&&scope.isCurrent(this)){
            setResult(RESULT_OK,Intent().putExtra("aiAssessment",value.assessment).putExtra("aiInstruction",displayAdvice(value)).putExtra("aiHeadline",value.headline)
                .putExtra("aiTask",scope.task.getString("id")).putExtra("aiScope",scope.key)
                .putExtra("aiOrientation",orientationForResult(value)))
        }
        if(::speech.isInitialized)speech.stop();session.close();cancelNetwork();finish()
    }
    private fun orientationForResult(value:AiReviewResult):String?=runCatching {
        val local=JSONObject(resultOrientation?:return null);local.remove("action")
        val action=value.orientationAction
        if(action!=null&&action.matches(value.assessment,scope.task.optString("kind"),local.optString("policy"),
                local.optString("status"),local.optString("directionStatus"),local.optString("flipCandidate"),
                local.optJSONObject("rotationSuggestion")?.optString("part"))) {
            local.put("action",JSONObject().put("kind",action.kind).put("part",action.part).put("flat_pair_visible",true))
        }
        local.toString()
    }.getOrNull()
    private fun cancelNetwork(){client?.cancel();client=null;sending=false}
    private fun currentStep():Boolean{
        if(!active||!::scope.isInitialized)return false
        if(scope.isCurrent(this))return true
        cancelNetwork();session.close();Toast.makeText(this,getString(R.string.review_context_changed),Toast.LENGTH_LONG).show();finish();return false
    }
    private fun clearPhoto(){
        if(::photoCue.isInitialized)photoCue.stop();resultCue=null
        clearThumbnails();image.setImageDrawable(null);shots.forEach{shot->
            if(!shot.frame.bitmap.isRecycled)shot.frame.bitmap.recycle()
            if(!shot.annotated.isRecycled)shot.annotated.recycle()
        };shots.clear();selectedShot=0
        frame=null;annotated=null;result=null;resultOrientation=null
    }
    override fun onPause(){
        if(::photoCue.isInitialized){resumeCuePhoto=photoCue.showingPhoto;photoCue.stop()}
        active=false;previewing=false;captureToken=null;session.pause();cancelNetwork()
        if(::speech.isInitialized)speech.stop();if(::camera.isInitialized)camera.pause();super.onPause()
    }
    override fun onResume(){
        super.onResume();ImmersiveAssembly.hide(window);active=true
        if(!::scope.isInitialized||!::camera.isInitialized)return
        if(!scope.isCurrent(this)){finish();return}
        when{
            permissionPrompt->Unit
            permissionDenied&&ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED->showPermission()
            result!=null->{if(session.state==AiReviewSession.State.PAUSED)session.resumeResult();showResult(result!!,false);if(resumeCuePhoto)photoCue.showPhoto()}
            frame!=null&&annotated!=null->{if(session.state==AiReviewSession.State.PAUSED)session.resumeReady();showBatch()}
            session.state!=AiReviewSession.State.CLOSED->startPreview()
        }
    }
    override fun onDestroy(){
        session.close();cancelNetwork();bgAnimation?.cancel();if(::speech.isInitialized)speech.close()
        if(::camera.isInitialized)camera.release();if(::image.isInitialized)clearPhoto();annotations.shutdown();io.shutdown();super.onDestroy()
    }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private val ink=Color.rgb(39,43,33);private val muted=Color.rgb(117,124,111)
    private val paper=Color.rgb(250,249,243);private val soft=Color.rgb(239,240,232)
    private val yellow=Color.rgb(238,239,181);private val purple=Color.rgb(112,76,183)

    private class SpeakerIcon:Drawable(){
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(98,121,109);style=Paint.Style.STROKE;strokeWidth=1.6f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
        override fun draw(canvas:Canvas){val save=canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.scale(bounds.width()/24f,bounds.height()/24f)
            val p=Path().apply{moveTo(11f,5f);lineTo(6f,9f);lineTo(3f,9f);lineTo(3f,15f);lineTo(6f,15f);lineTo(11f,19f);close()};canvas.drawPath(p,paint)
            canvas.drawArc(9f,6f,21f,18f,-48f,96f,false,paint);canvas.drawArc(7f,2f,27f,22f,-44f,88f,false,paint);canvas.restoreToCount(save)}
        override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
        override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
        @Deprecated("Drawable compatibility") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
}
