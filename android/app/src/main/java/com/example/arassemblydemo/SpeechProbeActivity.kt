package com.example.arassemblydemo

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Device capability probe only: no cloud LLM, model downloads, or automatic speech. */
class SpeechProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var generation = 0
    private var active = false
    private var ready = false
    private var requestedEngine: String? = null
    private var selectedVoice: Voice? = null
    private var utterance: String? = null
    private var serial = 0
    private var engineInventory = JSONArray()
    private var voiceInventory = JSONArray()
    private var defaultEngineName: String? = null
    private var lastSelectedVoice: String? = null
    private var initializationResult: Int? = null
    private lateinit var status: TextView
    private lateinit var inventory: LinearLayout
    private lateinit var speakButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedEngine = savedInstanceState?.getString("engine")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 249, 252))
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(dp(20) + insets.systemWindowInsetLeft,
                    dp(12) + insets.systemWindowInsetTop, dp(20) + insets.systemWindowInsetRight,
                    dp(12) + insets.systemWindowInsetBottom)
                insets
            }
        }
        root.addView(label("中文朗读 · 本机能力检查", 24f))
        root.addView(label("检测系统语音引擎与中文音色。此页只试读固定说明，不调用 AI，也不下载模型。"))
        status = label("等待初始化…").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        status.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { saveReport() }
        })
        root.addView(status)
        speakButton = button("朗读本步短句") { speak() }.apply { isEnabled = false }
        root.addView(speakButton)
        root.addView(button("停止朗读") { stopSpeech(); status.text = "已停止。可再次点击朗读。" })
        root.addView(button("重新检测当前引擎") { initialize() })
        root.addView(button("返回") { finish() })
        inventory = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(inventory) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.requestApplyInsets()
    }

    override fun onStart() {
        super.onStart()
        active = true
        initialize()
    }

    private fun initialize() {
        releaseEngine()
        if (!active) return
        val token = generation
        initializationResult = null
        engineInventory = JSONArray()
        voiceInventory = JSONArray()
        defaultEngineName = null
        lastSelectedVoice = null
        status.text = "正在初始化${requestedEngine ?: "系统默认语音引擎"}…"
        inventory.removeAllViews()
        main.postDelayed({
            if (active && generation == token && !ready) {
                releaseEngine()
                status.text = "初始化超时。请检查系统语音引擎，随后点击重新检测。"
            }
        }, 10_000)
        try {
            // Always post: a synchronous failure callback must not run before assignment to tts.
            val callback = TextToSpeech.OnInitListener { result ->
                main.post {
                    if (active && generation == token) onInitialized(token, result)
                }
            }
            tts = if (requestedEngine == null) TextToSpeech(this, callback)
                else TextToSpeech(this, callback, requestedEngine)
        } catch (error: Exception) {
            releaseEngine()
            status.text = "无法启动语音引擎（${error.javaClass.simpleName}）。可重新检测。"
        }
    }

    private fun onInitialized(token: Int, result: Int) {
        initializationResult = result
        if (result != TextToSpeech.SUCCESS) {
            releaseEngine()
            status.text = "语音引擎初始化失败。请确认系统已安装语音引擎及中文资源，再重新检测。"
            return
        }
        val engine = tts ?: return
        ready = true
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            private fun report(id: String?, text: String) = main.post {
                if (active && generation == token && utterance == id) status.text = text
            }
            override fun onStart(utteranceId: String?) { report(utteranceId, "正在朗读固定说明…") }
            override fun onDone(utteranceId: String?) {
                report(utteranceId, "引擎报告朗读完成。请人工确认是否听到清晰中文；离线能力仍需断网复测。")
            }
            @Deprecated("Legacy callback")
            override fun onError(utteranceId: String?) { report(utteranceId, "朗读失败。请检查语音资源后重试。") }
            override fun onError(utteranceId: String?, errorCode: Int) {
                report(utteranceId, "朗读失败（$errorCode）。请检查语音资源后重试。")
            }
        })
        try {
            val engines = engine.engines.orEmpty()
            engineInventory = JSONArray().apply {
                engines.forEach { put(JSONObject().put("package", it.name).put("label", it.label)) }
            }
            defaultEngineName = engine.defaultEngine
            inventory.addView(label("已发现的系统引擎（点击切换检测）：", 18f))
            inventory.addView(button("使用系统默认引擎") { requestedEngine = null; initialize() })
            if (engines.isEmpty()) inventory.addView(label("未枚举到引擎；请同时检查应用的 TTS 查询声明。"))
            engines.forEach { info ->
                inventory.addView(button("${info.label}\n${info.name}") { requestedEngine = info.name; initialize() })
            }
            inventory.addView(label("请求引擎：${requestedEngine ?: "系统默认"}\n系统默认：${engine.defaultEngine ?: "未知"}"))
            val voices = engine.voices.orEmpty().filter { it.locale.language == "zh" }
                .sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }
                    .thenBy { it.locale.country != "CN" }.thenBy { it.name })
            voiceInventory = JSONArray().apply {
                voices.forEach { voice -> put(JSONObject()
                    .put("name", voice.name).put("locale", voice.locale.toLanguageTag())
                    .put("networkRequired", voice.isNetworkConnectionRequired)
                    .put("notInstalled", voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true)) }
            }
            inventory.addView(label("中文音色：${voices.size} 个", 18f))
            voices.forEach { voice ->
                val needsData = voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true
                val mode = if (voice.isNetworkConnectionRequired) "需要网络" else "引擎声明本地"
                inventory.addView(label("${voice.name}\n${voice.locale.toLanguageTag()} · $mode" +
                    if (needsData) " · 资源待安装" else ""))
                if (!voice.isNetworkConnectionRequired && !needsData) {
                    inventory.addView(button("使用此本地候选音色") { selectVoice(voice) })
                }
            }
            val local = voices.firstOrNull { !it.isNetworkConnectionRequired &&
                it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
            if (local == null) {
                status.text = "初始化成功，但未找到可用的本地中文候选音色。请在系统语音设置中准备中文资源后重新检测；当前不自动改用联网朗读。"
            } else selectVoice(local)
        } catch (error: Exception) {
            status.text = "读取音色失败（${error.javaClass.simpleName}）。请重新检测。"
            speakButton.isEnabled = false
        }
    }

    private fun selectVoice(voice: Voice) {
        stopSpeech()
        selectedVoice = null
        speakButton.isEnabled = false
        try {
            if (tts?.setVoice(voice) == TextToSpeech.SUCCESS) {
                selectedVoice = voice
                lastSelectedVoice = voice.name
                speakButton.isEnabled = true
                status.text = "已选 ${voice.name}。引擎声明无需网络；请点击试读并断网复测后再确认离线可用。"
            } else status.text = "引擎未接受此音色，请选择其他候选或重新检测。"
        } catch (error: Exception) {
            status.text = "音色设置失败（${error.javaClass.simpleName}）。请重新检测。"
        }
    }

    private fun speak() {
        val engine = tts ?: return
        if (!active || !ready || selectedVoice == null) return
        stopSpeech()
        val id = "speech-probe-$generation-${++serial}"
        utterance = id
        try {
            engine.setSpeechRate(0.95f)
            val result = engine.speak("请找到紫色层板的两半。对齐榫卯后直插，装好以后再点击确认完成。",
                TextToSpeech.QUEUE_FLUSH, null, id)
            status.text = if (result == TextToSpeech.SUCCESS) "朗读请求已提交，等待引擎回调。"
                else "引擎拒绝朗读请求，请重新检测。"
        } catch (error: Exception) {
            status.text = "朗读请求失败（${error.javaClass.simpleName}）。"
        }
    }

    private fun stopSpeech() {
        utterance = null
        runCatching { tts?.stop() }
    }

    private fun releaseEngine() {
        generation++
        ready = false
        selectedVoice = null
        if (::speakButton.isInitialized) speakButton.isEnabled = false
        stopSpeech()
        runCatching { tts?.shutdown() }
        tts = null
        main.removeCallbacksAndMessages(null)
    }

    override fun onStop() {
        active = false
        releaseEngine()
        status.text = "页面已停止，语音已停止并释放；返回后重新检测。"
        super.onStop()
    }

    override fun onDestroy() {
        releaseEngine()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("engine", requestedEngine)
        super.onSaveInstanceState(outState)
    }

    /** Small diagnostic snapshot only; contains engine metadata, no user text or account data. */
    private fun saveReport() {
        if (!::status.isInitialized) return
        runCatching {
            val report = JSONObject()
                .put("schema", "speech-probe-v1")
                .put("timestampEpochMs", System.currentTimeMillis())
                .put("generation", generation)
                .put("active", active).put("initialized", ready)
                .put("initializationResult", initializationResult ?: JSONObject.NULL)
                .put("requestedEngine", requestedEngine ?: JSONObject.NULL)
                .put("systemDefaultEngine", defaultEngineName ?: JSONObject.NULL)
                .put("engines", engineInventory).put("chineseVoices", voiceInventory)
                .put("selectedVoice", selectedVoice?.name ?: JSONObject.NULL)
                .put("lastSelectedVoice", lastSelectedVoice ?: JSONObject.NULL)
                .put("status", status.text.toString())
                .put("offlineAudibleValidation", "NOT_VERIFIED_BY_PROBE")
            File(filesDir, "speech-probe.json").writeText(report.toString(2), Charsets.UTF_8)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float = 16f) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Color.rgb(28, 39, 58)); setPadding(0, dp(7), 0, dp(7))
    }
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; setOnClickListener { action() }
    }
}
