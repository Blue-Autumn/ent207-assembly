package com.example.arassemblydemo

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Same-photo evidence for the two halves of ONE gray frame. Never compare unrelated planes. */
internal object AiFrameOrientation {
    private val frameGroups = setOf("左侧板", "右侧板")
    private fun strings(a: JSONArray?) = a?.let { (0 until it.length()).mapNotNull { i -> it.opt(i) as? String } }.orEmpty()
    private fun group(task: JSONObject) = task.optString("group").ifBlank { task.optString("id").substringAfter(':') }
    private fun framePreparation(task: JSONObject) = task.optString("kind") == "prepare" && group(task) in frameGroups

    fun analyze(task: BoardLocalGuidance.Task, sites: List<BoardFaceRules.Site>,
                detections: List<BoardFaceRules.Detection>, captureId: String,
                measured: MeasuredBoardProjection.Catalog? = null, width:Int=0,height:Int=0): JSONObject {
        if(FinalAssemblyGuidance.supports(task)){
            val guidance=FinalAssemblyGuidance.evaluate(task,sites,detections,measured,width,height)
            return JSONObject().put("schema","final-assembly-check-1").put("taskId",task.id)
                .put("captureId",captureId).put("status",guidance.status).put("title",guidance.title)
                .put("instruction",guidance.message).put("markerIds",JSONArray(guidance.markerIds))
                .put("observedRow",guidance.observedRow).put("targetRow",guidance.targetRow)
                .put("notchRelation",guidance.notchRelation ?: "unknown")
                .put("installMotion",guidance.installMotion ?: "")
                .put("capMarkers",JSONArray(guidance.markerIds.distinct().filter { id->sites.any { it.id==id && it.part.startsWith("右侧板-") } }))
                .put("panelMarkers",JSONArray(guidance.markerIds.distinct().filter { id->sites.any { it.id==id && it.part in task.members } }))
                .put("baseMarkers",JSONArray(guidance.markerIds.distinct().filter { id->sites.any { it.id==id && it.part.startsWith("左侧板-") } }))
                .put("seatingVerified",false)
        }
        val required = task.kind == "prepare" && task.group in frameGroups
        val parts = task.parts.distinct()
        val out = JSONObject().put("schema", "frame-pair-check-1").put("required", required)
            .put("taskId", task.id).put("captureId", captureId).put("parts", JSONArray(parts))
            .put("scope", "current_gray_frame_pair").put("status", if(required) "insufficient" else "not_applicable")
            .put("seatingVerified", false).put("installationOrientationVerified", false)
        if (!required || parts.size != 2 || parts.any { it.substringBeforeLast('-') != task.group }) return out
        val own = sites.filter { it.part in parts }.associateBy { it.id }
        val seen = detections.filter { it.id in own }
        if (seen.isEmpty() || seen.map { it.id }.distinct().size != seen.size || seen.any { !validQuad(it) })
            return out.put("reason", "missing_or_ambiguous_markers")
        val observations = parts.map { BoardFaceRules.observe(it, own.values.toList(), seen) }
        out.put("faces", JSONArray(observations.map { o -> JSONObject().put("part", o.part)
            .put("face", o.face ?: JSONObject.NULL).put("markerIds", JSONArray(o.ids)) }))
        if (observations.any { it.face == null }) return out.put("reason", "one_face_per_half_not_observed")
        // A registered ID identifies the surface. Whole-plane geometry is useful for animation,
        // but a close-up of a known mating endpoint need not reconstruct the entire board.
        if (observations.map { it.face }.distinct().size != 1)
            return out.put("status", "face_mismatch").put("reason", "registered_faces_differ_same_photo")
        val local = BoardLocalGuidance.evaluate(sites, seen, task, measured)
        val status = when(local.status) { "direction_ok" -> "aligned"; "rotate" -> "rotate"; else -> "insufficient" }
        out.put("status", status).put("reason", "same_photo_local_guidance_${local.status}")
        local.part?.let { out.put("movingPart", it) }
        local.targetLabel?.takeIf { it != "箭头指的这块" }?.let { out.put("targetLabel", it) }
        local.angleDegrees?.let { out.put("angleDegrees", it) }
        if(status == "insufficient") {
            val joint = jointEvidence(parts, own, seen)
            out.put("jointEvidence", joint)
            if(joint.optString("status") in setOf("joint_match", "joint_mismatch"))
                out.put("status", joint.getString("status")).put("reason", "registered_local_connector_endpoints")
        }
        return out
    }

