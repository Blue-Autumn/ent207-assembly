package com.example.arassemblydemo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Each destination owns a fixed third of the pill, including during indicator motion. */
internal class PortfolioTabBar(context: Context, private val selectedTab: Int, private val previousTab: Int, private val select: (Int) -> Unit) : FrameLayout(context) {
    private val ink = 0xff30382c.toInt()
    private val indicator = View(context)
    private val destinations = listOf(
        R.string.portfolio_works to "projects", R.string.nav_community to "community", R.string.portfolio_my to "person")
    private var startX = 0f
    private var startY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var positioned = false
    private fun dp(value: Int) = (value*resources.displayMetrics.density+.5f).toInt()
    private fun pill(color: Int) = GradientDrawable().apply { cornerRadius = dp(36).toFloat(); setColor(color) }

    init {
        background = pill(0xffeeefe6.toInt()).apply { setStroke(dp(1),0xffdddfd4.toInt()) }
        elevation = dp(6).toFloat()
        indicator.background = pill(ink)
        indicator.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(indicator, LayoutParams(1, -1).apply { topMargin = dp(4); bottomMargin = dp(4) })
        val choices = LinearLayout(context)
        for ((index, destination) in destinations.withIndex()) {
            val tint = if (index == selectedTab) Color.WHITE else ink
            val label = context.getString(destination.first)
            val button = LinearLayout(context).apply {
                gravity = Gravity.CENTER
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                isSelected = index == selectedTab
                contentDescription = label
                setOnClickListener { if (index != selectedTab) select(index) }
            }
            button.addView(PortfolioIconView(context, destination.second,tint).apply {
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            },LinearLayout.LayoutParams(dp(32),dp(28)))
            button.addView(TextView(context).apply {
                text = label; textSize = 12f; setSingleLine(); setTextColor(tint); includeFontPadding = false
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            choices.addView(button,LinearLayout.LayoutParams(0,-1,1f))
        }
        addView(choices, LayoutParams(-1,-1))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed,l,t,r,b)
        val slotWidth = width / destinations.size
        val inset = dp(4)
        indicator.layout(inset,inset,slotWidth-inset,height-inset)
        val target = (selectedTab*slotWidth).toFloat()
        if (!positioned && previousTab != selectedTab && ValueAnimator.areAnimatorsEnabled()) {
            indicator.translationX = (previousTab*slotWidth).toFloat()
            indicator.animate().translationX(target).setDuration(205).setInterpolator(DecelerateInterpolator()).start()
        } else if (!positioned || changed) indicator.translationX = target
        positioned = true
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX=e.x; startY=e.y }
            MotionEvent.ACTION_MOVE -> if (abs(e.x-startX)>slop*2 && abs(e.x-startX)>abs(e.y-startY)*1.5) return true
        }
        return super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP && abs(e.x-startX)>dp(30)) {
            val target = (selectedTab + if (e.x > startX) 1 else -1).coerceIn(destinations.indices)
            if (target != selectedTab) select(target)
        }
        return true
    }
}
