package com.example.arassemblydemo

/** Fixed centered framing, never a target-tracking crop. Ratios apply to all CameraX streams. */
enum class CameraFramingMode(val wide: Boolean) {
    MAIN(false), WIDE_CLOSE(true), WIDE(true);
    fun next() = entries[(ordinal + 1) % entries.size]
    data class Applied(val ratio: Float, val label: String)
    fun resolve(separateWide: Boolean, min: Float, max: Float): Applied {
        val lo = min.takeIf { it.isFinite() && it > 0 } ?: 1f
        val hi = max.takeIf { it.isFinite() && it >= lo } ?: lo
        val wideAvailable = separateWide || lo < .95f
        if (!wide || !wideAvailable) return Applied(1f.coerceIn(lo, hi), "主摄")
        val full = if (separateWide) 1f.coerceIn(lo, hi) else lo
        // Keep the intermediate logical-camera view wider than the main camera.
        val near = if (separateWide) (full * 1.3f).coerceAtMost(hi) else (full * 1.3f).coerceAtMost(.95f).coerceIn(lo, hi)
        return if (this == WIDE_CLOSE && near > full * 1.02f) Applied(near, "广角·近") else Applied(full, "广角")
    }
}
