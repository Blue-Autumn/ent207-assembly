package com.example.arassemblydemo

import android.os.SystemClock
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import kotlin.math.*

/** Single analysis-thread owner. No smoothing or prediction; every rendered pose uses this frame. */
class BoardPoseEstimator(private val board: BoardLayout, val cubeLengthMm: Double = 30.0,
    private val clockNowNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() }) {
    private val markerLengthMm = board.markerLengthMm
    init { require(markerLengthMm.isFinite() && markerLengthMm > 0); require(cubeLengthMm.isFinite() && cubeLengthMm > 0) }
    private var previous: PoseResult? = null
    private var previousTime = 0L
    private var confirmed = 0
    fun reset() { previous = null; previousTime = 0L; confirmed = 0 }

    fun process(markers: List<Marker>, model: CameraModel, nowNanos: Long): PoseFrame {
        val accepted = ArrayList<Marker>()
        val rejected = ArrayList<RejectedMarker>()
        var failedFitErrors: Map<Int,Double> = emptyMap()
        fun stopped(state: String, reason: String): PoseFrame {
            reset()
            return PoseFrame(markers, accepted, rejected, null, state, reason, 0, markerErrors=failedFitErrors)
        }
        val age = clockNowNanos() - nowNanos
        if (nowNanos <= 0 || age < 0 || age >= 400_000_000L || (previousTime != 0L && nowNanos <= previousTime))
            return stopped("STALE", "时间戳异常或帧已过期")
        if (previousTime != 0L && nowNanos - previousTime > 400_000_000L) reset()
        if (model.width <= 0 || model.height <= 0 || !listOf(model.fx,model.fy,model.cx,model.cy).all { it.isFinite() } ||
            model.fx <= 0 || model.fy <= 0 || !model.distortion.all { it.isFinite() } || model.distortion.size !in listOf(0,4,5,8,12,14))
            return stopped("INVALID_CAMERA", "相机参数无效")
        for (marker in markers) {
            val reason = if (marker.id !in board.corners) "ID不在允许的0–3中" else geometryReason(marker.points, model)
            if (reason == null) accepted.add(marker) else rejected.add(RejectedMarker(marker, reason))
        }
        // Reject the whole frame if ANY configured ID is duplicated, even if one copy is malformed.
        val duplicateIds = markers.filter { it.id in board.corners }.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        if (duplicateIds.isNotEmpty()) return stopped("AMBIGUOUS", "重复ID $duplicateIds，板身份歧义")
        if (accepted.isEmpty()) return stopped("NO_ANCHOR", "未找到有效板标记")
        // Try every visible marker together first. A bad marker is removed only with >=2-marker consensus.
        var used = accepted.toList()
        val allFits = solve(used, model, Double.POSITIVE_INFINITY)
        allFits.minByOrNull { it.reprojectionError }?.let { failedFitErrors = residuals(it,used,model) }
        var candidates = allFits.filter { residuals(it, used, model).values.all { e -> e <= 5.0 } }
        if (candidates.isEmpty() && accepted.size >= 3) {
            val hypotheses = (accepted.indices.flatMap { i ->
                ((i+1) until accepted.size).flatMap { j -> solve(listOf(accepted[i],accepted[j]),model) }
            }).map { pose -> pose to residuals(pose,accepted,model).filterValues { it <= 5.0 }.keys }
                .filter { it.second.size >= 2 }
            val maximum = hypotheses.maxOfOrNull { it.second.size } ?: 0
            val groups = hypotheses.filter { it.second.size == maximum }.map { it.second }.distinct()
            // Equally supported but different subsets cannot silently choose a different world pose.
            if (groups.size != 1) return stopped("INCONSISTENT", "标记间不一致，无法唯一筛选；检查布局/标定")
            used = accepted.filter { it.id in groups.single() }
            candidates = solve(used,model).filter { residuals(it, used, model).values.all { e -> e <= 5.0 } }
        }
        if (candidates.isEmpty()) return stopped("INCONSISTENT", "板PnP不一致（布局/内参/深度/误差），不回退任意单标记")
        val bestError = candidates.minOf { it.reprojectionError }
        val old = previous
        // Continuity breaks ties only between similarly fitting IPPE solutions; never hide a bad fit.
        val nearBest = candidates.filter { it.reprojectionError <= bestError + 0.5 }
        val chosen = if (old == null) nearBest.minBy { it.reprojectionError }
            else nearBest.minBy { rotationDistance(it.rvec,old.rvec) + translationDistance(it,old)/max(1.0,old.tvec[2]) }
        val jumped = old != null && (rotationDistance(chosen.rvec,old.rvec) > Math.toRadians(35.0) ||
            translationDistance(chosen,old) > max(markerLengthMm * 1.5, old.tvec[2] * 0.25))
        confirmed = if (old == null || jumped) 1 else min(3,confirmed + 1)
        previous = chosen
        previousTime = nowNanos
        val errors = residuals(chosen, accepted, model)
        accepted.filter { it !in used }.forEach { rejected.add(RejectedMarker(it,"联合外点 RMS=%.2f px".format(java.util.Locale.US,errors[it.id]))) }
        return PoseFrame(markers,accepted,rejected,if (confirmed >= 3) chosen else null,
            if (confirmed >= 3) "TRACKING" else "CONFIRMING",
            if (confirmed >= 3) (if (used.size == 1) "单标记定位" else "多标记联合定位") + " · 板中心"
            else if (jumped) "大幅跳变，重新确认 1/3" else "连续确认 $confirmed/3",confirmed,
            used.map { it.id }, errors)
    }

    private fun residuals(pose: PoseResult, markers: List<Marker>, model: CameraModel): Map<Int,Double> {
        val obj=MatOfPoint3f(*markers.flatMap { board.corners.getValue(it.id) }.toTypedArray())
        val rv=Mat(3,1,CvType.CV_64F); val tv=Mat(3,1,CvType.CV_64F)
        val k=Mat.eye(3,3,CvType.CV_64F); val d=MatOfDouble(*model.distortion); val out=MatOfPoint2f()
        try {
            rv.put(0,0,*pose.rvec); tv.put(0,0,*pose.tvec)
            k.put(0,0,model.fx); k.put(1,1,model.fy); k.put(0,2,model.cx); k.put(1,2,model.cy)
            Calib3d.projectPoints(obj,rv,tv,k,d,out)
            val q=out.toArray()
            return markers.mapIndexed { index,m -> m.id to sqrt((0..3).sumOf { j ->
                (q[index*4+j].x-m.points[j*2]).pow(2)+(q[index*4+j].y-m.points[j*2+1]).pow(2)
            }/4) }.toMap()
        } finally { obj.release();rv.release();tv.release();k.release();d.release();out.release() }
    }

    private fun geometryReason(p: FloatArray, model: CameraModel): String? {
        if (p.size != 8 || p.any { !it.isFinite() }) return "角点无效"
        if ((0..3).any { p[it*2] < -1 || p[it*2] > model.width+1 || p[it*2+1] < -1 || p[it*2+1] > model.height+1 }) return "角点超出分析图像"
        var sign = 0.0
        var area = 0.0
        for (i in 0..3) {
            val j=(i+1)%4; val k=(i+2)%4
            val dx=(p[j*2]-p[i*2]).toDouble(); val dy=(p[j*2+1]-p[i*2+1]).toDouble()
            if (hypot(dx,dy) < 4.0) return "边长过小"
            val cross=dx*(p[k*2+1]-p[j*2+1])-dy*(p[k*2]-p[j*2])
            if (abs(cross) < 0.01 || (sign != 0.0 && cross*sign <= 0)) return "角点不构成凸四边形"
            sign=cross
            area += p[i*2].toDouble()*p[j*2+1]-p[j*2].toDouble()*p[i*2+1]
        }
        return if (area <= 0.0) "角点方向错误或镜像" else if (area*0.5 < 64.0) "面积过小" else null
    }

    /** CV_32FC3 Nx1 object points, CV_32FC2 Nx1 pixels. All points are BOARD coordinates.
     * Generic planar IPPE accepts >=4 coplanar points; IPPE_SQUARE would require a centred local square.
     * No averaging of marker rvec/tvec. Output maps board -> camera.
     */
    private fun solve(markers: List<Marker>, model: CameraModel, maxError: Double = 5.0): List<PoseResult> {
        val objectPoints=MatOfPoint3f(*markers.flatMap { board.corners.getValue(it.id) }.toTypedArray())
        val observed=markers.flatMap { m -> (0..3).map { Point(m.points[it*2].toDouble(),m.points[it*2+1].toDouble()) } }
        val imagePoints=MatOfPoint2f(*observed.toTypedArray())
        val camera=Mat.eye(3,3,CvType.CV_64F)
        val dist=MatOfDouble(*model.distortion)
        val rvecs=ArrayList<Mat>(); val tvecs=ArrayList<Mat>()
        val rotation=Mat(); val reprojected=MatOfPoint2f(); val rendered=MatOfPoint2f()
        val c=cubeLengthMm/2; val z=cubeLengthMm; val a=markerLengthMm/2
        // 0..3 cube base, 4..7 +Z cube top, 8 origin, 9/10/11 +X/+Y/+Z axes.
        val drawing=MatOfPoint3f(Point3(-c,c,0.0),Point3(c,c,0.0),Point3(c,-c,0.0),Point3(-c,-c,0.0),
            Point3(-c,c,z),Point3(c,c,z),Point3(c,-c,z),Point3(-c,-c,z),
            Point3(0.0,0.0,0.0),Point3(a,0.0,0.0),Point3(0.0,a,0.0),Point3(0.0,0.0,a))
        try {
            camera.put(0,0,model.fx); camera.put(1,1,model.fy); camera.put(0,2,model.cx); camera.put(1,2,model.cy)
            Calib3d.solvePnPGeneric(objectPoints,imagePoints,camera,dist,rvecs,tvecs,false,Calib3d.SOLVEPNP_IPPE)
            return rvecs.indices.mapNotNull { index ->
                if (markers.size >= 2) Calib3d.solvePnPRefineLM(objectPoints,imagePoints,camera,dist,rvecs[index],tvecs[index])
                val r=DoubleArray(3); val t=DoubleArray(3)
                rvecs[index].get(0,0,r); tvecs[index].get(0,0,t)
                if (r.any { !it.isFinite() } || t.any { !it.isFinite() } || t[2] <= 0) return@mapNotNull null
                Calib3d.Rodrigues(rvecs[index],rotation)
                val m=DoubleArray(9); rotation.get(0,0,m)
                // +Z must point out of the printed face, toward the camera half-space.
                if (m[2]*t[0] + m[5]*t[1] + m[8]*t[2] >= 0.0) return@mapNotNull null
                if ((objectPoints.toArray().toList()+drawing.toArray().toList()).any { m[6]*it.x+m[7]*it.y+m[8]*it.z+t[2] <= 1.0 }) return@mapNotNull null
                Calib3d.projectPoints(objectPoints,rvecs[index],tvecs[index],camera,dist,reprojected)
                val q=reprojected.toArray()
                val error=sqrt(observed.indices.sumOf { (q[it].x-observed[it].x).pow(2)+(q[it].y-observed[it].y).pow(2) }/observed.size)
                if (!error.isFinite() || error > maxError) return@mapNotNull null
                Calib3d.projectPoints(drawing,rvecs[index],tvecs[index],camera,dist,rendered)
                val p=rendered.toArray().flatMap { listOf(it.x.toFloat(),it.y.toFloat()) }.toFloatArray()
                if (p.any { !it.isFinite() || abs(it) > max(model.width,model.height)*20f }) return@mapNotNull null
                PoseResult(r,t,error,p)
            }
        } catch (_: CvException) { return emptyList() }
        finally {
            objectPoints.release(); imagePoints.release(); camera.release(); dist.release(); rotation.release()
            reprojected.release(); rendered.release(); drawing.release(); rvecs.forEach { it.release() }; tvecs.forEach { it.release() }
        }
    }

    private fun translationDistance(a: PoseResult,b: PoseResult)=sqrt((0..2).sumOf { (a.tvec[it]-b.tvec[it]).pow(2) })
    private fun rotationDistance(a: DoubleArray,b: DoubleArray): Double {
        fun quaternion(v: DoubleArray): DoubleArray {
            val theta=sqrt(v.sumOf { it*it }); val scale=if(theta<1e-12) 0.5 else sin(theta/2)/theta
            return doubleArrayOf(cos(theta/2),v[0]*scale,v[1]*scale,v[2]*scale)
        }
        val qa=quaternion(a); val qb=quaternion(b)
        return 2*acos(abs((0..3).sumOf { qa[it]*qb[it] }).coerceIn(0.0,1.0))
    }
}
