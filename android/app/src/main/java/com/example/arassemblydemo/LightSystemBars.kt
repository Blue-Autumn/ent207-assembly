package com.example.arassemblydemo

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** White page background extends behind visible system bars; content stays inside safe insets. */
internal object LightSystemBars {
    @Suppress("DEPRECATION")
    fun configure(window: Window) {
        window.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Older Android needs these colors; Android 15+ uses the white view drawn underneath.
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) window.navigationBarDividerColor = Color.TRANSPARENT
        // PhoneWindow reads theme contrast defaults while installing decor; apply overrides after it.
        val decor = window.decorView
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, decor).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
            show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun insetContent(root: View, left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) {
        root.setBackgroundColor(Color.WHITE)
        root.setPadding(left, top, right, bottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
            insets
        }
    }
}
