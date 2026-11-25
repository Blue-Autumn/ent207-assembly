package com.example.arassemblydemo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiReviewPolicyTest {
    private fun context()=JSONObject().put("task",JSONObject().put("id","install:层2板"))
        .put("reviewScope",JSONObject().put("version",2).put("focusParts",JSONArray(listOf("层2板-1","层2板-2"))))
        .put("parts",JSONArray(listOf("层2板-1","层2板-2","层1板-1","左侧板-1").map { JSONObject().put("id",it)
            .put("facePolicy",if(it.startsWith("层"))"unrestricted" else "cad_relative_orientation") }))
    private fun issue(kind:String="seating",targets:List<String> = listOf("层2板-1","左侧板-1"),evidence:String="visible_photo")=
        JSONObject().put("issue",JSONObject().put("kind",kind).put("parts",JSONArray(targets)).put("evidence",evidence).put("observation","当前蓝层接头可见向外张开的间隙。"))
    private fun answer(assessment:String="needs_adjustment")=AiReviewResult(assessment,"检查当前接头","当前蓝层接口有张开。先退出这一处重新对齐。",listOf("接口可见张开。"),if(assessment=="need_view")"拍清蓝层接口。" else "")
    @Test fun specificCurrentInterfaceProblemIsKept(){assertEquals(answer(),AiReviewPolicy.apply(answer(),issue(),context()))}
    @Test fun earlierComponentCannotReplaceCurrentTask(){val r=AiReviewPolicy.apply(answer(),issue(targets=listOf("层1板-1","左侧板-1")),context());assertEquals("need_view",r.assessment);assertFalse(r.instruction.contains("退出"))}
    @Test fun mixedLayerFacesNeverBecomeAnAdjustment(){val r=AiReviewPolicy.apply(answer(),issue("face_mismatch",listOf("层2板-1","层2板-2")),context());assertEquals("need_view",r.assessment);assertTrue(r.instruction.contains("混用允许"));assertNull(r.orientationAction)}
    @Test fun frameFaceRestrictionIsRetainedForCurrentFrame(){val c=context();c.getJSONObject("reviewScope").put("focusParts",JSONArray(listOf("左侧板-1")));assertEquals("needs_adjustment",AiReviewPolicy.apply(answer(),issue("frame_orientation",listOf("左侧板-1")),c).assessment)}
    @Test fun currentFrameFaceRestrictionMayUseLayerAsReference(){val c=context();c.getJSONObject("reviewScope").put("focusParts",JSONArray(listOf("左侧板-1")));assertEquals("needs_adjustment",AiReviewPolicy.apply(answer(),issue("face_mismatch",listOf("左侧板-1","层2板-1")),c).assessment)}
    @Test fun modelCannotRelabelLayerFaceMistakeAsFrameCriterion(){assertEquals("need_view",AiReviewPolicy.apply(answer(),issue("frame_orientation",listOf("层2板-1","层2板-2")),context()).assessment)}
    @Test fun currentLayerMayStillNeedAdjustmentAtItsFrameInterface(){assertEquals("needs_adjustment",AiReviewPolicy.apply(answer(),issue("frame_orientation",listOf("层2板-1","左侧板-1")),context()).assessment)}
    @Test fun explicitLayerFlipIsRejectedEvenWhenIssueIsMislabelled(){val r=answer().copy(orientationAction=AiOrientationAction("flip","层2板-1",true));assertEquals("need_view",AiReviewPolicy.apply(r,issue("seating"),context()).assessment)}
    @Test fun unknownOrMissingTagEvidenceCannotDeclareWrongAssembly(){for(evidence in listOf("missing_marker","not_seen",""))assertEquals("need_view",AiReviewPolicy.apply(answer(),issue(evidence=evidence),context()).assessment)}
    @Test fun missingMalformedOrUnknownScopeIssueIsNotAnErrorVerdict(){for(raw in listOf(JSONObject(),JSONObject().put("issue","text"),issue(targets=listOf("unknown")),issue(kind="missing_part")))assertEquals("need_view",AiReviewPolicy.apply(answer(),raw,context()).assessment)}
    @Test fun localNotchClaimRequiresMatchingLocalCandidate(){val c=context();val raw=issue("notch_direction",listOf("层2板-2"),"local_direction")
        assertEquals("need_view",AiReviewPolicy.apply(answer(),raw,c).assessment)
        c.put("localOrientation",JSONObject().put("policy","layer_notch_direction").put("directionStatus","rotate_in_plane").put("rotationSuggestion",JSONObject().put("part","层2板-2")))
        assertEquals("needs_adjustment",AiReviewPolicy.apply(answer(),raw,c).assessment)
        c.getJSONObject("localOrientation").put("directionStatus","direction_unknown");assertEquals("need_view",AiReviewPolicy.apply(answer(),raw,c).assessment)}
    @Test fun policyDoesNotTurnInsufficientEvidenceIntoSuccess(){for(state in listOf("looks_ok","need_view"))assertEquals(answer(state),AiReviewPolicy.apply(answer(state),JSONObject(),context()))}
    @Test fun legitimateSeatingProblemIsNotRemovedByFaceWords(){val r=answer().copy(instruction="A/B混面本身允许，但当前接口有张开，需要对齐。");assertEquals(r,AiReviewPolicy.apply(r,issue(),context()))}
    @Test fun mixedTypeTargetsDoNotSilentlyBecomeAValidIssue(){val raw=issue();raw.getJSONObject("issue").getJSONArray("parts").put(42);assertEquals("need_view",AiReviewPolicy.apply(answer(),raw,context()).assessment)}
    @Test fun observationMustBeActualNonblankText(){for(value in listOf<Any>(true,"   ")){val raw=issue();raw.getJSONObject("issue").put("observation",value);assertEquals("need_view",AiReviewPolicy.apply(answer(),raw,context()).assessment)}}
    @Test fun observedWrongFrameMayBeNamedAlongsideExpectedCurrentTarget() {
        val c=context().put("observedParts",JSONArray().put(JSONObject().put("id","右侧板-1")))
        c.getJSONObject("reviewScope").put("focusParts",JSONArray(listOf("左侧板-1")))
        val raw=issue("identity",listOf("左侧板-1","右侧板-1"))
        raw.getJSONObject("issue").put("observation","照片标签对应另一侧框，本步需要左侧框。")
        assertEquals("needs_adjustment",AiReviewPolicy.apply(answer(),raw,c).assessment)
    }
    @Test fun ObservingAnotherObjectDoesNotPermitReviewingItInsteadOfCurrentTarget() {
        val c=context().put("observedParts",JSONArray().put(JSONObject().put("id","右侧板-1")))
        assertEquals("need_view",AiReviewPolicy.apply(answer(),issue("identity",listOf("右侧板-1")),c).assessment)
    }
    @Test fun offTaskObjectCannotSupplySeatingOrFaceErrorEvidenceForCurrentStep() {
        val c=context().put("observedParts",JSONArray().put(JSONObject().put("id","右侧板-1")))
        for(kind in listOf("seating","frame_orientation","face_mismatch"))
            assertEquals("need_view",AiReviewPolicy.apply(answer(),issue(kind,listOf("层2板-1","右侧板-1")),c).assessment)
    }
}
