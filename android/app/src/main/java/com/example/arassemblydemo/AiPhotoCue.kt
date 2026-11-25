package com.example.arassemblydemo

import kotlin.math.*
import com.example.arassemblydemo.BoardFaceRules.Point

/** A teaching cue tied to one photograph. Directions use photo pixels with Y down. */
internal object AiPhotoCue {
    data class Facing(val x: Double, val y: Double, val parity: Int) {
        /** Screen direction of CAD +U and +V after removing perspective foreshortening. */
        fun screen(u: Double, v: Double) = Point(x*u - parity*y*v, y*u + parity*x*v)
    }
    sealed interface Lesson { val captureId:String }
    data class RowPlan(override val captureId:String,val observedRow:Int,val targetRow:Int):Lesson
    data class Plan(override val captureId: String, val part: String, val kind: String, val label: String,
        val markerIds: List<Int>, val markerCenter: Point, val cadNormal: Int, val faceSign: Int,
        val facing: Facing, val axisIndex: Int, val angleDegrees: Double):Lesson
    data class Phase(val stage:String,val progress:Double,val opacity:Double)

    fun plan(assessment:String,task:BoardLocalGuidance.Task,sites:List<BoardFaceRules.Site>,
             detections:List<BoardFaceRules.Detection>,captureId:String,
             measured:MeasuredBoardProjection.Catalog):Plan? {
        if(assessment!="needs_adjustment"||captureId.isBlank()||!measured.available)return null
        val check=AiFrameOrientation.analyze(task,sites,detections,captureId,measured)
        val state=check.optString("status")
        if(state !in setOf("face_mismatch","rotate","joint_mismatch"))return null
        val fixed=task.stationary.firstOrNull { it in task.parts }?:task.parts.firstOrNull()?:return null
        val moving=task.members.firstOrNull { it in task.parts&&it!=fixed }?:task.parts.firstOrNull { it!=fixed }?:return null
        val byId=sites.associateBy { it.id }
        fun tags(part:String)=detections.filter { byId[it.id]?.part==part }
        fun face(part:String,tags:List<BoardFaceRules.Detection>):MeasuredBoardProjection.Face? {
            val side=tags.map { byId.getValue(it.id).face }.distinct().singleOrNull()?:return null
            return measured.faces[part to side]
        }
        val seen=tags(moving)
        val fixedSeen=tags(fixed)
        val movingFace=face(moving,seen)?:return null
        val fixedFace=face(fixed,fixedSeen)?:return null
        val facing=photoFacing(movingFace,seen)?:return null
        val otherFacing=photoFacing(fixedFace,fixedSeen)?:return null
        val anchor=center(seen.flatMap { it.corners })
        val other=center(fixedSeen.flatMap { it.corners })
        val dx=anchor.x-other.x
        val dy=anchor.y-other.y
        val label=when {
            abs(dx)>abs(dy)*1.25&&abs(dx)>20->if(dx>0)"右手边这块" else "左手边这块"
            abs(dy)>abs(dx)*1.25&&abs(dy)>20->if(dy>0)"下面这块" else "上面这块"
            else->"标记的这块"
        }
        // A flip keeps one in-plane CAD axis and reverses the other. Choose the hinge that
        // leaves the moving half closest to the fixed half's observed orientation.
        val axis=if(facing.x*otherFacing.x+facing.y*otherFacing.y>=0.0)0 else 1
        val turn=if(state=="rotate")check.optDouble("angleDegrees",Double.NaN) else 180.0
        if(!turn.isFinite())return null
        return Plan(captureId,moving,if(state=="face_mismatch")"flip" else "rotate",label,
            seen.map { it.id }.sorted(),anchor,movingFace.normal,if(movingFace.face=="A")1 else -1,facing,axis,turn)
    }

    private fun center(p:List<Point>)=Point(p.map { it.x }.average(),p.map { it.y }.average())

    /** Ordered black-square corners correspond to the measured corners of this particular glued
     * tag. A single square recovers handedness and in-plane heading for a virtual front view.
     * Centre each tag independently so the result works on joint closeups as well as overviews. */
    internal fun photoFacing(face:MeasuredBoardProjection.Face,seen:List<BoardFaceRules.Detection>):Facing? {
        val parity=(if(face.normal==1)1 else -1)*(if(face.face=="A")1 else -1)
        val directions=seen.map { detection->
            val src=face.markers[detection.id]?:return null
            val dst=detection.corners
            if(src.size!=4||dst.size!=4)return null
            val q=center(src);val p=center(dst)
            var c=0.0;var s=0.0
            for(i in src.indices) {
                val u=src[i].x-q.x;val v=parity*(src[i].y-q.y)
                val dx=dst[i].x-p.x;val dy=dst[i].y-p.y
                c+=u*dx+v*dy
                s+=u*dy-v*dx
            }
            val n=hypot(c,s)
            val a=dst[0];val b=dst[1];val d=dst[3]
            val det=(b.x-a.x)*(d.y-a.y)-(d.x-a.x)*(b.y-a.y)
            if(!n.isFinite()||n<1e-6||det<.12*hypot(b.x-a.x,b.y-a.y)*hypot(d.x-a.x,d.y-a.y))return null
            Point(c/n,s/n)
        }
        if(directions.isEmpty())return null
        val mean=center(directions)
        val n=hypot(mean.x,mean.y)
        if(n<.92||directions.any { (it.x*mean.x+it.y*mean.y)/n<.82 })return null
        return Facing(mean.x/n,mean.y/n,parity)
    }

    fun rowPlan(assessment:String, task:BoardLocalGuidance.Task, captureId:String, check:org.json.JSONObject):RowPlan? {
        if(assessment!="needs_adjustment" || captureId.isBlank() || task.kind!="install" ||
            check.optString("schema")!="final-assembly-check-1" || check.optString("status")!="install_wrong_row" ||
            check.optString("taskId")!=task.id || check.optString("captureId")!=captureId) return null
        val from=check.optInt("observedRow",0)
        val to=check.optInt("targetRow",0)
        if(from !in 1..5 || to !in 1..5 || from==to || task.id!="install:层${to}板" ||
            check.optJSONArray("baseMarkers").let { it==null || it.length()==0 } ||
            check.optJSONArray("panelMarkers").let { it==null || it.length()==0 })return null
        return RowPlan(captureId,from,to)
    }

    fun mayPresent(plan:Lesson?,shownCapture:String?,latestCapture:String?,active:Boolean,
                     previewing:Boolean,sending:Boolean,hasResult:Boolean):Boolean =
          plan!=null&&active&&!previewing&&!sending&&hasResult&&
            shownCapture==plan.captureId&&latestCapture==plan.captureId

    fun phase(elapsedMs:Long):Phase {
        val t=(elapsedMs.coerceAtLeast(0)%6600).toDouble()
        fun eased(x:Double)=x*x*(3-2*x)
        return when {
            t<1600->Phase("withdraw",eased(t/1600),1.0)
            t<2300->Phase("withdraw",1.0,1.0)
            t<4800->Phase("turn",eased((t-2300)/2500),1.0)
            t<6000->Phase("turn",1.0,1.0)
            t<6300->Phase("reset",1.0,(6300-t)/300)
            else->Phase("reset",0.0,0.0)
        }
    }
}
