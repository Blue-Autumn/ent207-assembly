package org.json

/** Minimal JSON reader for the immutable public geometry asset, using JS JSON.parse. */
internal class JSONObject(private val obj:dynamic, raw:Unit) {
    constructor(text:String):this(JSON.parse<dynamic>(text),Unit)
    fun getString(key:String):String = obj[key] as String
    fun getInt(key:String):Int = obj[key] as Int
    fun getJSONArray(key:String):JSONArray = JSONArray(obj[key])
    fun optJSONArray(key:String):JSONArray? = if(obj[key]==null)null else JSONArray(obj[key])
}

internal class JSONArray(private val data:dynamic) {
    fun length():Int = data.length as Int
    fun getJSONObject(i:Int):JSONObject = JSONObject(data[i],Unit)
    fun getJSONArray(i:Int):JSONArray = JSONArray(data[i])
    fun getDouble(i:Int):Double = data[i] as Double
    fun getInt(i:Int):Int = data[i] as Int
}

