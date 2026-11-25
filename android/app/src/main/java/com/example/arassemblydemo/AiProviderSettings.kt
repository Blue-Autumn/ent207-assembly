package com.example.arassemblydemo

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Per-device credential. Never bundled in the app, exposed to the WebView, or logged. */
internal class AiProviderSettings(context:Context) {
    private val file=AtomicFile(File(context.filesDir,"deepseek-direct.json"))
    fun isConfigured():Boolean=runCatching{load();true}.getOrDefault(false)
    fun load():String= synchronized(lock) {
        try {
            val bytes=file.openRead().use { stream ->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(1024)
                while(true){val n=stream.read(buffer);if(n<0)break;require(output.size()+n<=4096);output.write(buffer,0,n)}
                output.toByteArray().also{require(it.isNotEmpty())}
            }
            val json=JSONObject(bytes.toString(Charsets.UTF_8))
            require(json.getString("base_url").trimEnd('/')=="https://api.deepseek.com")
            require(json.getString("model")==AiDirectProtocol.MODEL)
            json.getString("api_key").also { require(validKey(it)) }
        } catch(_:Exception){throw AiReviewException("NOT_CONFIGURED","请先在首页的 AI 设置里填写 DeepSeek API Key。")}
    }
    fun save(apiKey:String)= synchronized(lock) {
        val key=apiKey.trim();require(validKey(key)){"API Key 格式不完整，请重新粘贴。"}
        val bytes=JSONObject().put("schema",1).put("base_url","https://api.deepseek.com")
            .put("model",AiDirectProtocol.MODEL).put("api_key",key).toString().toByteArray(Charsets.UTF_8)
        val stream=file.startWrite()
        try {stream.write(bytes);file.finishWrite(stream)}
        catch(e:Exception){file.failWrite(stream);throw AiReviewException("CONFIG_SAVE_FAILED","AI 设置没有保存成功，请重试。")}
    }
    fun clear()= synchronized(lock){file.delete()}
    companion object {
        private val lock=Any()
        internal fun validKey(value:String)=value.length in 16..256&&value.all { it.code in 33..126 }
    }
}
