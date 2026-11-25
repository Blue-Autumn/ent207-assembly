package com.example.arassemblydemo

import kotlin.math.abs

/** UI-thread state. Identity stays on the same visible tag rather than detector list order. */
internal class LiveMarkerFocus(private val lostAfterMs: Long = FINDER_HOLD_MS) {
    var marker: Int? = null
        private set
    private var lastSeenAt: Long? = null

    fun observe(visibleIds: Set<Int>, nowMs: Long): Int? {
        val chosen = marker?.takeIf { it in visibleIds } ?: visibleIds.minOrNull() ?: return null
        marker = chosen
        lastSeenAt = nowMs
        return chosen
    }

    /** Also called without camera frames, so a stalled stream cannot leave a permanent dot. */
    fun expire(nowMs: Long): Boolean {
        val previous = lastSeenAt ?: return false
        if (nowMs - previous < lostAfterMs) return false
        clear()
        return true
    }

    fun clear() {
        marker = null
        lastSeenAt = null
    }
}

internal const val FINDER_HOLD_MS = 500L

/** A tag clipped against the edge can identify the target without showing the user where
 * to reach. Keep the preview open until a target tag, or the group of tags, is in frame. */
internal fun finderFramed(centers: Collection<Pair<Float, Float>>, width: Int, height: Int): Boolean {
    if (centers.isEmpty() || width <= 0 || height <= 0) return false
    fun inside(x: Float, y: Float) = x in width * .12f..width * .88f && y in height * .10f..height * .90f
    if (centers.any { (x, y) -> inside(x, y) }) return true
    return inside(centers.map { it.first }.average().toFloat(), centers.map { it.second }.average().toFloat())
}

/** Centre of a perspective quadrilateral, after CameraX has mapped its corners to the preview. */
internal fun markerQuadCenter(points: FloatArray): Pair<Float, Float>? {
    if (points.size != 8 || points.any { !it.isFinite() }) return null
    val ax = points[0].toDouble(); val ay = points[1].toDouble()
    val ux = points[4] - ax; val uy = points[5] - ay
    val bx = points[2].toDouble(); val by = points[3].toDouble()
    val vx = points[6] - bx; val vy = points[7] - by
    val determinant = ux * vy - uy * vx
    if (abs(determinant) < 1e-6) return null
    val dx = bx - ax; val dy = by - ay
    val t = (dx * vy - dy * vx) / determinant
    val s = (dx * uy - dy * ux) / determinant
    if (t !in 0.0..1.0 || s !in 0.0..1.0) return null
    return (ax + t * ux).toFloat() to (ay + t * uy).toFloat()
}
