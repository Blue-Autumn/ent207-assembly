package com.example.arassemblydemo

import org.json.JSONObject

/** Interpret per-shot measurements for the five layer insertions. Photos never share pixel geometry. */
internal object LayerPhotoReview {
    private val layerIds=(1..5).map { "install:层${it}板" }.toSet()
    private val corrections=setOf("install_base_flip","install_turn","install_wrong_row","install_wrong_part","install_stand")
    fun applies(task:JSONObject)=task.optString("id") in layerIds && task.optString("kind")=="install"

    private data class Shot(val check:JSONObject, val index:Int, val panelSeen:Boolean) {
        val status get()=check.optString("status")
        val relation get()=check.optString("notchRelation")
        val opposed get()=relation=="opposed"
        val aligned get()=relation=="aligned" || status=="install_row_ok"
        val correction get()=status in corrections || opposed
    }

    private fun checked(view:JSONObject?,task:String,index:Int):Shot? {
        val observation=view?.optJSONObject("observation") ?: return null
        val check=view.optJSONObject("framePairCheck") ?: return null
        val capture=observation.optString("captureId")
        if(capture.isBlank() || check.optString("captureId")!=capture || check.optString("taskId")!=task ||
            check.optString("schema")!="final-assembly-check-1") return null
        val markers=observation.optJSONArray("markers") ?: return null
        val seen=(0 until markers.length()).map { markers.optJSONObject(it)?.optInt("id",-1) ?: -1 }
        val evidence=check.optJSONArray("markerIds") ?: return null
        val used=(0 until evidence.length()).map { evidence.optInt(it,-1) }
        if(seen.any { it !in 0..249 } || seen.distinct().size!=seen.size ||
            (used.isEmpty() && check.optString("status") !in setOf("install_check","install_show_base","install_show_target")) ||
            used.distinct().size!=used.size || !seen.containsAll(used))return null
        val panel=check.optJSONArray("panelMarkers")?.let { a->(0 until a.length()).map { a.optInt(it,-1) } }.orEmpty()
        val base=check.optJSONArray("baseMarkers")?.let { a->(0 until a.length()).map { a.optInt(it,-1) } }.orEmpty()
        if(!used.containsAll(panel+base) || panel.any { it in base })return null
        if(check.optString("notchRelation") in setOf("aligned","opposed") && (panel.isEmpty() || base.isEmpty()))return null
        return Shot(check,index,panel.isNotEmpty())
    }