    /** This rack's two mating ends use slots 1/2 on half 1 and 0/3 on half 2 (B adds 4).
     * IDs come from the active registration, never from printed numbers or tag bitmap orientation.
     * Mutual-nearest neighboring labels identify a local connection, NOT a full-plane pose.
     */
    private fun jointEvidence(parts: List<String>, sites: Map<Int, BoardFaceRules.Site>,
                              seen: List<BoardFaceRules.Detection>): JSONObject {
        data class End(val marker: BoardFaceRules.Detection, val endpoint: Int, val center: BoardFaceRules.Point, val size: Double)
        fun ends(part: String): List<End> = seen.mapNotNull { d ->
            val site = sites.getValue(d.id)
            if(site.part != part) return@mapNotNull null
            val end = when(part.substringAfterLast('-') to (site.slot % 4)) {
                "1" to 1, "2" to 0 -> 0
                "1" to 2, "2" to 3 -> 1
                else -> return@mapNotNull null
            }
            val p=d.corners
            val area=abs(p.indices.sumOf { i->p[i].x*p[(i+1)%4].y-p[(i+1)%4].x*p[i].y })/2
            End(d,end,BoardFaceRules.Point(p.sumOf { it.x }/4,p.sumOf { it.y }/4),sqrt(area))
        }
        fun distance(a: End,b: End)=hypot(a.center.x-b.center.x,a.center.y-b.center.y)
        val a=ends(parts[0]);val b=ends(parts[1]);val pairs=JSONArray()
        for(x in a) {
            val y=b.minByOrNull { distance(x,it) } ?: continue
            if(a.minByOrNull { distance(it,y) } != x) continue
            val ratio=max(x.size,y.size)/min(x.size,y.size)
            val separation=distance(x,y)/sqrt(x.size*y.size)
            // Printed black square ~13 mm; assembled near-joint centers are ~40-46 mm apart.
            // Wide tolerance accepts oblique close-ups, but excludes overlapping or remote tags.
            if(ratio > 3 || separation !in .8..6.0) continue
            pairs.put(JSONObject().put("markerIds",JSONArray(listOf(x.marker.id,y.marker.id)))
                .put("endpoints",JSONArray(listOf(x.endpoint,y.endpoint)))
                .put("sameEndpoint",x.endpoint==y.endpoint).put("separationInTagWidths",separation))
        }
        val matching=(0 until pairs.length()).map { pairs.getJSONObject(it).getBoolean("sameEndpoint") }.distinct()
        val status=when(matching.singleOrNull()) { true->"joint_match"; false->"joint_mismatch"; null->"insufficient" }
        return JSONObject().put("status",status).put("pairs",pairs).put("scope","local_joint_only")
            .put("requiresVisualSeamCheck",true).put("fullBoardPoseVerified",false)
    }

