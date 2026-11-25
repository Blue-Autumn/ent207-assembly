package com.example.arassemblydemo

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity

/** A camera-free rehearsal of the authored CAD route with no connection to live progress. */
class VirtualAssemblyActivity : ComponentActivity() {
    private lateinit var page: WebView

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AssemblyLanguage.wrap(base))

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LightSystemBars.configure(window)
        val frame = FrameLayout(this)
        LightSystemBars.insetContent(frame)
        page = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.domStorageEnabled = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.isForMainFrame && request.url.toString() == "workshop://close") finish()
                    return true
                }
            }
        }
        frame.addView(page)
        setContentView(frame)
        val language = AssemblyLanguage.locale(this).toLanguageTag()
        val motion = if (ValueAnimator.areAnimatorsEnabled()) "auto" else "reduce"
        page.loadUrl("file:///android_asset/virtual-assembly/index.html?lang=$language&motion=$motion")
    }

    override fun onResume() {
        super.onResume()
        page.onResume()
    }

    override fun onPause() {
        page.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        (page.parent as? FrameLayout)?.removeView(page)
        page.destroy()
        super.onDestroy()
    }
}
