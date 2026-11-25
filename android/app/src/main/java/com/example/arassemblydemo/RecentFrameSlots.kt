package com.example.arassemblydemo

import kotlin.math.*

/** Short visual continuity for an already joined frame. The current common tags must still
 * agree with its previous image. Never used by the orientation or row acceptance path. */
internal class RecentFrameSlots {
    private var at=Long.MIN_VALUE
    private var slots=emptyMap<String,BoardFaceRules.Point>()
    private var quads=emptyMap<Int,List<BoardFaceRules.Point>>()
    fun clear(){at=Long.MIN_VALUE;slots=emptyMap();quads=emptyMap()}
    fun remember(points:Map<String,BoardFaceRules.Point>,tags:List<BoardFaceRules.Detection>,now:Long){
        slots=points.toMap();quads=tags.associate { it.id to it.corners };at=now
    }
    fun project(tags:List<BoardFaceRules.Detection>,now:Long):Map<String,BoardFaceRules.Point> {
        if(at<0 || now-at !in 0..650)return emptyMap()
        val pairs=tags.flatMap { tag -> quads[tag.id]?.zip(tag.corners).orEmpty() }
        if(pairs.size<4)return emptyMap()
        val ax=pairs.map { it.first.x }.average();val ay=pairs.map { it.first.y }.average()
        val bx=pairs.map { it.second.x }.average();val by=pairs.map { it.second.y }.average()
        val sum=pairs.sumOf { (it.first.x-ax).pow(2)+(it.first.y-ay).pow(2) }
        if(sum<100)return emptyMap()
        val c=pairs.sumOf { (it.first.x-ax)*(it.second.x-bx)+(it.first.y-ay)*(it.second.y-by) }/sum
        val s=pairs.sumOf { (it.first.x-ax)*(it.second.y-by)-(it.first.y-ay)*(it.second.x-bx) }/sum
        if(hypot(c,s) !in .93..1.07 || abs(atan2(s,c))>.10)return emptyMap()
        fun map(p:BoardFaceRules.Point)=BoardFaceRules.Point(bx+c*(p.x-ax)-s*(p.y-ay),by+s*(p.x-ax)+c*(p.y-ay))
        val edge=tags.filter { it.id in quads }.minOf { tag->tag.corners.indices.minOf { i->
            val a=tag.corners[i];val b=tag.corners[(i+1)%4];hypot(a.x-b.x,a.y-b.y)
        } }
        if(edge<9 || hypot(bx-ax,by-ay)>edge*1.25)return emptyMap()
        val maxError=pairs.maxOf { (a,b)->map(a).let { hypot(it.x-b.x,it.y-b.y) } }
        if(maxError>max(1.5,edge*.065))return emptyMap()
        // A small isolated tag cannot refresh a remote slot once camera perspective changes.
        return slots.mapValues { map(it.value) }.filterValues {
            pairs.size>=8 || hypot(it.x-bx,it.y-by)<edge*12
        }
    }
}
