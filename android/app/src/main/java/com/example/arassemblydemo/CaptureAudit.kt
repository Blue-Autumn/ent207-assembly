package com.example.arassemblydemo

import android.graphics.Rect
import android.hardware.camera2.*
import android.os.Build
import androidx.camera.core.ImageProxy
import org.json.JSONArray
import org.json.JSONObject

/** Per-binding exact SENSOR_TIMESTAMP join, never silently attach the latest result to another image. */
class CaptureAudit : CameraCaptureSession.CaptureCallback() {
    private val frames = linkedMapOf<Long, JSONObject>()
    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        val state=JSONObject()
        fun put(key: String, value: Any?) { state.put(key,when(value) {
            null -> "UNKNOWN"
            is Rect -> JSONArray(listOf(value.left,value.top,value.right,value.bottom))
            is FloatArray -> JSONArray(value.toList())
            else -> value
        }) }
        put("sensor_timestamp_ns",timestamp)
        put("crop_region",result.get(CaptureResult.SCALER_CROP_REGION))
        put("focal_mm",result.get(CaptureResult.LENS_FOCAL_LENGTH))
        put("af_mode",result.get(CaptureResult.CONTROL_AF_MODE))
        put("af_state",result.get(CaptureResult.CONTROL_AF_STATE))
        put("focus_diopters",result.get(CaptureResult.LENS_FOCUS_DISTANCE))
        put("video_stabilization",result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE))
        put("optical_stabilization",result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE))
        put("exposure_ns",result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
        put("sensitivity_iso",result.get(CaptureResult.SENSOR_SENSITIVITY))
        put("intrinsic_android",result.get(CaptureResult.LENS_INTRINSIC_CALIBRATION))
        if(Build.VERSION.SDK_INT>=28) {
            put("distortion_mode",result.get(CaptureResult.DISTORTION_CORRECTION_MODE))
            put("distortion_android_k1_k2_k3_p1_p2",result.get(CaptureResult.LENS_DISTORTION))
        }
        if(Build.VERSION.SDK_INT>=29) put("physical_camera_id",result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID))
        if(Build.VERSION.SDK_INT>=30) put("zoom_ratio",result.get(CaptureResult.CONTROL_ZOOM_RATIO))
        synchronized(frames) { frames[timestamp]=state;while(frames.size>32) frames.remove(frames.keys.first()) }
    }
    fun snapshot(image: ImageProxy, cameraId: String): JSONObject {
        val state=synchronized(frames) { frames[image.imageInfo.timestamp]?.let { JSONObject(it.toString()) } }
            ?: JSONObject().put("sensor_timestamp_ns",image.imageInfo.timestamp)
        val matched=state.has("crop_region")
        val crop=image.cropRect
        val signature=JSONObject().put("pipeline","CameraX-1.6.2-Y-crop-unrotated-FIT_CENTER-v043")
            .put("device",Build.FINGERPRINT).put("camera_id",cameraId)
            .put("stream",JSONArray(listOf(image.width,image.height)))
            .put("crop",JSONArray(listOf(crop.left,crop.top,crop.width(),crop.height())))
            .put("rotation",image.imageInfo.rotationDegrees)
        for(key in listOf("crop_region","focal_mm","af_mode","video_stabilization","optical_stabilization",
            "distortion_mode","physical_camera_id","zoom_ratio")) signature.put(key,state.opt(key) ?: "UNKNOWN")
        return JSONObject().put("matched_capture_result",matched).put("camera_signature",signature.toString())
            .put("capture",state).put("image_timestamp_ns",image.imageInfo.timestamp)
    }
}
