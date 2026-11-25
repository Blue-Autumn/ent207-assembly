package com.example.arassemblydemo

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class YPlaneReaderTest {
    @Test fun cropWithRowPaddingAndNonzeroBufferPosition() {
        val source = ByteBuffer.wrap(byteArrayOf(99, 0,1,2,3,88,88, 4,5,6,7,88,88, 8,9,10,11))
        source.position(1)
        val result = ByteArray(4)
        YPlaneReader.copy(source,6,1,1,1,2,2,result)
        assertArrayEquals(byteArrayOf(5,6,9,10),result)
        assertEquals(1,source.position())
    }
    @Test fun nonUnitPixelStrideAndNoFinalRowPadding() {
        val source = ByteBuffer.wrap(byteArrayOf(0,99,1,99,2,99,88,88,3,99,4,99,5))
        val result = ByteArray(4)
        YPlaneReader.copy(source,8,2,1,0,2,2,result)
        assertArrayEquals(byteArrayOf(1,2,4,5),result)
    }
    @Test(expected=IllegalArgumentException::class) fun refusesTruncatedPlane() {
        YPlaneReader.copy(ByteBuffer.wrap(ByteArray(7)),4,1,0,0,4,2,ByteArray(8))
    }
}
