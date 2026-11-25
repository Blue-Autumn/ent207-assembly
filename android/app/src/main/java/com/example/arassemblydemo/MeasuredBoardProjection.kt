package com.example.arassemblydemo

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Same-face photo-plane registration. Deliberately not a camera pose or seating estimator. */
internal object MeasuredBoardProjection {
    data class Face(val part:String,val face:String,val axes:List<Int>,val normal:Int,
        val markers:Map<Int,List<BoardFaceRules.Point>>,val outline:List<BoardFaceRules.Point>,
        val holes:List<List<BoardFaceRules.Point>>,val bounds:List<Double>,val cadOrigin:List<Double> = emptyList())
    class Catalog internal constructor(val faces:Map<Pair<String,String>,Face>,val reason:String) {
        val available:Boolean get()=faces.isNotEmpty()
        fun observe(part:String,sites:List<BoardFaceRules.Site>,detections:List<BoardFaceRules.Detection>):BoardFaceRules.Observation {
            val own=sites.filter { it.part==part }.associateBy { it.id }
            val seen=detections.filter { it.id in own }
            val ids=seen.map { it.id }.distinct().sorted()
            val side=ids.map { own.getValue(it).face }.distinct().singleOrNull()
            fun fail(reason:String)=BoardFaceRules.Observation(part,side,ids,reason)
            if(ids.isEmpty())return fail("no_tags")
            if(seen.size!=ids.size)return fail("duplicate_ids")
            if(side==null)return fail("both_faces_seen")
            val face=faces[part to side]?:return fail("measured_registration_unavailable")
            if(seen.size<2)return fail("face_only_more_tags_for_direction")
            if(seen.size>4 || seen.any { it.id !in face.markers })return fail("unexpected_marker")
            if(seen.any { !validQuad(it.corners) })return fail("invalid_corners")
            val b=face.bounds;val cu=(b[0]+b[2])/2;val cv=(b[1]+b[3])/2
            val hu=(b[2]-b[0])/2;val hv=(b[3]-b[1])/2
            fun local(p:BoardFaceRules.Point)=BoardFaceRules.Point((p.x-cu)/hu,(p.y-cv)/hv)
            val from=seen.flatMap { face.markers.getValue(it.id).map(::local) }
            val to=seen.flatMap { it.corners }
            val centers=seen.map { d->face.markers.getValue(d.id).map(::local).let { q->BoardFaceRules.Point(q.map { it.x }.average(),q.map { it.y }.average()) } }
            if(seen.size>=3 && triangleArea2(centers)<.08)return fail("collinear_tags")
            val h=fit(from,to)?:return fail("measured_unstable_transform")
            val projected=from.map { map(h,it)?:return fail("measured_projective_horizon") }
            val errors=projected.zip(to).map { (a,z)->distance(a,z) }
            val rms=sqrt(errors.sumOf { it*it }/errors.size)
            val tagSide=seen.flatMap { d->(0..3).map { k->distance(d.corners[k],d.corners[(k+1)%4]) } }.average()
            val tolerance=max(2.0,tagSide*.09)
            if(rms>tolerance || errors.max()>tolerance*3)return fail("measured_inconsistent_markers")
            // Eight ordered corners can constrain a plane even though two marker *centers*
            // cannot. Admit it only when extrapolating to the rest of this measured board
            // remains insensitive to plausible corner error; a successful solve alone is not enough.
            if(seen.size==2){
                val quality=twoTagQuality(from,to,h)
                if(!quality.accepted)return fail(quality.reason)
            }
            // Four visible markers permit a whole-marker holdout diagnostic. A counterfactual
            // omitted-marker extrapolation is not evidence that the *current full fit* is wrong.
            // Three-marker frames are fitted and gated anew; never inherit four-marker quality.
            var holdoutMax:Double?=null
            if(seen.size==4)for(held in seen.indices){
                val keep=from.indices.filter { it/4!=held }
                val sub=fit(keep.map { from[it] },keep.map { to[it] })
                val error=(held*4 until held*4+4).maxOf { k->map(sub,from[k])?.let { distance(it,to[k]) }?:Double.POSITIVE_INFINITY }
                holdoutMax=max(holdoutMax?:0.0,error)
            }
            val outline=face.outline.map(::local);val holes=face.holes.map { ring->ring.map(::local) }
            if((outline+holes.flatten()).any { map(h,it)==null })return fail("measured_projective_horizon")
            val center=map(h,BoardFaceRules.Point(0.0,0.0))?:return fail("measured_projective_horizon")
            val u0=map(h,BoardFaceRules.Point(-1.0,0.0))?:return fail("measured_projective_horizon")
            val u1=map(h,BoardFaceRules.Point(1.0,0.0))?:return fail("measured_projective_horizon")
            val v0=map(h,BoardFaceRules.Point(0.0,-1.0))?:return fail("measured_projective_horizon")
            val v1=map(h,BoardFaceRules.Point(0.0,1.0))?:return fail("measured_projective_horizon")
            val u=BoardFaceRules.Point((u1.x-u0.x)/(2*hu),(u1.y-u0.y)/(2*hu))
            val v=BoardFaceRules.Point((v1.x-v0.x)/(2*hv),(v1.y-v0.y)/(2*hv))
            val det=u.x*v.y-u.y*v.x;val norm=hypot(u.x,u.y)*hypot(v.x,v.y)
            val expected=(if(face.normal==1)1 else -1)*(if(side=="A")1 else -1)
            if(det*expected<=0)return fail("measured_layout_reflected")
            val extent=to.maxOf { a->to.maxOf { z->distance(a,z) } }
            if(extent<40 || norm<1e-10 || abs(det)/norm<.12)return fail("view_too_edge_on")
            val corners=listOf(-1.0 to -1.0,1.0 to -1.0,1.0 to 1.0,-1.0 to 1.0)
                .map { (x,y)->map(h,BoardFaceRules.Point(x,y))?:return fail("measured_projective_horizon") }
            val projection=BoardFaceRules.Projection(center,listOf(
                BoardFaceRules.ProjectedAxis(face.axes[0],u0,u1),BoardFaceRules.ProjectedAxis(face.axes[1],v0,v1)),
                corners,seen.size,"measured_homography",listOf(cu,cv),listOf(hu,hv),side,
                h.toList(),outline,holes)
            return BoardFaceRules.Observation(part,side,ids,if(seen.size==2)"measured_two_marker_plane" else "measured_plane",mapOf(face.axes[0] to u,face.axes[1] to v),rms,extent,projection,holdoutMax?.takeIf { it.isFinite() })
        }
    }
    fun unavailable(reason:String)=Catalog(emptyMap(),reason)
    fun parse(text:String,assembly:String,sites:List<BoardFaceRules.Site>):Catalog = runCatching {
        val root=JSONObject(text);require(root.getString("schema")=="measured-board-planes-1")
        require(root.getString("assembly")==assembly)
        require(root.getString("sourceSha256").matches(Regex("[0-9a-f]{64}")))
        fun JSONArray.rows()=(0 until length()).map { getJSONObject(it) }
        fun identity(x:JSONObject)=Triple(x.getString("part"),x.getInt("slot"),x.getInt("id"))
        val registration=root.getJSONArray("registration").rows().map(::identity)
        require(sites.isNotEmpty() && registration.size==sites.size && registration.distinct().size==registration.size)
        require(registration.toSet()==sites.map { Triple(it.part,it.slot,it.id) }.toSet())
        val own=sites.associateBy { it.id };require(own.size==sites.size)
        val entries=root.getJSONArray("faces").rows()
        val faces=entries.map { row->
            val part=row.getString("part");val side=row.getString("face");require(side in setOf("A","B"))
            val normal=row.getInt("normalAxis");require(normal in 0..2)
            val axes=row.getJSONArray("axisIndices").let { a->require(a.length()==2);(0..1).map { a.getInt(it) } }
            require(axes==(0..2).filter { it!=normal })
            val markerRows=row.getJSONArray("markers").rows();require(markerRows.size==4)
            val markers=markerRows.associate { m->
                val id=m.getInt("id");val s=own[id]?:error("unknown marker")
                require(s.part==part && s.face==side && s.slot==m.getInt("slot") && s.axis==normal)
                id to points(m.getJSONArray("corners"),4,4)
            };require(markers.size==4)
            val outline=points(row.getJSONArray("outline"),3,4096)
            val holes=row.getJSONArray("holes").let { a->require(a.length()<=64);(0 until a.length()).map { points(a.getJSONArray(it),3,4096) } }
            val bounds=row.getJSONArray("bounds").let { a->require(a.length()==4);(0..3).map { a.getDouble(it) } }
            require(bounds.all { it.isFinite() } && bounds[2]-bounds[0]>1 && bounds[3]-bounds[1]>1)
            require((outline+holes.flatten()+markers.values.flatten()).all { p->p.x>=bounds[0]-2 && p.x<=bounds[2]+2 && p.y>=bounds[1]-2 && p.y<=bounds[3]+2 })
            // The source measurement frame has already verified positive CAD in-plane axes.
            // Optional for old assets: without this origin we can draw the measured face, but
            // must not infer a target placement relative to another independently measured face.
            val origin=row.optJSONArray("cadOrigin")?.let { a->
                require(a.length()==3);(0..2).map { a.getDouble(it) }.also { require(it.all(Double::isFinite)) }
            }?:emptyList()
            Face(part,side,axes,normal,markers,outline,holes,bounds,origin)
        }
        require(faces.size==sites.map { it.part to it.face }.distinct().size)
        val keyed=faces.associateBy { it.part to it.face };require(keyed.size==faces.size)
        Catalog(keyed,"measured_photo_candidate")
    }.getOrElse { unavailable("measured_asset_or_registration_mismatch") }

