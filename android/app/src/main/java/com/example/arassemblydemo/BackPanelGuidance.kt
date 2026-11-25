package com.example.arassemblydemo

import kotlin.math.*

private typealias BackPoint = BoardFaceRules.Point
private typealias BackFace = MeasuredBoardProjection.Face

/** The open end of the rack lies on CAD -X. Both frame faces have the same X/Y axes;
 * the back has Y/Z axes. The user may turn the entire rack to access that opening. */
internal object BackPanelGuidance {
    private data class Reading(val face:BackFace,val tag:InstallTagGeometry) {
        val globalX get()=face.cadOrigin[0]+tag.local.x
        val globalY get()=face.cadOrigin[1]+tag.local.y
    }
    private data class Interface(val onBack:BackPoint,val onFrame:BackPoint,val extent:Double)
    private fun length(p:BackPoint)=hypot(p.x,p.y)
    private fun delta(to:BackPoint,from:BackPoint)=BackPoint(to.x-from.x,to.y-from.y)
    private fun unit(p:BackPoint)=length(p).takeIf { it>1e-5 }?.let { BackPoint(p.x/it,p.y/it) }
    private fun cosine(a:BackPoint,b:BackPoint)=(a.x*b.x+a.y*b.y)/(length(a)*length(b))
    private fun mean(points:List<BackPoint>)=BackPoint(points.map { it.x }.average(),points.map { it.y }.average())
    private fun sign(values:List<Double>,threshold:Double):Int=when {
        values.isEmpty()->0
        values.all { it>threshold }->1
        values.all { it< -threshold }->-1
        else->0
    }
    private fun project(o:BoardFaceRules.Observation,f:BackFace,u:Double,v:Double):BackPoint? {
        val p=o.projection ?:return null
        if(p.method!="measured_homography" || u !in f.bounds[0]-10..f.bounds[2]+10 || v !in f.bounds[1]-10..f.bounds[3]+10)return null
        return MeasuredBoardProjection.map(p.planeToImage.toDoubleArray(),BackPoint(
            (u-p.localCenter[0])/p.localHalfExtent[0],(v-p.localCenter[1])/p.localHalfExtent[1]))
    }

