package com.example.arassemblydemo

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.util.concurrent.Executors

/** Local reviewed explanations only. No camera, network, secret, or assembly-state write. */
class StepCoachActivity : ComponentActivity() {
    private lateinit var coachContext: CoachContext
    private lateinit var answerView: TextView
    private val gate = CoachRequestGate()
    private val provider: StepCoachProvider = OfflineStepCoachProvider()
    private val executor = Executors.newSingleThreadExecutor()
    private var active = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parsed = readContext(intent)
        if (parsed == null) { finish(); return }
        coachContext = parsed
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(20))
            setBackgroundColor(Color.rgb(247,249,252))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(dp(22),dp(12)+insets.systemWindowInsetTop,dp(22),dp(20)+insets.systemWindowInsetBottom)
            insets
        }
        fun label(text: String, size: Float = 18f) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(Color.rgb(28,39,58))
            setPadding(0,dp(8),0,dp(8)); root.addView(this)
        }
        label("离线步骤帮助（非在线AI）",24f)
        label("${ShelfRules.colorName(coachContext.layer)}层 · 两半预装")
        label(if (coachContext.screenOnly) "屏幕练习：这些说明不表示完成实物装配。" else "依据已确认的层配对和直插规则；不能代替你检查实物。",16f)
        listOf(OfflineStepCoachProvider.WHY,OfflineStepCoachProvider.SIMPLE,OfflineStepCoachProvider.STUCK).forEach { question ->
            root.addView(Button(this).apply { text = question; isAllCaps = false; setOnClickListener { ask(question) } },LinearLayout.LayoutParams(-1,-2))
        }
        val input = EditText(this).apply {
            hint = "输入本步问题（离线仅支持上述问题及少量同义表达）"
            minLines = 2; maxLines = 4; filters = arrayOf(InputFilter.LengthFilter(300))
            root.addView(this,LinearLayout.LayoutParams(-1,-2))
        }
        root.addView(Button(this).apply { text = "查看离线说明"; setOnClickListener { ask(input.text.toString()) } },LinearLayout.LayoutParams(-1,-2))
        answerView = label("选择一个问题查看说明。没有在线AI请求，不保存你的问答。")
        root.addView(Button(this).apply {
            text = "取消当前回答"; setOnClickListener { gate.invalidate(); answerView.text = "已取消。可重新选择问题。" }
        },LinearLayout.LayoutParams(-1,-2))
        root.addView(Button(this).apply { text = "返回装配"; setOnClickListener { finish() } },LinearLayout.LayoutParams(-1,-2))
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    override fun onStart() {
        super.onStart()
        if (!::coachContext.isInitialized || !sameAccount()) { finish(); return }
        active = true
    }

    private fun sameAccount(): Boolean =
        (LocalAccounts(this).current()?.id ?: "guest") == coachContext.accountId

    private fun ask(question: String) {
        if (!active || !sameAccount()) { gate.invalidate(); finish(); return }
        val token = gate.begin(coachContext)
        answerView.text = "正在查阅本机步骤说明…"
        executor.execute {
            val result = try { provider.answer(token.context,question) }
            catch (_: Exception) { CoachAnswer("本机步骤说明暂时不可用。请返回指导页核对，不据此判断装配。",false) }
            runOnUiThread {
                if (!active || isFinishing || isDestroyed) return@runOnUiThread
                if (!sameAccount()) { gate.invalidate(); finish(); return@runOnUiThread }
                if (gate.accept(token,coachContext)) answerView.text = "来源：本机已审核规则 · 非在线AI\n\n${result.text}"
            }
        }
    }

    override fun onStop() {
        active = false; gate.invalidate()
        if (::answerView.isInitialized) answerView.text = "返回后请重新选择问题，旧回答已清除。"
        super.onStop()
    }

    override fun onDestroy() { gate.invalidate(); executor.shutdownNow(); super.onDestroy() }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun createIntent(context: Context, facts: CoachContext): Intent = Intent(context,StepCoachActivity::class.java)
            .putExtra("coach.account",facts.accountId).putExtra("coach.run",facts.runId)
            .putExtra("coach.version",facts.contentVersion).putExtra("coach.layer",facts.layer)
            .putExtra("coach.step",facts.stepId).putExtra("coach.screenOnly",facts.screenOnly)

        private fun readContext(intent: Intent): CoachContext? = try {
            if (!intent.hasExtra("coach.screenOnly")) null else CoachContext(
                intent.getStringExtra("coach.account") ?: "",intent.getStringExtra("coach.run") ?: "",
                intent.getStringExtra("coach.version") ?: "",intent.getIntExtra("coach.layer",0),
                intent.getStringExtra("coach.step") ?: "",intent.getBooleanExtra("coach.screenOnly",true))
        } catch (_: IllegalArgumentException) { null }
    }
}
