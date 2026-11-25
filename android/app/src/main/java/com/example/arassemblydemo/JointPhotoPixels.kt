package com.example.arassemblydemo

import java.nio.ByteBuffer

/** Pixel-only operations: cropped YUV and marker coordinates always undergo the same rotation. */
internal object JointPhotoPixels {
    data class Plane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)
    data class Pixels(val argb: IntArray, val width: Int, val height: Int)

    fun yuvCrop(planes: List<Plane>, left: Int, top: Int, width: Int, height: Int): IntArray {
        require(planes.size == 3 && left >= 0 && top >= 0 && width > 0 && height > 0)
        require(width.toLong() * height <= 16_000_000)
        val buffers = planes.map { it.buffer.duplicate() }
        val bases = buffers.map { it.position() }
        planes.forEachIndexed { i, plane ->
            require(plane.rowStride > 0 && plane.pixelStride > 0)
            val divisor = if (i == 0) 1 else 2
            val last = bases[i].toLong() + ((top.toLong() + height - 1) / divisor) * plane.rowStride +
                ((left.toLong() + width - 1) / divisor) * plane.pixelStride
            require(last < buffers[i].limit()) { "YUV plane does not contain requested crop" }
        }
        fun sample(plane: Int, x: Int, y: Int): Int {
            val p = planes[plane]
            return buffers[plane].get(bases[plane] + y * p.rowStride + x * p.pixelStride).toInt() and 255
        }
        return IntArray(width * height) { index ->
            val x = left + index % width
            val y = top + index / width
            val luminance = (sample(0, x, y) - 16).coerceAtLeast(0)
            val u = sample(1, x / 2, y / 2) - 128
            val v = sample(2, x / 2, y / 2) - 128
            val r = ((298 * luminance + 409 * v + 128) shr 8).coerceIn(0, 255)
            val g = ((298 * luminance - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
            val b = ((298 * luminance + 516 * u + 128) shr 8).coerceIn(0, 255)
            (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    fun rotatePixels(argb: IntArray, width: Int, height: Int, rotation: Int): Pixels {
        require(width > 0 && height > 0 && argb.size == width * height)
        require(rotation in setOf(0, 90, 180, 270))
        val outWidth = if (rotation == 90 || rotation == 270) height else width
        val outHeight = if (rotation == 90 || rotation == 270) width else height
        if (rotation == 0) return Pixels(argb, width, height)
        val output = IntArray(argb.size)
        for (y in 0 until height) for (x in 0 until width) {
            val (rx, ry) = when (rotation) {
                90 -> height - 1 - y to x
                180 -> width - 1 - x to height - 1 - y
                else -> y to width - 1 - x
            }
            output[ry * outWidth + rx] = argb[y * width + x]
        }
        return Pixels(output, outWidth, outHeight)
    }

    /** OpenCV pixel-center convention, preserving each marker's decoded corner ordering. */
    fun rotatePoints(points: FloatArray, width: Int, height: Int, rotation: Int): FloatArray {
        require(points.size % 2 == 0 && points.all { it.isFinite() })
        require(width > 0 && height > 0 && rotation in setOf(0, 90, 180, 270))
        return FloatArray(points.size).also { out ->
            for (i in points.indices step 2) {
                val x = points[i]
                val y = points[i + 1]
                val (rx, ry) = when (rotation) {
                    90 -> height - 1f - y to x
                    180 -> width - 1f - x to height - 1f - y
                    270 -> y to width - 1f - x
                    else -> x to y
                }
                out[i] = rx; out[i + 1] = ry
            }
        }
    }
}
