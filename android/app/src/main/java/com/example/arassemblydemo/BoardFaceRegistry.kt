package com.example.arassemblydemo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Restores declared side/corner identity while preserving the existing part-only progress revision. */
internal class BoardFaceRegistry private constructor(val assembly:String,val sites:List<BoardFaceRules.Site>,
    val source:String,val reason:String) {
    private val byId=sites.associateBy { it.id }
    val available:Boolean get()=sites.isNotEmpty()
    val fingerprint:String=sha(JSONArray(listOf(assembly,source,sites.sortedBy { it.id }.map {
        listOf(it.part,it.slot,it.id,it.axis,it.center) })).toString())
    fun lookup(id:Int):BoardFaceRules.Site?=byId[id]
    fun observe(markers:List<Marker>):List<BoardFaceRules.Observation> {
        val detections=markers.map { m->BoardFaceRules.Detection(m.id,
            if(m.points.size==8)(0..3).map { BoardFaceRules.Point(m.points[2*it].toDouble(),m.points[2*it+1].toDouble()) } else emptyList()) }
        return markers.mapNotNull { lookup(it.id)?.part }.distinct().map { BoardFaceRules.observe(it,sites,detections) }
    }
    fun describe(markers:List<Marker>):JSONObject=JSONObject().put("source",source).put("available",available)
        .put("reason",reason).put("metricPose",false).put("markerRotationUsed",false)
        .put("parts",JSONArray(observe(markers).map { observation ->
            JSONObject().put("part",observation.part).put("face",observation.face?:JSONObject.NULL)
                .put("markerIds",JSONArray(observation.ids)).put("reason",observation.reason)
                .put("normalAxis",sites.first { it.part==observation.part }.axis)
                .put("normalSign",when(observation.face){"A"->1;"B"->-1;else->JSONObject.NULL})
                .put("axisDirections",JSONArray(observation.axes.entries.sortedBy { it.key }.map { (axis,p) ->
                    val length=kotlin.math.hypot(p.x,p.y)
                    JSONObject().put("cadAxis",listOf("X","Y","Z")[axis])
                        .put("screenUnit",JSONArray(listOf(p.x/length,p.y/length)))
                        .put("screenPerModelUnit",JSONArray(listOf(p.x,p.y)))
                        .put("screenDegreesClockwise",observation.heading(axis)) }))
                .put("fitRmsPx",observation.rmsPx?:JSONObject.NULL).put("markerExtentPx",observation.extentPx?:JSONObject.NULL)
        }))
    fun analyze(markers:List<Marker>,task:JSONObject):JSONObject {
        fun strings(name:String):List<String> = task.optJSONArray(name)?.let { a->(0 until a.length()).map { a.getString(it) } }.orEmpty()
        val expected=strings("context").toSet()
        val all=observe(markers);val observed=all.filter { it.part in expected }
        val pairPolicy=task.optString("kind")=="prepare" && task.optString("group").startsWith("层") && expected.size==2
        val json=describe(markers).put("policy",if(pairPolicy)"layer_notch_direction" else "cad_assembly")
            .put("layerFacesUnrestricted",true).put("layerPreservedCadAxis","X")
            .put("evidence","author_declared_faces_and_candidate_corner_layout_not_measured_pose")
            .put("coplanarityVerified",false).put("automaticAssemblyVerification",false)
        val parts=json.getJSONArray("parts")
        json.put("parts",JSONArray((0 until parts.length()).map { parts.getJSONObject(it) }.filter { it.getString("part") in expected }))
        json.put("otherVisibleParts",JSONArray(all.filterNot { it.part in expected }.map { it.part }))
        if(pairPolicy) {
            val fixed=strings("stationary").firstOrNull()?:expected.first()
            val moving=expected.first { it!=fixed }
            val a=observed.firstOrNull { it.part==fixed };val b=observed.firstOrNull { it.part==moving }
            json.put("fixedPart",fixed).put("movingPart",moving)
            if(a==null||b==null)return json.put("status","insufficient")
                .put("instruction","正反面可以混用。把两半平放，并拍清每半三枚分散标签来核对缺口方向。")
            val result=BoardFaceRules.compareLayerPair(a,b)
            json.put("status","faces_unrestricted").put("instruction",result.message)
            json.put("directionStatus",result.status)
            if(result.status=="rotate_in_plane")json.put("rotationSuggestion",JSONObject().put("part",moving)
                .put("screenDegreesClockwise",result.angleDegrees).put("requiresFlatPair",true))
            return json
        }
        return json.put("status",if(observed.any { it.axes.isNotEmpty() })"orientation_observed" else "insufficient")
            .put("instruction","彩色层板正反面可混用，只保留缺口的CAD X方向；侧框仍按模型的面向和轴向核对。不同平面的A/B不能直接互相比，屏幕轴向仅为粗略参考。")
            .put("sharedAxisComparisons",JSONArray(observed.indices.flatMap { i->(i+1 until observed.size).map { j ->
                val deltas=BoardFaceRules.sharedAxisDifferences(observed[i],observed[j])
                JSONObject().put("partA",observed[i].part).put("partB",observed[j].part)
                    .put("axisDifferencesDegrees",JSONObject().apply { deltas.forEach { (axis,angle)->put(listOf("X","Y","Z")[axis],angle) } })
                    .put("verdict","not_a_3d_orientation_verdict") } }))
    }
    companion object {
        const val PREF_SITES="bindingSites"
        private fun sha(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
        /** Save this alongside bindings/revision in the same preferences transaction on future imports. */
        fun serializeSites(project:JSONObject):String {
            val raw=project.getJSONObject("sites")
            val rows=raw.keys().asSequence().toList().mapNotNull { key ->
                val entry=raw.getJSONObject(key);if(entry.isNull("tag"))return@mapNotNull null
                val split=key.split('|');require(split.size==2)
                val slot=split[1].toInt();require(split[1]==slot.toString()&&slot in 0..7)
                val id=entry.get("tag");require(id is Number&&id.toDouble()==id.toInt().toDouble()&&id.toInt() in 0..249)
                AssemblyBinding(split[0],slot,id.toInt())
            }
            AssemblyMarkerBindings.validate(rows,rows.map { it.part }.toSet())
            return JSONObject().put("schema","board-face-sites-1").put("assembly",project.getString("assembly"))
                .put("sites",JSONArray(rows.sortedWith(compareBy<AssemblyBinding>{it.part}.thenBy{it.slot}).map {
                    JSONObject().put("part",it.part).put("slot",it.slot).put("id",it.marker) })).toString()
        }
        fun load(context:Context,currentBindings:Map<String,Collection<Int>>):BoardFaceRegistry {
            val geometry=JSONObject(context.assets.open("shelf-phone/board-faces.json").bufferedReader().use { it.readText() })
            val assembly=geometry.getString("assembly")
            val sourceSites=geometry.getJSONArray("sites").let { a ->(0 until a.length()).map { i ->
                val s=a.getJSONObject(i);val p=s.getJSONArray("candidateCenter")
                BoardFaceRules.Site(s.getString("part"),s.getInt("slot"),s.getInt("id"),s.getInt("axis"),(0..2).map { p.getDouble(it) }) } }
            val known=sourceSites.map { it.part }.toSet()
            val prefs=context.getSharedPreferences("assembly-bindings-v1",0)
            val stored=prefs.getString(PREF_SITES,null)
            fun matches(sites:List<BoardFaceRules.Site>):Boolean = sites.groupBy { it.part }.mapValues { (_,v)->v.map { it.id }.toSet() } ==
                currentBindings.mapValues { it.value.toSet() }
            if(stored!=null) {
                return runCatching {
                    val json=JSONObject(stored);require(json.getString("schema")=="board-face-sites-1"&&json.getString("assembly")==assembly)
                    val entries=json.getJSONArray("sites").let { a -> (0 until a.length()).map { i ->val s=a.getJSONObject(i)
                        AssemblyBinding(s.getString("part"),s.getInt("slot"),s.getInt("id")) } }
                    AssemblyMarkerBindings.validate(entries,known)
                    val slots=sourceSites.associateBy { it.part to it.slot }
                    val active=entries.map { row -> slots.getValue(row.part to row.slot).copy(id=row.marker) }
                    require(matches(active))
                    BoardFaceRegistry(assembly,active,"authored_sites","当前导入工程声明的面与角位")
                }.getOrElse { BoardFaceRegistry(assembly,emptyList(),"unavailable","面登记与当前标签归属不一致，请重新导入教程工程。") }
            }
            return if(matches(sourceSites)) BoardFaceRegistry(assembly,sourceSites,"legacy_exact_binding_match","当前128码归属完整匹配用户已完成的正式工程，恢复其中面与角位。")
                else BoardFaceRegistry(assembly,emptyList(),"unavailable","只有零件归属，面与角位尚未恢复，请导入教程工程。")
        }
    }
}
