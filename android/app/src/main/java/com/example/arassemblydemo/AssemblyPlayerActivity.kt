package com.example.arassemblydemo

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.widget.Button
import android.widget.LinearLayout

/** Offline manual instruction player. No JavaScript bridge, camera or remote resources. */
class AssemblyPlayerActivity : Activity() {
    private lateinit var player: WebView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        player = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.blockNetworkLoads = true
            settings.allowContentAccess = false
            settings.allowFileAccess = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) =
                    request?.url?.toString() != "file:///android_asset/assembly-player.html"
            }
            loadUrl("file:///android_asset/assembly-player.html")
        }
        root.addView(player, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply { text = "返回主页"; setOnClickListener { finish() } })
        setContentView(root)
        root.requestApplyInsets()
    }
    override fun onPause() {
        player.evaluateJavascript("window.dispatchEvent(new Event('pagehide'));", null)
        player.onPause()
        super.onPause()
    }
    override fun onResume() { super.onResume(); if (::player.isInitialized) player.onResume() }
    override fun onDestroy() { player.destroy(); super.onDestroy() }
}
