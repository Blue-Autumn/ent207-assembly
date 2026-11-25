package com.example.arassemblydemo

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class BoardFaceRulesTest {
    private fun sites(part:String="blue-1",axis:Int=1,start:Int=0):List<BoardFaceRules.Site> = (0..7).map { slot->
        val uv=listOf(0.0 to 0.0,120.0 to 0.0,120.0 to 180.0,0.0 to 180.0)[slot%4]
        val axes=(0..2).filter { it!=axis };val point=MutableList(3){0.0};point[axes[0]]=uv.first;point[axes[1]]=uv.second
        point[axis]=if(slot<4)4.5 else 0.0
        BoardFaceRules.Site(part,slot,start+slot,axis,point)
    }
    private fun observation(part:String="blue-1",axis:Int=1,face:String="A",rotation:Double=0.0,start:Int=0,count:Int=4,
                            tagBitmapQuarterTurns:Int=0):BoardFaceRules.Observation {
        val own=sites(part,axis,start);val angle=Math.toRadians(rotation);val uv=(0..2).filter { it!=axis }
        val parity=(if(axis==1)1.0 else -1.0)*(if(face=="A")1 else -1)
        val detected=own.filter { it.face==face }.take(count).map { s->
            val x=s.center[uv[0]]*1.5;val y=s.center[uv[1]]*parity*1.1
            val cx=400+x*cos(angle)-y*sin(angle);val cy=400+x*sin(angle)+y*cos(angle)
            val corners=listOf(-5.0 to -5.0,5.0 to -5.0,5.0 to 5.0,-5.0 to 5.0)
                .map { BoardFaceRules.Point(cx+it.first,cy+it.second) }
            BoardFaceRules.Detection(s.id,(0..3).map { corners[(it+tagBitmapQuarterTurns)%4] })
        }
        return BoardFaceRules.observe(part,own,detected)
    }

    @Test fun oneMarkerIdentifiesFaceButNotDirection() {
        for(face in listOf("A","B")) {
            val seen=observation(face=face,count=1)
            assertEquals(face,seen.face);assertEquals("face_only_more_tags_for_direction",seen.reason);assertTrue(seen.axes.isEmpty())
        }
    }
    @Test fun allGlobalPlanesAndSidesRecoverImageAxisHeading() {
        for(axis in 0..2)for(face in listOf("A","B"))for(rotation in listOf(-170.0,-90.0,0.0,42.0,179.0)) {
            val seen=observation(axis=axis,face=face,rotation=rotation)
            assertEquals("coarse_axes",seen.reason);assertEquals(face,seen.face)
            val u=(0..2).first { it!=axis }
            assertEquals(0.0,BoardFaceRules.angle(seen.heading(u)!!-rotation),1e-6)
        }
    }
    @Test fun tagBitmapRotationDoesNotRotateBoardEstimate() {
        val baseline=observation(rotation=32.0)
        for(turn in 1..3)for(axis in baseline.axes.keys) {
            val shifted=observation(rotation=32.0,tagBitmapQuarterTurns=turn).axes.getValue(axis)
            assertEquals(baseline.axes.getValue(axis).x,shifted.x,1e-9)
            assertEquals(baseline.axes.getValue(axis).y,shifted.y,1e-9)
        }
    }
    @Test fun bothCommonFacesAcceptedAndMixedExposureIsNotDeclaredAssemblyFailure() {
        for(face in listOf("A","B"))assertEquals("same_face_coarse_direction_ok",
            BoardFaceRules.compareFlatPair(observation(face=face),observation("blue-2",face=face,start=8)).status)
        val mixed=BoardFaceRules.compareFlatPair(observation(),observation("blue-2",face="B",start=8))
        assertEquals("exposed_faces_differ",mixed.status);assertEquals("blue-2",mixed.movingPart)
        assertTrue(mixed.message.contains("先将两半平放"))
    }
    @Test fun oppositeInPlaneDirectionSuggestsHalfTurnWithoutFlippingFace() {
        val pair=BoardFaceRules.compareFlatPair(observation(rotation=28.0),observation("blue-2",rotation=-152.0,start=8))
        assertEquals("rotate_in_plane",pair.status);assertEquals(180.0,abs(pair.angleDegrees!!),1e-6)
    }
    @Test fun layerAllowsEveryFaceCombinationWhenNotchAxisMatches() {
        for(a in listOf("A","B"))for(b in listOf("A","B")) {
            val pair=BoardFaceRules.compareLayerPair(observation(face=a,rotation=31.0),observation("blue-2",face=b,rotation=31.0,start=8))
            assertEquals("coarse_direction_ok",pair.status);assertNull(pair.movingPart)
        }
    }
    @Test fun mixedLayerFacesDoNotExemptTheReversedNotchDirection() {
        for(a in listOf("A","B"))for(b in listOf("A","B")) {
            val pair=BoardFaceRules.compareLayerPair(observation(face=a),observation("blue-2",face=b,rotation=180.0,start=8))
            assertEquals("rotate_in_plane",pair.status);assertEquals("blue-2",pair.movingPart)
        }
    }
    @Test fun layerUnknownDirectionDoesNotBecomeFaceFailureOrSuccess() {
        val pair=BoardFaceRules.compareLayerPair(observation(count=1),observation("blue-2",face="B",start=8))
        assertEquals("direction_unknown",pair.status);assertNull(pair.angleDegrees)
    }
    @Test fun missingMarkersNeverBecomeAnOrientationSuccess() {
        val seen=BoardFaceRules.observe("blue-1",sites(),emptyList())
        assertNull(seen.face);assertEquals("need_view",BoardFaceRules.compareFlatPair(seen,observation("blue-2",start=8)).status)
    }
    @Test fun duplicateAndBothFaceReadingsAreAmbiguous() {
        fun d(id:Int)=BoardFaceRules.Detection(id,listOf(BoardFaceRules.Point(10.0,10.0),BoardFaceRules.Point(20.0,10.0),BoardFaceRules.Point(20.0,20.0),BoardFaceRules.Point(10.0,20.0)))
        assertEquals("duplicate_ids",BoardFaceRules.observe("blue-1",sites(),listOf(d(0),d(0))).reason)
        val both=BoardFaceRules.observe("blue-1",sites(),listOf(d(0),d(4)))
        assertEquals("both_faces_seen",both.reason);assertNull(both.face)
    }
    @Test fun collinearOrTinyObservedTriangleDoesNotInventAnAxis() {
        val own=sites().map { s->s.copy(center=listOf(s.slot.toDouble()*30,0.0,0.0)) }
        val d=(0..2).map { i->BoardFaceRules.Detection(i,List(4){BoardFaceRules.Point(i.toDouble()*100,50.0)}) }
        assertEquals("collinear_tags",BoardFaceRules.observe("blue-1",own,d).reason)
    }
    @Test fun crossPlaneAssemblyReturnsSharedGlobalAxisOnly() {
        val frame=observation("frame",axis=2,rotation=15.0,start=8)
        val layer=observation(rotation=15.0)
        val common=BoardFaceRules.sharedAxisDifferences(frame,layer)
        assertEquals(setOf(0),common.keys);assertEquals(0.0,common.getValue(0),1e-6)
    }
}