    private fun points(a:JSONArray,min:Int,max:Int):List<BoardFaceRules.Point>{
        require(a.length() in min..max)
        return (0 until a.length()).map { i->val p=a.getJSONArray(i);require(p.length()==2)
            BoardFaceRules.Point(p.getDouble(0),p.getDouble(1)).also { require(it.x.isFinite()&&it.y.isFinite()) } }
    }
    private fun cross(a:BoardFaceRules.Point,b:BoardFaceRules.Point,c:BoardFaceRules.Point)=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)
    private fun distance(a:BoardFaceRules.Point,b:BoardFaceRules.Point)=hypot(a.x-b.x,a.y-b.y)
    private fun validQuad(p:List<BoardFaceRules.Point>)=p.size==4 && p.all { it.x.isFinite()&&it.y.isFinite() } &&
        (0..3).all { cross(p[it],p[(it+1)%4],p[(it+2)%4])>1e-5 && distance(p[it],p[(it+1)%4])>=4.0 }
    private fun triangleArea2(p:List<BoardFaceRules.Point>)=p.maxOf { a->p.maxOf { b->p.maxOf { c->abs(cross(a,b,c)) } } }

    /** Engineering sensitivity bound, not a calibrated probability or millimetre accuracy claim. */
    internal data class TwoTagQuality(val reason:String,val relativeUncertainty:Double?=null,
        val axisUncertaintyDegrees:Double?=null,val spreadRatio:Double?=null,
        val quarterPixelSensitivityPx:Double?=null,val assumedCornerNoisePx:Double?=null,val fittedRmsPx:Double?=null) {
        val accepted:Boolean get()=reason=="two_marker_conditioned"
    }
    private val planeProbes=listOf(-1.0,0.0,1.0).flatMap { y->listOf(-1.0,0.0,1.0).map { x->BoardFaceRules.Point(x,y) } }

    /** Only used for exactly two canonical marker quads. Three/four-marker acceptance is unchanged. */
    internal fun twoTagQuality(from:List<BoardFaceRules.Point>,to:List<BoardFaceRules.Point>,h:DoubleArray):TwoTagQuality {
        if(from.size!=8 || to.size!=8)return TwoTagQuality("measured_two_marker_bad_support")
        val edges=(0..1).flatMap { tag->(0..3).map { k->distance(to[tag*4+k],to[tag*4+(k+1)%4]) } }
        // At very small image scales a subpixel uncertainty model is not credible.
        if(edges.min()<7.0)return TwoTagQuality("measured_two_marker_too_small")
        fun center(p:List<BoardFaceRules.Point>)=BoardFaceRules.Point(p.map { it.x }.average(),p.map { it.y }.average())
        val localSeparation=distance(center(from.take(4)),center(from.drop(4)))
        val imageSeparation=distance(center(to.take(4)),center(to.drop(4)))
        val meanSide=edges.average()
        val base=planeProbes.map { map(h,it)?:return TwoTagQuality("measured_projective_horizon") }
        val span=base.maxOf { a->base.maxOf { b->distance(a,b) } }
        val spread=imageSeparation/span
        // Both small clusters, a collapsed pair, or excessive extrapolation remain identity-only.
        if(localSeparation<.8 || imageSeparation<meanSide*4 || spread<.32)
            return TwoTagQuality("measured_two_marker_poor_spread",spreadRatio=spread)
        val variance=DoubleArray(planeProbes.size);val angleVariance=DoubleArray(2)
        fun headings(p:List<BoardFaceRules.Point>)=doubleArrayOf(
            atan2(p[5].y-p[3].y,p[5].x-p[3].x),atan2(p[7].y-p[1].y,p[7].x-p[1].x))
        // Numerical Jacobian: move each image coordinate +/- 0.25 pixel, independently.
        // The probes include unseen board corners and both full axes, so low residual at the
        // two visible clusters cannot hide a poorly constrained far edge.
        for(i in to.indices)for(axis in 0..1){
            fun sample(delta:Double):List<BoardFaceRules.Point>? {
                val changed=to.toMutableList();val p=to[i]
                changed[i]=if(axis==0)BoardFaceRules.Point(p.x+delta,p.y) else BoardFaceRules.Point(p.x,p.y+delta)
                val perturbed=fit(from,changed)?:return null
                return planeProbes.map { map(perturbed,it)?:return null }
            }
            val plus=sample(.25)?:return TwoTagQuality("measured_two_marker_unstable")
            val minus=sample(-.25)?:return TwoTagQuality("measured_two_marker_unstable")
            for(k in variance.indices)variance[k]+=((plus[k].x-minus[k].x)/2).pow(2)+((plus[k].y-minus[k].y)/2).pow(2)
            val pa=headings(plus);val ma=headings(minus)
            for(k in 0..1){val delta=atan2(sin(pa[k]-ma[k]),cos(pa[k]-ma[k]))/2;angleVariance[k]+=delta*delta}
        }
        val sensitivity=sqrt(variance.max())
        // The relative floor also represents source registration/imaging deviations, which do
        // not vanish when a high-resolution photograph is rescaled. It is deliberately larger
        // than numerical corner refinement alone. Neither this nor the result is a confidence CI.
        val fitErrors=from.indices.map { i->val p=map(h,from[i])?:return TwoTagQuality("measured_projective_horizon");distance(p,to[i]) }
        val rms=sqrt(fitErrors.sumOf { it*it }/fitErrors.size)
        // Eight 2D corners minus eight fitted parameters leave eight scalar residual degrees
        // of freedom: this point-RMS is also the estimated per-coordinate residual noise.
        val noise=maxOf(.25,meanSide*.015,rms)
        val relative=sensitivity*(noise/.25)/span
        val angle=Math.toDegrees(sqrt(angleVariance.max()))*(noise/.25)
        val reason=if(relative.isFinite() && angle.isFinite() && relative<=.02 && angle<=6.0)
            "two_marker_conditioned" else "measured_two_marker_unstable"
        return TwoTagQuality(reason,relative,angle,spread,sensitivity,noise,rms)
    }
    fun map(h:DoubleArray?,p:BoardFaceRules.Point):BoardFaceRules.Point? {
        if(h==null || h.size!=9)return null
        val d=h[6]*p.x+h[7]*p.y+h[8]
        if(!d.isFinite() || d<=.05)return null
        return BoardFaceRules.Point((h[0]*p.x+h[1]*p.y+h[2])/d,(h[3]*p.x+h[4]*p.y+h[5])/d)
            .takeIf { it.x.isFinite() && it.y.isFinite() }
    }
    /** Normalized eight-parameter least squares using Householder QR (same method as photo joints). */
    internal fun fit(from:List<BoardFaceRules.Point>,to:List<BoardFaceRules.Point>):DoubleArray? {
        if(from.size!=to.size || from.size<4)return null
        val cx=to.map { it.x }.average();val cy=to.map { it.y }.average()
        val variance=to.sumOf { (it.x-cx).pow(2)+(it.y-cy).pow(2) }/to.size
        if(!variance.isFinite() || variance<1e-8)return null
        val scale=sqrt(2/variance)
        val a=Array(from.size*2){DoubleArray(8)};val b=DoubleArray(a.size)
        for(i in from.indices){val p=from[i];val x=(to[i].x-cx)*scale;val y=(to[i].y-cy)*scale
            a[2*i]=doubleArrayOf(p.x,p.y,1.0,0.0,0.0,0.0,-x*p.x,-x*p.y)
            a[2*i+1]=doubleArrayOf(0.0,0.0,0.0,p.x,p.y,1.0,-y*p.x,-y*p.y);b[2*i]=x;b[2*i+1]=y
        }
        val diagonal=DoubleArray(8)
        for(k in 0..7){var norm=0.0;for(i in k until a.size)norm=hypot(norm,a[i][k])
            if(!norm.isFinite()||norm<1e-8)return null
            if(a[k][k]<0)norm=-norm
            for(i in k until a.size)a[i][k]/=norm
            a[k][k]+=1
            for(j in k+1..7){var s=0.0;for(i in k until a.size)s+=a[i][k]*a[i][j]
                s=-s/a[k][k];for(i in k until a.size)a[i][j]+=s*a[i][k] }
            var s=0.0;for(i in k until a.size)s+=a[i][k]*b[i]
            s=-s/a[k][k];for(i in k until a.size)b[i]+=s*a[i][k]
            diagonal[k]=-norm
        }
        if(diagonal.maxOf { abs(it) }/diagonal.minOf { abs(it) }>1e7)return null
        val h=b.copyOf(8)
        for(k in 7 downTo 0){h[k]/=diagonal[k];for(i in 0 until k)h[i]-=h[k]*a[i][k]}
        return doubleArrayOf(h[0]/scale+cx*h[6],h[1]/scale+cx*h[7],h[2]/scale+cx,
            h[3]/scale+cy*h[6],h[4]/scale+cy*h[7],h[5]/scale+cy,h[6],h[7],1.0).takeIf { it.all(Double::isFinite) }
    }
}
