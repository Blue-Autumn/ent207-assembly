package com.example.arassemblydemo

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/** Brief semantic feedback, respecting the user's system haptic preference. */
internal object AssemblyFeedback {
    fun confirm(view:View) { view.performHapticFeedback(if(Build.VERSION.SDK_INT>=30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK) }
    fun attention(view:View) { view.performHapticFeedback(if(Build.VERSION.SDK_INT>=30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS) }
    fun capture(view:View) { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
}