    fun decide(model:AiReviewResult, review:JSONObject, task:JSONObject, issue:JSONObject?):AiReviewResult {
        val copy=AiReviewEnglish(review.optJSONObject("context"))
        val views=review.optJSONArray("views")
        val entries=if(views==null)listOf(JSONObject().put("observation",review.optJSONObject("observation"))
            .put("framePairCheck",review.optJSONObject("context")?.optJSONObject("framePairCheck")))
        else (0 until views.length()).map { views.optJSONObject(it) }
        val id=task.getString("id")
        val examined=entries.mapIndexed { i,view -> checked(view,id,i+1) }
        val last=examined.lastOrNull()
        val previous=examined.dropLast(1).lastOrNull { it!=null && (it.correction||it.aligned) }
        if(last?.correction==true) {
            val title=copy.cue(last.check.optString("title","先调整这块板"),"Adjust this panel")
            return AiReviewResult("needs_adjustment",title,copy.cue(last.check.optString("instruction",title),title),
                listOf(copy.say("这张照片的标签表明：$title。","The tags in this photo show the following: $title.")),"")
        }

        val count=entries.size
        val referred=issue?.optString("view_id")?.removePrefix("view-")?.toIntOrNull()
        val members=task.optJSONArray("members")
        val targets=issue?.optJSONArray("parts")
        val currentLayer=members!=null && targets!=null && (0 until targets.length()).any { i->
            (0 until members.length()).any { j->members.optString(j)==targets.optString(i) }
        }
        val gap=if(currentLayer && model.assessment=="needs_adjustment" && issue?.optString("evidence")=="visible_photo" &&
            issue.optString("kind")=="seating" && issue.optString("interface")=="shelf_to_base" &&
            issue.optString("symptom") in setOf("open_gap","raised_edge","exposed_tab") && referred!=null &&
            referred in 1..count) issue.optString("observation").trim().trimEnd('。','！','；','，','.').takeIf { it.isNotEmpty() } else null
        if(gap!=null) {
            val earlier=referred!=count
            val title=if(earlier)copy.say("看一下刚才的接头","Check the joint again") else copy.say("这处接头还有缝","There is a gap here")
            val lead=if(last?.aligned==true)copy.say("缺口方向对了。","The notch is facing the right way. ") else ""
            val advice=if(earlier)copy.say("第${referred}张里，$gap。看看这处现在有没有贴平。","Photo $referred: $gap. Check whether this edge now sits flush.") else copy.say("$gap。扶稳板边，对准槽再压平。","$gap. Hold the edge steady, line up the slots and press the shelf down.")
            return AiReviewResult(if(earlier)"need_view" else "needs_adjustment",title,lead+advice,
                listOf(copy.say("第${referred}张照片里当前层板下沿与灰框相接处：$gap。","Photo $referred, where the shelf meets the base: $gap.")),"")
        }
        if(last?.aligned==true) {
            val row=last.status=="install_row_ok"
            val title=if(row)copy.say("缺口和孔位对上了","The notch and slots line up") else copy.say("缺口方向对了","The notch faces the right way")
            val next=when { row->copy.say("两端贴平就可以继续。","Continue once both ends sit flush."); previous?.status=="install_wrong_row"->copy.say("刚才孔排有偏差，再看看两端是否落在示范的那一排。","The shelf was over a different row. Check that both ends are now in the highlighted row.")
                else->copy.say("看一下两端是否落进同一排槽。","Check that both ends are in the same row of slots.") }
            return AiReviewResult("looks_ok",title,copy.say("$title。$next","$title. $next"),listOf(copy.say("同张照片中，当前层板和灰框可见标签的缺口方向一致。","The shelf and base tags in this photo agree on the notch direction.")),"")
        }
        // A later detail may hide the reference. Refer to the earlier independent result by photo
        // number; a partial latest photo cannot certify the present pose or cancel a past reversal.
        if(previous!=null && last!=null) {
            val evidence=copy.say("第${previous.index}张照片中","In photo ${previous.index}, ")+if(previous.correction)copy.say("曾看到摆放需要调整。","the panel needed adjusting. ") else copy.say("层板与灰框的缺口方向一致。","the notch lined up with the base. ")
            val advice=if(previous.correction)copy.say("若已调整，让缺口和前面几层朝同一边，再确认。","After adjusting the shelf, check that its notch faces the same way as the others.") else copy.say("若板子没动，看一眼两端落槽就可以确认。","If the shelf is still there, check both ends are in the slots, then confirm.")
            return AiReviewResult("need_view",if(previous.correction)copy.say("再对照一下缺口","Check the notch again") else copy.say("先前的缺口方向对了","The notch lined up earlier"),
                evidence+advice,listOf(evidence),"")
        }
        if(id=="install:层5板" && last?.panelSeen==true) {
            return AiReviewResult("need_view",copy.say("对照前一层的缺口","Match the previous notch"),copy.say("底框被挡住了。让绿板的缺口和前一层朝同一边，再确认。","The base is hidden. Match the green shelf’s notch to the previous shelf before confirming."),
                listOf(copy.say("绿色层板已识别，拍到的底框标签不足以核对方向。","The green shelf is visible. Too few base tags are visible to confirm its direction.")),"")
        }
        if(count>=3)return AiReviewResult("need_view",copy.say("手动对一下缺口","Check the notch by eye"),copy.say("这些照片还定不了朝向。把缺口与示范朝向对齐，再看两端是否落槽。","Match the notch to the model, then check that both ends are in their slots."),
            listOf(copy.say("当前方向缺少同张照片的明确依据。","These photos do not show enough of the shelf and base together to check direction.")),"")
        val view=copy.say("从侧上方拍到当前层板和灰框露出的标签。","Take an angled photo showing the exposed tags on the shelf and base.")
        return AiReviewResult("need_view",copy.say("还没看清缺口方向","Show the notch and base"),view,listOf(copy.say("需要在同一张照片中看到当前层板和底框。","Show the current shelf and base together in one photo.")),view)
    }
}
