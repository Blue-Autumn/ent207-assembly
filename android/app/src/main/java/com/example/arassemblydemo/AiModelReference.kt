package com.example.arassemblydemo

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest

/** Optional trusted CAD expectation, independently rendered from the installed workflow. */
internal object AiModelReference {
    private const val ROOT="shelf-phone/ai-reference/"
    private fun entry(context:Context,taskId:String):JSONObject? = runCatching {
        val index=JSONObject(context.assets.open(ROOT+"index.json").bufferedReader().use{it.readText()})
        require(index.getString("schema")=="assembly-ai-model-reference-1")
        val workflow=JSONObject(context.assets.open("shelf-phone/workflow.json").bufferedReader().use{it.readText()})
        require(index.getString("assembly")==workflow.getString("assembly")&&index.getString("actionsSource")==workflow.getString("sourceSha256"))
        val tasks=workflow.getJSONArray("tasks")
        val task=(0 until tasks.length()).map{tasks.getJSONObject(it)}.single{it.getString("id")==taskId}
        val entries=index.getJSONArray("entries")
        (0 until entries.length()).map{entries.getJSONObject(it)}.single{it.getString("task")==taskId}.also{
            require(it.getString("file").matches(Regex("step-[0-9]{2}\\.png")))
            require(it.getInt("width")==1200&&it.getInt("height")==640&&it.getInt("amount")==1)
            require(it.getJSONArray("parts").toString()==task.getJSONArray("context").toString())
            require(it.getJSONArray("members").toString()==task.getJSONArray("members").toString())
            val expected=task.getJSONObject("workPose");val actual=it.getJSONObject("workPose")
            require(actual.getInt("normalAxis")==expected.getInt("normalAxis")&&actual.getInt("upSign")==expected.getInt("upSign"))
        }
    }.getOrNull()
    fun metadata(context:Context,taskId:String):JSONObject?=runCatching{
        val info=entry(context,taskId)?:return null
        context.assets.open(ROOT+info.getString("file")).use{require(it.available()==info.getInt("bytes"))}
        JSONObject().put("available",true).put("task",taskId).put("source","trusted_CAD_expected_state")
            .put("views",2).put("faceRule",info.getString("faceRule"))
            .put("faceConvention","A is positive CAD thin-axis face; B is negative. Same convention as the author registration.")
            .put("workPose",info.getJSONObject("workPose"))
            .put("referenceViews",info.getJSONArray("views"))
            .put("registeredToPhoto",false)
            .put("note","本步CAD期望终态的两个视角，不是现场照片，也未配准到实拍。")
    }.getOrNull()
    fun load(context:Context,taskId:String):JSONObject?=runCatching {
        val info=entry(context,taskId)?:return null
        val bytes=context.assets.open(ROOT+info.getString("file")).use{stream->
            val data=stream.readBytes();require(data.size in 1..4*1024*1024);data}
        require(bytes.size==info.getInt("bytes"))
        val digest=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        require(digest==info.getString("sha256"))
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth==info.getInt("width")&&bounds.outHeight==info.getInt("height"))
        JSONObject().put("role","model_reference").put("mime","image/png")
            .put("width",bounds.outWidth).put("height",bounds.outHeight).put("base64",Base64.encodeToString(bytes,Base64.NO_WRAP))
    }.getOrNull()
}
