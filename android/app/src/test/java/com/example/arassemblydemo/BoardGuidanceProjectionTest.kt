package com.example.arassemblydemo

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Synthetic geometry checks. No real-camera calibration or physical seating claim. */
class BoardGuidanceProjectionTest {
    private fun sites(part:String,start:Int,normal:Int=2)=(0..7).map { slot->
        val xy=listOf(0.0 to 0.0,120.0 to 0.0,120.0 to 220.0,0.0 to 220.0)[slot%4]
        val axes=(0..2).filter { it!=normal };val xyz=MutableList(3){0.0}
        xyz[axes[0]]=xy.first;xyz[axes[1]]=xy.second;xyz[normal]=if(slot<4)7.0 else 0.0
        BoardFaceRules.Site(part,slot,start+slot,normal,xyz)
    }
    private fun detections(sites:List<BoardFaceRules.Site>,face:String="A",rotation:Double=0.0,
                           omitted:Int?=null,jitterMm:Double=0.0,offsetX:Double=0.0):List<BoardFaceRules.Detection> {
        val rad=Math.toRadians(rotation)
        return sites.filter { it.face==face && it.slot%4!=omitted }.map { s->
            val uv=(0..2).filter { it!=s.axis }
            // A deterministic physical displacement at most 2 mm in each coordinate.
            val dx=if(s.slot%2==0)jitterMm else -jitterMm
            val dy=if(s.slot%3==0)-jitterMm else jitterMm
            val x=(s.center[uv[0]]+dx)*1.7
            val y=(s.center[uv[1]]+dy)*1.3*(if(s.axis==1)1 else -1)*(if(face=="A")1 else -1)
            val cx=500+offsetX+x*cos(rad)-y*sin(rad);val cy=500+x*sin(rad)+y*cos(rad)
            BoardFaceRules.Detection(s.id,listOf(-7.0 to -7.0,7.0 to -7.0,7.0 to 7.0,-7.0 to 7.0)
                .map { BoardFaceRules.Point(cx+it.first,cy+it.second) })
        }
    }
    private fun observed(own:List<BoardFaceRules.Site>,face:String="A",rotation:Double=0.0,
                         omitted:Int?=null,jitter:Double=0.0)=
        BoardFaceRules.observe(own.first().part,own,detections(own,face,rotation,omitted,jitter))

    @Test fun eachMissingCornerStillProjectsFullBoardAxesOnEveryPlaneFaceAndCameraRoll() {
        for(normal in 0..2)for(face in listOf("A","B"))for(roll in listOf(-170.0,-90.0,0.0,37.0,90.0,179.0)) {
            val own=sites("part",0,normal);val full=observed(own,face,roll).projection!!
            for(missing in 0..3) {
                val observation=observed(own,face,roll,missing)
                assertEquals("coarse_axes",observation.reason)
                val projected=observation.projection!!
                assertEquals(3,projected.supportCount);assertEquals("nominal_affine",projected.method)
                assertEquals(full.center.x,projected.center.x,1e-7);assertEquals(full.center.y,projected.center.y,1e-7)
                assertEquals(4,projected.footprint.size);assertEquals(2,projected.axes.size)
                projected.axes.zip(full.axes).forEach { (actual,expected)->
                    assertEquals(expected.axis,actual.axis)
                    assertEquals(expected.start.x,actual.start.x,1e-7);assertEquals(expected.start.y,actual.start.y,1e-7)
                    assertEquals(expected.end.x,actual.end.x,1e-7);assertEquals(expected.end.y,actual.end.y,1e-7)
                }
            }
        }
    }

    @Test fun twoMillimeterPlacementVariationAndOneHiddenTagDoNotFlipDirectionOrFace() {
        val own=sites("frame",0)
        for(face in listOf("A","B"))for(roll in listOf(-120.0,0.0,68.0,179.0))for(missing in 0..3) {
            val baseline=observed(own,face,roll);val shifted=observed(own,face,roll,missing,2.0)
            assertEquals("coarse_axes",shifted.reason);assertEquals(face,shifted.face)
            for(axis in baseline.axes.keys)
                assertTrue(abs(BoardFaceRules.angle(baseline.heading(axis)!!-shifted.heading(axis)!!))<4)
            val a=baseline.projection!!.center;val b=shifted.projection!!.center
            assertTrue(hypot(a.x-b.x,a.y-b.y)<8)
        }
    }

