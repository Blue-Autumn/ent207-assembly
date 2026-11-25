package com.example.arassemblydemo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EnglishGuidanceTest {
    @Test fun physicalSidesAndSlotNumbersSurviveTranslation() {
        val sides=mapOf("左边" to "left", "右边" to "right", "画面上方" to "top of the image", "画面下方" to "bottom of the image")
        for ((source, target) in sides) {
            assertEquals("Turn the notch toward the $target",FinalGuideEnglish.text("把缺口转向$source"))
            assertEquals("The notch faces the $target",FinalGuideEnglish.text("缺口朝$source"))
        }
        for (row in 1..5) assertEquals("Lift the shelf, line up both ends with row $row, then lower it.",
            FinalGuideEnglish.text("提起层板，对准第${row}排再放下。"))
    }

    @Test fun localWrongRowOverridesEnglishProviderSuccessWithEnglishSpeech() {
        val task=JSONObject().put("id","install:层2板").put("kind","install")
            .put("members",JSONArray(listOf("层2板-1","层2板-2")))
        val measurement=JSONObject().put("schema","final-assembly-check-1").put("captureId","photo")
            .put("taskId",task.getString("id")).put("status","install_wrong_row")
            .put("title","对着另一排孔了").put("instruction","提起层板，对准第2排再放下。")
            .put("markerIds",JSONArray(listOf(43,127)))
            .put("panelMarkers",JSONArray(listOf(43))).put("baseMarkers",JSONArray(listOf(127)))
        val review=JSONObject().put("context",JSONObject().put("uiLanguage","en").put("task",task)
            .put("framePairCheck",measurement))
            .put("observation",JSONObject().put("captureId","photo").put("markers",JSONArray(listOf(
                JSONObject().put("id",43),JSONObject().put("id",127)))))
        val result=AiFrameOrientation.apply(AiReviewResult("looks_ok","Looks good","Proceed.",emptyList(),""),review)
        assertEquals("needs_adjustment",result.assessment)
        assertEquals("The shelf is over the wrong row",result.headline)
        assertTrue(AiReplyText.spoken(result,true).contains("row 2"))
    }
}
