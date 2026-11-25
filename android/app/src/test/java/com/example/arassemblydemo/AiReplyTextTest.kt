package com.example.arassemblydemo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiReplyTextTest {
    private fun reply(text:String,view:String="")=AiReviewResult("need_view","靠近接头看看",text,
        listOf("左侧板-1 的接缝被挡住。"),view)
    private fun context()=JSONObject().put("parts",JSONArray().put(JSONObject().put("id","左侧板-1")
        .put("visibleMarkers",JSONArray(listOf(116,76)))))

    @Test fun knownInternalIdBecomesVisibleTagReferenceWithoutGuessingScreenSide() {
        val value=AiReplyText.prepare(reply("将左侧板-1靠近另一半。"),context())
        assertEquals("将贴076标签的这一半靠近另一半。",value.instruction)
        assertFalse(value.instruction.contains("左边"))
        assertTrue(value.findings.single().contains("贴076标签"))
    }
    @Test fun invisiblePartUsesPlainDescriptionAndNeverAnUnseenRegisteredTag() {
        val c=JSONObject().put("parts",JSONArray().put(JSONObject().put("id","层2板-2")
            .put("markers",JSONArray(listOf(1,2,3)))))
        assertEquals("蓝色层板的一半还有开口。",AiReplyText.prepare(reply("层2板-2还有开口。"),c).instruction)
    }
    @Test fun observedWrongObjectAlsoGetsItsActualTagReference() {
        val c=context().put("observedParts",JSONArray().put(JSONObject().put("id","右侧板-1")
            .put("visibleMarkers",JSONArray(listOf(39,47)))))
        assertEquals("贴039标签的这一半是另一侧框。",AiReplyText.prepare(reply("右侧板-1是另一侧框。"),c).instruction)
    }
    @Test fun resultVerdictAndPhysicalActionAreNotChangedByWordingCleanup() {
        val action=AiOrientationAction("rotate_in_plane","层2板-2",true)
        val original=reply("把这一半转到接头相对。先退出已经插入的接口，再转动。")
            .copy(assessment="needs_adjustment",orientationAction=action)
        val value=AiReplyText.prepare(original,context())
        assertEquals(original.assessment,value.assessment)
        assertEquals(action,value.orientationAction)
        assertEquals(original.instruction,value.instruction)
    }
    @Test fun exactRepeatedSentenceRemovedButDifferentAdviceRetained() {
        val text="靠近接缝拍一张。靠近接缝拍一张。保留两边标签。"
        assertEquals("靠近接缝拍一张。保留两边标签。",AiReplyText.prepare(reply(text),context()).instruction)
    }
    @Test fun viewRequestIsNotAppendedAsASecondVersionOfTheInstruction() {
        val value=reply("这处被挡住了。把手机移到接缝旁边。","从接缝旁边斜着再拍。")
        assertEquals(value.instruction,AiReplyText.advice(value))
    }
    @Test fun blankInstructionCanUseCaptureRequest() {
        assertEquals("靠近接头。",AiReplyText.advice(reply("","靠近接头。")))
    }
    @Test fun markerHandlesAreThreeDigitsAndRejectOutOfRangeIds() {
        assertEquals("贴004标签的这一半",AiReplyText.userReference("层1板-1",listOf(4,14)))
        assertEquals("紫色层板的一半",AiReplyText.userReference("层1板-1",listOf(-1,300)))
    }
    @Test fun unknownNamesRemainUnknownRatherThanInventingAnObject() {
        assertEquals("陌生零件",AiReplyText.partDescription("陌生零件"))
        assertEquals("未知件-1应该再看看。",AiReplyText.prepare(reply("未知件-1应该再看看。"),context()).instruction)
    }
    @Test fun voiceLeadsWithOutcomeAndDoesNotReadTagHandles() {
        val value=reply("将贴076标签的这一半靠近另一半。").copy(assessment="needs_adjustment")
        assertEquals("先调整一下。将这半块板靠近另一半。",AiReplyText.spoken(value))
        assertTrue(AiReplyText.advice(value).contains("076"))
    }
    @Test fun voiceDoesNotClaimAssemblyCompleteAndKeepsNecessaryActionOrder() {
        val ok=reply("接缝没有明显翘起。").copy(assessment="looks_ok")
        assertEquals("这处看着对了。接缝没有明显翘起。",AiReplyText.spoken(ok))
        assertFalse(AiReplyText.spoken(ok).contains("拼好了"))
        val caution=reply("先退出已经插入的接口，再转动。不要硬压。","").copy(assessment="needs_adjustment")
        assertTrue(AiReplyText.spoken(caution).endsWith(caution.instruction))
    }
    @Test fun voiceUsesPlainPartReferencesWithoutInventingScreenSide() {
        val value=reply("左侧板-1和A板接头被挡住了。靠近接缝拍一张。")
        assertEquals("还没看清。这半块侧框和这半块板接头被挡住了。靠近接缝拍一张。",AiReplyText.spoken(value))
        assertFalse(AiReplyText.spoken(value).contains("左手边"))
    }
}
