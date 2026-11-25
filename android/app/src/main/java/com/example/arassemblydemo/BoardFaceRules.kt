package com.example.arassemblydemo

import kotlin.math.*

/** Author-declared faces plus coarse axes from several ID centers. No tag bitmap angle is used. */
internal object BoardFaceRules {
    data class Point(val x:Double,val y:Double)
    data class Site(val part:String,val slot:Int,val id:Int,val axis:Int,val center:List<Double>) {
        val face:String get()=if(slot<4)"A" else "B"
    }
    data class Detection(val id:Int,val corners:List<Point>)
    data class ProjectedAxis(val axis:Int,val start:Point,val end:Point)
    /** Pixel-space cue support, not a measured CAD outline or calibrated pose. */
    data class Projection(val center:Point,val axes:List<ProjectedAxis>,val footprint:List<Point>,
        val supportCount:Int,val method:String="nominal_affine",val localCenter:List<Double> = emptyList(),
        val localHalfExtent:List<Double> = emptyList(),val face:String?=null,
        val planeToImage:List<Double> = emptyList(),val outlineLocal:List<Point> = emptyList(),
        val holesLocal:List<List<Point>> = emptyList())
    data class Observation(val part:String,val face:String?,val ids:List<Int>,val reason:String,
        val axes:Map<Int,Point> = emptyMap(), val rmsPx:Double?=null, val extentPx:Double?=null,
        val projection:Projection?=null,val holdoutMaxPx:Double?=null) {
        fun heading(axis:Int):Double?=axes[axis]?.let { Math.toDegrees(atan2(it.y,it.x)) }
    }
    data class PairResult(val status:String,val movingPart:String?,val angleDegrees:Double?,val message:String)

