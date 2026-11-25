package com.example.arassemblydemo

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Manual preparation checklist, deliberately separate from unverified assembly instructions. */
class GuidanceActivity : Activity() {
    private data class Preparation(val title: String, val explanation: String)

    private val items = listOf(
        Preparation(
            "核对灰色插入销",
            "请找到灰色插入销，与自己提供的“插入销.stl”对应。\n\n" +
                "已确认信息：灰色、模型以毫米导出、打印比例100%。模型外包尺寸约61.8 × 13.2 × 15.4毫米，供核对零件身份，不是装配间隙要求。\n\n" +
                "本页不会调用摄像头，也不会判断你手里的零件是否正确。若实物与这些信息不一致，请记录差异并告诉我们。",
        ),
        Preparation(
            "准备正确装配顺序",
            "请保留已有说明书或能正确完成装配的实物演示，后续用于确认步骤。现在不用重新拍摄或上传。\n\n" +
                "需要确认的内容：最先使用哪些零件、每一步从哪个方向移动、是否需要先留空再回位，以及容易装错的位置。\n\n" +
                "已收到用户按实物拆卸记录的15段动作。返回主页进入“逐步装配”可查看反向动画；具体轨迹仍需结合实物核对。",
        ),
    )
    private val preferences by lazy { getSharedPreferences("guidance_preparation", MODE_PRIVATE) }
    private lateinit var progress: GuidanceProgress
    private lateinit var heading: TextView
    private lateinit var explanation: TextView
    private lateinit var position: TextView
    private lateinit var previous: Button
    private lateinit var next: Button
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        progress = GuidanceProgress.restore(
            CONTENT_VERSION,
            items.size,
            preferences.getString("content_version", null),
            preferences.getInt("current_index", 0),
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 249, 252))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(
                dp(20) + insets.systemWindowInsetLeft,
                dp(12) + insets.systemWindowInsetTop,
                dp(20) + insets.systemWindowInsetRight,
                dp(12) + insets.systemWindowInsetBottom,
            )
            insets
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(label("装配准备 · 流程预览", 24f))
        content.addView(label("当前没有实物识别；15步动画请从主页进入。", 16f), row())
        position = label("", 15f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addView(position, row())
        heading = label("", 22f)
        content.addView(heading, row())
        explanation = label("", 18f).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        content.addView(explanation, row())
        previous = button("上一项") { navigate(progress.previous()) }
        next = button("下一项") { navigate(progress.next()) }
        content.addView(previous, row())
        content.addView(next, row())
        content.addView(button("重看本项") {
            scroll.smoothScrollTo(0, 0)
            heading.announceForAccessibility("重新查看：${items[progress.currentIndex].title}")
        }, row())
        content.addView(label("仅保存你当前浏览到哪一项，不代表已识别或已完成装配。放下手机后，可以随时回来继续查看。", 14f), row())
        content.addView(button("从第一项重新查看") {
            AlertDialog.Builder(this)
                .setTitle("重置浏览位置？")
                .setMessage("将回到准备清单第一项，不修改模型或任何相机记录。")
                .setNegativeButton("取消", null)
                .setPositiveButton("回到第一项") { _, _ -> navigate(progress.reset()) }
                .show()
        }, row())
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(button("返回主页（已保存浏览位置）") { finish() }, row())
        setContentView(root)
        root.requestApplyInsets()
        render()
        persist()
    }

    private fun navigate(value: GuidanceProgress) {
        progress = value
        persist()
        render()
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun persist() {
        preferences.edit()
            .putString("content_version", CONTENT_VERSION)
            .putInt("current_index", progress.currentIndex)
            .apply()
    }

    private fun render() {
        position.text = "准备项 ${progress.currentIndex + 1} / ${items.size} · 浏览位置自动保存"
        heading.text = items[progress.currentIndex].title
        explanation.text = items[progress.currentIndex].explanation
        previous.isEnabled = progress.canGoPrevious
        next.isEnabled = progress.canGoNext
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(28, 39, 58))
    }

    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        minHeight = dp(48)
        setOnClickListener { action() }
    }

    private fun row() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val CONTENT_VERSION = "preparation-v1"
    }
}
