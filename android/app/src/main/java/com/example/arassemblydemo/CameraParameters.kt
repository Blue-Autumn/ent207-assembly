package com.example.arassemblydemo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import android.util.SizeF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.tan

/** Approximate pinhole only: static metadata does not identify the actual capture crop/mode. */
class CameraParameters private constructor(
    val cameraId: String,
    private val physical: SizeF?,
    private val pixels: Size?,
    private val active: Rect?,
    private val focalMm: Double?
) {
    fun forImage(width: Int, height: Int, cropLeft: Int, cropTop: Int,
                 cropWidth: Int, cropHeight: Int): CameraModel {
        require(width > 0 && height > 0 && cropWidth > 0 && cropHeight > 0)
        require(cropLeft >= 0 && cropTop >= 0 && cropLeft + cropWidth <= width && cropTop + cropHeight <= height)
        val p = physical
        val n = pixels
        val a = active
        val f = focalMm
        val usable = p != null && n != null && a != null && f != null &&
            p.width.isFinite() && p.height.isFinite() && p.width > 0 && p.height > 0 &&
            n.width > 0 && n.height > 0 && a.width() > 0 && a.height() > 0 &&
            a.left >= 0 && a.top >= 0 && a.right <= n.width && a.bottom <= n.height
        val fx: Double
        val fy: Double
        val source: String
        if (usable) {
            // Approximate optical center at active-array center, no sensor skew. Assume unit zoom
            // and centered sensor crop to stream aspect ratio before the ImageProxy viewport crop.
            val scale = max(width.toDouble() / a!!.width(), height.toDouble() / a.height())
            fx = f!! * n!!.width / p!!.width * scale
            fy = f * n.height / p.height * scale
            source = "approx camera=$cameraId focal/sensor; centered crop, zoom=1 assumed; D=0"
        } else {
            fx = width / (2.0 * tan(Math.toRadians(60.0) / 2.0))
            fy = fx
            source = "approx camera=$cameraId assumed horizontal FOV=60deg; D=0"
        }
        return CameraModel(cropWidth, cropHeight, fx, fy,
            width / 2.0 - cropLeft, height / 2.0 - cropTop,
            DoubleArray(5), source, false)
    }

    companion object {
        fun fromCameraId(context: Context, id: String): CameraParameters {
            val c = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(id)
            val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            // Multiple advertised focal lengths cannot tell us which lens the current frame used.
            val focal = focals?.singleOrNull()?.toDouble()?.takeIf { it.isFinite() && it > 0.0 }
            return CameraParameters(id, c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE),
                c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE),
                c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE), focal)
        }

        fun saveReport(context: Context): File {
            val manager = context.getSystemService(CameraManager::class.java)
            val list = JSONArray()
            for (id in manager.cameraIdList) {
                val entry = JSONObject().put("cameraId", id)
                try {
                    val c = manager.getCameraCharacteristics(id)
                    fun put(name: String, value: Any?) {
                        entry.put(name, when (value) {
                            null -> JSONObject.NULL
                            is FloatArray -> JSONArray(value.toList())
                            is IntArray -> JSONArray(value.toList())
                            is Rect -> JSONArray(listOf(value.left, value.top, value.right, value.bottom))
                            is Size -> JSONArray(listOf(value.width, value.height))
                            is SizeF -> JSONArray(listOf(value.width, value.height))
                            else -> value
                        })
                    }
                    put("lensFacing", c.get(CameraCharacteristics.LENS_FACING))
                    put("hardwareLevel", c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
                    put("sensorOrientation", c.get(CameraCharacteristics.SENSOR_ORIENTATION))
                    put("intrinsicCalibration_fx_fy_cx_cy_skew", c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION))
                    put("activeArray_ltrb", c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE))
                    put("preCorrectionActiveArray_ltrb", c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE))
                    put("pixelArray_wh", c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE))
                    put("physicalSizeMm_wh", c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE))
                    put("availableFocalLengthsMm", c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS))
                    if (Build.VERSION.SDK_INT >= 28) {
                        put("distortion_k1_k2_k3_p1_p2", c.get(CameraCharacteristics.LENS_DISTORTION))
                        put("distortionCorrectionAvailableModes", c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES))
                        put("physicalCameraIds", JSONArray(c.physicalCameraIds.toList()))
                    }
                } catch (e: Exception) {
                    entry.put("readError", "${e.javaClass.simpleName}: ${e.message}")
                }
                list.put(entry)
            }
            val report = JSONObject().put("schemaVersion", 1).put("device", Build.MODEL)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("cameraPermissionGranted", context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                .put("captureResultVerified", false).put("calibrated", false)
                .put("note", "Static characteristics only. Null may be permission-restricted. No current crop, focal length, distortion mode or physical lens verified. Raw Android distortion order is NOT OpenCV order.")
                .put("cameras", list)
            return File(context.filesDir, "camera-capabilities.json").also { it.writeText(report.toString(2)) }
        }
    }
}
