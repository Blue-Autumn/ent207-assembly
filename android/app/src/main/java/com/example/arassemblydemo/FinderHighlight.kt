package com.example.arassemblydemo

import org.json.JSONObject
import kotlin.math.*

private typealias ImagePoint = BoardFaceRules.Point
private typealias Rings = List<List<ImagePoint>>

/** Presentation only. Regions and short-lived poses never enter the direction/acceptance rules. */
internal class FinderHighlight(private val shapes: Map<Pair<String,String>, Rings>) {
    data class Region(val part:String, val rings:Rings, val opacity:Float=1f)
    private data class Reference(val time:Long, val face:String, val region:Region,
                                 val anchors:Map<Int,List<ImagePoint>>, var lastVisual:Region=region, var lastSeen:Long=time)
    private val references = mutableMapOf<String,Reference>()
    private var token = ""
    private var generation = -1L

    fun update(request:String, cameraGeneration:Long, targets:List<String>, sites:List<BoardFaceRules.Site>,
               markers:List<Marker>, catalog:MeasuredBoardProjection.Catalog, now:Long):List<Region> {
        if (request != token || generation != cameraGeneration) {
            references.clear()
            token = request
            generation = cameraGeneration
        }
        val detections = markers.filter { it.points.size==8 && it.points.all(Float::isFinite) }.map { m ->
            BoardFaceRules.Detection(m.id, (0..3).map { i -> ImagePoint(m.points[2*i].toDouble(),m.points[2*i+1].toDouble()) })
        }
        val observed = targets.associateWith { catalog.observe(it, sites, detections) }
        return targets.mapNotNull { part ->
            val obs = observed.getValue(part)
            val current = detections.filter { it.id in obs.ids }
            val side = obs.face
            val key = part to (side ?: "")
            val direct = obs.projection?.let { project(part, it) }
            val confirmed = direct ?: if(side != null && current.size==2 && targets.size==2) {
                val other = observed.entries.firstOrNull { (name,o) ->
                    name != part && name.substringBeforeLast('-') == part.substringBeforeLast('-') &&
                        o.face == side && o.projection != null }
                other?.let { jointRegion(key,current,it.value,catalog) }
            } else null
            if(confirmed != null && side != null) {
                references[part] = Reference(now,side,confirmed,current.associate { it.id to it.corners })
                confirmed
            } else {
                val ref = references[part] ?: return@mapNotNull null
                val continuation = when {
                    now-ref.time !in 0..650 || obs.ids.distinct().size != current.size -> null
                    current.isEmpty() -> if(now-ref.lastSeen <= 220)
                        ref.lastVisual.copy(opacity=(ref.lastVisual.opacity * .58*(1-(now-ref.lastSeen)/240.0)).toFloat()) else null
                    side != ref.face || current.size > 2 -> null
                    else -> follow(ref,current,now)
                }
                // A contradictory observation invalidates the old lock; a lost face can only
                // return after a new solve. Predicted frames never renew the reference time.
                if(current.isNotEmpty() && continuation!=null){ref.lastVisual=continuation;ref.lastSeen=now}
                if(continuation==null && (current.isNotEmpty() || now-ref.time>650)) references.remove(part)
                continuation
            }
        }
    }

    /** Show a stable orientation on the halves that can be located in this camera frame. */
    fun alignedPair(result: BoardLocalGuidance.Result): List<Region> {
        if (result.status != "direction_ok" || result.placement != null) return emptyList()
        return result.alignedPlanes.mapNotNull { (part, plane) -> project(part, plane) }
    }

    private fun project(part: String, plane: BoardFaceRules.Projection): Region? {
        if (plane.method != "measured_homography" || plane.planeToImage.size != 9) return null
        val rings = shapes[part to (plane.face ?: return null)] ?: return null
        val h = plane.planeToImage.toDoubleArray()
        return mapped(rings) { MeasuredBoardProjection.map(h, it) }?.let { Region(part, it) }
    }

    private fun jointRegion(key:Pair<String,String>, current:List<BoardFaceRules.Detection>,
                            strong:BoardFaceRules.Observation, catalog:MeasuredBoardProjection.Catalog):Region? {
        val target=catalog.faces[key] ?: return null
        val anchor=catalog.faces[strong.part to strong.face] ?: return null
        val projection=JoinedFaceProjection.verified(anchor,strong.projection ?: return null,target,current) ?: return null
        val b=target.bounds
        val rings=shapes[key] ?: return null
        return mapped(rings) { p -> projection.pixel(ImagePoint(
            (b[0]+b[2]+p.x*(b[2]-b[0]))*.5,(b[1]+b[3]+p.y*(b[3]-b[1]))*.5)) }?.let { Region(key.first,it) }
    }

    /** Apply only small camera-plane similarity corrections measured from the same ordered
     * corners. A single square can update a recent good fit while a free single-tag homography
     * extrapolated to the opposite edge is too sensitive to tilt and corner noise. */
    private fun follow(ref:Reference,current:List<BoardFaceRules.Detection>,now:Long):Region? {
        val before=current.flatMap { ref.anchors[it.id] ?: return null }
        val after=current.flatMap { it.corners }
        fun centre(p:List<ImagePoint>)=ImagePoint(p.sumOf { it.x }/p.size,p.sumOf { it.y }/p.size)
        val a=centre(before);val b=centre(after)
        val radius=ref.region.rings.first().maxOf { dist(it,a) }
        if(dist(a,b)>max(10.0,radius*.12)) return null
        var gram=0.0;var dot=0.0;var cross=0.0
        for(i in before.indices){
            val x=before[i].x-a.x;val y=before[i].y-a.y
            val u=after[i].x-b.x;val v=after[i].y-b.y
            gram+=x*x+y*y;dot+=x*u+y*v;cross+=x*v-y*u
        }
        if(gram<=0)return null
        val c=dot/gram;val s=cross/gram
        if(hypot(c,s) !in .92..1.08 || abs(atan2(s,c))>.13) return null
        fun warp(p:ImagePoint)=ImagePoint(b.x+c*(p.x-a.x)-s*(p.y-a.y),b.y+s*(p.x-a.x)+c*(p.y-a.y))
        val tagSize=current.minOf { d -> (0..3).minOf { dist(d.corners[it],d.corners[(it+1)%4]) } }
        if(tagSize<11 || before.indices.any { dist(warp(before[it]),after[it])>min(3.4,max(.9,tagSize*.045)) })return null
        val alpha=(.83-.45*(now-ref.time)/650.0).toFloat()
        return ref.region.copy(rings=ref.region.rings.map { ring->ring.map(::warp) },opacity=alpha)
    }

    companion object {
        fun load(json:String):FinderHighlight {
            val source=JSONObject(json);require(source.getInt("schema")==1)
            val faces=source.getJSONArray("faces")
            return FinderHighlight((0 until faces.length()).associate { i->
                val row=faces.getJSONObject(i);val rings=row.getJSONArray("rings")
                (row.getString("part") to row.getString("face")) to (0 until rings.length()).map { j->
                    val ring=rings.getJSONArray(j)
                    (0 until ring.length()).map { k->val p=ring.getJSONArray(k);ImagePoint(p.getDouble(0),p.getDouble(1)) }
                }
            })
        }
        private fun dist(a:ImagePoint,b:ImagePoint)=hypot(a.x-b.x,a.y-b.y)
        private fun mapped(rings:Rings, transform:(ImagePoint)->ImagePoint?):Rings? =
            rings.map { ring->ring.map { p->transform(p)?:return null } }
    }
}
