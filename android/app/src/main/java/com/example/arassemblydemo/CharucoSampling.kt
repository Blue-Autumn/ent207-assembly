package com.example.arassemblydemo

import android.content.Context
import androidx.camera.core.ImageProxy
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.objdetect.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.*

data class SamplingFrame(val points: FloatArray, val count: Int, val sharpness: Double, val message: String)

/** Single analyzer-thread owner; manual requests only, bounded files, no video/network. */
class CharucoSampling(private val context: Context, private val diagnostic: Boolean=false) : AutoCloseable {
    private val raw=context.assets.open("charuco-v1.json").use { it.readBytes() }
    private val config=JSONObject(String(raw,Charsets.UTF_8))
    val boardHash=sha(raw)
    private val board=CharucoBoard(Size(config.getJSONArray("squares").getDouble(0),config.getJSONArray("squares").getDouble(1)),config.getDouble("square_mm").toFloat(),config.getDouble("marker_mm").toFloat(),
        Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50)).apply { setLegacyPattern(config.getBoolean("legacy_pattern")) }
    private val detector=CharucoDetector(board)
    private val gray=Mat(); private val corners=Mat(); private val ids=Mat()
    private var pixels=ByteArray(0)
    private var session=UUID.randomUUID().toString()
    private var lastMessage=""
    private var lastRole=""
    private var lastMeasurement=""
    private var previousPoints=emptyMap<Int,Pair<Double,Double>>()
    private var lastSavedTimestamp=0L
    private val directory=File(context.filesDir,if(diagnostic) "board-diagnostic-v1" else "calibration-samples").apply { mkdirs() }
    private val limit=if(diagnostic) 16 else 100
    private var count=directory.listFiles()?.count { it.extension=="json" } ?: 0
    private val roleCounts=mutableMapOf<String,Int>().apply { directory.listFiles()?.filter {it.extension=="json"}?.forEach {file ->
        try {val r=JSONObject(file.readText()).getString("role");this[r]=(this[r] ?: 0)+1} catch(_: Exception) {}
    } }
    private var imageCount=directory.listFiles()?.count { it.extension=="png" } ?: 0

    fun process(image: ImageProxy, audit: JSONObject, approximate: CameraModel, role: String,
        horizontalMm: Double, verticalMm: Double, flatConfirmed: Boolean, save: Boolean, saveGray: Boolean,
        markers: List<Marker>, boardRulerMm: Double): SamplingFrame {
        val crop=image.cropRect; val size=crop.width()*crop.height()
        if(pixels.size!=size) pixels=ByteArray(size)
        val plane=image.planes[0]
        YPlaneReader.copy(plane.buffer,plane.rowStride,plane.pixelStride,crop.left,crop.top,crop.width(),crop.height(),pixels)
        gray.create(crop.height(),crop.width(),CvType.CV_8UC1);gray.put(0,0,pixels)
        corners.release();ids.release()
        if(role!="board_validation") detector.detectBoard(gray,corners,ids)
        val sharp=Mat();val mean=MatOfDouble();val std=MatOfDouble()
        val sharpness=try { Imgproc.Laplacian(gray,sharp,CvType.CV_64F);Core.meanStdDev(sharp,mean,std);std.toArray()[0].pow(2) }
            finally { sharp.release();mean.release();std.release() }
        val n=ids.total().toInt()
        val points=FloatArray(n*2); val idArray=IntArray(n)
        if(n>0) {corners.get(0,0,points);ids.get(0,0,idArray)}
        val progress=if(diagnostic) "装配板诊断 · 可见 ${markers.size} 枚" else "训练${roleCounts["train"] ?: 0} · 独立${roleCounts["validation"] ?: 0} · 四标记${roleCounts["board_validation"] ?: 0}"
        var message="已存 $count / 上限$limit · ${if(role=="board_validation") "四标记验证" else "角点 $n/28"} · 清晰度 %.0f".format(sharpness)
        if(save) {
            val measurement="$horizontalMm/$verticalMm/$flatConfirmed/$boardRulerMm"
            if(role!=lastRole || measurement!=lastMeasurement) {session=UUID.randomUUID().toString();previousPoints=emptyMap();lastRole=role;lastMeasurement=measurement}
            val current=if(role=="board_validation") markers.flatMap { m -> (0..3).map { j -> m.id*4+j to (m.points[2*j].toDouble() to m.points[2*j+1].toDouble()) } }.toMap()
                else idArray.indices.associate { idArray[it] to (points[it*2].toDouble() to points[it*2+1].toDouble()) }
            val common=current.keys.intersect(previousPoints.keys)
            val movement=if(common.size>=8) sqrt(common.sumOf { id -> val a=current.getValue(id);val b=previousPoints.getValue(id);(a.first-b.first).pow(2)+(a.second-b.second).pow(2) }/common.size) else Double.POSITIVE_INFINITY
            val reason=when {
                count>=limit -> "已达${limit}帧上限，请导出后创建下一批"
                diagnostic && (!validMeasurements(horizontalMm,horizontalMm,flatConfirmed) || !saveGray) -> "请确认原板100mm尺、平面及保存灰度授权"
                !diagnostic && !validMeasurements(horizontalMm,verticalMm,flatConfirmed) -> "先实测水平/垂直100mm尺并确认平面（差异≤1%）"
                audit.getString("camera_signature").let { JSONObject(it).getString("camera_id") }!="4" -> "本轮只采广角ID4；请切广角"
                !audit.getBoolean("matched_capture_result") -> "没有精确匹配的帧元数据，请重试"
                audit.getJSONObject("capture").optInt("af_mode",-1)!=0 || abs(audit.getJSONObject("capture").optDouble("focus_diopters",Double.NaN)-2.0).let {!it.isFinite() || it>0.02} -> "等待固定对焦生效（AF关闭、2屈光度）"
                image.imageInfo.timestamp-lastSavedTimestamp<1_500_000_000L -> "采样太密，换角度再采"
                role=="board_validation" && (markers.size<3 || markers.map { it.id }.distinct().size!=markers.size || markers.any { it.id !in 0..3 }) -> "验证需至少3个唯一的装配板ID0–3"
                role!="board_validation" && (n<12 || board.checkCharucoCornersCollinear(ids)) -> "需至少12个非共线ChArUco角点"
                sharpness<40 -> "清晰度不足，请稳住手机/改善反光"
                movement<12 -> "与上一帧接近，请改变角度、距离或画面位置"
                else -> null
            }
            if(reason!=null) message="未保存：$reason"
            else {
                val sample=JSONObject().put("schema",1).put("sample_id",UUID.randomUUID().toString()).put("session",session)
                    .put("role",role).put("board_sha256",boardHash).put("board_config",config)
                    .put("screen",JSONObject().put("horizontal_ruler_mm",horizontalMm).put("flat_confirmed",flatConfirmed).apply {
                        if(!diagnostic) put("vertical_ruler_mm",verticalMm) else put("measurement_kind","original_board_horizontal_ruler_only")
                    })
                    .put("audit",audit).put("sharpness_laplacian_variance",sharpness)
                    .put("charuco_ids",JSONArray(idArray.toList())).put("charuco_points",JSONArray((0 until n).map { listOf(points[it*2],points[it*2+1]) }))
                    .put("markers",JSONArray(markers.map { JSONObject().put("id",it.id).put("points",JSONArray(it.points.toList())) }))
                    .put("assembly_board_sha256",sha(context.assets.open("board-v1.json").use { it.readBytes() }))
                    .put("assembly_ruler_mm",boardRulerMm)
                    .put("approximate",JSONObject().put("fx",approximate.fx).put("fy",approximate.fy).put("cx",approximate.cx).put("cy",approximate.cy).put("D",JSONArray(approximate.distortion.toList())))
                val name=sample.getString("sample_id")
                if(diagnostic) sample.put("diagnostic_dataset","board-diagnostic-v1").put("detector","OpenCV-4.13.0-default-NONE")
                    .put("assembly_board_config",JSONObject(context.assets.open("board-v1.json").bufferedReader().use {it.readText()}))
                    .put("app_version",context.packageManager.getPackageInfo(context.packageName,0).versionName)
                if(saveGray && imageCount<(if(diagnostic) 16 else 8)) {
                    val file=File(directory,"$name.png")
                    check(Imgcodecs.imwrite(file.path,gray))
                    sample.put("gray_file",file.name).put("gray_sha256",sha(file.readBytes())).put("gray_user_opt_in",true)
                    imageCount++
                }
                File(directory,"$name.json").writeText(sample.toString(2))
                count++;roleCounts[role]=(roleCounts[role] ?: 0)+1;previousPoints=current;lastSavedTimestamp=image.imageInfo.timestamp
                message="已保存 $count 帧 · $role · 请换视角"
            }
        }
        if(save) lastMessage=message
        return SamplingFrame(points,n,sharpness,if(save) message else "$progress\n$message\n$lastMessage")
    }
    override fun close() {gray.release();corners.release();ids.release();detector.clear()}
    companion object {
        fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun validMeasurements(h: Double,v: Double,flat: Boolean)=flat && h.isFinite() && v.isFinite() && h in 50.0..150.0 && v in 50.0..150.0 && abs(h-v)/((h+v)/2)<=0.01
    }
}
