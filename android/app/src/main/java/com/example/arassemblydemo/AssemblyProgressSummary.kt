package com.example.arassemblydemo

import android.content.Context
import org.json.JSONObject

/** Disposable, read-only home summary. The guide's scoped WebView record remains authoritative. */
object AssemblyProgressSummary {
    data class Snapshot(val owner:String,val scope:String,val done:Int,val total:Int,
        val prepared:Int,val preparationTotal:Int,val activeTitle:String)
    private const val PREFS="assembly-home-summary-v1"
    private fun workflow(context:Context)=JSONObject(context.assets.open("shelf-phone/workflow.json").bufferedReader().use{it.readText()})
    private fun scope(context:Context,flow:JSONObject):Pair<String,String>{
        val owner=LocalAccounts(context).current()?.id?:"guest"
        val revision=context.getSharedPreferences("assembly-bindings-v1",Context.MODE_PRIVATE).getString("revision","unregistered")?:"unregistered"
        return owner to "shelf-phone-r56:${flow.getString("sourceSha256")}:$owner:$revision"
    }
    internal fun parse(raw:String,expectedOwner:String,expectedScope:String,flow:JSONObject):Snapshot {
        require(raw.length<=24000)
        val value=JSONObject(raw);require(value.getString("storageKey")==expectedScope)
        val tasks=flow.getJSONArray("tasks");val byId=(0 until tasks.length()).associate{
            val t=tasks.getJSONObject(it);t.getString("id") to t }
        val completed=value.getJSONArray("done");val ids=(0 until completed.length()).map{completed.getString(it)}
        require(ids.size==ids.toSet().size&&ids.all{it in byId})
        require(ids.all{id->val deps=byId.getValue(id).getJSONArray("requires");(0 until deps.length()).all{deps.getString(it) in ids}})
        val active=value.getString("active");require(active in byId)
        val prep=flow.getJSONArray("preparations");val p=(0 until prep.length()).map{prep.getString(it)}
        return Snapshot(expectedOwner,expectedScope,ids.size,byId.size,p.count{it in ids},p.size,byId.getValue(active).getString("title"))
    }
    fun publish(context:Context,raw:String):Boolean=runCatching {
        val flow=workflow(context);val(owner,key)=scope(context,flow)
        parse(raw,owner,key,flow)
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(owner,raw).commit()
    }.getOrDefault(false)
    fun read(context:Context):Snapshot?=runCatching {
        val flow=workflow(context);val(owner,key)=scope(context,flow)
        val raw=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(owner,null)?:return null
        parse(raw,owner,key,flow)
    }.getOrNull()
}
