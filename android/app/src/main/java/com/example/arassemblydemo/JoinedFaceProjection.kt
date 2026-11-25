package com.example.arassemblydemo

import kotlin.math.hypot
import kotlin.math.max

/** Projects one half using its neighbour only when this frame's ordered corners also agree
 * with their registered, assembled positions. Input to pixel() is weak-face local millimetres. */
internal class JoinedFaceProjection private constructor(private val h:DoubleArray,
    private val centre:List<Double>,private val half:List<Double>,private val dx:Double,private val dy:Double) {
    fun pixel(p:BoardFaceRules.Point):BoardFaceRules.Point? =
        MeasuredBoardProjection.map(h,BoardFaceRules.Point((p.x+dx-centre[0])/half[0],(p.y+dy-centre[1])/half[1]))

    companion object {
        fun verified(strongFace:MeasuredBoardProjection.Face,strongPlane:BoardFaceRules.Projection,
                     weakFace:MeasuredBoardProjection.Face,weakTags:List<BoardFaceRules.Detection>):JoinedFaceProjection? {
            if(weakTags.size !in 2..4 || weakTags.distinctBy { it.id }.size!=weakTags.size ||
                strongFace.part==weakFace.part || strongFace.part.substringBeforeLast('-')!=weakFace.part.substringBeforeLast('-') ||
                strongFace.face!=weakFace.face || weakFace.axes!=strongFace.axes || weakFace.cadOrigin.size!=3 ||
                strongFace.cadOrigin.size!=3 || strongPlane.face!=strongFace.face || strongPlane.method!="measured_homography") return null
            val axes=strongFace.axes
            val transformer=JoinedFaceProjection(strongPlane.planeToImage.toDoubleArray(),
                strongPlane.localCenter,strongPlane.localHalfExtent,
                weakFace.cadOrigin[axes[0]]-strongFace.cadOrigin[axes[0]],
                weakFace.cadOrigin[axes[1]]-strongFace.cadOrigin[axes[1]])
            fun distance(a:BoardFaceRules.Point,b:BoardFaceRules.Point)=hypot(a.x-b.x,a.y-b.y)
            for(d in weakTags){
                val corners=weakFace.markers[d.id] ?: return null
                if(d.corners.size!=4 || d.corners.any { !it.x.isFinite() || !it.y.isFinite() }) return null
                val side=(0..3).map { distance(d.corners[it],d.corners[(it+1)%4]) }.average()
                if(side<10 || corners.indices.any { i->
                    transformer.pixel(corners[i])?.let { distance(it,d.corners[i])>max(2.0,side*.20) } != false
                })return null
            }
            return transformer
        }
    }
}
