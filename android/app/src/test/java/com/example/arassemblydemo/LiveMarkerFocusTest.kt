package com.example.arassemblydemo

import org.junit.Assert.*
import org.junit.Test

class LiveMarkerFocusTest {
    @Test fun samePartKeepsCurrentTagDespiteDetectorOrderChanges() {
        val focus = LiveMarkerFocus()
        assertEquals(6, focus.observe(linkedSetOf(6, 7), 100))
        assertEquals(6, focus.observe(linkedSetOf(7, 6, 4), 200))
        assertEquals(7, focus.observe(setOf(7), 300))
    }

    @Test fun staleDotExpiresEvenWithoutMoreFrames() {
        val focus = LiveMarkerFocus()
        focus.observe(setOf(4), 100)
        assertNull(focus.observe(emptySet(), 300))
        assertEquals(4, focus.marker)
        assertFalse(focus.expire(599))
        assertTrue(focus.expire(600))
        assertNull(focus.marker)
        assertFalse(focus.expire(1000))
    }

    @Test fun edgeTagCanGuideFramingWithoutUnlockingPickup() {
        assertFalse(finderFramed(listOf(60f to 400f), 1000, 1000))
        assertFalse(finderFramed(listOf(60f to 200f, 70f to 600f), 1000, 1000))
        assertTrue(finderFramed(listOf(520f to 400f), 1000, 1000))
        assertTrue(finderFramed(listOf(70f to 30f, 910f to 950f), 1000, 1000))
    }

    @Test fun clearSeparatesPartAndCameraSessions() {
        val focus = LiveMarkerFocus()
        focus.observe(setOf(127), 0)
        focus.clear()
        assertNull(focus.marker)
        assertEquals(4, focus.observe(setOf(127, 4), 20))
    }

    @Test fun rotatedQuadPreservesCenter() {
        val center = markerQuadCenter(floatArrayOf(10f, 0f, 20f, 10f, 10f, 20f, 0f, 10f))!!
        assertEquals(10f, center.first, 0.001f)
        assertEquals(10f, center.second, 0.001f)
    }

    @Test fun perspectiveCenterUsesDiagonalIntersectionRatherThanCornerAverage() {
        val center = markerQuadCenter(floatArrayOf(0f, 0f, 8f, 0f, 6f, 4f, 2f, 4f))!!
        assertEquals(4f, center.first, 0.001f)
        assertEquals(8f / 3f, center.second, 0.001f)
    }

    @Test fun brokenGeometryNeverProducesVisiblePoint() {
        assertNull(markerQuadCenter(floatArrayOf(0f, 0f, 1f, 0f, 2f, 0f, 3f, 0f)))
        assertNull(markerQuadCenter(floatArrayOf(Float.NaN, 0f, 8f, 0f, 6f, 4f, 2f, 4f)))
        assertNull(markerQuadCenter(floatArrayOf(1f, 2f)))
    }
}
