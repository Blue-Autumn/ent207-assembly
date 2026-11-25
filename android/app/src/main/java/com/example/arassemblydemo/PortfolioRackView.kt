package com.example.arassemblydemo

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import org.json.JSONObject

/** One persistent, read-only mesh viewer. The exact pre-rendered mesh covers cold WebView startup. */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor", "ClickableViewAccessibility")
internal class PortfolioRackView(context: Context, interactive: Boolean) : FrameLayout(context) {
    private val browser = WebView(context)
    private val poster = ImageView(context).apply {
        setImageResource(R.drawable.portfolio_rack_poster)
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var interactive = interactive
    private var opened = false
    private var tap: (() -> Unit)? = null
    private var disposed = false
    private var active = false
    private var stateRequest = 0L
    init {
        contentDescription = context.getString(R.string.portfolio_model_description)
        addView(poster, LayoutParams(-1, -1))
        browser.alpha = 0f
        addView(browser, LayoutParams(-1, -1))
        browser.setBackgroundColor(Color.TRANSPARENT)
        browser.isVerticalScrollBarEnabled = false
        browser.isHorizontalScrollBarEnabled = false
        browser.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = false; blockNetworkLoads = true
            allowContentAccess = false; allowFileAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            builtInZoomControls = false; displayZoomControls = false
        }
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun onPageFinished(view: WebView, url: String) {
                if (disposed) return
                view.evaluateJavascript("Boolean(window.showcaseReady)") { ready ->
                    if (disposed || ready != "true") return@evaluateJavascript
                    opened = true
                    syncViewer()
                }
            }
        }
        val message = JSONObject.quote(context.getString(R.string.portfolio_model_unavailable))
        browser.loadDataWithBaseURL("file:///android_asset/shelf-phone/", """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:transparent}canvas{width:100%;height:100%;display:block;touch-action:none}#error{font:14px sans-serif;color:#747969;padding:28px}</style></head>
            <body><canvas id="cv"></canvas><div id="error" hidden></div>
            <script>window.sceneSurface='rgba(0,0,0,0)';
            window.onerror=function(){document.getElementById('error').textContent=$message;};</script>
            <script src="scene.js"></script><script src="renderer.js"></script>
            <script>configureOverview();drawScene();window.showcaseReady=true;
            const coverPose={yaw,pitch,zoom}, reduced=matchMedia('(prefers-reduced-motion: reduce)');
            let detail=false,active=false,animate=true,orbitFrame=0,previous=0;
            function orbit(t){
                yaw+=Math.max(0,Math.min(64,t-previous))*Math.PI*2/22000;
                previous=t;paintScene();orbitFrame=requestAnimationFrame(orbit);
            }
            function syncOrbit(){
                const run=detail&&active&&animate&&!reduced.matches&&!document.hidden&&!pointer;
                if(run&&!orbitFrame){previous=performance.now();orbitFrame=requestAnimationFrame(orbit)}
                if(!run&&orbitFrame){cancelAnimationFrame(orbitFrame);orbitFrame=0}
            }
            window.setShowcaseState=function(nextDetail,nextActive,motionEnabled){
                if(detail!==nextDetail){
                    yaw=coverPose.yaw;pitch=coverPose.pitch;zoom=coverPose.zoom;paintScene();
                }
                detail=nextDetail;active=nextActive;animate=motionEnabled;
                if(!active||!detail)pointer=null;
                cv.style.pointerEvents=detail?'auto':'none';
                syncOrbit();
            };
            for(const event of ['pointerdown','pointerup','pointercancel'])cv.addEventListener(event,syncOrbit);
            cv.addEventListener('lostpointercapture',()=>{pointer=null;syncOrbit()});
            document.addEventListener('visibilitychange',syncOrbit);
            reduced.addListener(syncOrbit);
            </script></body></html>
        """.trimIndent(), "text/html", "UTF-8", null)
        var downX = 0f; var downY = 0f
        browser.setOnTouchListener { _, event ->
            if (this.interactive) false else {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y }
                    MotionEvent.ACTION_UP -> if (kotlin.math.abs(event.x - downX) + kotlin.math.abs(event.y - downY) < 24 * resources.displayMetrics.density) performClick()
                }
                true
            }
        }
        setOnClickListener { tap?.invoke() }
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    fun clickablePreview(action: () -> Unit) { tap = action; isClickable = true; setInteractive(false) }
    fun setInteractive(value: Boolean) {
        interactive = value
        if (value) tap = null
        isClickable = !value
        contentDescription = if(value) context.getString(R.string.portfolio_model_description)
            else context.getString(R.string.portfolio_open_work, context.getString(R.string.portfolio_shelf))
        browser.importantForAccessibility = if(value) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (!value) {
            browser.alpha = 0f
            poster.visibility = VISIBLE
        }
        syncViewer()
    }
    private fun syncViewer() {
        val ticket = ++stateRequest
        if (!opened || disposed) return
        val motionEnabled = ValueAnimator.areAnimatorsEnabled()
        val reveal = active && interactive && browser.alpha == 0f
        // Commit the cover pose before exposing the WebView, including after a cold load.
        browser.evaluateJavascript("window.setShowcaseState($interactive,${active && !reveal},$motionEnabled)") {
            if (disposed || stateRequest != ticket || !reveal) return@evaluateJavascript
            browser.postVisualStateCallback(ticket, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (disposed || stateRequest != requestId) return
                    browser.alpha = 1f
                    poster.visibility = GONE
                    browser.evaluateJavascript("window.setShowcaseState(true,true,$motionEnabled)", null)
                }
            })
        }
    }
    fun pauseShowcase() { if (!disposed) { active=false; syncViewer(); browser.onPause() } }
    fun resumeShowcase() { if (!disposed) { browser.onResume(); active=true; syncViewer() } }
    fun destroy() { disposed = true; removeView(browser); browser.destroy() }
}
