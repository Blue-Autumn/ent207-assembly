package com.example.arassemblydemo

import android.os.SystemClock
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import kotlin.math.*

/** Intrinsics in cropped, UNROTATED analysis pixels; translations are millimetres. */
data class CameraModel(val width: Int, val height: Int, val fx: Double, val fy: Double,
    val cx: Double, val cy: Double, val distortion: DoubleArray = DoubleArray(5),
    val source: String = "approximate", val calibrated: Boolean = false)
data class PoseResult(val rvec: DoubleArray, val tvec: DoubleArray, val reprojectionError: Double,
    val projected: FloatArray, val state: String = "TRACKING", val reason: String = "")
data class RejectedMarker(val marker: Marker, val reason: String)
data class PoseFrame(val raw: List<Marker>, val accepted: List<Marker>, val rejected: List<RejectedMarker>,
    val pose: PoseResult?, val state: String, val reason: String, val confirmationCount: Int,
    val usedIds: List<Int> = emptyList(), val markerErrors: Map<Int, Double> = emptyMap())

/** Single analysis-thread owner. No smoothing or prediction; every rendered pose uses this frame. */
class PoseEstimator(val markerLengthMm: Double = 50.0, val cubeLengthMm: Double = 30.0,
    private val clockNowNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() }) {
    init { require(markerLengthMm.isFinite() && markerLengthMm > 0); require(cubeLengthMm.isFinite() && cubeLengthMm > 0) }
    private var previous: PoseResult? = null
    private var previousTime = 0L
    private var confirmed = 0
    fun reset() { previous = null; previousTime = 0L; confirmed = 0 }

    fun process(markers: List<Marker>, model: CameraModel, nowNanos: Long): PoseFrame {
        val accepted = ArrayList<Marker>()
        val rejected = ArrayList<RejectedMarker>()
        fun stopped(state: String, reason: String): PoseFrame {
            reset()
            return PoseFrame(markers, accepted, rejected, null, state, reason, 0)
        }
        val age = clockNowNanos() - nowNanos
        if (nowNanos <= 0 || age < 0 || age >= 400_000_000L || (previousTime != 0L && nowNanos <= previousTime))
            return stopped("STALE", "时间戳异常或帧已过期")
        if (previousTime != 0L && nowNanos - previousTime > 400_000_000L) reset()
        if (model.width <= 0 || model.height <= 0 || !listOf(model.fx,model.fy,model.cx,model.cy).all { it.isFinite() } ||
            model.fx <= 0 || model.fy <= 0 || !model.distortion.all { it.isFinite() } || model.distortion.size !in listOf(0,4,5,8,12,14))
            return stopped("INVALID_CAMERA", "相机参数无效")
        for (marker in markers) {
            val reason = if (marker.id !in 0..3) "ID不在允许的0–3中" else geometryReason(marker.points, model)
            if (reason == null) accepted.add(marker) else rejected.add(RejectedMarker(marker, reason))
        }
        // Count raw ID0 as well: a malformed duplicate must not silently resolve anchor ambiguity.
        if (markers.count { it.id == 0 } > 1) return stopped("AMBIGUOUS", "多个ID0，无法确定唯一锚点")
        val anchor = accepted.singleOrNull { it.id == 0 } ?: return stopped("NO_ANCHOR", "未找到有效ID0")
        val candidates = solve(anchor, model)
        if (candidates.isEmpty()) return stopped("REJECTED", "PnP无有效解（深度、投影或误差）")
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
        return PoseFrame(markers,accepted,rejected,if (confirmed >= 3) chosen else null,
            if (confirmed >= 3) "TRACKING" else "CONFIRMING",
            if (confirmed >= 3) "ID0位姿有效" else if (jumped) "大幅跳变，重新确认 1/3" else "连续确认 $confirmed/3",confirmed)
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

    private fun solve(marker: Marker, model: CameraModel): List<PoseResult> {
        val h=markerLengthMm/2
        val objectPoints=MatOfPoint3f(Point3(-h,h,0.0),Point3(h,h,0.0),Point3(h,-h,0.0),Point3(-h,-h,0.0))
        val imagePoints=MatOfPoint2f(*(0..3).map { Point(marker.points[it*2].toDouble(),marker.points[it*2+1].toDouble()) }.toTypedArray())
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
            Calib3d.solvePnPGeneric(objectPoints,imagePoints,camera,dist,rvecs,tvecs,false,Calib3d.SOLVEPNP_IPPE_SQUARE)
            return rvecs.indices.mapNotNull { index ->
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
                val error=sqrt((0..3).sumOf { (q[it].x-marker.points[it*2]).pow(2)+(q[it].y-marker.points[it*2+1]).pow(2) }/4)
                if (!error.isFinite() || error > 5.0) return@mapNotNull null
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
