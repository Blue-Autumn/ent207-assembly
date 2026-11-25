package com.example.arassemblydemo

import kotlin.math.*

private typealias Pixel = BoardFaceRules.Point
private typealias TagReading = BoardFaceRules.Detection
private typealias FrameFace = MeasuredBoardProjection.Face

/** A display-only address for a slot whose own half lacks a full plane. Lock the
 * part identities and registration before pooling measurements from the two halves. */
internal object FrameSlotGrid {
    private data class Sample(val id:Int,val face:FrameFace,val model:List<Pixel>,val image:List<Pixel>)
    private data class Slot(val part:String,val row:Int,val model:Pixel,val halfWidth:Double)
    private data class Shape(val center:Pixel,val length:Double,val unit:Pixel)
    private data class Match(val marks:Map<Slot,Shape>,val residual:Double)
    private fun dist(a:Pixel,b:Pixel)=hypot(a.x-b.x,a.y-b.y)
    private fun average(a:List<Pixel>)=Pixel(a.map { it.x }.average(),a.map { it.y }.average())
    private fun FrameFace.world(p:Pixel)=Pixel(p.x+cadOrigin[0],p.y+cadOrigin[1])
    private fun FrameFace.local(p:Pixel)=Pixel(p.x-cadOrigin[0],p.y-cadOrigin[1])
    private fun project(h:DoubleArray,p:Pixel)=MeasuredBoardProjection.map(h,Pixel(p.x/200,p.y/200))
    private fun fit(src:List<Pixel>,dst:List<Pixel>)=MeasuredBoardProjection.fit(src.map { Pixel(it.x/200,it.y/200) },dst)
    private fun cornersToShape(q:List<Pixel>):Shape? {
        if(q.size!=4 || q.any { !it.x.isFinite() || !it.y.isFinite() })return null
        val axis=(0..1).maxBy { dist(q[it],q[it+1]) }
        val a=q[axis];val b=q[axis+1];val length=dist(a,b)
        if(length<7)return null
        return Shape(average(q),length,Pixel((b.x-a.x)/length,(b.y-a.y)/length))
    }
    fun locate(catalog:MeasuredBoardProjection.Catalog, detections:List<TagReading>, row:Int,
               outlines:List<List<Pixel>>, width:Int,height:Int):Map<String,Pixel> {
        if(row !in 0..4 || detections.size !in 1..8 || width<1 || height<1)return emptyMap()
        val faces=listOfNotNull(catalog.faces["左侧板-1" to "A"],catalog.faces["左侧板-2" to "A"])
        if(faces.size!=2 || faces.any { it.cadOrigin.size!=3 || it.holes.size!=5 })return emptyMap()
        val samples=detections.mapNotNull { d->
            val f=faces.firstOrNull { d.id in it.markers }?:return@mapNotNull null
            Sample(d.id,f,f.markers.getValue(d.id).map { p->f.world(p) },d.corners)
        }
        if(samples.size!=detections.size || samples.map { it.id }.distinct().size!=samples.size)return emptyMap()
        val slots=faces.flatMap { f->f.holes.sortedBy { h->(h.minOf { it.y }+h.maxOf { it.y })/2 }.mapIndexed { n,h->
            val lo=h.minOf { it.x };val hi=h.maxOf { it.x }
            Slot(f.part,n,f.world(Pixel((lo+hi)/2,(h.minOf { it.y }+h.maxOf { it.y })/2)),(hi-lo)/2)
        }}
        val selected=slots.filter { it.row==row }
        if(samples.size>=3 && samples.distinctBy { it.face.part }.size==2){
            val common=pooled(samples,selected,slots,width,height)
            if(common.isNotEmpty() || outlines.isEmpty())return common
        }
        if(outlines.isEmpty() || outlines.size>96)return emptyMap()
        val marks=outlines.mapNotNull(::cornersToShape)
        val anchor=samples.maxByOrNull { s->s.image.indices.minOf { k->dist(s.image[k],s.image[(k+1)%4]) } }?:return emptyMap()
        return visiblePattern(anchor,samples,slots,marks,row)
    }
    private fun pooled(samples:List<Sample>, selected:List<Slot>, slots:List<Slot>,width:Int,height:Int):Map<String,Pixel> {
        fun matrix(items:List<Sample>)=fit(items.flatMap { it.model },items.flatMap { it.image })
        val h=matrix(samples)?:return emptyMap()
        val leaveOne=samples.map { omitted->
            val sub=matrix(samples.filter { it.id!=omitted.id })?:return emptyMap()
            val side=omitted.image.indices.minOf { i->dist(omitted.image[i],omitted.image[(i+1)%4]) }
            if(side<10 || omitted.model.indices.any { k->
                val p=project(sub,omitted.model[k])
                p==null || dist(p,omitted.image[k])>max(2.5,side*.23)
            })return emptyMap()
            sub
        }
        return selected.mapNotNull { slot->
            val p=project(h,slot.model)?:return@mapNotNull null
            if(p.x !in 0.0..width.toDouble() || p.y !in 0.0..height.toDouble())return@mapNotNull null
            val pitch=slots.filter { it.part==slot.part && abs(it.row-slot.row)==1 }.mapNotNull { project(h,it.model) }.minOfOrNull { dist(p,it) }?:return@mapNotNull null
            val end=project(h,Pixel(slot.model.x+slot.halfWidth,slot.model.y))?:return@mapNotNull null
            val limit=min(pitch*.21,dist(p,end)*.52)
            if(limit<2 || leaveOne.any { m->project(m,slot.model)?.let { dist(p,it)>limit }!=false })null else slot.part to p
        }.toMap()
    }
    private fun visiblePattern(anchor:Sample,samples:List<Sample>,slots:List<Slot>,shapes:List<Shape>,row:Int):Map<String,Pixel> {
        if(shapes.size<6)return emptyMap()
        val face=anchor.face
        val tag=InstallTagGeometry.create(face,TagReading(anchor.id,anchor.image))?:return emptyMap()
        val nearest=slots.filter { it.part==face.part }.minBy { dist(it.model,average(anchor.model)) }
        if(nearest.row!=0 && nearest.row!=4)return emptyMap()
        val adjacent=slots.single { it.part==face.part && it.row==if(nearest.row==0)1 else 3 }
        val across=slots.single { it.part!=face.part && it.row==nearest.row }
        fun tangent(s:Slot)=tag.tangent(face.local(s.model))
        val a=tangent(nearest);val b=tangent(across);val c=tangent(adjacent)
        val span=dist(a,b);val pitch=dist(a,c)
        if(span<65 || pitch<20)return emptyMap()
        fun options(center:Pixel,along:Double,normal:Double):List<Shape>{
            val ux=(b.x-a.x)/span;val uy=(b.y-a.y)/span
            return shapes.filter { q->
                val dx=q.center.x-center.x;val dy=q.center.y-center.y
                abs(dx*ux+dy*uy)<along && abs(dx*uy-dy*ux)<normal &&
                    abs(q.unit.x*ux+q.unit.y*uy)>.88 && q.length in tag.weight*.65..tag.weight*3.1
            }.sortedBy { dist(center,it.center) }.take(4)
        }
        val fits=mutableListOf<Match>()
        val aa=options(a,tag.weight*2.6,pitch*.30)
        val bb=options(b,span*.60,pitch*.48)
        val cc=options(c,tag.weight*3.1,pitch*.52)
        val tagSource=samples.flatMap { it.model }
        val tagImage=samples.flatMap { it.image }
        fun compatible(h:DoubleArray, tolerance:Double)=samples.all { s->
            s.model.indices.all { n->project(h,s.model[n])?.let { dist(s.image[n],it)<=max(3.0,tag.pixelSide*tolerance) }==true }
        }
        fun match(h:DoubleArray,tolerance:Double):Match {
            val marks=mutableMapOf<Slot,Shape>()
            var error=0.0
            for(slot in slots){
                val p=project(h,slot.model)?:continue
                val end=project(h,Pixel(slot.model.x+slot.halfWidth,slot.model.y))?:continue
                val w=dist(p,end)*2
                if(w<10)continue
                val shape=shapes.filter { q->
                    q.length in .60*w..1.35*w && abs((end.x-p.x)*q.unit.x+(end.y-p.y)*q.unit.y)>.45*w
                }.minByOrNull { dist(it.center,p) }?:continue
                val residual=dist(p,shape.center)/w
                if(residual<=tolerance && shape !in marks.values){marks[slot]=shape;error+=residual}
            }
            return Match(marks,if(marks.isEmpty())Double.POSITIVE_INFINITY else error/marks.size)
        }
        for(i in aa)for(j in bb)for(k in cc) {
            val h=fit(tagSource+listOf(nearest.model,across.model,adjacent.model),
                tagImage+listOf(i.center,j.center,k.center))?:continue
            if(!compatible(h,.24))continue
            val provisional=match(h,.65).marks
            if(provisional.size<5 || provisional[nearest]!=i || provisional[across]!=j)continue
            val refined=fit(tagSource+provisional.keys.map { it.model },tagImage+provisional.values.map { it.center })?:continue
            if(!compatible(refined,.20))continue
            val bestMatch=match(refined,.28)
            val marks=bestMatch.marks
            val paired=marks.keys.groupBy { it.row }.filterValues { it.size==2 }.keys
            val needed=if(nearest.row==0)setOf(0,1,2) else setOf(4,3,2)
            if(paired.containsAll(needed) && row in paired)fits+=bestMatch
        }
        val best=fits.sortedWith(compareByDescending<Match>{it.marks.size}.thenBy{it.residual}).firstOrNull()?:return emptyMap()
        val chosen=best.marks.filterKeys { it.row==row }
        if(fits.any { m->m.marks.size>=best.marks.size && m.residual<=best.residual+.06 &&
            chosen.any { (slot,s)->m.marks[slot]?.let { dist(it.center,s.center)>.35*s.length }!=false } })return emptyMap()
        return chosen.mapKeys { it.key.part }.mapValues { it.value.center }
    }
}
