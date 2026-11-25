package com.example.arassemblydemo

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/** Phone-to-provider HTTPS only; credentials stay in this app's private per-device configuration. */
internal class AiReviewClient(context: Context) {
    private val application=context.applicationContext
    private val cancelled=AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection?=null

    fun cancel(){cancelled.set(true);connection?.disconnect()}
    private fun checkCancelled(){if(cancelled.get())throw AiReviewException("CANCELLED","检查已取消")}

    fun review(payload:JSONObject,requestId:String,taskId:String):AiReviewResult {
        checkCancelled()
        val credential=AiProviderSettings(application).load()
        val prompt=application.assets.open("ai-review-prompt.txt").bufferedReader().use{it.readText()}
        val body=AiDirectProtocol.request(payload,requestId,taskId,prompt).toString().toByteArray(Charsets.UTF_8)
        if(body.size>AiDirectProtocol.MAX_BODY)throw AiReviewException("IMAGE_TOO_LARGE","照片太大，请重新拍摄。")
        checkCancelled()
        val conn=URL(AiDirectProtocol.ENDPOINT).openConnection() as HttpsURLConnection
        connection=conn
        val timedOut=AtomicBoolean(false)
        val deadline=timer.schedule({timedOut.set(true);conn.disconnect()},60,TimeUnit.SECONDS)
        try {
            checkCancelled()
            conn.requestMethod="POST";conn.connectTimeout=8_000;conn.readTimeout=60_000
            conn.instanceFollowRedirects=false;conn.doOutput=true
            conn.setRequestProperty("Content-Type","application/json; charset=utf-8")
            conn.setRequestProperty("Authorization","Bearer $credential")
            conn.setFixedLengthStreamingMode(body.size)
            checkCancelled();conn.outputStream.use { it.write(body) }
            val status=conn.responseCode
            if(status !in 200..299)throw AiDirectProtocol.httpError(status)
            val bytes=ByteArrayOutputStream()
            conn.inputStream.use { stream -> val buffer=ByteArray(4096)
                while(true){checkCancelled();val n=stream.read(buffer);if(n<0)break
                    if(bytes.size()+n>64*1024)throw AiReviewException("INVALID_RESPONSE","AI 的回答过长，请重试。")
                    bytes.write(buffer,0,n)
                }
            }
            checkCancelled()
            if(timedOut.get())throw AiReviewException("TIMEOUT","AI 这次等得有点久，可以重试或先按图核对。")
            val json=try{JSONObject(bytes.toString("UTF-8"))}catch(_:Exception){throw AiReviewException("INVALID_RESPONSE","AI 的回答格式不完整，请重试。")}
            return AiDirectProtocol.response(json,payload,requestId,taskId)
        } catch(e:Exception){
            if(cancelled.get())throw AiReviewException("CANCELLED","检查已取消")
            if(timedOut.get()||e is java.net.SocketTimeoutException)throw AiReviewException("TIMEOUT","AI 这次等得有点久，可以重试或先按图核对。")
            if(e is AiReviewException)throw e
            throw AiReviewException("UNREACHABLE","手机暂时连不上 DeepSeek。请检查 Wi-Fi 或移动网络，再重试。")
        } finally {deadline.cancel(false);conn.disconnect();connection=null}
    }
    companion object {
        private val timer=Executors.newSingleThreadScheduledExecutor { action->Thread(action,"ai-request-deadline").apply{isDaemon=true} }
    }
}

internal class AiReviewException(val code:String,message:String):Exception(message)

internal data class AiReviewResult(val assessment:String,val headline:String,val instruction:String,
    val findings:List<String>,val viewRequest:String,val orientationAction:AiOrientationAction?=null) {
    companion object {
        fun parse(json:JSONObject,requestId:String,taskId:String):AiReviewResult {
            try {
                require(json.get("ok")==true)
                require(json.getString("schema")=="assembly-ai-review-result-1")
                require(json.getString("requestId")==requestId && json.getString("taskId")==taskId)
                require(json.getString("provider")=="DeepSeek" && json.getString("model")=="deepseek-flash")
                require(json.get("automaticCompletion")==false)
                val assessment=json.getString("assessment")
                require(assessment in setOf("looks_ok","needs_adjustment","need_view"))
                fun string(name:String,limit:Int,empty:Boolean=false):String {
                    require(json.get(name) is String)
                    val s=json.getString(name).trim()
                    require((empty||s.isNotEmpty())&&s.length<=limit && s.none { it.code<32&&it!='\n'&&it!='\t' })
                    return s
                }
                val findings=json.getJSONArray("findings");require(findings.length()<=6)
                val items=(0 until findings.length()).map { index ->
                    require(findings.get(index) is String);findings.getString(index).trim().also {
                        require(it.length in 1..300 && it.none { c -> c.code<32&&c!='\n'&&c!='\t' }) } }
                val action=runCatching { val value=json.getJSONObject("orientation_action")
                    require(value.length()==3&&value.get("flat_pair_visible")==true)
                    AiOrientationAction(value.getString("kind"),value.getString("part"),true).also { require(it.valid) }
                }.getOrNull()
                return AiReviewResult(assessment,string("headline",80),string("instruction",700),items,
                    string("view_request",400,assessment!="need_view"),action)
            } catch(_:Exception){throw AiReviewException("INVALID_RESPONSE","AI 回答与这次照片没有对应好，请重试。")}
        }
    }
}
