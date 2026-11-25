package com.example.arassemblydemo

import kotlin.math.*

private typealias Site = BoardFaceRules.Site
private typealias Observation = BoardFaceRules.Observation
private typealias P = BoardFaceRules.Point
private typealias GuideResult = BoardLocalGuidance.Result

/** Screen directions of two different planes are not directly comparable. Recover each measured
 * plane's camera-space unit axes using the SAME candidate pinhole camera. Only issue a direction
 * verdict if the plausible focal lengths agree. Translation/seating depth are never inferred. */
internal object FinalAssemblyGuidance {
    class History {
        internal val slots=RecentFrameSlots()
        fun reset() = slots.clear()
    }
    private const val BASE = "左侧板"
    private const val CAP = "右侧板"
    private const val BACK = "背板"
    private val layers = (1..5).map { "层${it}板" }
    fun supports(task: BoardLocalGuidance.Task) = task.kind == "install" && task.id == "install:${task.group}" &&
        task.group in layers + listOf(BASE, CAP, BACK) && task.members.toSet() == setOf("${task.group}-1", "${task.group}-2")
    private data class V(val x:Double,val y:Double,val z:Double) {
        fun dot(b:V) = x*b.x+y*b.y+z*b.z
        fun cross(b:V) = V(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x)
        fun length() = sqrt(dot(this))
        fun unit() = length().let { V(x/it,y/it,z/it) }
    }
    private data class Axes(val u:V,val v:V,val error:Double) {
        val normal get() = u.cross(v).unit()
    }
    private data class Plane(val observation:Observation,val face:MeasuredBoardProjection.Face) {
        val projection = requireNotNull(observation.projection)
        private val h = projection.planeToImage.toDoubleArray()
        fun axes(f:Double,cx:Double,cy:Double):Axes {
            fun column(i:Int):V {
                val half = projection.localHalfExtent[i]
                return V((h[i]-cx*h[6+i])/(half*f),(h[3+i]-cy*h[6+i])/(half*f),h[6+i]/half)
            }
            val a=column(0); val b=column(1)
            val u=a.unit(); val v=b.unit()
            return Axes(u,v,max(abs(u.dot(v)),abs(ln(a.length()/b.length()))))
        }
        fun pixel(x:Double,y:Double):P? {
            val c = projection.localCenter; val s = projection.localHalfExtent
            return MeasuredBoardProjection.map(h,P((x-c[0])/s[0],(y-c[1])/s[1]))
        }
        /** Each frame half has five measured openings. Their CAD +Y order is purple to green. */
        fun rows():List<P> = rowsOf(face)
    }
    private fun rowsOf(face:MeasuredBoardProjection.Face):List<P> = if(face.holes.size!=5) emptyList() else face.holes.map { ring ->
            P((ring.minOf { it.x }+ring.maxOf { it.x })/2,(ring.minOf { it.y }+ring.maxOf { it.y })/2)
        }.sortedBy { it.y }
    private data class Comparison(val common:Double,val vertical:Double,val secondary:Double,val error:Double)
    private fun compare(base:Plane,target:Plane,width:Int,height:Int,group:String):List<Comparison> {
        if(width<1 || height<1)return emptyList()
        val longSide=max(width,height).toDouble()
        val fits=(0..34).map { i->
            val f=longSide*(.30+i*.065)
            val a=base.axes(f,width*.5,height*.5);val b=target.axes(f,width*.5,height*.5)
            val common=if(group==BACK)a.v.dot(b.u) else a.u.dot(b.u)
            Comparison(common,a.normal.dot(b.v),a.v.dot(b.v),max(a.error,b.error))
        }.filter { it.error.isFinite() && it.error<=.24 }
        val best=fits.minOfOrNull { it.error }?:return emptyList()
        return fits.filter { it.error <= best+.085 }
    }
    private fun dist(a:P,b:P) = hypot(a.x-b.x,a.y-b.y)
    private data class RowMatch(val row:Int,val gap:Double)
    /** Distance to a measured row divided by its local projected pitch. Either Z edge of a
     * colored half is allowed because the manufacturing rule permits independently flipped faces. */
    private fun rowMatch(base:Plane,panel:Plane,highEdge:Boolean):RowMatch? {
        val rows=base.rows()
        if(rows.size!=5)return null
        val pixels=rows.map { base.pixel(it.x,it.y)?:return null }
        val x=base.face.cadOrigin[0]+rows[2].x-panel.face.cadOrigin[0]
        val b=panel.face.bounds
        if(x !in b[0]..b[2])return null
        val edge=panel.pixel(x,if(highEdge)b[3]-7.0 else b[1]+7.0)?:return null
        return rows.indices.mapNotNull { j->
            val pitch=listOf(j-1,j+1).filter { it in rows.indices }.minOf { dist(pixels[it],pixels[j]) }
            if(pitch<10.0)null else RowMatch(j,dist(edge,pixels[j])/pitch)
        }.minByOrNull { it.gap }
    }
    private fun validTag(marker:BoardFaceRules.Detection):Boolean {
        val p=marker.corners
        if(p.size!=4 || p.any { !it.x.isFinite() || !it.y.isFinite() })return false
        return (0..3).all { i->
            val a=p[i]; val b=p[(i+1)%4]; val c=p[(i+2)%4]
            dist(a,b)>=4 && (b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)>1e-5
        }
    }
    private fun tagCenter(d:BoardFaceRules.Detection) = P(d.corners.map { it.x }.average(),d.corners.map { it.y }.average())

