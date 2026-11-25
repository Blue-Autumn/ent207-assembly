package com.example.arassemblydemo

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.roundToInt

/** The same local CAD lesson as the live assembly screen. Photos stay in the native image view. */
@SuppressLint("SetJavaScriptEnabled")
internal class PhotoRowLessonView(context:Context):FrameLayout(context){
    private val page=WebView(context)
    private val reference=ImageView(context)
    private var plan:AiPhotoCue.RowPlan?=null
    private var generation=0L
    private var loaded=false
    private var failed:(()->Unit)?=null
    private val url="file:///android_asset/row-photo-lesson.html?lang=${AssemblyLanguage.locale(context).toLanguageTag()}"
    private fun dp(value:Int)=(value*resources.displayMetrics.density).roundToInt()

    init{
        visibility=GONE
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        page.setBackgroundColor(Color.TRANSPARENT)
        page.settings.javaScriptEnabled=true
        page.settings.allowFileAccess=false
        page.settings.allowContentAccess=false
        page.settings.blockNetworkLoads=true
        page.isHorizontalScrollBarEnabled=false;page.isVerticalScrollBarEnabled=false
        page.isFocusable=false
        page.webViewClient=object:WebViewClient(){
            override fun onPageFinished(view:WebView, finished:String){
                if(finished==url){loaded=true;startCurrent()}
            }
        }
        addView(page,LayoutParams(-1,-1))
        reference.scaleType=ImageView.ScaleType.FIT_CENTER
        reference.background=GradientDrawable().apply{setColor(Color.rgb(240,239,233));cornerRadius=dp(9).toFloat()}
        reference.clipToOutline=true
        addView(reference,LayoutParams(dp(90),dp(110),Gravity.TOP or Gravity.START).apply{setMargins(dp(10),dp(10),0,0)})
        page.loadUrl(url)
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
        super.onSizeChanged(w,h,oldw,oldh)
        val lp=reference.layoutParams
        lp.width=(w*.23f).roundToInt().coerceIn(1,dp(104))
        lp.height=(h*.23f).roundToInt().coerceIn(1,dp(118))
        reference.layoutParams=lp
    }
    fun play(next:AiPhotoCue.RowPlan,photo:Bitmap,onFailure:()->Unit){
        generation++;plan=next;failed=onFailure
        setReference(photo)
        // Keep the source photo visible until the first CAD frame is ready.
        alpha=0f;visibility=VISIBLE
        page.onResume();startCurrent()
    }
    fun setReference(photo:Bitmap){if(plan!=null)reference.setImageBitmap(photo)}
    private fun startCurrent(){
        val current=plan?:return
        if(!loaded)return
        val ticket=generation
        val reduced=!ValueAnimator.areAnimatorsEnabled()
        page.evaluateJavascript("window.playLesson && playLesson(${current.observedRow},${current.targetRow},$reduced)"){result->
            if(generation==ticket && plan==current){
                if(result=="true")alpha=1f else failed?.invoke()
            }
        }
    }
    fun pauseLesson(){
        if(plan==null)return
        generation++;plan=null;failed=null
        reference.setImageDrawable(null);visibility=GONE
        if(loaded)page.evaluateJavascript("window.stopLesson && stopLesson()",null)
        page.onPause()
    }
    fun release(){
        pauseLesson();page.stopLoading();removeView(page);page.destroy()
    }
}
