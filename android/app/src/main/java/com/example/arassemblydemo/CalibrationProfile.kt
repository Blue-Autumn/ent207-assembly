package com.example.arassemblydemo

import android.content.Context
import org.json.JSONObject
import java.io.File

data class ModelSelection(val model: CameraModel, val status: String, val reason: String)

/** Formal profiles and explicit, per-entry controlled trials remain separate. Neither grants physical validation. */
object CalibrationProfile {
    fun select(approx: CameraModel, audit: JSONObject, profile: JSONObject?, enabled: Boolean, controlledTrial: Boolean=false): ModelSelection {
        fun fallback(reason: String)=ModelSelection(approx,"INVALID_OR_MISMATCHED",reason)
        if(!enabled) return ModelSelection(approx,"APPROXIMATE","旧近似参数")
        if(profile==null) return fallback("未导入经验证参数，回退近似")
        return try {
            if(controlledTrial) {
                require(profile.getInt("schema")==2 && profile.getString("status")=="CONTROLLED_CANDIDATE")
                require(!profile.getBoolean("activation_allowed") && profile.getBoolean("trial_allowed"))
                require(profile.getString("detector")=="opencv-4.13.0-SUBPIX-defaults-v1")
                val validation=profile.getJSONObject("paired_validation")
                require(validation.getInt("sample_count")>=12 && validation.getInt("heldout_points")>=100)
                require(validation.getInt("failures")==0 && validation.getBoolean("native_agrees"))
                require(listOf("overall_p95","edge_p95","three_marker_p95").all {validation.getDouble(it).let {v -> v.isFinite() && v in 0.0..5.0}})
            } else {
            require(profile.getInt("schema")==1)
            require(profile.getString("status")=="SCREEN_CALIBRATED")
            require(profile.getBoolean("activation_allowed"))
            require(profile.getString("distortion_model")=="opencv_brown_conrady_5")
            require(profile.getJSONObject("validation").getBoolean("passed"))
            require(profile.getJSONObject("validation").getInt("train_views")>=24)
            require(profile.getJSONObject("validation").getInt("holdout_views")>=10)
            require(profile.getJSONObject("validation").getInt("board_views")>=8)
            }
            require(profile.getString("distortion_model")=="opencv_brown_conrady_5")
            require(audit.getBoolean("matched_capture_result")) { "当前帧缺少精确匹配的相机元数据" }
            require(profile.getString("camera_signature")==audit.getString("camera_signature")) { "镜头配置签名不同" }
            val focus=audit.getJSONObject("capture").opt("focus_diopters")
            val range=profile.optJSONArray("focus_range")
            if(range==null) require(focus==null || focus=="UNKNOWN")
            else { require(focus is Number);require(focus.toDouble() in (range.getDouble(0)-0.02)..(range.getDouble(1)+0.02)) { "焦点不匹配：$focus" } }
            val k=profile.getJSONArray("K"); val d=profile.getJSONArray("D")
            require(k.length()==3 && (0..2).all { k.getJSONArray(it).length()==3 } && d.length()==5)
            require((0..2).all { i -> (0..2).all { j -> k.getJSONArray(i).getDouble(j).isFinite() } })
            fun v(i: Int,j: Int)=k.getJSONArray(i).getDouble(j)
            require(v(0,1)==0.0 && v(1,0)==0.0 && v(2,0)==0.0 && v(2,1)==0.0 && v(2,2)==1.0)
            val distortion=DoubleArray(5) { d.getDouble(it) }
            require(distortion.all { it.isFinite() && kotlin.math.abs(it)<=3.0 })
            require(v(0,0) in 0.1*approx.width..5.0*approx.width && v(1,1) in 0.1*approx.height..5.0*approx.height)
            require(v(0,2) in 0.0..approx.width.toDouble() && v(1,2) in 0.0..approx.height.toDouble())
            val status=if(controlledTrial) "CONTROLLED_CANDIDATE" else "SCREEN_CALIBRATED"
            ModelSelection(approx.copy(fx=v(0,0),fy=v(1,1),cx=v(0,2),cy=v(1,2),distortion=distortion,
                source="$status ${profile.getString("profile_id")}",calibrated=true),status,
                if(controlledTrial) "受控试用 · SUBPIX · 未做实体精度认证" else "屏幕标定 · 非实体精度认证")
        } catch(e: Exception) { fallback("参数/当前帧配置不匹配，回退近似：${e.message ?: e.javaClass.simpleName}") }
    }
    fun read(context: Context): JSONObject? = try {
        File(context.filesDir,"calibration-profile.json").takeIf { it.exists() }?.let { JSONObject(it.readText()) }
    } catch(_: Exception) { null }
}
