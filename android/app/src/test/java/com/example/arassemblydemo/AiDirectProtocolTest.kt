package com.example.arassemblydemo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Pure synthetic fixtures; no key, files, camera, HTTP or provider request. */
class AiDirectProtocolTest {
    private fun png()=Base64.getEncoder().encodeToString(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
    private fun image(role:String)=JSONObject().put("role",role).put("mime","image/png").put("base64",png()).apply{
        if(role=="model_reference"){put("width",1200);put("height",640)} }
    private fun review()=JSONObject().put("schema","assembly-ai-review-1").put("requestId","new-photo")
        .put("context",JSONObject().put("schema","assembly-step-context-1")
            .put("task",JSONObject().put("id","prepare:层2板").put("title","拼好蓝色层板").put("kind","prepare").put("instruction","两半正反面可混用，核对缺口方向。"))
            .put("parts",JSONArray().put(JSONObject().put("id","层2板-1")).put(JSONObject().put("id","层2板-2")))
            .put("localOrientation",JSONObject().put("policy","layer_notch_direction").put("status","faces_unrestricted").put("directionStatus","rotate_in_plane").put("rotationSuggestion",JSONObject().put("part","层2板-2"))))
        .put("observation",JSONObject().put("captureId","synthetic").put("width",640).put("height",480).put("markers",JSONArray()))
        .put("images",JSONArray().put(image("original")).put(image("annotated")))
    private fun answer(state:String="needs_adjustment")=JSONObject().put("assessment",state).put("headline","先调整这一半")
        .put("instruction","把蓝色第二半平放转向，对齐缺口和接头。")
        .put("findings",JSONArray().put("两半平放，缺口方向相反。"))
        .put("view_request",if(state=="need_view")"拍清两半标签和接头。" else "")
    private fun envelope(answer:JSONObject)=JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop")
        .put("message",JSONObject().put("content",answer.toString()))))
    private fun rotation()=JSONObject().put("kind","rotate_in_plane").put("part","层2板-2").put("flat_pair_visible",true)
    private fun parse(answer:JSONObject,review:JSONObject=review())=AiDirectProtocol.response(envelope(answer),review,"new-photo","prepare:层2板")
    private fun rejected(block:()->Unit){try{block();fail("Expected rejected protocol input")}catch(_:AiReviewException){}}

    @Test fun englishReviewKeepsMarkerIdentityButDisplaysAndSpeaksEnglish(){
        val r=review()
        r.getJSONObject("context").put("uiLanguage","en-US")
            .getJSONArray("parts").getJSONObject(0).put("visibleMarkers",JSONArray(listOf(43)))
        val prompt=AiDirectProtocol.request(r,"new-photo","prepare:层2板","SYSTEM")
            .getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(prompt.startsWith("SYSTEM\n") && prompt.contains("spoken English"))
        val raw=answer("looks_ok").put("headline","The ends line up")
            .put("instruction","Steady 层2板-1. Press down at the joint.")
            .put("findings",JSONArray(listOf("The visible edges sit flush.")))
        val result=parse(raw,r)
        assertEquals("looks_ok",result.assessment)
        assertEquals("Steady the half with tag 043. Press down at the joint.",result.instruction)
        assertEquals("Steady this half. Press down at the joint.",AiReplyText.spoken(result,true))
        assertEquals("层2板-1",r.getJSONObject("context").getJSONArray("parts").getJSONObject(0).getString("id"))
        try { parse(answer("looks_ok"),r);fail("Wrong-language prose must not reach TTS") }
        catch(e:AiReviewException){assertEquals("WRONG_LANGUAGE",e.code)}
    }

    @Test fun endpointAndModelAreFixedIndependentOfPayload(){
        assertEquals("https://api.deepseek.com/chat/completions",AiDirectProtocol.ENDPOINT)
        val r=review().put("endpoint","http://127.0.0.1:8879").put("model","unknown")
        val request=AiDirectProtocol.request(r,"new-photo","prepare:层2板","SYSTEM")
        assertEquals("deepseek-flash",request.getString("model"));assertFalse(request.has("endpoint"));assertFalse(request.has("api_key"))
        assertEquals("disabled",request.getJSONObject("thinking").getString("type"))
        assertEquals("json_object",request.getJSONObject("response_format").getString("type"))
        assertEquals(false,request.get("stream"))
    }
    @Test fun requestSeparatesTrustedPromptFromUserFactsAndOrdersImages(){
        val r=review().put("question","任意用户文本")
        r.put("images",JSONArray().put(image("model_reference")).put(image("annotated")).put(image("original")))
        val request=AiDirectProtocol.request(r,"new-photo","prepare:层2板","SYSTEM")
        val messages=request.getJSONArray("messages");assertEquals("SYSTEM",messages.getJSONObject(0).getString("content"))
        val c=messages.getJSONObject(1).getJSONArray("content");assertEquals(7,c.length())
        assertTrue(c.getJSONObject(0).getString("text").contains("任意用户文本"))
        assertTrue(c.getJSONObject(1).getString("text").startsWith("图1：手机原图"))
        assertTrue(c.getJSONObject(5).getString("text").contains("不是现场实拍"))
        for(i in listOf(2,4,6)){assertEquals("image_url",c.getJSONObject(i).getString("type"));assertTrue(c.getJSONObject(i).getJSONObject("image_url").getString("url").startsWith("data:image/png;base64,"))}
    }
    @Test fun requestRejectsMissingRepeatedOrUnknownImageRoles(){
        for(roles in listOf(listOf("original"),listOf("original","original"),listOf("original","unknown"))){
            rejected{AiDirectProtocol.request(review().put("images",JSONArray(roles.map{image(it)})),"new-photo","prepare:层2板","SYSTEM")}
        }
    }
    @Test fun requestRejectsExternalImageUrlOrInvalidEncoding(){
        for(b64 in listOf("https://example.com/private.png","bad!")){
            val r=review();r.getJSONArray("images").getJSONObject(0).put("base64",b64)
            rejected{AiDirectProtocol.request(r,"new-photo","prepare:层2板","SYSTEM")}
        }
    }
    @Test fun requestRejectsStaleTaskAndInvalidCorners(){
        rejected{AiDirectProtocol.request(review(),"old-photo","prepare:层2板","SYSTEM")}
        rejected{AiDirectProtocol.request(review(),"new-photo","prepare:层1板","SYSTEM")}
        val r=review();r.getJSONObject("observation").getJSONArray("markers").put(JSONObject().put("id",4).put("corners",JSONArray(listOf(0,0,900,0,20,20,0,20))))
        rejected{AiDirectProtocol.request(r,"new-photo","prepare:层2板","SYSTEM")}
    }
    @Test fun allThreeStatesKeepManualCompletionContract(){
        for(state in listOf("looks_ok","needs_adjustment","need_view")){val result=parse(answer(state));assertEquals(state,result.assessment);assertNull(result.orientationAction)}
    }
    @Test fun maliciousOrUnexpectedResultFieldsCannotReplaceRequestOrProgress(){
        for((name,value) in listOf("automaticCompletion" to true,"taskId" to "elsewhere","requestId" to "old-photo","provider" to "other"))
            rejected{parse(answer().put(name,value))}
    }
    @Test fun incompleteOrOversizeResponseIsRejected(){
        rejected{parse(answer().put("instruction","字".repeat(701)))}
        rejected{parse(answer("need_view").put("view_request",""))}
        rejected{AiDirectProtocol.response(envelope(answer()).apply{getJSONArray("choices").getJSONObject(0).put("finish_reason","length")},review(),"new-photo","prepare:层2板")}
    }
    @Test fun explicitFlatPairActionMustMatchLocalCandidate(){
        assertEquals("rotate_in_plane",parse(answer().put("orientation_action",rotation())).orientationAction?.kind)
        for(action in listOf(rotation().put("part","层2板-1"),rotation().put("flat_pair_visible",false),rotation().put("extra","ignored")))
            assertNull(parse(answer().put("orientation_action",action)).orientationAction)
    }
    @Test fun FinalAssemblyOrNonAdjustmentNeverTriggersPairCorrection(){
        val r=review();r.getJSONObject("context").getJSONObject("task").put("kind","install")
        assertNull(parse(answer().put("orientation_action",rotation()),r).orientationAction)
        for(state in listOf("looks_ok","need_view"))assertNull(parse(answer(state).put("orientation_action",rotation())).orientationAction)
    }
    @Test fun malformedOptionalActionDoesNotDiscardUsefulGuidance(){
        for(action in listOf<Any>(JSONObject.NULL,"flip",JSONObject().put("kind","flip"))){
            val parsed=parse(answer().put("orientation_action",action));assertEquals("needs_adjustment",parsed.assessment);assertNull(parsed.orientationAction)
        }
    }
    @Test fun errorsAreSpecificAndNeverIncludeRawProviderText(){
        assertEquals("AUTH_FAILED",AiDirectProtocol.httpError(401).code)
        assertEquals("BALANCE",AiDirectProtocol.httpError(402).code)
        assertEquals("RATE_LIMIT",AiDirectProtocol.httpError(429).code)
        assertEquals("PROVIDER_UNAVAILABLE",AiDirectProtocol.httpError(503).code)
        assertEquals("REDIRECT_REJECTED",AiDirectProtocol.httpError(302).code)
        assertFalse(AiDirectProtocol.httpError(401).message!!.contains("电脑"))
    }
    @Test fun credentialsCannotContainHeaderInjectionOrWhitespace(){
        assertTrue(AiProviderSettings.validKey("synthetic-placeholder-key"))
        assertFalse(AiProviderSettings.validKey("key\r\nAuthorization: x"))
        assertFalse(AiProviderSettings.validKey("key with spaces here"))
        assertFalse(AiProviderSettings.validKey("short"))
    }

    private fun multiReview(count:Int):JSONObject {
        val r=review();val views=JSONArray()
        for(i in 0 until count){
            val observation=JSONObject(r.getJSONObject("observation").toString()).put("captureId","shot-$i")
                .put("width",640+i*100).put("height",480+i*50)
            if(i==0)r.put("observation",observation)
            views.put(JSONObject().put("viewId","view-${i+1}").put("purpose",if(i==0)"overview" else "detail")
                .put("observation",observation).put("images",JSONArray().put(image("original")).put(image("annotated"))))
        }
        r.put("views",views);r.getJSONArray("images").put(image("model_reference"));return r
    }
    @Test fun threeViewsSendAllSixPhotosAndOneReferenceInOneRequest(){
        val request=AiDirectProtocol.request(multiReview(3),"new-photo","prepare:层2板","SYSTEM")
        val content=request.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals(15,content.length())
        assertEquals(7,(0 until content.length()).count{content.getJSONObject(it).optString("type")=="image_url"})
        val text=content.getJSONObject(0).getString("text");assertTrue(text.contains("shot-2"));assertTrue(text.contains("view-3"))
        assertTrue(content.getJSONObject(5).getString("text").contains("视角2原图"))
        assertTrue(content.getJSONObject(9).getString("text").contains("视角3原图"))
        assertTrue(content.getJSONObject(13).getString("text").contains("不是现场实拍"))
    }
    @Test fun multiViewRejectsMissingDuplicatedOrOutOfOrderFrames(){
        for(count in listOf(0,4))rejected{AiDirectProtocol.request(multiReview(count),"new-photo","prepare:层2板","SYSTEM")}
        val duplicate=multiReview(2);duplicate.getJSONArray("views").getJSONObject(1).getJSONObject("observation").put("captureId","shot-0")
        rejected{AiDirectProtocol.request(duplicate,"new-photo","prepare:层2板","SYSTEM")}
        val order=multiReview(2);order.getJSONArray("views").getJSONObject(1).put("viewId","view-1")
        rejected{AiDirectProtocol.request(order,"new-photo","prepare:层2板","SYSTEM")}
    }
    @Test fun eachCloseupUsesItsOwnDimensionsForMarkerValidation(){
        val r=multiReview(2);val closeup=r.getJSONArray("views").getJSONObject(1).getJSONObject("observation")
        closeup.getJSONArray("markers").put(JSONObject().put("id",4).put("corners",JSONArray(listOf(660,20,680,20,680,40,660,40))))
        AiDirectProtocol.request(r,"new-photo","prepare:层2板","SYSTEM") // valid for the 740-wide closeup, not 640-wide overview
        closeup.getJSONArray("markers").getJSONObject(0).getJSONArray("corners").put(0,999)
        rejected{AiDirectProtocol.request(r,"new-photo","prepare:层2板","SYSTEM")}
    }
    @Test fun closeupCannotSmuggleAnotherReferenceOrExternalUrl(){
        val r=multiReview(2);val images=r.getJSONArray("views").getJSONObject(1).getJSONArray("images")
        images.put(image("model_reference"));rejected{AiDirectProtocol.request(r,"new-photo","prepare:层2板","SYSTEM")}
        images.remove(2);images.getJSONObject(0).put("base64","https://example.com/image.jpg")
        rejected{AiDirectProtocol.request(r,"new-photo","prepare:层2板","SYSTEM")}
    }
}
