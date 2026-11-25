package com.example.arassemblydemo

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class JointPhotoPixelsTest {
    @Test fun everyRightAngleKeepsMarkerCornersOnTheSamePixel() {
        val width = 5; val height = 3
        val input = IntArray(width * height) { 100 + it }
        val points = floatArrayOf(0f, 0f, 4f, 0f, 4f, 2f, 0f, 2f, 1f, 1f)
        for (rotation in listOf(0, 90, 180, 270)) {
            val output = JointPhotoPixels.rotatePixels(input, width, height, rotation)
            val transformed = JointPhotoPixels.rotatePoints(points, width, height, rotation)
            for (i in points.indices step 2) {
                val before = input[points[i + 1].toInt() * width + points[i].toInt()]
                val after = output.argb[transformed[i + 1].toInt() * output.width + transformed[i].toInt()]
                assertEquals(before, after)
            }
            assertEquals(input.size, output.width * output.height)
        }
    }

    @Test fun fractionalCoordinatesRotateWithoutReorderingCorners() {
        val points = floatArrayOf(1.25f, 2.5f, 3.75f, 2.25f, 3.5f, 4.75f, 1.5f, 4.25f)
        val rotated = JointPhotoPixels.rotatePoints(points, 8, 6, 90)
        assertArrayEquals(floatArrayOf(2.5f, 1.25f, 2.75f, 3.75f, .25f, 3.5f, .75f, 1.5f), rotated, .0001f)
        val restored = JointPhotoPixels.rotatePoints(rotated, 6, 8, 270)
        assertArrayEquals(points, restored, .0001f)
    }

    @Test fun paddedPositionedInterleavedPlanesAndOddCropRetainColorAndBuffers() {
        // Positions are nonzero, Y has pixel padding and U/V represent two chroma columns.
        val y = ByteBuffer.allocate(70).apply { position(3) }
        val u = ByteBuffer.allocate(30).apply { position(2) }
        val v = ByteBuffer.allocate(30).apply { position(4) }
        for (row in 0..3) for (col in 0..3) y.put(3 + row * 12 + col * 2, 82.toByte())
        for (row in 0..1) {
            u.put(2 + row * 6, 90.toByte()); u.put(2 + row * 6 + 2, 240.toByte())
            v.put(4 + row * 6, 240.toByte()); v.put(4 + row * 6 + 2, 90.toByte())
        }
        val out = JointPhotoPixels.yuvCrop(listOf(
            JointPhotoPixels.Plane(y, 12, 2), JointPhotoPixels.Plane(u, 6, 2), JointPhotoPixels.Plane(v, 6, 2)
        ), 1, 1, 2, 2)
        // x=1 samples chroma column0 (red), x=2 samples column1 (blue).
        assertTrue((out[0] shr 16 and 255) > 240)
        assertTrue((out[0] and 255) < 10)
        assertTrue((out[1] and 255) > 240)
        assertTrue((out[1] shr 16 and 255) < 30)
        assertArrayEquals(intArrayOf(out[0], out[1]), out.sliceArray(2..3))
        assertEquals(3, y.position()); assertEquals(2, u.position()); assertEquals(4, v.position())
    }

    @Test fun neutralBlackWhiteUseLuminanceWithoutTint() {
        val y = ByteBuffer.wrap(byteArrayOf(16, 235.toByte(), 16, 235.toByte()))
        val uv = ByteBuffer.wrap(byteArrayOf(128.toByte()))
        val result = JointPhotoPixels.yuvCrop(listOf(
            JointPhotoPixels.Plane(y, 2, 1), JointPhotoPixels.Plane(uv, 1, 1), JointPhotoPixels.Plane(uv, 1, 1)
        ), 0, 0, 2, 2)
        assertEquals(0xff000000.toInt(), result[0]); assertEquals(0xffffffff.toInt(), result[1])
    }

    @Test(expected = IllegalArgumentException::class) fun outOfBoundsCropFailsBeforeReading() {
        val b = ByteBuffer.allocate(2)
        JointPhotoPixels.yuvCrop(List(3) { JointPhotoPixels.Plane(b, 4, 1) }, 0, 0, 4, 4)
    }

    @Test(expected = IllegalArgumentException::class) fun nonRightAngleDoesNotSilentlyMisalignPoints() {
        JointPhotoPixels.rotatePoints(floatArrayOf(1f, 1f), 10, 10, 45)
    }
}
