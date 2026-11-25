package com.example.arassemblydemo

import kotlin.math.*

/** Calibrated only for the photographed purple pair and the printed sites used in that installation. */
internal object PurpleJointLocator {
    data class XY(val x: Double, val y: Double)
    private data class Shape(val ids: List<Int>, val origin: XY, val other: XY, val outline: List<XY>, val sign: Int)
    private val shapes = listOf(
        Shape(listOf(4,5), XY(235.75,231.75), XY(242.5,1414.5),
            listOf(XY(1104.0,763.0),XY(1160.0,718.0),XY(1161.0,935.0),XY(1105.0,886.0)),1),
        Shape(listOf(6,7), XY(1134.0,214.0), XY(1134.5,1415.75),
            listOf(XY(284.0,762.0),XY(340.0,715.0),XY(340.0,941.0),XY(284.0,890.0)),-1)
    )
    fun locate(gray: ByteArray, w: Int, h: Int, centers: Map<Int,XY>): List<XY>? {
        require(gray.size == w*h)
        if (centers.keys != setOf(4,5,6,7)) return null
        return shapes.map { search(gray,w,h,it,centers) ?: return null }
    }
    private data class Sample(val x: Int,val y: Int,val nx: Int,val ny: Int)
    private fun search(gray: ByteArray,w: Int,h: Int,s: Shape,centers: Map<Int,XY>): XY? {
        val first=centers.getValue(s.ids[0]); val last=centers.getValue(s.ids[1])
        val ux=s.other.x-s.origin.x; val uy=s.other.y-s.origin.y
        val vx=last.x-first.x; val vy=last.y-first.y
        val a=(vx*ux+vy*uy)/(ux*ux+uy*uy); val b=(vy*ux-vx*uy)/(ux*ux+uy*uy)
        val scale=hypot(a,b)
        if (scale < 0.15 || scale > 4) return null
        val edge=s.outline.map { val x=it.x-s.origin.x; val y=it.y-s.origin.y
            XY(first.x+a*x-b*y,first.y+b*x+a*y) }
        val segments=edge.zipWithNext().map { (p,q) ->
            val dx=q.x-p.x; val dy=q.y-p.y; val len=hypot(dx,dy)
            val margin=max(3.0,14*scale)
            (1..12).map { i -> val t=i/13.0
                Sample((p.x+t*dx).roundToInt(),(p.y+t*dy).roundToInt(),
                    (margin*dy/len).roundToInt(),(-margin*dx/len).roundToInt()) }
        }
        val radius=(95*scale).roundToInt()
        val step=max(1,(2*scale).roundToInt())
        var best=0.0; var result: XY?=null
        for (dy in -radius..radius step step) for (dx in -radius..radius step step) {
            var weakest=100.0
            for (segment in segments) {
                var sum=0; var hits=0
                for (p in segment) {
                    val x=p.x+dx; val y=p.y+dy
                    if (x-abs(p.nx) !in 0 until w || x+abs(p.nx) !in 0 until w ||
                        y-abs(p.ny) !in 0 until h || y+abs(p.ny) !in 0 until h) continue
                    val outside=gray[(y+p.ny)*w+x+p.nx].toInt() and 255
                    val inside=gray[(y-p.ny)*w+x-p.nx].toInt() and 255
                    val contrast=s.sign*(outside-inside)
                    if (contrast>=12) hits++
                    sum+=contrast.coerceIn(0,80)
                }
                if (hits<8) { weakest=0.0; break }
                weakest=min(weakest,sum/12.0)
            }
            if (weakest>best && weakest>=18) {
                best=weakest
                result=XY((edge[1].x+edge[2].x)/2+dx,(edge[1].y+edge[2].y)/2+dy)
            }
        }
        return result
    }
}

