package com.example.arassemblydemo

import kotlin.math.*

/** Pixel-space label packing; knows no camera, Android UI, board pose, or assembly outcome. */
internal object AiLabelLayout {
    data class Point(val x:Double,val y:Double)
    data class Box(val left:Double,val top:Double,val right:Double,val bottom:Double) {
        val width get()=right-left
        val height get()=bottom-top
        val center get()=Point((left+right)/2,(top+bottom)/2)
        fun expand(amount:Double)=Box(left-amount,top-amount,right+amount,bottom+amount)
        fun overlaps(other:Box)=left<other.right-1e-7 && right>other.left+1e-7 && top<other.bottom-1e-7 && bottom>other.top+1e-7
        fun valid()=listOf(left,top,right,bottom).all { it.isFinite() } && width>0 && height>0
    }
    data class Label(val key:Int,val marker:Box,val width:Double,val compactWidth:Double,val height:Double)
    data class Placed(val key:Int,val box:Box?,val compact:Boolean,val leader:List<Point>)

    fun arrange(width:Double,height:Double,labels:List<Label>,gap:Double=3.0):List<Placed> {
        require(width.isFinite() && height.isFinite() && width>0 && height>0 && gap.isFinite() && gap>=0)
        require(labels.map { it.key }.distinct().size==labels.size)
        require(labels.all { it.marker.valid() && it.marker.left>=0 && it.marker.top>=0 && it.marker.right<=width && it.marker.bottom<=height &&
            listOf(it.width,it.compactWidth,it.height).all { n->n.isFinite() && n>0 } })
        val result=mutableListOf<Placed>()
        val markers=labels.map { it.marker }
        for((index,label) in labels.withIndex()) {
            val placed=result.mapNotNull { it.box }
            val obstacles=markers+placed
            val maxDistance=maxOf(min(width,height)*0.22,label.marker.width*3,label.marker.height*3)
            fun select(labelWidth:Double):Pair<Box,List<Point>>? {
                if(labelWidth>width || label.height>height)return null
                val candidates=candidates(width,height,labelWidth,label.height,label.marker,obstacles,gap)
                val lineObstacles=markers.filterIndexed { i,_->i!=index }.map { it.expand(gap/2) }+placed.map { it.expand(gap/2) }
                return candidates.asSequence()
                    .filter { distance(it.center,label.marker.center)<=maxDistance }
                    .filter { box -> result.all { old->old.leader.zipWithNext().none { (a,b)->segmentIntersects(a,b,box.expand(gap/2)) } } }
                    .sortedBy { distance(it.center,label.marker.center) }
                    .mapNotNull { box -> leader(label.marker,box,lineObstacles)?.let { box to it } }.firstOrNull()
            }
            val full=select(label.width)
            val found=full?:select(label.compactWidth)
            result+=Placed(label.key,found?.first,full==null,found?.second.orEmpty())
        }
        return result
    }

    /** All obstacle-edge rows plus the desired above/below rows; sweep horizontal free intervals. */
    private fun candidates(width:Double,height:Double,w:Double,h:Double,marker:Box,obstacles:List<Box>,gap:Double):List<Box> {
        val ys=(listOf(0.0,height-h,marker.top-h-gap,marker.bottom+gap,marker.center.y-h/2)+
            obstacles.flatMap { listOf(it.top-h-gap,it.bottom+gap) }).filter { it>=0 && it+h<=height }.distinct()
        val preferredX=marker.center.x-w/2
        val boxes=linkedSetOf<Box>()
        for(y in ys) {
            val blocked=obstacles.filter { it.top-gap<y+h && it.bottom+gap>y }
                .map { max(0.0,it.left-gap) to min(width,it.right+gap) }.sortedBy { it.first }
            val free=mutableListOf<Pair<Double,Double>>()
            var cursor=0.0
            for((left,right) in blocked) { if(left>cursor)free+=cursor to left;cursor=max(cursor,right) }
            if(cursor<width)free+=cursor to width
            for((left,right) in free) {
                if(right-left+1e-7<w)continue
                val last=(right-w).coerceAtLeast(left)
                for(x in listOf(preferredX.coerceIn(left,last),left,last)) {
                    val box=Box(x,y,x+w,y+h)
                    if(obstacles.none { box.overlaps(it.expand(gap)) })boxes+=box
                }
            }
        }
        return boxes.toList()
    }

    private fun distance(a:Point,b:Point)=hypot(a.x-b.x,a.y-b.y)
    private fun nearestEdge(box:Box,point:Point)=Point(point.x.coerceIn(box.left,box.right),point.y.coerceIn(box.top,box.bottom))
    private fun leader(marker:Box,label:Box,obstacles:List<Box>):List<Point>? {
        val start=nearestEdge(marker,label.center);val end=nearestEdge(label,marker.center)
        val choices=listOf(listOf(start,end),listOf(start,Point(start.x,end.y),end),listOf(start,Point(end.x,start.y),end))
        return choices.firstOrNull { points -> points.zipWithNext().all { (a,b)->obstacles.none { segmentIntersects(a,b,it) } } }
            ?.let { points->points.filterIndexed { i,p->i==0 || p!=points[i-1] } }
    }

    /** Closed slab intersection, including a line crossing a foreign tag with both ends outside. */
    internal fun segmentIntersects(a:Point,b:Point,box:Box):Boolean {
        var lo=0.0;var hi=1.0
        for((origin,delta,minBound,maxBound) in listOf(
            listOf(a.x,b.x-a.x,box.left,box.right),listOf(a.y,b.y-a.y,box.top,box.bottom))) {
            if(abs(delta)<1e-9) { if(origin<minBound || origin>maxBound)return false }
            else {
                val p=(minBound-origin)/delta;val q=(maxBound-origin)/delta
                lo=max(lo,min(p,q));hi=min(hi,max(p,q))
                if(lo>hi)return false
            }
        }
        return true
    }
}
