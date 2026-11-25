package com.example.arassemblydemo

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView

class AssemblyVoiceActivity : Activity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AssemblyLanguage.wrap(base))

    private data class Engine(val id: String, val label: String)

    private val language by lazy { AssemblyLanguage.locale(this) }
    private val savedChoice by lazy { AssemblyVoicePreferences.selection(this, language) }
    private val main = Handler(Looper.getMainLooper())
    private val ink = Color.rgb(40, 45, 36)
    private val surface = Color.rgb(238, 240, 230)
    private var generation = 0
    private var engine: TextToSpeech? = null
    private var engineId: String? = null
    private var voiceId: String? = null
    private var installed = emptyList<Engine>()
    private var voices = emptyList<Voice>()
    private lateinit var engineButton: Button
    private lateinit var voiceButton: Button
    private lateinit var preview: Button
    private lateinit var save: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LightSystemBars.configure(window)
        engineId = if (savedInstanceState == null) savedChoice.engine else savedInstanceState.getString("engine")
        voiceId = if (savedInstanceState == null) savedChoice.voice else savedInstanceState.getString("voice")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(250, 249, 245))
        }
        LightSystemBars.insetContent(root, dp(22), dp(14), dp(22), dp(18))
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        scroll.addView(body)
        body.addView(button(R.string.settings_back, transparent = true) { finish() }, -2, dp(48))
        body.addView(label(getString(R.string.voice_picker_title), 28f, true))
        body.addView(label(getString(R.string.voice_picker_intro), 16f))
        body.addView(label(getString(R.string.voice_picker_engine), 14f, true))
        engineButton = button(R.string.voice_picker_loading) { showEngines() }
        body.addView(engineButton, -1, dp(54))
        body.addView(label(getString(R.string.voice_picker_voice), 14f, true))
        voiceButton = button(R.string.voice_picker_loading) { showVoices() }
        body.addView(voiceButton, -1, dp(54))
        preview = button(R.string.voice_picker_preview, transparent = true) { playSample() }
        body.addView(preview, -1, dp(50))
        if (language.language == "en" && "arm64-v8a" in Build.SUPPORTED_ABIS) {
            body.addView(Space(this), 1, dp(20))
            body.addView(label(getString(R.string.voice_picker_extra), 15f))
            body.addView(button(R.string.voice_picker_download, transparent = true) {
                val url = Uri.parse("https://huggingface.co/csukuangfj/sherpa-onnx-apk/resolve/main/tts-engine-new/1.12.22/" +
                    "sherpa-onnx-1.12.22-arm64-v8a-en-tts-engine-vits-piper-en_US-ljspeech-medium.apk")
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }.onFailure {
                    AlertDialog.Builder(this).setMessage(url.toString()).setPositiveButton(android.R.string.ok, null).show()
                }
            }, -1, dp(48))
        }
        save = button(R.string.voice_picker_apply) {
            val choice = voices.firstOrNull { it.name == voiceId } ?: return@button
            AssemblyVoicePreferences.select(this, language, AssemblyVoicePreferences.Selection(engineId, choice.name))
            finish()
        }.apply {
            background = shape(ink)
            setTextColor(Color.WHITE)
        }
        root.addView(save, -1, dp(52))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        installed = packageManager.queryIntentServices(intent, 0).map {
            Engine(it.serviceInfo.packageName, it.loadLabel(packageManager).toString())
        }.distinctBy { it.id }.sortedBy { it.label }
        if (engineId != null && installed.none { it.id == engineId }) {
            engineId = null
            voiceId = null
        }
        connect()
    }

    private fun connect() {
        val token = ++generation
        engine?.stop()
        engine?.shutdown()
        engine = null
        voices = emptyList()
        engineButton.text = installed.find { it.id == engineId }?.label ?: getString(R.string.voice_picker_system)
        engineButton.isEnabled = installed.isNotEmpty()
        voiceButton.text = getString(R.string.voice_picker_loading)
        setControls(false)
        val ready = TextToSpeech.OnInitListener { status ->
            main.post {
                if (token != generation || isFinishing) return@post
                val tts = engine ?: return@post
                if (status == TextToSpeech.SUCCESS) {
                    val ranked = runCatching { AssemblyLanguage.rankedVoices(tts, language, voiceId) }.getOrDefault(emptyList())
                    voices = ranked
                    val candidate = ranked.firstOrNull { runCatching { AssemblyLanguage.setLocalVoice(tts, it, language) }.getOrDefault(false) }
                    if (candidate != null) {
                        voiceId = candidate.name
                        voiceButton.text = voiceLabel(candidate)
                        setControls(true)
                        return@post
                    }
                }
                voiceButton.setText(R.string.voice_picker_empty)
            }
        }
        runCatching { engine = TextToSpeech(applicationContext, ready, engineId) }.onFailure {
            voiceButton.setText(R.string.voice_picker_empty)
        }
    }

    private fun showEngines() {
        val names = listOf(getString(R.string.voice_picker_system)) + installed.map { it.label }
        AlertDialog.Builder(this).setTitle(R.string.voice_picker_engine)
            .setSingleChoiceItems(names.toTypedArray(), installed.indexOfFirst { it.id == engineId } + 1) { dialog, i ->
                dialog.dismiss()
                val selected = installed.getOrNull(i - 1)?.id
                if (selected != engineId) {
                    engineId = selected
                    voiceId = if (selected == savedChoice.engine) savedChoice.voice else null
                    connect()
                }
            }.show()
    }

    private fun showVoices() {
        AlertDialog.Builder(this).setTitle(R.string.voice_picker_voice)
            .setSingleChoiceItems(voices.map(::voiceLabel).toTypedArray(), voices.indexOfFirst { it.name == voiceId }) { dialog, i ->
                dialog.dismiss()
                val tts = engine ?: return@setSingleChoiceItems
                tts.stop()
                val selected = voices[i]
                if (runCatching { AssemblyLanguage.setLocalVoice(tts, selected, language) }.getOrDefault(false)) {
                    voiceId = selected.name
                    voiceButton.text = voiceLabel(selected)
                    setControls(true)
                } else {
                    voiceId = null
                    voiceButton.setText(R.string.voice_picker_empty)
                    preview.isEnabled = false
                    save.isEnabled = false
                }
            }.show()
    }

    private fun voiceLabel(v: Voice): String = "${v.locale.getDisplayName(resources.configuration.locales[0])} · ${v.name}"

    private fun playSample() {
        val tts = engine ?: return
        if (tts.voice?.name != voiceId || !AssemblyLanguage.usableVoice(tts.voice, language)) return
        tts.setSpeechRate(.95f)
        val status = tts.speak(getString(R.string.voice_picker_sample), TextToSpeech.QUEUE_FLUSH, null, "assembly-voice-preview")
        if (status != TextToSpeech.SUCCESS)
            android.widget.Toast.makeText(this, R.string.voice_picker_sample_failed, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun setControls(ready: Boolean) {
        voiceButton.isEnabled = ready
        preview.isEnabled = ready
        save.isEnabled = ready
        save.alpha = if (ready) 1f else .45f
    }

    private fun label(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(ink)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(8))
    }

    private fun button(title: Int, transparent: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = getString(title)
        isAllCaps = false
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setTextColor(ink)
        stateListAnimator = null
        background = shape(if (transparent) Color.TRANSPARENT else surface)
        setOnClickListener { onClick() }
    }

    private fun shape(color: Int) = GradientDrawable().apply { cornerRadius = dp(17).toFloat(); setColor(color) }
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("engine", engineId)
        outState.putString("voice", voiceId)
    }

    override fun onPause() { engine?.stop(); super.onPause() }
    override fun onStop() {
        ++generation
        engine?.shutdown()
        engine = null
        super.onStop()
    }
}
