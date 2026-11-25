package com.example.arassemblydemo

import kotlin.math.*

/** A planar teaching motion between two measured gray-frame halves, never a seating verdict.
 *
 * Each photographed half has its own local millimetre origin. The source calibration's CAD
 * origins give their intended assembled relationship. Two accepted homographies are compared
 * over the whole moving face before emitting a translation. This consistency check rejects
 * obvious independently tilted/lifted pieces; it does not measure physical coplanarity or depth.
 */
internal object BoardPlacementCue {
    data class Placement(val deltaLocal:BoardFaceRules.Point,val distanceMm:Double,val fixedPart:String,
                         val method:String="coplanar_measured_translation")

    fun suggest(fixed:BoardFaceRules.Observation,moving:BoardFaceRules.Observation,
                catalog:MeasuredBoardProjection.Catalog?):Placement? {
        if(catalog==null || fixed.face==null || fixed.face!=moving.face || fixed.part==moving.part)return null
        if(fixed.part.substringBeforeLast('-') !in setOf("左侧板","右侧板") ||
            fixed.part.substringBeforeLast('-')!=moving.part.substringBeforeLast('-'))return null
        val a=fixed.projection?:return null;val b=moving.projection?:return null
        val fa=catalog.faces[fixed.part to fixed.face]?:return null
        val fb=catalog.faces[moving.part to moving.face]?:return null
        if(fa.axes!=fb.axes || fa.normal!=fb.normal || fa.cadOrigin.size!=3 || fb.cadOrigin.size!=3)return null
        if(abs(fa.cadOrigin[fa.normal]-fb.cadOrigin[fb.normal])>1.0)return null
        if(listOf(a,b).any { it.method!="measured_homography" || it.supportCount<2 ||
                    it.planeToImage.size!=9 || it.localCenter.size!=2 || it.localHalfExtent.size!=2 ||
                    it.localHalfExtent.any { half->!half.isFinite() || half<=1.0 } })return null
        val axes=fa.axes
        // Moving normalized coordinates -> intended CAD location -> fixed normalized coordinates.
        val scale=DoubleArray(2) { b.localHalfExtent[it]/a.localHalfExtent[it] }
        val offset=DoubleArray(2) { k->
            (b.localCenter[k]+fb.cadOrigin[axes[k]]-fa.cadOrigin[axes[k]]-a.localCenter[k])/a.localHalfExtent[k]
        }
        val targetH=multiply(a.planeToImage.toDoubleArray(),doubleArrayOf(scale[0],0.0,offset[0],0.0,scale[1],offset[1],0.0,0.0,1.0))
        val targetCenter=MeasuredBoardProjection.map(targetH,BoardFaceRules.Point(0.0,0.0))?:return null
        val sourceH=b.planeToImage.toDoubleArray()
        val delta=inverseMap(sourceH,targetCenter)?:return null
        val distance=hypot(delta.x*b.localHalfExtent[0],delta.y*b.localHalfExtent[1])
        // Source registration and FDM edges are not millimetre metrology. Stop directing a move
        // once broadly lined up, so an already-joined interface is not told to separate again.
        val deadZone=max(14.0,b.localHalfExtent.min()*.16)
        if(!distance.isFinite() || distance<=deadZone || distance>b.localHalfExtent.max()*6)return null
        val probes=listOf(-1.0,0.0,1.0).flatMap { y->listOf(-1.0,0.0,1.0).map { x->BoardFaceRules.Point(x,y) } }
        val targets=probes.map { MeasuredBoardProjection.map(targetH,it)?:return null }
        val actual=probes.map { p->MeasuredBoardProjection.map(sourceH,BoardFaceRules.Point(p.x+delta.x,p.y+delta.y))?:return null }
        val span=targets.maxOf { p->targets.maxOf { q->hypot(p.x-q.x,p.y-q.y) } }
        if(span<50 || !span.isFinite())return null
        // Independent planes must agree on the size, perspective and orientation of the target.
        // Fit residual at the original tags alone says nothing about this pair-level assumption.
        val discrepancy=targets.indices.maxOf { k->hypot(targets[k].x-actual[k].x,targets[k].y-actual[k].y) }
        if(discrepancy>max(8.0,span*.08))return null
        for(t in listOf(0.0,.25,.5,.75,1.0))for(p in probes) {
            if(MeasuredBoardProjection.map(sourceH,BoardFaceRules.Point(p.x+delta.x*t,p.y+delta.y*t))==null)return null
        }
        return Placement(delta,distance,fixed.part)
    }

    private fun multiply(a:DoubleArray,b:DoubleArray)=DoubleArray(9) { i->
        val row=i/3;val col=i%3;(0..2).sumOf { k->a[row*3+k]*b[k*3+col] }
    }

    /** Inverting only the requested point avoids arbitrary matrix scale/sign affecting horizon checks. */
    private fun inverseMap(h:DoubleArray,p:BoardFaceRules.Point):BoardFaceRules.Point? {
        val a=h[0]-p.x*h[6];val b=h[1]-p.x*h[7];val c=p.x*h[8]-h[2]
        val d=h[3]-p.y*h[6];val e=h[4]-p.y*h[7];val f=p.y*h[8]-h[5]
        val det=a*e-b*d
        if(!det.isFinite() || abs(det)<1e-9)return null
        val q=BoardFaceRules.Point((c*e-b*f)/det,(a*f-c*d)/det)
        return q.takeIf { it.x.isFinite() && it.y.isFinite() && MeasuredBoardProjection.map(h,it)!=null }
    }
}
