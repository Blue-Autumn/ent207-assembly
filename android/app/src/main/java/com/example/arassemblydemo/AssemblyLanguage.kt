package com.example.arassemblydemo

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

internal object AssemblyLanguage {
    val choices = listOf("system", "zh-CN", "en")
    fun preference(context: Context): String =
        context.getSharedPreferences("assembly-language", Context.MODE_PRIVATE).getString("tag", "en") ?: "en"

    fun select(context: Context, tag: String) {
        require(tag in choices)
        context.getSharedPreferences("assembly-language", Context.MODE_PRIVATE).edit().putString("tag", tag).apply()
    }

    fun locale(context: Context): Locale {
        val language = when (val preferred = preference(context)) {
            "system" -> Resources.getSystem().configuration.locales[0].language
            else -> preferred
        }
        return if (language == "en") Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE
    }
    fun english(context: Context) = locale(context).language == "en"

    fun wrap(base: Context): Context = base.createConfigurationContext(
        Configuration(base.resources.configuration).apply { setLocale(locale(base)) }
    )

    fun usableVoice(voice: Voice?, language: Locale) = voice != null &&
        voice.locale.language == language.language && !voice.isNetworkConnectionRequired &&
        voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true

    fun rankedVoices(engine: TextToSpeech, language: Locale, preferredName: String? = null): List<Voice> {
        val target = if (language.language == "en") Locale.US else Locale.SIMPLIFIED_CHINESE
        val vendorChoice = if (engine.setLanguage(target) >= TextToSpeech.LANG_AVAILABLE) engine.voice?.name else null
        return engine.voices.orEmpty().filter { usableVoice(it, language) }.sortedWith(
            compareByDescending<Voice> { it.name == preferredName }
                .thenByDescending { it.quality }
                .thenByDescending { it.name == vendorChoice }
                .thenByDescending { it.locale.country == target.country }
                .thenBy { it.latency }.thenBy { it.name }
        )
    }

    fun setLocalVoice(engine: TextToSpeech, voice: Voice, language: Locale): Boolean =
        usableVoice(voice, language) && engine.setVoice(voice) == TextToSpeech.SUCCESS &&
            engine.voice?.let { it.name == voice.name && usableVoice(it, language) } == true

    fun chooseVoice(engine: TextToSpeech, language: Locale, preferredName: String? = null): Voice? =
        rankedVoices(engine, language, preferredName).firstOrNull { voice ->
            setLocalVoice(engine, voice, language)
        }
}
