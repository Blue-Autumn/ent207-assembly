package com.example.arassemblydemo

import org.json.JSONArray
import org.json.JSONObject

/** Applies scope and user-declared exceptions, not a second visual classifier or a success override. */
internal object AiReviewPolicy {
    private fun strings(a:JSONArray?)=a?.let { (0 until it.length()).mapNotNull { i->it.opt(i) as? String } }.orEmpty()

    fun apply(result:AiReviewResult,raw:JSONObject,context:JSONObject):AiReviewResult {
        val copy=AiReviewEnglish(context)
        val scope=context.optJSONObject("reviewScope")?:return result
        if(scope.optInt("version")<2||result.assessment!="needs_adjustment")return result
        val focus=strings(scope.optJSONArray("focusParts")).toSet()
        val parts=context.optJSONArray("parts")?:JSONArray()
        val rows=(0 until parts.length()).mapNotNull { parts.optJSONObject(it) }
        val observed=context.optJSONArray("observedParts")?.let { a->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id") }
        }.orEmpty()
        val known=rows.map { it.optString("id") }.toSet()
        val exempt=rows.filter { it.optString("facePolicy")=="unrestricted"||it.optString("id").startsWith("层") }
            .map { it.optString("id") }.toSet()
        val issue=raw.optJSONObject("issue")
        val targets=strings(issue?.optJSONArray("parts"))
        val detail=issue?.optString("observation").orEmpty()
        val kind=issue?.optString("kind").orEmpty()
        val evidence=issue?.optString("evidence").orEmpty()
        val allowedKeys=setOf("kind","parts","evidence","observation") +
            if(context.optJSONObject("task")?.let(LayerPhotoReview::applies)==true) setOf("interface","symptom","view_id") else emptySet()
        val valid=issue!=null&&issue.keys().asSequence().all { it in allowedKeys }&&
            kind in setOf("face_mismatch","notch_direction","seating","identity","frame_orientation")&&
            issue.optJSONArray("parts")?.length()==targets.size&&issue.opt("observation") is String&&
            targets.size in 1..4&&targets.toSet().size==targets.size&&
            targets.all { it in known||(kind=="identity"&&it in observed) }&&targets.any { it in focus }&&
            detail.length in 1..300&&detail.isNotBlank()&&detail.none { it.code<32 }&&evidence in setOf("visible_photo","local_direction")
        if(!valid)return uncertain(context,copy.say("本次说明没有明确指向当前接头，先只核对这一处。","Focus on the joint being fitted in this step."))
        if(kind=="face_mismatch"&&targets.filter { it in focus }.all { it in exempt })
            return uncertain(context,copy.say("彩色层板正反混用允许，不需要为了统一面向拆开。","Either face of a colored shelf may face up. You can leave the halves together."),true)
        // A model may mislabel its issue. A frame-only criterion cannot be applied to a current layer.
        if(kind=="frame_orientation"&&targets.none { it !in exempt })
            return uncertain(context,copy.say("当前检查的是层板缺口和接口，不用按侧框的正反要求重装层板。","Check the shelf notch and its joint. Either face of this shelf can face up."))
        if(result.orientationAction?.kind=="flip"&&targets.any { it in focus && it in exempt })
            return uncertain(context,copy.say("彩色层板正反混用允许，不需要为了统一面向拆开。","Either face of a colored shelf may face up. You can leave the halves together."),true)
        if(evidence=="local_direction") {
            val local=context.optJSONObject("localOrientation")
            val candidate=local?.optJSONObject("rotationSuggestion")?.optString("part")
            if(kind!="notch_direction"||local?.optString("policy")!="layer_notch_direction"||
                local.optString("directionStatus")!="rotate_in_plane"||candidate !in targets)
                return uncertain(context,copy.say("这次标签还不足以确定缺口方向，先补看当前接头。","Show the notch beside the joint so its direction can be checked."))
        }
        return result
    }

    private fun uncertain(context:JSONObject,reason:String,faceException:Boolean=false):AiReviewResult {
        val copy=AiReviewEnglish(context)
        val layer=context.optJSONObject("task")?.optString("id")?.substringAfter(':')?.startsWith("层")==true
        val view=if(layer)copy.say("靠近层板正在接的地方，斜着补一张，带上缺口。","Take a close, angled photo of the joint with the notch in view.") else copy.say("靠近正在接的地方，斜着补一张，带上接缝两边。","Take a close, angled photo showing both edges of the joint.")
        val lead=if(faceException)copy.say("正反混用允许，不用拆开。","Either face can face up. Leave the halves together. ") else copy.say("这次还没看清接头有没有对齐。","The joint is hard to see. ")
        return AiReviewResult("need_view",if(faceException)copy.say("正反面不用改","Either face is fine") else copy.say("靠近接头再看看","Move closer to the joint"),"$lead$view",listOf(reason),view,null)
    }
}