    /** A green visual seam verdict also needs compatible gray-frame faces and local connection. */
    fun apply(result: AiReviewResult, review: JSONObject, issue:JSONObject?=null): AiReviewResult {
        val context = review.optJSONObject("context") ?: return result
        val copy=AiReviewEnglish(context)
        val task = context.optJSONObject("task") ?: return result
        if(task.optString("kind")=="install" && task.optJSONArray("members")!=null) {
            if(LayerPhotoReview.applies(task))return LayerPhotoReview.decide(result,review,task,issue)
            return applyInstallation(result,review,task)
        }
        if (!framePreparation(task)) return result
        val pair = strings(context.optJSONObject("reviewScope")?.optJSONArray("focusParts")).toSet()
        val expected = setOf("${group(task)}-1", "${group(task)}-2")
        if (pair != expected) return if(result.assessment == "looks_ok") unresolved(copy,copy.say("这张还没核对两半的方向。","The direction of the two halves is still unclear. ")) else result
        val views = review.optJSONArray("views")
        val sources = if(views == null) listOf(JSONObject().put("observation", review.optJSONObject("observation"))
            .put("framePairCheck", context.optJSONObject("framePairCheck")))
        else (0 until views.length()).mapNotNull { views.optJSONObject(it) }
        val checks = sources.map { source ->
            val check = source.optJSONObject("framePairCheck")
            val capture = source.optJSONObject("observation")?.optString("captureId").orEmpty()
            check?.takeIf { it.optString("schema") == "frame-pair-check-1" && it.optBoolean("required") &&
                capture.isNotBlank() && it.optString("captureId") == capture && it.optString("taskId") == task.optString("id") &&
                strings(it.optJSONArray("parts")).toSet() == expected && faceEvidenceMatches(it, source.optJSONObject("observation")) }
        }
        // A fresh self-contained local check may supersede an earlier wrong arrangement.
        // Do not import old coordinates, and do not make a corrected scene fail because of history.
        val latest = checks.lastOrNull()
        val latestStatus = latest?.optString("status")
        if (latestStatus == "face_mismatch") return AiReviewResult("needs_adjustment", copy.say("两半正反没对上","One frame half is flipped"),
            copy.say("两半正反没对上。先退出接头，把其中一半翻一下面，再对齐。","Separate the joint. Flip one half over, then line up the joint again."),
            listOf(copy.say("同一张照片中，两半的已登记标签对应不同的面；侧框不适用彩色层板的混面豁免。","The frame tags show opposite faces in the same photo.")), "")
        if (latestStatus == "rotate") {
            val label = latest.optString("targetLabel").takeIf { it in setOf("左手边这块", "右手边这块", "上面这块", "下面这块") }
            val which=when(label){ "左手边这块"->"the half on the left"; "右手边这块"->"the half on the right"; "上面这块"->"the upper half"; "下面这块"->"the lower half"; else->"one half" }
            val direction = if(label == null) "把一半平放着转正" else "把${label}平放着转正"
            return AiReviewResult("needs_adjustment", copy.say("两半方向没对上","The halves point different ways"), copy.say("方向没对上。先退出接头，${direction}，再对齐。","Separate the joint. Turn $which on the table, then line up the ends."),
                listOf(copy.say("同一张照片的标签布局显示两半轴向不一致；接缝贴合不能代替朝向核对。","The tags show that the two halves point in different directions.")), "")
        }
        if(latestStatus == "joint_mismatch") return AiReviewResult("needs_adjustment", copy.say("接头两端没对上","The ends are reversed"),
            copy.say("接头两端没对上。先退出接头，把其中一半平放着转半圈，再对齐。","Separate the joint. Turn one half 180° on the table, then line the ends up again."),
            listOf(copy.say("当前相邻接头标签属于同一面的相反端；侧框两端不能互换。","The tags beside the joint belong to opposite ends on the same face.")), "")
        if (result.assessment != "looks_ok") return result // Orientation alone never manufactures success.
        if (latestStatus in setOf("aligned", "joint_match")) return result
        return unresolved(copy,copy.say("接缝看到了，再带上旁边的标签。","The seam is visible. "))
    }