    fun evaluate(catalog:MeasuredBoardProjection.Catalog?,seen:List<BoardFaceRules.Detection>,
        sites:Map<Int,BoardFaceRules.Site>,refs:List<BoardFaceRules.Observation>,moving:List<BoardFaceRules.Observation>,
        fits:List<Pair<Double,Double>>,width:Int,height:Int):BoardLocalGuidance.Result {
        val ids=seen.map { it.id }
        fun reply(state:String,title:String,message:String,motion:String?=null,points:List<BackPoint> = emptyList())=
            BoardLocalGuidance.Result(state,title,message,markerIds=ids,installMotion=motion,backCue=points)
        if(moving.isEmpty())return reply("install_show_target","拿起背板，对着架子开口","把架子整体转到背面朝你，让上框和手里的背板入镜。")
        if(refs.isEmpty() || catalog==null || width<1 || height<1)
            return reply("install_show_base","拍到上框和背板","对准上方灰框和手里的背板；能看到下框一角更好。")
        if(moving.any { it.face==null } || moving.map { it.face }.distinct().size>1)
            return reply("install_check","先看看背板的两半","扶稳背板，让两半同一侧的标签一起入镜。")
        val readings=seen.mapNotNull { d->
            val site=sites[d.id] ?:return@mapNotNull null
            val face=catalog.faces[site.part to site.face] ?:return@mapNotNull null
            InstallTagGeometry.create(face,d,allowOblique=true)?.let { Reading(face,it) }
        }
        val refParts=refs.map { it.part }.toSet()
        val frames=readings.filter { r -> r.face.part in refParts && r.face.face=="A" }
        val backs=readings.filter { it.face.part.startsWith("背板-") }
        val caps=frames.filter { it.face.part.startsWith("右侧板-") }
        val bases=frames.filter { it.face.part.startsWith("左侧板-") }
        // Corresponding top/bottom tags close in CAD X/Y give the physical upright vector in
        // the current image, even when only the near side rail of the bottom frame remains visible.
        val up=caps.flatMap { cap->bases.mapNotNull { base->
            val v=delta(cap.tag.center,base.tag.center)
            if(hypot(base.globalX-cap.globalX,base.globalY-cap.globalY)<=24 &&
                length(v)>max(50.0,3.5*max(cap.tag.pixelSide,base.tag.pixelSide))) v else null
        }}
        val projectedY=frames.map { it.tag.v }
        val backY=backs.map { it.tag.u }
        val backZ=backs.map { it.tag.v }
        val transverse=up.isNotEmpty() && projectedY.all { y->up.all { z->abs(cosine(y,z))<.66 } }
        val sparseY=sign(projectedY.flatMap { y->backY.map { cosine(y,it) } },.81)
        val sparseZ=if(transverse) sign(up.flatMap { z->backZ.map { cosine(z,it) } },.81) else 0
        fun vote(values:List<Double>,local:Int):Int {
            if(values.isEmpty())return local
            // A held board can approach obliquely. An entire family of poses in the opposite
            // hemisphere warrants a correction sooner than a positive alignment verdict.
            val full=if(values.all { it < -.65 }) -1 else sign(values,.80)
            return if(local==0 || local==full) full else 0
        }
        val ySign=vote(fits.map { it.first },sparseY)
        val zSign=vote(fits.map { it.second },sparseZ)
        if(ySign==0 || zSign==0)return reply("install_check","露出上框和背板的两个角",
            if(caps.isNotEmpty()) "稍微斜拍，带上下框靠近你的一个角。" else "从架子背面斜拍，让上框的两个角入镜。")

        fun axis(directions:List<BackPoint>,radial:Boolean=false):List<BackPoint> {
            if(backs.isEmpty() || directions.isEmpty())return emptyList()
            val center=mean(backs.map { it.tag.center })
            val dir=unit(mean(directions.mapNotNull(::unit))) ?:return emptyList()
            val radius=min(width,height)*.10
            fun at(fraction:Double)=BackPoint(center.x+dir.x*radius*fraction,center.y+dir.y*radius*fraction)
            return if(radial)listOf(center,at(1.0)) else listOf(at(-1.0),at(1.0))
        }
        if(ySign<0 && zSign<0)return reply("install_turn","把背板掉个头","先离开接头，保持这一面朝你，在板面内转半圈。",
            "turn_back_in_plane",axis(backY,true))
        if(zSign<0)return reply("install_turn","背板上下倒了","先离开接头，沿横轴翻半圈，把上端换到底下。",
            "turn_over_end",axis(backY))
        if(ySign<0)return reply("install_turn","把背板左右翻过来","保持上端朝上，沿竖轴翻半圈，让另一面朝你。",
            "turn_back_ends",axis(backZ))

        // Compare the SAME CAD point on both surfaces. Choose a visible rail near the opening
        // and the corresponding edge of a measured back half. This is an image alignment hint.
        val matches=refs.flatMap { ref->
            val rail=catalog.faces[ref.part to "A"] ?:return@flatMap emptyList()
            val z=rail.cadOrigin[2] // At the top this extends the back to the outer reference plane by one frame thickness.
            moving.mapNotNull { o->
                val face=catalog.faces[o.part to o.face] ?:return@mapNotNull null
                val y=(face.bounds[0]+face.bounds[2])/2
                val onBack=project(o,face,y,z-face.cadOrigin[2]) ?:return@mapNotNull null
                val onFrame=project(ref,rail,face.cadOrigin[0]-rail.cadOrigin[0],y+face.cadOrigin[1]-rail.cadOrigin[1]) ?:return@mapNotNull null
                val extent=ref.extentPx ?:return@mapNotNull null
                if(extent<40 || listOf(onFrame,onBack).any { it.x !in 0.0..width.toDouble() || it.y !in 0.0..height.toDouble() })return@mapNotNull null
                Interface(onBack,onFrame,extent)
            }
        }
        val worst=matches.maxByOrNull { length(delta(it.onFrame,it.onBack))/it.extent }
        if(worst!=null && length(delta(worst.onFrame,worst.onBack))<worst.extent*.075)
            return reply("install_oriented","背板方向和接头对上了","沿开口直推。检查上下两边是否贴齐，再点下一步。")
        if(worst!=null)return reply("install_align","方向对了，移到架子背面","让背板靠近亮起的接头，再对准直推。","move_back_to_frame",
            listOf(worst.onBack,worst.onFrame))
        return reply("install_align","背板方向对了","保持这个方向，把上下两边对到架子背面的接头，再直推。")
    }
}
