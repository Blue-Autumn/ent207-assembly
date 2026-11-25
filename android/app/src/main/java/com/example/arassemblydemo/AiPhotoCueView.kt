package com.example.arassemblydemo

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.*

/** Full-size teaching model with a small photo reference; deliberately not an AR registration. */
internal class AiPhotoCueView(context:Context):FrameLayout(context) {
    private var cue:AiPhotoCue.Lesson?=null
    private var rows:PhotoRowLessonView?=null
    private var photograph:Bitmap?=null
    private val models=mutableMapOf<Pair<String,Int>,AiPhotoCadMesh>()
    private var model:AiPhotoCadMesh?=null
    private var frame:AiPhotoCadMesh.Frame?=null
    private val triangle=Path()
    private var started=0L
    private var demonstration=true
    val showingPhoto get()=cue!=null && !demonstration
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val density=resources.displayMetrics.density
    private val tick=object:Runnable{override fun run(){if(cue==null||!isAttachedToWindow)return;invalidate();if(demonstration)postOnAnimation(this)}}
    init {
        setWillNotDraw(false);isClickable=true;isFocusable=true;visibility=GONE
        setOnClickListener{demonstration=!demonstration;started=SystemClock.uptimeMillis();present()}
    }
    // The stage owns taps and photo swipes, including over the read-only CAD canvas.
    override fun onInterceptTouchEvent(ev:MotionEvent)=true
    fun play(plan:AiPhotoCue.Lesson,photo:Bitmap) {
        if(cue==plan&&visibility==VISIBLE){
            if(photograph!==photo){photograph=photo;rows?.setReference(photo);invalidate()}
            return
        }
        stop()
        if(plan is AiPhotoCue.Plan){
            val cad=runCatching { models.getOrPut(plan.part to plan.cadNormal) {
                val source=context.assets.open("shelf-phone/scene.js").bufferedReader().use { it.readText() }
                AiPhotoCadMesh.readScene(source,plan.part,plan.cadNormal)
            } }.getOrNull()?:return
            model=cad;frame=cad.frame()
        }
        cue=plan;photograph=photo;started=SystemClock.uptimeMillis();demonstration=true
        visibility=VISIBLE;present()
    }
    private fun present(){
        removeCallbacks(tick)
        val plan=cue
        if(plan is AiPhotoCue.RowPlan && demonstration && photograph!=null){
            val player=rows?:PhotoRowLessonView(context).also{rows=it;addView(it,LayoutParams(-1,-1))}
            player.play(plan,requireNotNull(photograph)){showPhoto()}
        }else{
            rows?.pauseLesson()
            if(demonstration && plan is AiPhotoCue.Plan)postOnAnimation(tick)
        }
        describe();invalidate()
    }
    fun showPhoto(){demonstration=false;present()}
    fun stop(){removeCallbacks(tick);rows?.pauseLesson();cue=null;photograph=null;visibility=GONE;invalidate()}
    override fun onDetachedFromWindow(){
        stop()
        rows?.let{it.release();removeView(it)};rows=null
        super.onDetachedFromWindow()
    }
    private fun describe(){contentDescription=if(demonstration)context.getString(R.string.cue_show_photo) else context.getString(R.string.cue_show_action)}
    private fun dp(value:Float)=value*density
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val p=cue?:return;val photo=photograph?.takeUnless { it.isRecycled }?:return
        if(width<=0||height<=0)return
        if(p is AiPhotoCue.RowPlan){
            // The actual full-size ImageView is immediately underneath this transparent host.
            if(!demonstration)label(canvas,context.getString(R.string.cue_row_tap),height-dp(20f))
            return
        }
        if(p !is AiPhotoCue.Plan)return
        val caption=if(p.kind=="flip")context.getString(R.string.cue_flip) else context.getString(R.string.cue_turn)
        if(!demonstration){
            val rect=fitPhoto(photo,RectF(0f,0f,width.toFloat(),height.toFloat()))
            drawPhoto(canvas,photo,rect,p)
            label(canvas,context.getString(R.string.cue_watch, caption),height-dp(20f));return
        }
        canvas.drawColor(Color.rgb(250,249,243))
        val phase=AiPhotoCue.phase(SystemClock.uptimeMillis()-started)
        // Preserve a photograph for comparison and mark the chosen half. The teaching model is
        // presented separately so a close-up cannot clip the rest of the board.
        val photoBox=RectF(dp(8f),dp(8f),width*.27f,min(height*.27f,dp(110f)))
        drawPhoto(canvas,photo,fitPhoto(photo,photoBox),p)
        paint.style=Paint.Style.FILL;paint.color=Color.rgb(108,62,23);paint.textSize=dp(16f)
        paint.typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD);paint.textAlign=Paint.Align.LEFT
        canvas.drawText(caption,width*.32f,dp(30f),paint)
        paint.textSize=dp(13f);paint.typeface=Typeface.DEFAULT;canvas.drawText(context.getString(when(p.label){
            "右手边这块" -> R.string.cue_right
            "左手边这块" -> R.string.cue_left
            "下面这块" -> R.string.cue_lower
            "上面这块" -> R.string.cue_upper
            else -> R.string.cue_marked
        }),width*.32f,dp(52f),paint)
        val instruction=if(phase.stage=="withdraw")context.getString(R.string.cue_withdraw) else if(p.kind=="flip")context.getString(R.string.cue_flip_hint) else context.getString(R.string.cue_turn_hint)
        val top=max(photoBox.bottom,dp(60f))+dp(4f)
        val bottom=height-dp(65f)
        val size=min(width-dp(22f),bottom-top)
        if(size<=0)return
        val cx=width/2f
        val cy=(top+bottom)/2f
        val lift=if(phase.stage=="withdraw")phase.progress else 1.0
        val scale=size*.47f
        val center=PointF(cx,cy-dp(6f)*lift.toFloat())
        val alpha=(phase.opacity*255).roundToInt().coerceIn(0,255)
        val angle=if(phase.stage=="withdraw")0.0 else p.angleDegrees*phase.progress
        val cad=model?:return
        val projected=frame?:return
        cad.project(p,angle,projected)
        drawBoard(canvas,cad,projected,center,scale,alpha)
        val direction=if(p.axisIndex==0)p.facing.screen(1.0,0.0) else p.facing.screen(0.0,1.0)
        val hingeRotation=Math.toDegrees(atan2(direction.y,direction.x)).toFloat()
        if(p.kind=="flip"){
            val length=scale*(if(p.axisIndex==0)cad.points.maxOf { abs(it.u) } else cad.points.maxOf { abs(it.v) }).toFloat()
            canvas.save()
            canvas.rotate(hingeRotation,center.x,center.y)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(2.5f);paint.color=Color.argb(alpha,255,255,255)
            paint.pathEffect=DashPathEffect(floatArrayOf(dp(8f),dp(5f)),0f)
            canvas.drawLine(center.x-length,center.y,center.x+length,center.y,paint)
            paint.pathEffect=null
            canvas.restore()
        }
        val arrowRadius=min(dp(42f),size*.16f)
        if(phase.stage=="withdraw") {
            arrow(canvas,PointF(center.x,center.y+arrowRadius*.5f),PointF(center.x,center.y-arrowRadius),alpha)
        } else {
            canvas.save()
            if(p.kind=="flip")canvas.rotate(hingeRotation,center.x,center.y)
            val rx=arrowRadius*(if(p.kind=="flip").56f else 1f)
            val ry=arrowRadius
            val arc=RectF(center.x-rx,center.y-ry,center.x+rx,center.y+ry)
            val sense=if(p.angleDegrees<0)-1f else 1f
            val start=if(p.kind=="flip")90f else 210f*sense
            val sweep=(if(p.kind=="flip")180f else 225f)*sense
            paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(6f);paint.strokeCap=Paint.Cap.ROUND;paint.color=Color.argb(alpha,168,77,9)
            canvas.drawArc(arc,start,sweep,false,paint)
            val a=Math.toRadians((start+sweep).toDouble())
            val tip=PointF(center.x+rx*cos(a).toFloat(),center.y+ry*sin(a).toFloat())
            val tx=-rx*sin(a).toFloat()*sense;val ty=ry*cos(a).toFloat()*sense;val n=hypot(tx,ty).coerceAtLeast(1f)
            arrow(canvas,PointF(tip.x-tx/n*dp(10f),tip.y-ty/n*dp(10f)),tip,alpha)
            canvas.restore()
        }
        label(canvas,instruction,height-dp(42f))
        paint.style=Paint.Style.FILL;paint.color=Color.rgb(117,124,111);paint.textSize=dp(11f);paint.textAlign=Paint.Align.CENTER
        canvas.drawText(context.getString(R.string.cue_photo),width/2f,height-dp(13f),paint)
    }
    private fun fitPhoto(photo:Bitmap,box:RectF):RectF {
        val scale=min(box.width()/photo.width,box.height()/photo.height)
        return RectF(box.centerX()-photo.width*scale/2,box.centerY()-photo.height*scale/2,
            box.centerX()+photo.width*scale/2,box.centerY()+photo.height*scale/2)
    }
    private fun drawPhoto(canvas:Canvas,photo:Bitmap,rect:RectF,plan:AiPhotoCue.Plan) {
        paint.style=Paint.Style.FILL;paint.alpha=255;canvas.drawBitmap(photo,null,rect,paint)
        val x=rect.left+plan.markerCenter.x.toFloat()/photo.width*rect.width()
        val y=rect.top+plan.markerCenter.y.toFloat()/photo.height*rect.height()
        val radius=if(demonstration)dp(5f) else dp(13f)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(2f);paint.color=Color.WHITE;canvas.drawCircle(x,y,radius,paint)
        paint.strokeWidth=dp(2f);paint.color=Color.rgb(166,82,12);canvas.drawCircle(x,y,radius+dp(2f),paint)
    }
    private fun label(canvas:Canvas,text:String,y:Float) {
        paint.pathEffect=null;paint.style=Paint.Style.FILL;paint.textSize=dp(15f);paint.typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD);paint.textAlign=Paint.Align.CENTER
        val w=paint.measureText(text)+dp(22f);val r=RectF((width-w)/2,y-dp(19f),(width+w)/2,y+dp(5f))
        paint.color=Color.argb(242,255,240,204);canvas.drawRoundRect(r,dp(9f),dp(9f),paint)
        paint.color=Color.rgb(108,62,23);canvas.drawText(text,width/2f,y,paint)
    }
    private fun arrow(canvas:Canvas,a:PointF,b:PointF,alpha:Int) {
        paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(8f);paint.strokeCap=Paint.Cap.ROUND;paint.color=Color.argb(alpha,168,77,9)
        canvas.drawLine(a.x,a.y,b.x,b.y,paint)
        val angle=atan2((b.y-a.y).toDouble(),(b.x-a.x).toDouble());val length=dp(15f)
        val tip=Path().apply{moveTo(b.x,b.y);lineTo(b.x-length*cos(angle-.65).toFloat(),b.y-length*sin(angle-.65).toFloat());lineTo(b.x-length*cos(angle+.65).toFloat(),b.y-length*sin(angle+.65).toFloat());close()}
        paint.style=Paint.Style.FILL;canvas.drawPath(tip,paint)
    }
    private fun drawBoard(canvas:Canvas,cad:AiPhotoCadMesh,frame:AiPhotoCadMesh.Frame,center:PointF,scale:Float,alpha:Int) {
        if(alpha==0)return
        // Composite the opaque solid once during the fade. Coplanar adjacent CAD triangles
        // share their fill and a subpixel edge, which avoids antialiased triangulation seams.
        val layer=if(alpha<255)canvas.saveLayerAlpha(null,alpha) else canvas.save()
        paint.style=Paint.Style.FILL_AND_STROKE
        paint.strokeWidth=.65f
        paint.strokeJoin=Paint.Join.ROUND
        for(i in frame.order) {
            val t=cad.triangles[i]
            val a=t[0];val b=t[1];val c=t[2]
            val ux=frame.x[b]-frame.x[a];val uy=frame.y[b]-frame.y[a];val uz=frame.z[b]-frame.z[a]
            val vx=frame.x[c]-frame.x[a];val vy=frame.y[c]-frame.y[a];val vz=frame.z[c]-frame.z[a]
            val nx=uy*vz-uz*vy;val ny=uz*vx-ux*vz;val nz=ux*vy-uy*vx
            val length=sqrt(nx*nx+ny*ny+nz*nz)
            if(length<1e-9||abs(nz)<1e-10)continue
            val light=.54+.46*abs((-.21*nx-.38*ny+.90*nz)/length).coerceAtMost(1.0)
            paint.color=Color.rgb((154*light).toInt(),(175*light).toInt(),(181*light).toInt())
            triangle.rewind()
            triangle.moveTo(center.x+frame.x[a].toFloat()*scale,center.y+frame.y[a].toFloat()*scale)
            triangle.lineTo(center.x+frame.x[b].toFloat()*scale,center.y+frame.y[b].toFloat()*scale)
            triangle.lineTo(center.x+frame.x[c].toFloat()*scale,center.y+frame.y[c].toFloat()*scale)
            triangle.close()
            canvas.drawPath(triangle,paint)
        }
        canvas.restoreToCount(layer)
    }
}
