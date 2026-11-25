package com.example.arassemblydemo

import kotlin.math.*

private typealias Point2 = BoardFaceRules.Point
private typealias Quad = BoardFaceRules.Detection
private typealias Face = MeasuredBoardProjection.Face

/** Local differential of one detected square. Its signed +X edge can guide a hand even when
 * the uncertainty of a homography extrapolated to a distant board corner would be excessive. */
internal class InstallTagGeometry private constructor(
    val part:String, val center:Point2, val local:Point2, val u:Point2, val v:Point2,
    private val metricSide:Double, val pixelSide:Double, private val from:List<Point2>,private val to:List<Point2>
) {
    val xHeading get() = atan2(u.y,u.x)
    val weight get() = hypot(u.x,u.y)*metricSide
    fun tangent(point:Point2):Point2 {
        val dx=point.x-local.x;val dy=point.y-local.y
        return Point2(center.x+dx*u.x+dy*v.x,center.y+dx*u.y+dy*v.y)
    }
    fun nearSlot(point:Point2):Point2? {
        // These long rectangular holes are on a regular row pitch. A tag close to the same
        // row can locate its own end; never project across the entire frame from one square.
        val dx=point.x-local.x; val dy=point.y-local.y
        if(abs(dx)>6.2*metricSide || abs(dy)>2.3*metricSide || pixelSide<13)return null
        val a=Point2(center.x+dx*u.x+dy*v.x,center.y+dx*u.y+dy*v.y)
        val h=MeasuredBoardProjection.fit(from,to)?:return null
        val projective=MeasuredBoardProjection.map(h,Point2(dx/metricSide,dy/metricSide))?:return null
        // y errors can select the neighboring row. Limit disagreement to a fraction of a tag.
        if(hypot(a.x-projective.x,a.y-projective.y)>pixelSide*.70)return null
        return Point2((2*a.x+projective.x)/3,(2*a.y+projective.y)/3)
    }
    companion object {
        private fun average(p:List<Point2>)=Point2(p.map { it.x }.average(),p.map { it.y }.average())
        fun create(face:Face,d:Quad,allowOblique:Boolean=false):InstallTagGeometry? {
            val a=face.markers[d.id]?:return null;val b=d.corners
            if(a.size!=4||b.size!=4)return null
            val ca=average(a);val cb=average(b)
            val s=(a.indices).map { hypot(a[it].x-a[(it+1)%4].x,a[it].y-a[(it+1)%4].y) }.average()
            val side=b.indices.map { hypot(b[it].x-b[(it+1)%4].x,b[it].y-b[(it+1)%4].y) }.min()
            if(side<9 || s<1)return null
            val aa=a.map { Point2(it.x-ca.x,it.y-ca.y) };val bb=b.map { Point2(it.x-cb.x,it.y-cb.y) }
            val xx=aa.sumOf { it.x*it.x };val yy=aa.sumOf { it.y*it.y };val xy=aa.sumOf { it.x*it.y }
            val det=xx*yy-xy*xy
            if(det<1e-5)return null
            fun coef(x:Double,y:Double)=Point2((yy*x-xy*y)/det,(xx*y-xy*x)/det)
            val px=coef(aa.indices.sumOf { aa[it].x*bb[it].x },aa.indices.sumOf { aa[it].y*bb[it].x })
            val py=coef(aa.indices.sumOf { aa[it].x*bb[it].y },aa.indices.sumOf { aa[it].y*bb[it].y })
            val u=Point2(px.x,py.x);val v=Point2(px.y,py.y)
            val lu=hypot(u.x,u.y);val lv=hypot(v.x,v.y)
            val signedArea=u.x*v.y-u.y*v.x
            val sign=(if(face.normal==1)1 else -1)*(if(face.face=="A")1 else -1)
            if(lu<(if(allowOblique).34 else .52)*lv || lu>3.4*lv || signedArea*sign<.44*lu*lv)return null
            val error=aa.indices.maxOf { i->hypot(aa[i].x*u.x+aa[i].y*v.x-bb[i].x,aa[i].x*u.y+aa[i].y*v.y-bb[i].y) }
            if(error>max(1.5,.09*side))return null
            return InstallTagGeometry(face.part,cb,ca,u,v,s,side,aa.map { Point2(it.x/s,it.y/s) },b)
        }
    }
}
