package com.example.arassemblydemo

internal object Math {
    fun toDegrees(angle:Double) = angle * (180.0 / kotlin.math.PI)
}
// Android's two-field detector record. Image decoding stays in the web worker.
internal data class Marker(val id:Int,val points:FloatArray)

private fun doubles(value:dynamic):List<Double> = (value as Array<Double>).toList()
private fun strings(value:dynamic):List<String> = (value as Array<String>).toList()
private fun objects(value:dynamic):List<dynamic> = (value as Array<dynamic>).toList()
private fun pix(value:BoardFaceRules.Point):dynamic {
    val q:dynamic=js("({})");q.x=value.x;q.y=value.y;return q
}
private fun projection(value:BoardFaceRules.Projection):dynamic {
    val r:dynamic=js("({})")
    r.center=pix(value.center);r.footprint=value.footprint.map(::pix).toTypedArray()
    r.axes=value.axes.map { a->val q:dynamic=js("({})");q.axis=a.axis;q.start=pix(a.start);q.end=pix(a.end);q }.toTypedArray()
    r.method=value.method;r.face=value.face;r.supportCount=value.supportCount
    r.localCenter=value.localCenter.toTypedArray();r.localHalfExtent=value.localHalfExtent.toTypedArray()
    r.planeToImage=value.planeToImage.toTypedArray()
    r.outlineLocal=value.outlineLocal.map(::pix).toTypedArray()
    r.holesLocal=value.holesLocal.map { ring->ring.map(::pix).toTypedArray() }.toTypedArray()
    return r
}

private lateinit var sites:List<BoardFaceRules.Site>
private lateinit var catalog:MeasuredBoardProjection.Catalog
private lateinit var tasks:Map<String,BoardLocalGuidance.Task>
private val history=FinalAssemblyGuidance.History()
private val stabilizer=BoardLocalGuidance.Stabilizer()
private lateinit var finder:FinderHighlight

private fun initialize(data:dynamic) {
    sites=objects(data.sites).map { BoardFaceRules.Site(it.part,it.slot,it.id,it.axis,doubles(it.candidateCenter)) }
    catalog=MeasuredBoardProjection.parse(JSON.stringify(data.measured),data.measured.assembly,sites)
    require(catalog.available) { catalog.reason }
    finder=FinderHighlight.load(JSON.stringify(data.finderCad))
    tasks=objects(data.tasks).map { BoardLocalGuidance.Task(it.id,it.kind,it.group,strings(it.context),strings(it.stationary),strings(it.members)) }.associateBy { it.id }
}
private fun reset(){ history.reset();stabilizer.reset() }
private fun findParts(input:dynamic):dynamic {
    val markers=objects(input.markers).map { tag->
        val points=objects(tag.corners).flatMap { p->listOf((p.x as Double).toFloat(),(p.y as Double).toFloat()) }
        Marker(tag.id,points.toFloatArray())
    }
    val regions=finder.update(input.token,(input.generation as Double).toLong(),strings(input.targets),sites,markers,catalog,(input.atMs as Double).toLong())
    return regions.map { region->
        val result:dynamic=js("({})")
        result.part=region.part;result.opacity=region.opacity
        result.rings=region.rings.map { ring->ring.map { p->arrayOf(p.x,p.y) }.toTypedArray() }.toTypedArray()
        result
    }.toTypedArray()
}
private fun run(input:dynamic):dynamic {
    val task=tasks.getValue(input.taskId as String)
    val detections=objects(input.markers).map { tag->
        BoardFaceRules.Detection(tag.id,objects(tag.corners).map { point->BoardFaceRules.Point(point.x,point.y) })
    }
    val slots=objects(input.slotQuads).map { q->objects(q).map { BoardFaceRules.Point(it.x,it.y) } }
    val streaming=input.mode=="stream"
    val at=(input.atMs as Double).toLong()
    val raw=BoardLocalGuidance.evaluate(sites,detections,task,catalog,input.width,input.height,if(streaming)history else null,at,slots)
    val update=if(streaming)stabilizer.update(raw,at) else null
    val value=update?.result?:raw
    val r:dynamic=js("({})")
    r.status=value.status;r.title=value.title;r.message=value.message
    r.titleEn=FinalGuideEnglish.text(value.title);r.messageEn=FinalGuideEnglish.text(value.message)
    r.rawStatus=raw.status;r.part=value.part;r.markerIds=value.markerIds.toTypedArray();r.angleDegrees=value.angleDegrees
    r.anchor=value.anchor?.let(::pix);r.axis=value.axis;r.targetLabel=value.targetLabel;r.flipAxis=value.flipAxis
    r.cueGeometry=value.cueGeometry?.let(::projection)
    r.observedRow=value.observedRow;r.targetRow=value.targetRow;r.notchRelation=value.notchRelation;r.installMotion=value.installMotion
    r.slotTargets=value.slotTargets.map(::pix).toTypedArray()
    r.inferredSlotTargets=value.inferredSlotTargets.map(::pix).toTypedArray()
    r.notchCue=value.notchCue.map(::pix).toTypedArray();r.backCue=value.backCue.map(::pix).toTypedArray()
    val planes:dynamic=js("({})");value.alignedPlanes.forEach { (part,plane)-> planes[part]=projection(plane) }
    r.alignedPlanes=planes
    value.placement.let { v->if(v==null)r.placement=null else {
        val q:dynamic=js("({})");q.deltaLocal=pix(v.deltaLocal);q.distanceMm=v.distanceMm;q.fixedPart=v.fixedPart;q.method=v.method;r.placement=q
    } }
    r.notify=update?.notify?:false
    return r
}
fun main(){
    val api:dynamic=js("({})")
    api.init=::initialize;api.reset=::reset;api.evaluate=::run;api.findParts=::findParts
    val global:dynamic=js("globalThis");global.shelfGeometryCore=api
}
