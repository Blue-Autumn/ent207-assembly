package com.example.arassemblydemo

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class BoardLocalGuidanceTest {
    private val fixed = "层2板-1"
    private val moving = "层2板-2"
    private val task = BoardLocalGuidance.Task("prepare:层2板", "prepare", "层2板", listOf(fixed,moving), listOf(fixed), listOf(moving))
    private fun sites(part: String, start: Int, axis: Int = 1): List<BoardFaceRules.Site> = (0..7).map { slot ->
        val uv = listOf(0.0 to 0.0,120.0 to 3.0,120.0 to 180.0,0.0 to 180.0)[slot%4]
        val axes = (0..2).filter { it != axis }; val p = MutableList(3){0.0}
        p[axes[0]]=uv.first; p[axes[1]]=uv.second; p[axis]=if(slot<4)4.5 else 0.0
        BoardFaceRules.Site(part,slot,start+slot,axis,p)
    }
    private fun detections(sites: List<BoardFaceRules.Site>, face: String = "A", angle: Double = 0.0,
                           slots: Set<Int> = setOf(0,1,2,3), scale: Double = 1.5, bitmapTurns: Int = 0): List<BoardFaceRules.Detection> {
        val a = Math.toRadians(angle)
        return sites.filter { it.face==face && it.slot%4 in slots }.map { s ->
            val uv = (0..2).filter { it!=s.axis }; val parity=(if(s.axis==1)1.0 else -1.0)*(if(face=="A")1 else -1)
            val x=s.center[uv[0]]*scale; val y=s.center[uv[1]]*scale*parity
            val cx=400+x*cos(a)-y*sin(a); val cy=400+x*sin(a)+y*cos(a)
            val corners=listOf(-6.0 to -6.0,6.0 to -6.0,6.0 to 6.0,-6.0 to 6.0).map { BoardFaceRules.Point(cx+it.first,cy+it.second) }
            BoardFaceRules.Detection(s.id,(0..3).map { corners[(it+bitmapTurns)%4] })
        }
    }
    private val a=sites(fixed,0); private val b=sites(moving,8)
    private fun evaluate(da: List<BoardFaceRules.Detection> = detections(a), db: List<BoardFaceRules.Detection> = detections(b)) =
        BoardLocalGuidance.evaluate(a+b,da+db,task)

    @Test fun allFourLayerFaceCombinationsKeepNotchDirectionAndNeverRequestFlip() {
        for(fa in listOf("A","B")) for(fb in listOf("A","B")) {
            val r=evaluate(detections(a,fa),detections(b,fb))
            assertEquals("direction_ok",r.status); assertEquals(0,r.axis); assertNull(r.angleDegrees)
        }
    }
    @Test fun halfTurnIsDetectedEvenWithMixedFaces() {
        for(face in listOf("A","B")) {
            val r=evaluate(detections(a,angle=37.0),detections(b,face,angle=-143.0))
            assertEquals("rotate",r.status); assertEquals(moving,r.part)
            assertEquals(180.0,abs(r.angleDegrees!!),1e-7)
        }
    }
    @Test fun twoSeparatedMarkersProvideNotchAxisWithoutInventingFullPlane() {
        val r=evaluate(detections(a,slots=setOf(0,1)),detections(b,"B",slots=setOf(0,1)))
        assertEquals("direction_ok",r.status); assertEquals(4,r.markerIds.size)
        val turned=evaluate(detections(a,slots=setOf(0,1)),detections(b,angle=90.0,slots=setOf(0,1)))
        assertEquals("rotate",turned.status); assertEquals(-90.0,turned.angleDegrees!!,1e-7)
    }
    @Test fun diagonalOrDepthOnlyPairsCannotGiveNotchDirection() {
        for(pair in listOf(setOf(0,2),setOf(0,3),setOf(1,3))) {
            assertEquals("need_view",evaluate(detections(a,slots=pair),detections(b,slots=pair)).status)
        }
    }
    @Test fun bitmapRotationNeverChangesBoardDirection() {
        for(turn in 0..3) {
            val r=evaluate(detections(a,bitmapTurns=turn),detections(b,angle=90.0,bitmapTurns=(turn+1)%4))
            assertEquals("rotate",r.status); assertEquals(-90.0,r.angleDegrees!!,1e-7)
        }
    }
    @Test fun missingMemberAndTinyPairGiveNeutralNotFailure() {
        assertEquals("need_view",evaluate(db=emptyList()).status)
        assertEquals("searching",evaluate(emptyList(),emptyList()).status)
        assertEquals("need_view",evaluate(detections(a,slots=setOf(0,1),scale=.2),detections(b,slots=setOf(0,1),scale=.2)).status)
    }
    @Test fun moderateDisagreementAsksForFlatViewRatherThanUnstableArrow() {
        assertEquals("need_view",evaluate(db=detections(b,angle=32.0)).status)
        assertEquals("direction_ok",evaluate(db=detections(b,angle=12.0)).status)
    }
    @Test fun previousStepPartsCannotMaskCurrentStep() {
        val old=sites("层1板-1",20)
        val current=evaluate()
        val withUnrelated=BoardLocalGuidance.evaluate(a+b+old,detections(a)+detections(b)+detections(old,angle=180.0),task)
        assertEquals(current,withUnrelated)
    }
    @Test fun clearlyDifferentGroupIsNamedButOneTagOrCrowdedGroupsAreNotGuessed() {
        val old=sites("层1板-1",20);val another=sites("左侧板-1",40,2)
        fun r(d: List<BoardFaceRules.Detection>) = BoardLocalGuidance.evaluate(a+b+old+another,d,task)
        val wrong=r(detections(old,slots=setOf(0,1)))
        assertEquals("wrong_part",wrong.status);assertTrue(wrong.message.contains("紫色层板"));assertTrue(wrong.message.contains("蓝色层板"))
        assertNull(wrong.angleDegrees)
        assertEquals("searching",r(detections(old,slots=setOf(0))).status)
        assertEquals("searching",r(detections(old)+detections(another)).status)
        val duplicate=detections(old,slots=setOf(0))
        assertEquals("searching",r(duplicate+duplicate).status)
        assertEquals("need_view",r(detections(a)+detections(old)).status)
    }
    @Test fun wrongPartNeedsStabilityAndDoesNotSurviveTheCorrectTarget() {
        val old=sites("层1板-1",20)
        val wrong=BoardLocalGuidance.evaluate(a+b+old,detections(old),task)
        val stable=BoardLocalGuidance.Stabilizer()
        stable.update(wrong,0);stable.update(wrong,250)
        assertEquals("wrong_part",stable.update(wrong,500).result.status)
        assertEquals("need_view",stable.update(evaluate(),600).result.status)
        stable.update(evaluate(),850)
        assertEquals("direction_ok",stable.update(evaluate(),1100).result.status)
    }
    @Test fun duplicateOrBothFaceOrMalformedMarkersClearDirections() {
        val da=detections(a)
        assertEquals("need_view",evaluate(da+da.first()).status)
        assertEquals("need_view",evaluate(da+detections(a,"B")).status)
        assertEquals("need_view",evaluate(da.map { it.copy(corners=List(4){BoardFaceRules.Point(2.0,2.0)}) }).status)
    }
    @Test fun totalAssemblyDoesNotMakeDifferentPlaneOrientationVerdict() {
        val install=task.copy(kind="install",id="install:层2板")
        val r=BoardLocalGuidance.evaluate(a+b,detections(a)+detections(b,angle=180.0),install)
        assertEquals("identity_only",r.status);assertNull(r.angleDegrees)
        assertEquals("need_view",BoardLocalGuidance.evaluate(a+b,detections(a),install).status)
    }
    @Test fun sideFramesKeepFaceRestrictionAndNeedTwoAxes() {
        val s1=sites("左侧板-1",30,2);val s2=sites("左侧板-2",40,2)
        val t=task.copy(group="左侧板",parts=listOf("左侧板-1","左侧板-2"),stationary=listOf("左侧板-1"),members=listOf("左侧板-2"))
        assertEquals("flip",BoardLocalGuidance.evaluate(s1+s2,detections(s1)+detections(s2,"B"),t).status)
        assertEquals("direction_ok",BoardLocalGuidance.evaluate(s1+s2,detections(s1)+detections(s2),t).status)
        assertEquals("need_view",BoardLocalGuidance.evaluate(s1+s2,detections(s1,slots=setOf(0,1))+detections(s2,slots=setOf(0,1)),t).status)
    }
    @Test fun frameFlipPrecedesRotationAndUsesOneActionAtATime() {
        val s1=sites("左侧板-1",30,2);val s2=sites("左侧板-2",40,2)
        val t=task.copy(group="左侧板",parts=listOf("左侧板-1","左侧板-2"),stationary=listOf("左侧板-1"),members=listOf("左侧板-2"))
        fun result(face: String, angle: Double) = BoardLocalGuidance.evaluate(s1+s2,detections(s1)+detections(s2,face,angle),t)
        for (angle in listOf(-150.0,-90.0,0.0,45.0,180.0)) {
            val flipped=result("B",angle)
            assertEquals("flip",flipped.status);assertNull(flipped.angleDegrees);assertEquals("左侧板-2",flipped.part)
        }
        assertEquals("rotate",result("A",90.0).status)
        assertEquals("direction_ok",result("A",0.0).status)
    }
    @Test fun incompleteFaceEvidenceDoesNotCommandAFlipAndLossWithdrawsIt() {
        val s1=sites("左侧板-1",30,2);val s2=sites("左侧板-2",40,2)
        val t=task.copy(group="左侧板",parts=listOf("左侧板-1","左侧板-2"),stationary=listOf("左侧板-1"),members=listOf("左侧板-2"))
        val sparse=BoardLocalGuidance.evaluate(s1+s2,detections(s1,slots=setOf(0))+detections(s2,"B",slots=setOf(0)),t)
        assertEquals("face_check",sparse.status);assertNull(sparse.angleDegrees)
        val flip=BoardLocalGuidance.evaluate(s1+s2,detections(s1)+detections(s2,"B"),t)
        val stable=BoardLocalGuidance.Stabilizer()
        stable.update(flip,0);stable.update(flip,250)
        assertEquals("flip",stable.update(flip,500).result.status)
        val loss=stable.update(sparse,600)
        assertEquals("face_check",loss.result.status);assertFalse(loss.notify)
        assertEquals("flip",stable.update(flip,650).result.status)
    }
    @Test fun targetLabelUsesVisiblePositionNotInternalPartSuffix() {
        val left=detections(a).map { d->d.copy(corners=d.corners.map { it.copy(x=it.x-400) }) }
        val right=detections(b,angle=180.0).map { d->d.copy(corners=d.corners.map { it.copy(x=it.x+500) }) }
        val r=evaluate(left,right)
        assertEquals("rotate",r.status);assertEquals("右手边这块",r.targetLabel)
        assertFalse(r.title.contains("-2"));assertFalse(r.message.contains("第2半"))
    }
    @Test fun stableHintsNeedBothTimeAndFramesAndNotifyOnce() {
        val stable=BoardLocalGuidance.Stabilizer();val r=evaluate()
        assertEquals("need_view",stable.update(r,0).result.status)
        assertFalse(stable.update(r,250).notify)
        val ready=stable.update(r,500);assertEquals("direction_ok",ready.result.status);assertTrue(ready.notify)
        assertFalse(stable.update(r,625).notify)
        stable.reset();assertEquals("need_view",stable.update(r,700).result.status)
    }
    @Test fun lostFrameImmediatelyWithdrawsArrowAndLateGapsRequireRestabilizing() {
        val stable=BoardLocalGuidance.Stabilizer();val r=evaluate(db=detections(b,angle=180.0))
        stable.update(r,0);stable.update(r,250);assertEquals("rotate",stable.update(r,500).result.status)
        val lost=stable.update(evaluate(emptyList(),emptyList()),550)
        assertEquals("searching",lost.result.status);assertNull(lost.result.angleDegrees)
        assertEquals("rotate",stable.update(r,650).result.status)
        stable.update(r,900);assertFalse(stable.update(r,1150).notify)
        assertEquals("need_view",stable.update(r,2200).result.status)
    }
    @Test fun changingDirectionRestartsStabilityInsteadOfKeepingPreviousError() {
        val s=BoardLocalGuidance.Stabilizer();val wrong=evaluate(db=detections(b,angle=90.0));val right=evaluate()
        s.update(wrong,0);s.update(wrong,250);s.update(wrong,500)
        val changed=s.update(right,600);assertEquals("need_view",changed.result.status);assertNull(changed.result.angleDegrees)
        s.update(right,850);val ready=s.update(right,1100);assertEquals("direction_ok",ready.result.status);assertTrue(ready.notify)
    }
}