    /** Verify against only the newest capture. A clearly visible gap remains actionable even
     * when the angle or row cannot be recovered in that photo. */
    private fun applyInstallation(result:AiReviewResult,review:JSONObject,task:JSONObject):AiReviewResult {
        val copy=AiReviewEnglish(review.optJSONObject("context"))
        val views=review.optJSONArray("views")
        val source=if(views==null) review else views.optJSONObject(views.length()-1)
        val obs=source?.optJSONObject("observation")
        val raw=source?.optJSONObject("framePairCheck") ?: if(views==null) review.optJSONObject("context")?.optJSONObject("framePairCheck") else null
        val ids=obs?.optJSONArray("markers")
        val seen=if(ids==null) emptySet() else (0 until ids.length()).mapNotNull { ids.optJSONObject(it)?.optInt("id") }.toSet()
        val evidence=raw?.optJSONArray("markerIds")
        val valid=raw!=null && raw.optString("schema")=="final-assembly-check-1" && raw.optString("taskId")==task.optString("id") &&
            !obs?.optString("captureId").isNullOrBlank() && raw.optString("captureId")==obs?.optString("captureId") &&
            evidence!=null && evidence.length()>0 && (0 until evidence.length()).all { evidence.optInt(it,-1) in seen }
        val status=if(valid) raw?.optString("status") else null
        if(status in setOf("install_base_flip","install_turn","install_wrong_row","install_wrong_part","install_stand")) {
            val title=copy.cue(raw!!.getString("title"),"Adjust the panel")
            return AiReviewResult("needs_adjustment",title,copy.cue(raw.getString("instruction"),title),listOf(title),"")
        }
        if(status=="install_base_heading" && task.optString("id")=="install:左侧板" && result.assessment=="looks_ok"){
            return AiReviewResult("looks_ok",copy.say("灰框内侧朝上","The base is inside up"),copy.say("平转灰框，把第一排孔放到远端，再放稳。这样下一块板好插。","Turn the gray frame on the table so the first row of slots is farthest from you."),
                listOf(copy.say("标签核对了内侧朝上。第一排孔的位置是桌面的摆放建议。","The tags confirm that the inside faces up. The first row goes at the far end for easier access.")),"")
        }
        if(result.assessment!="looks_ok" || status in setOf("install_row_ok","install_base_ready","install_oriented"))return result
        val hint=if(valid)copy.cue(raw!!.optString("instruction"),"Show the panel and base together.") else copy.say("稍微退一点，把正在装的板和底框旁边的标签一同拍进来。","Step back a little and show the tags on the panel and base together.")
        return AiReviewResult("need_view",copy.say("再看一下安装位置","Check the panel position"),hint,listOf(copy.say("接缝可见，当前照片还需核对这一步的方向或位置。","The seam is visible. The panel direction or position still needs checking.")),hint)
    }

    private fun unresolved(copy:AiReviewEnglish, reason:String):AiReviewResult {
        val view=copy.say("把这个接头两侧各一枚标签一起拍进去，不用拍全板。","Show one tag on each side of this joint.")
        return AiReviewResult("need_view",copy.say("带上接头旁的标签","Show the tags beside the joint"),reason+view,listOf(reason),view)
    }

    private fun faceSignature(check: JSONObject): String? {
        val faces = check.optJSONArray("faces") ?: return null
        if(faces.length() != 2) return null
        val rows = (0 until faces.length()).mapNotNull { faces.optJSONObject(it) }
        if(rows.size != 2 || rows.any { it.optString("face") !in setOf("A", "B") || it.optJSONArray("markerIds")?.length() == 0 }) return null
        return rows.sortedBy { it.optString("part") }.joinToString("|") { "${it.optString("part")}:${it.optString("face")}" }
    }

    private fun faceEvidenceMatches(check: JSONObject, observation: JSONObject?): Boolean {
        val actual = observation?.optJSONArray("markers") ?: return false
        val ids = (0 until actual.length()).mapNotNull { actual.optJSONObject(it)?.optInt("id", -1) }
        if(ids.size != ids.distinct().size) return false
        val status = check.optString("status")
        val rows = check.optJSONArray("faces") ?: return status == "insufficient"
        if(status in setOf("aligned", "rotate", "face_mismatch", "joint_match", "joint_mismatch") && faceSignature(check) == null) return false
        if(rows.length() != 2 || (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.optString("part") }.toSet() !=
            strings(check.optJSONArray("parts")).toSet()) return false
        return (0 until rows.length()).all { n -> val row = rows.optJSONObject(n) ?: return@all false
            val evidence = row.optJSONArray("markerIds") ?: return@all false
            (0 until evidence.length()).all { evidence.optInt(it, -1) in ids }
        }
    }

    private fun validQuad(d: BoardFaceRules.Detection): Boolean {
        val p = d.corners
        return p.size == 4 && p.all { it.x.isFinite() && it.y.isFinite() } &&
            abs(p.indices.sumOf { i -> p[i].x*p[(i+1)%4].y-p[(i+1)%4].x*p[i].y }) >= 32.0
    }
}
