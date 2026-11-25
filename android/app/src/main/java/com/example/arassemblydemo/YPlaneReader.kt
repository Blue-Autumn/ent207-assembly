package com.example.arassemblydemo

import java.nio.ByteBuffer

/** Copies an unrotated crop from Y; respects buffer position, row padding and pixel stride. */
object YPlaneReader {
    fun copy(buffer: ByteBuffer, rowStride: Int, pixelStride: Int,
             left: Int, top: Int, width: Int, height: Int, output: ByteArray) {
        require(rowStride > 0 && pixelStride > 0 && left >= 0 && top >= 0 && width > 0 && height > 0)
        require(output.size >= width.toLong() * height)
        val source = buffer.duplicate()
        val base = source.position().toLong()
        val last = base + (top.toLong() + height - 1) * rowStride + (left.toLong() + width - 1) * pixelStride
        require(last < source.limit()) { "Y plane crop exceeds buffer limit" }
        for (y in 0 until height) {
            val start = (base + (top.toLong() + y) * rowStride + left.toLong() * pixelStride).toInt()
            if (pixelStride == 1) {
                source.position(start)
                source.get(output, y * width, width)
            } else {
                for (x in 0 until width) output[y * width + x] = source.get(start + x * pixelStride)
            }
        }
    }
}