    fun observe(part:String,sites:List<Site>,detections:List<Detection>):Observation {
        val own=sites.filter { it.part==part }.associateBy { it.id }
        val seen=detections.filter { it.id in own }
        val ids=seen.map { it.id }.distinct().sorted()
        if(ids.isEmpty())return Observation(part,null,ids,"no_tags")
        if(seen.size!=ids.size)return Observation(part,null,ids,"duplicate_ids")
        if(seen.any { it.corners.size!=4 || it.corners.any { p->!p.x.isFinite()||!p.y.isFinite() } })return Observation(part,null,ids,"invalid_corners")
        val faces=ids.map { own.getValue(it).face }.distinct()
        if(faces.size!=1)return Observation(part,null,ids,"both_faces_seen")
        val face=faces.single()
        if(ids.size<3)return Observation(part,face,ids,"face_only_more_tags_for_direction")
        val normal=own.getValue(ids.first()).axis
        if(normal !in 0..2 || own.values.any { it.center.size!=3 || it.center.any { value->!value.isFinite() } })
            return Observation(part,face,ids,"invalid_model_coordinates")
        val uv=(0..2).filter { it!=normal }
        if(ids.any { own.getValue(it).axis!=normal })return Observation(part,face,ids,"inconsistent_model_axes")
        val model=seen.map { d->own.getValue(d.id).center.let { Point(it[uv[0]],it[uv[1]]) } }
        val image=seen.map { d->Point(d.corners.sumOf { it.x }/4,d.corners.sumOf { it.y }/4) }
        val mx=model.map { it.x }.average();val my=model.map { it.y }.average()
        val scale=max(model.maxOf { abs(it.x-mx) },model.maxOf { abs(it.y-my) })
        if(scale<1e-6)return Observation(part,face,ids,"model_points_degenerate")
        // Three corners are sufficient. A narrow triangle amplifies the 1-2 mm placement uncertainty,
        // so do not infer a plane from three nearly aligned sites even if the solve is nonsingular.
        val largestTriangle=model.indices.maxOf { i->model.indices.maxOf { j->model.indices.maxOf { k->
            abs((model[j].x-model[i].x)*(model[k].y-model[i].y)-(model[j].y-model[i].y)*(model[k].x-model[i].x))
        } } }
        if(largestTriangle<scale*scale*.08)return Observation(part,face,ids,"collinear_tags")
        val inputs=model.map { doubleArrayOf((it.x-mx)/scale,(it.y-my)/scale,1.0) }
        val gram=Array(3){r->DoubleArray(3){c->inputs.sumOf { it[r]*it[c] }}}
        val ax=solve(gram,DoubleArray(3){k->inputs.indices.sumOf { inputs[it][k]*image[it].x }})
        val ay=solve(gram,DoubleArray(3){k->inputs.indices.sumOf { inputs[it][k]*image[it].y }})
        if(ax==null||ay==null)return Observation(part,face,ids,"collinear_tags")
        val u=Point(ax[0]/scale,ay[0]/scale);val v=Point(ax[1]/scale,ay[1]/scale)
        val lengths=hypot(u.x,u.y)*hypot(v.x,v.y)
        val determinant=u.x*v.y-u.y*v.x
        val extent=image.maxOf { a->image.maxOf { b->hypot(a.x-b.x,a.y-b.y) } }
        if(extent<40||lengths<1e-10||abs(determinant)/lengths<0.18)return Observation(part,face,ids,"view_too_edge_on")
        // Image y points down. The XZ basis reverses parity relative to the other model planes.
        val expected=(if(normal==1)1 else -1)*(if(face=="A")1 else -1)
        if(determinant*expected<=0)return Observation(part,face,ids,"corner_layout_inconsistent")
        val rms=sqrt(inputs.indices.sumOf { i->val p=inputs[i]
            val ex=(0..2).sumOf { ax[it]*p[it] }-image[i].x
            val ey=(0..2).sumOf { ay[it]*p[it] }-image[i].y;ex*ex+ey*ey }/inputs.size)
        if(rms>extent*0.08)return Observation(part,face,ids,"perspective_or_layout_uncertain",rmsPx=rms,extentPx=extent)
        fun project(x:Double,y:Double)=Point(ax[0]*(x-mx)/scale+ax[1]*(y-my)/scale+ax[2],
            ay[0]*(x-mx)/scale+ay[1]*(y-my)/scale+ay[2])
        // Always use the full registered face bounds, including an occluded corner. Using only the
        // visible centers makes the cue jump a third of a board whenever one marker disappears.
        val faceSites=own.values.filter { it.face==face && it.axis==normal }
        val loU=faceSites.minOf { it.center[uv[0]] };val hiU=faceSites.maxOf { it.center[uv[0]] }
        val loV=faceSites.minOf { it.center[uv[1]] };val hiV=faceSites.maxOf { it.center[uv[1]] }
        val cu=(loU+hiU)/2;val cv=(loV+hiV)/2
        val projection=Projection(project(cu,cv),listOf(
            ProjectedAxis(uv[0],project(loU,cv),project(hiU,cv)),
            ProjectedAxis(uv[1],project(cu,loV),project(cu,hiV))),
            listOf(project(loU,loV),project(hiU,loV),project(hiU,hiV),project(loU,hiV)),ids.size,
            localCenter=listOf(cu,cv),localHalfExtent=listOf((hiU-loU)/2,(hiV-loV)/2),face=face)
        return Observation(part,face,ids,"coarse_axes",mapOf(uv[0] to u,uv[1] to v),rms,extent,projection)
    }

    /** Only call this for the current pair placed flat on the same table, not arbitrary assembled planes. */
    fun compareFlatPair(fixed:Observation,moving:Observation):PairResult {
        if(fixed.face==null||moving.face==null)return PairResult("need_view",null,null,"把这两半平放，并拍清每一半至少一枚标签。")
        if(fixed.face!=moving.face)return PairResult("exposed_faces_differ",moving.part,null,"看到的面不同。先将两半平放在同一桌面；若仍不同，保持一半不动，把另一半翻到同一面朝上。")
        val shared=fixed.axes.keys.intersect(moving.axes.keys).sorted()
        if(shared.size<2)return PairResult("same_face_direction_unknown",null,null,"两半是同一面朝上。再拍清每半的三枚分散标签，检查旋转方向。")
        val deltas=shared.map { axis -> angle(fixed.heading(axis)!!-moving.heading(axis)!!) }
        val mean=angle(deltas[0]+angle(deltas[1]-deltas[0])/2)
        val disagreement=abs(angle(deltas[0]-deltas[1]))
        // The same table direction projects differently at opposite sides of a perspective image.
        // Each axis must support the same coarse conclusion; disagreement alone is not a coplanarity
        // test. Keep a neutral band instead of converting a doubtful view into a green verdict.
        if(deltas.all { abs(it)<=35 } && abs(mean)<=25 && disagreement<=45)
            return PairResult("same_face_coarse_direction_ok",null,mean,"两半同面，粗略方向一致。继续核对接头并直插。")
        if(disagreement>45 || abs(mean)<42)
            return PairResult("need_view",null,null,"暂时看不准转向。把手机稍微移到两块中间。")
        return PairResult("rotate_in_plane",moving.part,mean,"两半同面，但方向还不同。保持一半不动，按指示转动另一半，对齐接头。")
    }

