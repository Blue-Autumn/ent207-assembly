package com.example.arassemblydemo

import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector

/** Only cameras actually exposed by CameraX are candidates. Scores are metadata estimates. */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
object LensChoice {
    data class Plan(val selector: CameraSelector, val cameraId: String, val isWide: Boolean, val report: String)
    fun choose(infos: List<CameraInfo>, requestWide: Boolean): Plan {
        val backs = CameraSelector.DEFAULT_BACK_CAMERA.filter(infos)
        require(backs.isNotEmpty()) { "No back camera available" }
        val baseline = backs.first()
        fun id(info: CameraInfo) = Camera2CameraInfo.from(info).cameraId
        fun score(info: CameraInfo): Double? {
            val c = Camera2CameraInfo.from(info)
            val f = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.singleOrNull()?.toDouble() ?: return null
            val sensor = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
            val pixel = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return null
            val active = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
            if (!f.isFinite() || f <= 0 || sensor.width <= 0 || pixel.width <= 0 || active.width() <= 0) return null
            return sensor.width.toDouble() * active.width() / pixel.width / f
        }
        val defaultScore = score(baseline)
        val widest = backs.mapNotNull { info -> score(info)?.let { info to it } }.maxByOrNull { it.second }
        val selected = if (requestWide && defaultScore != null && widest != null && widest.second > defaultScore * 1.05) widest.first else baseline
        val selectedId = id(selected)
        val isWide = selectedId != id(baseline)
        val report = "requestedWide=$requestWide selected=$selectedId wide=$isWide default=${id(baseline)} candidates=" +
            backs.joinToString { "${id(it)}:activeSensorWidth/f=${score(it)}" }
        val selector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter { candidates -> candidates.filter { id(it) == selectedId } }.build()
        return Plan(selector, selectedId, isWide, report)
    }
}