    @Test fun flipAxisChangesWithBoardOrientationButNotCameraRollOrOccludedCorner() {
        val fixed=sites("frame-1",0);val moving=sites("frame-2",8)
        for(roll in listOf(-130.0,-45.0,0.0,75.0,180.0))for(missing in 0..3) {
            val a=observed(fixed,"A",roll,missing)
            assertEquals(0,BoardFaceRules.preferredFlipAxis(a,observed(moving,"B",roll,missing)))
            assertEquals(1,BoardFaceRules.preferredFlipAxis(a,observed(moving,"B",roll+180,missing)))
            assertNull(BoardFaceRules.preferredFlipAxis(a,observed(moving,"A",roll,missing)))
        }
    }

    @Test fun quarterTurnFlipTieDoesNotAlternateOnSmallPositionNoise() {
        val fixed=sites("frame-1",0);val moving=sites("frame-2",8)
        for(rotation in listOf(88.0,89.0,90.0,91.0,92.0))for(missing in 0..3)
            assertEquals(1,BoardFaceRules.preferredFlipAxis(observed(fixed),observed(moving,"B",rotation,missing,1.0)))
    }

    @Test fun projectedTargetCenterSurvivesOcclusionAndInstructionsContainOnlyOneAction() {
        val a=sites("左侧板-1",0);val b=sites("左侧板-2",8)
        val task=BoardLocalGuidance.Task("prepare:左侧板","prepare","左侧板",listOf(a[0].part,b[0].part),listOf(a[0].part),listOf(b[0].part))
        for(rotation in listOf(0.0,90.0,180.0,270.0))for(missing in 0..3) {
            val result=BoardLocalGuidance.evaluate(a+b,detections(a,omitted=missing)+detections(b,"B",rotation,missing,offsetX=700.0),task)
            assertEquals("flip",result.status);assertNotNull(result.flipAxis);assertNotNull(result.cueGeometry)
            assertEquals(result.cueGeometry!!.center,result.anchor)
            assertTrue(result.title.contains("翻一下面"));assertFalse(result.message.contains("旋转"))
            assertFalse(result.title.contains("-2"));assertFalse(result.message.contains("A 面"))
            assertTrue(result.targetLabel in setOf("左手边这块","右手边这块","上面这块","下面这块","箭头指的这块"))
        }
    }

    @Test fun perspectiveToleranceStillKeepsAmbiguousAxesAndBorderlineRotationNeutral() {
        fun o(part:String,a:Double,b:Double)=BoardFaceRules.Observation(part,"A",listOf(1,2,3),"coarse_axes",
            mapOf(0 to BoardFaceRules.Point(cos(Math.toRadians(a)),sin(Math.toRadians(a))),
                1 to BoardFaceRules.Point(cos(Math.toRadians(b)),sin(Math.toRadians(b)))))
        val fixed=o("fixed",0.0,90.0)
        // Same table's vertical direction can lean differently on the left and right of the photo.
        assertEquals("same_face_coarse_direction_ok",BoardFaceRules.compareFlatPair(fixed,o("moving",2.0,119.0)).status)
        assertEquals("need_view",BoardFaceRules.compareFlatPair(fixed,o("moving",34.0,56.0)).status)
        assertEquals("need_view",BoardFaceRules.compareFlatPair(fixed,o("moving",40.0,50.0)).status)
        assertEquals("need_view",BoardFaceRules.compareFlatPair(fixed,o("moving",30.0,120.0)).status)
        assertEquals("rotate_in_plane",BoardFaceRules.compareFlatPair(fixed,o("moving",175.0,235.0)).status)
    }
}