    /** Choose a board axis for a physical half-turn, rather than a fixed screen-vertical flip.
     * A flip preserves the selected CAD axis and reverses the other. Both choices change the face;
     * prefer the one leaving less in-plane rotation. Perspective makes this a coarse instruction,
     * and the caller must observe the new face again instead of assuming the flip completed alignment.
     */
    fun preferredFlipAxis(fixed:Observation,moving:Observation):Int? {
        if(fixed.face==null || moving.face==null || fixed.face==moving.face)return null
        val shared=fixed.axes.keys.intersect(moving.axes.keys).sorted()
        if(shared.size!=2 || moving.projection==null)return null
        val costs=shared.associateWith { keep->
            sqrt(shared.sumOf { axis->
                val after=moving.heading(axis)!!+if(axis==keep)0 else 180
                angle(fixed.heading(axis)!!-after).pow(2)
            }/2)
        }
        if(abs(costs.getValue(shared[0])-costs.getValue(shared[1]))<12) {
            // Around a quarter-turn both flips are equally useful. Prefer the longer visible axis
            // consistently, rather than alternating the instruction on tiny marker-center noise.
            return moving.projection.axes.maxByOrNull { hypot(it.end.x-it.start.x,it.end.y-it.start.y) }?.axis
        }
        return shared.minByOrNull { costs.getValue(it) }
    }

    /** Layer A/B faces may mix. The notch-bearing CAD X direction is the invariant; Z may mirror. */
    fun compareLayerPair(fixed:Observation,moving:Observation,preservedAxis:Int=0):PairResult {
        val a=fixed.heading(preservedAxis);val b=moving.heading(preservedAxis)
        if(a==null||b==null)return PairResult("direction_unknown",null,null,
            "正反面可以混用。把两半平放，拍清各半三枚分散标签，再核对缺口朝向。")
        val delta=angle(a-b)
        if(abs(delta)<=25)return PairResult("coarse_direction_ok",null,delta,
            "缺口方向粗略一致。对齐接头后直插；是否接合到位仍看接缝。")
        return PairResult("rotate_in_plane",moving.part,delta,
            "两半的缺口方向还不一致。保持一半不动，平放并转动另一半，对齐接头。")
    }

    /** Shared global-axis image headings aid assembly comparison but cannot prove 3D pose or seating. */
    fun sharedAxisDifferences(a:Observation,b:Observation):Map<Int,Double> =
        a.axes.keys.intersect(b.axes.keys).associateWith { angle(a.heading(it)!!-b.heading(it)!!) }
    fun angle(degrees:Double):Double=((degrees+180)%360+360)%360-180
    private fun solve(input:Array<DoubleArray>,rhs:DoubleArray):DoubleArray? {
        val a=Array(3){r->DoubleArray(4){c->if(c==3)rhs[r] else input[r][c]}}
        for(c in 0..2){val pivot=(c..2).maxBy { abs(a[it][c]) }
            if(abs(a[pivot][c])<1e-6)return null
            val tmp=a[c];a[c]=a[pivot];a[pivot]=tmp
            val d=a[c][c];for(k in c..3)a[c][k]/=d
            for(r in 0..2)if(r!=c){val f=a[r][c];for(k in c..3)a[r][k]-=f*a[c][k]}}
        return DoubleArray(3){a[it][3]}
    }
}
