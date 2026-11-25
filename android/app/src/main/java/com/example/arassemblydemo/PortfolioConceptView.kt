package com.example.arassemblydemo

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView

/** Catalog artwork and its matching rigid-part assembly study use the same source geometry. */
internal enum class PortfolioIdea(val key: String, val label: Int, val poster: Int, val surface: Int) {
    STOOL("stool",R.string.portfolio_stool,R.drawable.portfolio_stool,0xffeee4d9.toInt()),
    LAMP("lamp",R.string.portfolio_lamp,R.drawable.portfolio_lamp,0xffedf0de.toInt()),
    RECORD_PLAYER("recordplayer",R.string.portfolio_recordplayer,R.drawable.portfolio_recordplayer,0xffe8e4dd.toInt()),
    BRIDGE("bridge",R.string.portfolio_bridge,R.drawable.portfolio_bridge,0xffe1e9e4.toInt()),
    BOOKS("bookstand",R.string.portfolio_bookstand,R.drawable.portfolio_bookstand,0xffe7e4ee.toInt()),
    TRAY("tray",R.string.portfolio_tray,R.drawable.portfolio_tray,0xffe4ece8.toInt()),
    XYLOPHONE("xylophone",R.string.portfolio_xylophone,R.drawable.portfolio_xylophone,0xfff1e4d6.toInt()),
    DRAWERS("drawers",R.string.portfolio_drawers,R.drawable.portfolio_drawers,0xffe7e9d9.toInt()),
    PLANTS("plantstand",R.string.portfolio_plantstand,R.drawable.portfolio_plantstand,0xffe9ebdf.toInt()),
    ORGANIZER("organizer",R.string.portfolio_organizer,R.drawable.portfolio_organizer,0xffeee5dd.toInt());

    companion object {
        val byKey = values().associateBy { it.key }
    }
}

@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class PortfolioConceptView(context: Context, private val idea: PortfolioIdea, private val detail: Boolean = false) : FrameLayout(context) {
    private val poster = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(idea.poster)
    }
    private var browser: WebView? = null

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        clipToOutline = true
        addView(poster, LayoutParams(-1,-1))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!detail || !ValueAnimator.areAnimatorsEnabled()) return
        val view = WebView(context).apply {
            alpha = 0f
            isFocusable = false
            setBackgroundColor(Color.TRANSPARENT)
            settings.apply {
                javaScriptEnabled = true
                allowContentAccess = false
                allowFileAccess = false
                blockNetworkLoads = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, title: String?) {
                    if (title == "concept-ready") revealWhenReady(view)
                }
            }
        }
        browser = view
        addView(view, LayoutParams(-1,-1))
        view.loadUrl("file:///android_asset/concept-studio/index.html?id=" + idea.key)
    }

    private fun revealWhenReady(view: WebView) {
        view.evaluateJavascript("window.conceptReady===true") { ready ->
            if (browser !== view || ready != "true") return@evaluateJavascript
            view.postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (browser !== view) return
                    view.alpha = 1f
                    poster.visibility = View.INVISIBLE
                }
            })
        }
    }

    fun stopMotion() {
        browser?.evaluateJavascript("window.setConceptActive?.(false)",null)
    }

    override fun onDetachedFromWindow() {
        val view = browser
        browser = null
        if (view != null) {
            removeView(view)
            view.stopLoading()
            view.destroy()
        }
        poster.visibility = View.VISIBLE
        super.onDetachedFromWindow()
    }
}
