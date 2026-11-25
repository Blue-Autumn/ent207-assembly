package com.example.arassemblydemo

import android.graphics.Bitmap
import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import org.json.JSONArray
import org.json.JSONObject

/** A user-requested cropped color frame; its markers are in this bitmap's upright pixel coordinates. */
data class JointPhotoFrame(
    val token: String,
    val bitmap: Bitmap,
    val markers: List<Marker>,
    val cameraId: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    val rotationDegrees: Int,
    val timestampNs: Long,
    val capturedElapsedRealtimeMs: Long
) {
    // The receiving Activity owns the bitmap and must recycle it after display/export is complete.
    fun metadata(): JSONObject = JSONObject()
        .put("token", token).put("width", bitmap.width).put("height", bitmap.height)
        .put("cameraId", cameraId).put("sourceWidth", sourceWidth).put("sourceHeight", sourceHeight)
        .put("cropLeft", cropLeft).put("cropTop", cropTop).put("cropWidth", cropWidth).put("cropHeight", cropHeight)
        .put("rotationDegrees", rotationDegrees).put("timestampNs", timestampNs.toString())
        .put("capturedElapsedRealtimeMs", capturedElapsedRealtimeMs)
        .put("dictionary", "DICT_4X4_250").put("coordinates", "upright-cropped-pixel-centers")
        .put("mirrored", false).put("sameFrame", true)
        .put("markers", JSONArray().apply { markers.forEach { marker ->
            put(JSONObject().put("id", marker.id).put("corners", JSONArray(marker.points.map { it.toDouble() })))
        } })

    companion object {
        /** Called on analysis executor immediately after detection, before closing this exact proxy. */
        fun fromProxy(token: String, frame: ImageProxy, detection: Detection, cameraId: String,
                      elapsedRealtimeMs: Long): JointPhotoFrame {
            require(frame.format == ImageFormat.YUV_420_888)
            val crop = frame.cropRect
            val width = crop.width(); val height = crop.height()
            val rotation = frame.imageInfo.rotationDegrees
            // CameraX converts the complete YUV buffer using its native converter.
            // Crop and rotate once, with the same geometry as the detected marker corners.
            val raw = frame.toBitmap()
            val matrix = android.graphics.Matrix().apply { setRotate(rotation.toFloat()) }
            val bitmap = try {
                Bitmap.createBitmap(raw, crop.left, crop.top, width, height, matrix, false)
            } catch (e: Exception) {
                raw.recycle()
                throw e
            }
            if (bitmap !== raw) raw.recycle()
            val transformed = detection.markers.map {
                Marker(it.id, JointPhotoPixels.rotatePoints(it.points, width, height, rotation))
            }
            return JointPhotoFrame(token, bitmap, transformed, cameraId, frame.width, frame.height,
                crop.left, crop.top, width, height, rotation, frame.imageInfo.timestamp, elapsedRealtimeMs)
        }
    }
}
