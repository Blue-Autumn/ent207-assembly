package com.example.arassemblydemo

import org.junit.Assert.*
import org.junit.Test

class GuidanceReacquisitionTest {
    private fun action(x:Double=100.0)=BoardLocalGuidance.Result("rotate","转动左手边这块","按箭头转。",
        part="左侧板-2",angleDegrees=180.0,anchor=BoardFaceRules.Point(x,100.0),markerIds=listOf(1,2))
    private val loss=BoardLocalGuidance.Result("need_view","拍清左手边这块","靠近一点。")
    private fun confirmed(s:BoardLocalGuidance.Stabilizer){
        s.update(action(),0);s.update(action(),250);assertEquals("rotate",s.update(action(),500).result.status)
    }
    @Test fun briefDropoutNeverRetainsGeometryAndReacquiresFromCurrentObservation() {
        val s=BoardLocalGuidance.Stabilizer();confirmed(s)
        val lost=s.update(loss,600)
        assertEquals("need_view",lost.result.status);assertNull(lost.result.anchor);assertNull(lost.result.cueGeometry)
        val current=s.update(action(230.0),750)
        assertEquals("rotate",current.result.status);assertEquals(230.0,current.result.anchor!!.x,0.0)
        assertFalse(current.notify)
    }
    @Test fun longLossRequiresFreshDwellEvenWithContinuousNeutralFrames() {
        val s=BoardLocalGuidance.Stabilizer();confirmed(s)
        s.update(loss,800);s.update(loss,1120)
        assertEquals("need_view",s.update(action(),1200).result.status)
        s.update(action(),1450)
        assertEquals("rotate",s.update(action(),1700).result.status)
    }
    @Test fun aDifferentActionAfterShortLossCannotBorrowPreviousConfirmation() {
        val s=BoardLocalGuidance.Stabilizer();confirmed(s);s.update(loss,550)
        val different=action().copy(status="direction_ok",angleDegrees=null)
        assertEquals("need_view",s.update(different,650).result.status)
        s.update(different,900)
        assertEquals("direction_ok",s.update(different,1150).result.status)
    }
    @Test fun neverConfirmedAlternatingEvidenceDoesNotAccumulateStability() {
        val s=BoardLocalGuidance.Stabilizer()
        for(t in 0L..2000L step 200){
            assertEquals("need_view",s.update(action(),t).result.status)
            s.update(loss,t+100)
        }
    }
    @Test fun resetForNewLensDiscardsSemanticMemory() {
        val s=BoardLocalGuidance.Stabilizer();confirmed(s);s.reset()
        assertEquals("need_view",s.update(action(),650).result.status)
    }
}
