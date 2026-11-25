package com.example.arassemblydemo

import android.content.Context
import org.json.JSONObject
import org.opencv.core.Point3
import kotlin.math.*

/** Geometry is read from the SAME asset used by tools/board/generate_board.py. */
class BoardLayout(val id: String, val markerLengthMm: Double, val scale: Double,
    val corners: Map<Int, List<Point3>>) {
    companion object {
        fun load(context: Context, rulerMeasuredMm: Double = 100.0): BoardLayout =
            parse(context.assets.open("board-v1.json").bufferedReader().use { it.readText() }, rulerMeasuredMm)

        fun parse(text: String, rulerMeasuredMm: Double = 100.0): BoardLayout {
            val json = JSONObject(text)
            require(json.getInt("schema") == 1 && json.getString("units") == "mm")
            require(json.getString("dictionary") == "DICT_4X4_50")
            require(rulerMeasuredMm.isFinite() && rulerMeasuredMm in 80.0..120.0)
            val ruler = json.getDouble("ruler_mm")
            require(ruler == 100.0)
            val scale = rulerMeasuredMm / ruler
            val length = json.getDouble("marker_length_mm") * scale
            require(length.isFinite() && length > 0)
            val result = linkedMapOf<Int, List<Point3>>()
            val markers = json.getJSONArray("markers")
            require(markers.length() == 4)
            for (i in 0 until markers.length()) {
                val marker = markers.getJSONObject(i)
                val id = marker.getInt("id")
                require(id in 0..3 && id !in result)
                val array = marker.getJSONArray("corners_mm")
                require(array.length() == 4)
                val points = (0..3).map { j ->
                    val p = array.getJSONArray(j)
                    require(p.length() == 3)
                    Point3(p.getDouble(0)*scale, p.getDouble(1)*scale, p.getDouble(2)*scale)
                }
                require(points.all { it.x.isFinite() && it.y.isFinite() && it.z == 0.0 })
                for (j in 0..3) {
                    val a=points[j]; val b=points[(j+1)%4]; val c=points[(j+2)%4]
                    val dx=b.x-a.x; val dy=b.y-a.y
                    val ex=c.x-b.x; val ey=c.y-b.y
                    require(abs(hypot(dx,dy)-length) < 1e-5)
                    require(abs(dx*ex+dy*ey) < 1e-5 && dx*ey-dy*ex < 0)
                }
                result[id] = points
            }
            return BoardLayout(json.getString("board_id"), length, scale, result)
        }
    }
}
