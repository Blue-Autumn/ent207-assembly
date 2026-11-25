package com.example.arassemblydemo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.UUID

/** Spoken result in the selected language, local installed voice only. Stop also cancels initialization-time pending speech. */
internal class AiReplySpeech(context:Context,private val onState:(Boolean,Boolean)->Unit) {
    private val appContext=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private val language=AssemblyLanguage.locale(context)
    private val choice=AssemblyVoicePreferences.selection(context,language)
    private var engine:TextToSpeech?=null
    private var closed=false
    private var ready=false
    private var initialized=false
    private var pending:String?=null
    private var utterance:String?=null
    var speaking=false;private set
    init {
        engine=TextToSpeech(context.applicationContext, { status -> main.post {
            if(closed)return@post
            val tts=engine?:return@post
            initialized=true
            if(status==TextToSpeech.SUCCESS) {
                ready=runCatching {
                    AssemblyLanguage.chooseVoice(tts, language, choice.voice) != null
                }.getOrDefault(false)
                if(ready){tts.setSpeechRate(0.96f);tts.setPitch(1f)}
            }
            tts.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
                override fun onStart(id:String?){main.post { if(!closed&&id==utterance){speaking=true;onState(true,ready)} }}
                override fun onDone(id:String?){finished(id)}
                @Deprecated("Legacy TTS error callback") override fun onError(id:String?){finished(id)}
                override fun onError(id:String?,errorCode:Int){finished(id)}
            })
            speaking=false;onState(false,ready)
            val text=pending;pending=null;if(ready&&text!=null)speak(text)
        } }, choice.engine)
    }
    private fun finished(id:String?){main.post { if(!closed&&id==utterance){utterance=null;speaking=false;onState(false,ready)} }}
    fun speak(text:String) {
        if(closed)return
        stop()
        if(!AssemblyVoicePreferences.enabled(appContext))return
        if(!initialized){pending=text;speaking=true;onState(true,false);return}
        if(!ready || !runCatching { AssemblyLanguage.usableVoice(engine?.voice,language) }.getOrDefault(false)){onState(false,false);return}
        val id=UUID.randomUUID().toString();utterance=id
        val status=engine?.speak(text.take(1800),TextToSpeech.QUEUE_FLUSH,null,id)
        if(status!=TextToSpeech.SUCCESS){utterance=null;speaking=false;onState(false,ready)}
        else {speaking=true;onState(true,ready)}
    }
    fun stop(){pending=null;utterance=null;speaking=false;runCatching{engine?.stop()};if(!closed)onState(false,ready)}
    fun close(){stop();closed=true;runCatching{engine?.shutdown()};engine=null}
}
