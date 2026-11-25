package com.example.arassemblydemo

import org.junit.Assert.*
import org.junit.Test

class CameraFramingModeTest {
    @Test fun separateWideHasIntermediateCenteredCrop() {
        assertEquals(1.3f,CameraFramingMode.WIDE_CLOSE.resolve(true,1f,8f).ratio,.001f)
        assertEquals(1f,CameraFramingMode.WIDE.resolve(true,1f,8f).ratio,.001f)
    }
    @Test fun logicalWideNeverCrossesIntoMainFieldOfView() {
        assertEquals(.65f,CameraFramingMode.WIDE_CLOSE.resolve(false,.5f,8f).ratio,.001f)
        assertTrue(CameraFramingMode.WIDE_CLOSE.resolve(false,.9f,8f).ratio<1)
    }
    @Test fun noWideHardwareNeverClaimsWide() {
        for(mode in CameraFramingMode.entries) assertEquals("主摄",mode.resolve(false,1f,8f).label)
    }
    @Test fun limitedZoomAvoidsFakeIntermediateLabel() {
        assertEquals("广角",CameraFramingMode.WIDE_CLOSE.resolve(true,1f,1f).label)
    }
    @Test fun badMetadataStillHasValidRatio() {
        assertEquals(1f,CameraFramingMode.WIDE_CLOSE.resolve(false,Float.NaN,Float.NaN).ratio,.001f)
        assertEquals(CameraFramingMode.MAIN,CameraFramingMode.WIDE.next())
    }
}