    // Ambiguous piles must not let a distant correct base endorse an installation on the other
    // frame. Proximity is used to request a clearer arrangement, never to prove a connection.
    private fun nearerCap(seen:List<BoardFaceRules.Detection>,sites:Map<Int,Site>,parts:Set<String>):Boolean {
        fun centers(prefix:String)=seen.filter { sites[it.id]?.part?.startsWith("$prefix-")==true }.map(::tagCenter)
        val left=centers(BASE); val right=centers(CAP)
        if(left.isEmpty() || right.size<2)return false
        return parts.all { part->
            val points=seen.filter { sites[it.id]?.part==part }.map(::tagCenter)
            points.size>=2 && points.minOf { p->right.minOf { dist(p,it) } }*1.45 <
                points.minOf { p->left.minOf { dist(p,it) } }
        }
    }
    private fun tagSpan(points:List<BoardFaceRules.Detection>):Double {
        val centers=points.map(::tagCenter)
        return centers.maxOfOrNull { p->centers.maxOf { dist(p,it) } }?:0.0
    }
    // Ask both independently observed halves where two shared CAD locations would land.
    // Two separated flat halves can have the correct faces and headings while missing the seam.
    private fun baseJoined(planes:List<Plane>):Boolean {
        if(planes.size!=2)return false
        val a=planes[0]; val b=planes[1]
        val seamX=(max(a.face.cadOrigin[0]+a.face.bounds[0],b.face.cadOrigin[0]+b.face.bounds[0])+
            min(a.face.cadOrigin[0]+a.face.bounds[2],b.face.cadOrigin[0]+b.face.bounds[2]))/2
        val pitch=a.rows().takeIf { it.size==5 } ?:return false
        return listOf(pitch.first(),pitch.last()).all { hole->
            val y=a.face.cadOrigin[1]+hole.y
            val u=a.pixel(seamX-a.face.cadOrigin[0],hole.y)
            val v=b.pixel(seamX-b.face.cadOrigin[0],y-b.face.cadOrigin[1])
            val width=a.observation.extentPx ?:0.0
            u!=null && v!=null && width>40 && dist(u,v)<width*.065
        }
    }
    private fun cappedRows(cap:List<Plane>,layersSeen:List<Plane>,width:Int,height:Int):Set<String> {
        return layersSeen.mapNotNull { shelf->
            val layer=shelf.face.part.substringBeforeLast('-')
            cap.firstOrNull { c->
                val fit=compare(c,shelf,width,height,layer)
                val pair=rowMatch(c,shelf,fit.firstOrNull()?.vertical?.let { it>0 }?:return@firstOrNull false)
                pair!=null && pair.row==layers.indexOf(layer) && pair.gap<.38 &&
                    fit.isNotEmpty() && fit.all { it.common>.82 && abs(it.vertical)>.77 }
            }?.let { layer }
        }.toSet()
    }
    fun evaluate(task:BoardLocalGuidance.Task,sites:List<Site>,detections:List<BoardFaceRules.Detection>,
                 measured:MeasuredBoardProjection.Catalog?,width:Int,height:Int,history:History?=null,atMs:Long=0,slotQuads:List<List<P>> = emptyList()):GuideResult {
        val group=task.group
        val targetParts=task.members.toSet()
        val relevant=sites.filter { it.part in targetParts || it.part.startsWith("$BASE-") || it.part.startsWith("$CAP-") ||
            group==CAP && it.part.startsWith("层") }.associateBy { it.id }
        val seen=detections.filter { it.id in relevant && validTag(it) }
        val ids=seen.map { it.id }
        val observations=mutableMapOf<String,Observation>()
        fun obs(part:String):Observation = observations.getOrPut(part) {
            measured?.observe(part,sites,seen)?:BoardFaceRules.observe(part,sites,seen)
        }
        fun planes(list:List<Observation>)=list.mapNotNull { o->
            val face=measured?.faces?.get(o.part to o.face) ?:return@mapNotNull null
            if(face.cadOrigin.size!=3 || o.projection?.method!="measured_homography")null else Plane(o,face)
        }
        val base=listOf(obs("$BASE-1"),obs("$BASE-2")).filter { it.ids.isNotEmpty() }
        val moving=targetParts.map(::obs).filter { it.ids.isNotEmpty() }
        val targets=mutableListOf<P>()
        val inferred=mutableListOf<P>()
        var notchCue=emptyList<P>()
        var notchRelation:String?=null
        val coherentBaseIds=base.flatMap { it.ids }.toSet()
        val baseTags=seen.filter { it.id in coherentBaseIds }
        val localTags by lazy {
            seen.mapNotNull { tag->
                val site=relevant[tag.id]?:return@mapNotNull null
                measured?.faces?.get(site.part to site.face)?.let { InstallTagGeometry.create(it,tag) }
            }
        }
        fun answer(status:String,title:String,message:String,who:Observation?=moving.maxByOrNull { it.ids.size }):GuideResult =
            GuideResult(status,title,message,part=who?.part,markerIds=ids.distinct(),anchor=who?.projection?.center,
                cueGeometry=who?.projection,slotTargets=targets.toList(),inferredSlotTargets=inferred.toList(),notchCue=notchCue,notchRelation=notchRelation)
        if (ids.size!=ids.distinct().size || base.any { it.face!="A" })history?.reset()
        if(ids.size!=ids.distinct().size)return answer("install_check","拿稳手机","等标签清楚再继续。")
        if(group==BACK){
            val refs=(base+listOf(obs("$CAP-1"),obs("$CAP-2"))).filter { it.face=="A" }
            val fits=planes(refs).flatMap { frame->
                planes(moving).flatMap { back->compare(frame,back,width,height,BACK).map { it.common to it.vertical } }
            }
            return BackPanelGuidance.evaluate(measured,seen,relevant,refs,moving,fits,width,height)
        }
        val basePlanes=planes(base)
        val isLayer=group in layers
        val row=layers.indexOf(group)
        val capVisible=ids.count { relevant[it]?.part?.startsWith("$CAP-")==true }
        val goodBase=base.filter { it.face=="A" }
        val faceRequired=group==BASE || isLayer
        if(faceRequired && goodBase.isEmpty() && capVisible>=3 && capVisible>base.sumOf { it.ids.size })
            return answer("install_wrong_part","换另一块灰框","这块是最后盖上的。把作底的灰框单独放到桌上。",null)
        if(isLayer && capVisible>=2 && nearerCap(seen,relevant,targetParts)){
            if(goodBase.isEmpty())return answer("install_wrong_part","先换成作底的灰框","先拿开层板，把作底的灰框内侧朝上放平。",null)
            return answer("install_show_base","移开旁边的另一块灰框","把层板拿到作底的灰框上方，让目标孔入镜。",null)
        }
        if(faceRequired && base.any { it.face=="B" }) {
            val mixed=base.any { it.face=="A" }
            return answer("install_base_flip",if(mixed)"灰框两半正反不同" else "把底下的灰框翻面",
                if(mixed)"先分开灰框，把朝外的一半翻过来，再接好。" else "先拿开层板，把灰框翻过来，内侧朝上平放。",base.first { it.face=="B" }).copy(installMotion=if(mixed)"flip_one" else "flip_group")
        }
        if(faceRequired && goodBase.isEmpty())return answer("install_show_base","对准作底的灰框","把平放灰框的标签带进画面。",null)
        if(base.size==2 && basePlanes.size==2 && BoardFaceRules.compareFlatPair(base[0],base[1]).status=="rotate_in_plane")
            return answer("install_turn","先摆正底框的两半","先松开接头，平放转正这一半，再拼成框。",base[1]).copy(installMotion="turn_one")
        val visibleLayerIds=if(isLayer && moving.isEmpty())sites.filter { it.part.startsWith("层") }.map { it.id }.toSet() else emptySet()
        val firstRowBeforePickup=group==layers.first() && moving.isEmpty() && detections.none { it.id in visibleLayerIds }
        if((group==BASE || firstRowBeforePickup) && goodBase.size==2 && capVisible<3 && baseJoined(basePlanes)){
            val ends=basePlanes.mapNotNull { plane->
                val rows=plane.rows().takeIf { it.size==5 }?:return@mapNotNull null
                val p=plane.pixel(rows.first().x,rows.first().y)?:return@mapNotNull null
                val q=plane.pixel(rows.last().x,rows.last().y)?:return@mapNotNull null
                p to q
            }
            if(ends.size==2){
                val back=P(ends.map { it.first.x }.average(),ends.map { it.first.y }.average())
                val front=P(ends.map { it.second.x }.average(),ends.map { it.second.y }.average())
                val angle=BoardFaceRules.angle(-90.0-Math.toDegrees(atan2(back.y-front.y,back.x-front.x)))
                if(abs(angle)>38 && dist(back,front)>50){
                    val turn=if(abs(angle)>148) "把底框平转半圈" else if(angle>0) "把底框顺时针转" else "把底框逆时针转"
                    return answer("install_base_heading",turn,
                        if(group==BASE)"把亮起的这排转到远端，后面好插板。" else "若还没插板，先把亮起的这排转到远端，再拿起紫板。",basePlanes[0].observation)
                        .copy(angleDegrees=angle,installMotion="turn_base_toward_back",anchor=P((back.x+front.x)/2,(back.y+front.y)/2),
                            slotTargets=ends.map { it.first })
                }
            }
        }
        if(group==BASE){
            val baseSize=tagSpan(seen.filter { relevant[it.id]?.part?.startsWith("$BASE-")==true })
            val capSize=tagSpan(seen.filter { relevant[it.id]?.part?.startsWith("$CAP-")==true })
            if(capVisible>=3 && capSize>baseSize*.85)
                return answer("install_show_base","把作底的灰框单独放好","把另一块灰框移出画面，再看平放的这一块。",goodBase.first())
            if(goodBase.size!=2 || !baseJoined(basePlanes))
                return answer("install_check","把完整底框放平","朝着中间的接缝拍，让两半的内侧标签一起入镜。",goodBase.first())
            return answer("install_base_ready","灰框内侧朝上","把框平放在桌上，后面都以它为准。",goodBase.first())
        }
        val correctBasePlanes=basePlanes.filter { it.observation.face=="A" }
        if(isLayer){
            val missing=mutableListOf<MeasuredBoardProjection.Face>()
            val byPart=mutableMapOf<String,P>()
            listOf("$BASE-1","$BASE-2").forEach { part->
                val face=measured?.faces?.get(part to "A")?:return@forEach
                val hole=rowsOf(face).getOrNull(row)?:return@forEach
                val plane=correctBasePlanes.firstOrNull { it.face.part==part }
                val px=plane?.pixel(hole.x,hole.y)
                if(px!=null){targets+=px;byPart[part]=px}else missing+=face
            }
            if(correctBasePlanes.size==2 && baseJoined(correctBasePlanes)){
                if(byPart.size==2)history?.slots?.remember(byPart,baseTags,atMs)
            }else if(correctBasePlanes.size==2)history?.reset()
            val recent=if(missing.isNotEmpty())history?.slots?.project(baseTags,atMs).orEmpty() else emptyMap()
            val sparseSlots = if(missing.isNotEmpty() && measured!=null && correctBasePlanes.size<2)
                FrameSlotGrid.locate(measured,baseTags,row,slotQuads,width,height) else emptyMap()
            missing.forEach { face->
                val hole=rowsOf(face)[row]
                val weak=baseTags.filter { it.id in face.markers }
                val ref=correctBasePlanes.firstOrNull()
                val sameFrame=if(ref!=null)JoinedFaceProjection.verified(ref.face,ref.projection,face,weak)?.pixel(hole) else null
                val nearby=localTags.filter { it.part==face.part }.mapNotNull { it.nearSlot(hole) }
                // Multiple local estimates should name the same hole, including its transverse position.
                val local=nearby.takeIf { it.isNotEmpty() && it.maxOf { a->it.maxOf { b->dist(a,b) } }<min(width,height)*.024 }
                    ?.let { P(it.map { p->p.x }.average(),it.map { p->p.y }.average()) }
                (sameFrame ?: sparseSlots[face.part] ?: local ?: recent[face.part])?.let(inferred::add)
            }
        }
        if(moving.isEmpty())return answer("install_show_target",if(isLayer)"拿起当前层板" else "拿起这一步的组件",
            if(isLayer)"让层板和框上的目标孔一起入镜。" else "把组件的标签和安装位置一起拍进来。")
        if(group==CAP && moving.any { it.face=="B" })return answer("install_base_flip","把上面的灰框翻面","从桌面上方看，把上框的外侧翻朝上，再对准层板。",
            moving.first { it.face=="B" }).copy(installMotion=if(moving.any { it.face=="A" })"flip_one" else "flip_group")
        val movingPlanes=planes(moving)
        var sparseOpposed=false
        var sparseAligned=false
        var notchSide=""
        if(isLayer && width>0 && height>0){
            val anchors=localTags.filter { it.part.startsWith("$BASE-") }
            val panels=localTags.filter { it.part in targetParts }
            val sample=panels.map { it.center }
            // The camera-space X direction is shared by both boards. A single square can
            // indicate this signed local direction; it cannot verify row contact or depth.
            if(anchors.isNotEmpty() && sample.isNotEmpty()){
                val x=anchors.sumOf { cos(it.xHeading)*it.weight }
                val y=anchors.sumOf { sin(it.xHeading)*it.weight }
                val angle=atan2(y,x)
                if(anchors.all { cos(it.xHeading-angle)>.94 } && panels.all { abs(cos(it.xHeading-angle))>.84 }){
                    sparseOpposed=panels.all { cos(it.xHeading-angle)<-.84 }
                    sparseAligned=panels.all { cos(it.xHeading-angle)>.84 }
                    val origin=P(sample.map { it.x }.average(),sample.map { it.y }.average())
                    val span=min(width,height)*.16
                    if(sparseAligned || sparseOpposed) {
                        notchRelation=if(sparseAligned)"aligned" else "opposed"
                        notchCue=listOf(origin,P(origin.x+cos(angle)*span,origin.y+sin(angle)*span))
                        notchSide=when { abs(cos(angle))>=abs(sin(angle)) -> if(cos(angle)>0)"右边" else "左边"
                            sin(angle)>0->"画面下方";else->"画面上方" }
                    }
                }
            }
        }
        fun sparseHint():GuideResult? {
            if(!isLayer || notchCue.isEmpty())return null
            if(sparseOpposed)return answer("install_notch_hint","把缺口转向"+notchSide,"先提离槽口。扶住板边，转到箭头指的一侧。")
                .copy(installMotion="turn_panel_upright",anchor=notchCue.first())
            if(sparseAligned)return answer("install_notch_hint","缺口朝"+notchSide,"保持这个朝向，稍微抬高手机，让基准灰框也露出来。")
                .copy(anchor=notchCue.first())
            return null
        }
        if(movingPlanes.isEmpty())return sparseHint()?:answer("install_check","再露出板上的一个角","手扶住板边，把隔开的标签一起拍清。")
        if(group==CAP && movingPlanes.size==2){
            val halves=movingPlanes.map { it.observation }
            if(BoardFaceRules.compareFlatPair(halves[0],halves[1]).status=="rotate_in_plane")
                return answer("install_turn","上框两半方向没对上","先放下上框，松开接头，把这一半平放转正。",halves[1]).copy(installMotion="turn_one")
            if(!baseJoined(movingPlanes))return answer("install_check","先对齐上框的两半","拍清上框两端接头，把两半接齐，再盖到层板上。")
        }
        // Actual free endpoints of two different registered layers must project near their
        // designated cap rows before this surface can act as a substitute for the occluded base.
        if(group==CAP && correctBasePlanes.isEmpty()){
            val layerRefs=planes(sites.map { it.part }.distinct().filter { it.startsWith("层") }.map(::obs))
            if(cappedRows(movingPlanes,layerRefs,width,height).size>=2)
                return answer("install_oriented","拍到的上框孔位对上了","逐排对准层板的凸榫，再平稳放下。")
            return answer("install_check","托住上框，对准层板","让上框和两层凸榫一起入镜，核对盖合方向。")
        }
        if(correctBasePlanes.isEmpty())return sparseHint()?:answer("install_show_base","把基准和组件一起拍进来","在灰框和手持组件上各露出分开的标签。")
        // Best supported reference half is sufficient. Check every available target half so a
        // contradictory local heading cannot be hidden by another correctly oriented half.
        val reference=correctBasePlanes.maxBy { it.observation.ids.size }
        val fits=movingPlanes.associateWith { compare(reference,it,width,height,group) }
        if(fits.values.any { it.isEmpty() })return sparseHint()?:answer("install_check","稍微斜一点拍","让灰框和手里的板各露出两个角。")
        val reverse=fits.entries.firstOrNull { (_,fits)->fits.all { it.common<-.78 } }
        if(reverse!=null && isLayer)notchRelation="opposed"
        if(reverse!=null)return answer("install_turn",if(isLayer && notchSide.isNotEmpty())"把缺口转向"+notchSide else if(isLayer)"把缺口转到另一端" else "把组件转半圈",
            if(group==CAP)"托住上框，平放转半圈，再对准层板。" else "先提离槽口。扶稳层板，竖着转半圈。",reverse.key.observation).copy(installMotion=if(isLayer)"turn_panel_upright" else null)
        val all=fits.values.flatten()
        if(isLayer && all.all { it.common>.80 })notchRelation="aligned"
        if(isLayer && all.all { abs(it.vertical)<.52 })return answer("install_stand","把层板立起来","一只手扶住板边，让凸榫朝下。")
        val oriented=all.all { it.common>.80 && (if(group==CAP) it.secondary>.80 else abs(it.vertical)>.77) }
        if(!oriented)return answer("install_align","扶稳这块板","按示范摆正，等方向对齐再插。")
        if(group==CAP){
            val layerRefs=planes(sites.map { it.part }.distinct().filter { it.startsWith("层") }.map(::obs))
            return if(cappedRows(movingPlanes,layerRefs,width,height).size>=2)
                answer("install_oriented","拍到的上框孔位对上了","对齐各层凸榫，平稳放下。")
            else answer("install_align","上框方向对了","托住上框，对准每排凸榫，再盖下。")
        }
        val matches=correctBasePlanes.flatMap { b->movingPlanes.mapNotNull { p->
            rowMatch(b,p,fits.getValue(p).first().vertical<0)
        } }
        if(matches.isNotEmpty() && matches.all { it.row!=row && it.gap<.36 } && matches.map { it.row }.distinct().size==1)
            return answer("install_wrong_row","对着另一排孔了","提起层板，对准第${row+1}排再放下。")
                .copy(observedRow=matches.first().row+1, targetRow=row+1)
        if(matches.isNotEmpty() && matches.all { it.row==row && it.gap<.38 })
            return answer("install_row_ok","方向和孔位对上了","扶稳层板，向下插到接缝齐平。")
        return answer("install_align","方向对了，移到亮起的孔","扶稳层板，两端对准同一排，再向下插。")
    }
}
