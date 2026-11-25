package com.example.arassemblydemo

import org.junit.Assert.*
import org.junit.Test

class MarkerLiveStatsTest {
    @Test fun rateCountsEmptySampleAndEachIdOnce() {
        val s=MarkerLiveStats(); s.add(0,mapOf(4 to 20.0,6 to 30.0),10.0); s.add(200,emptyMap(),11.0); s.add(400,mapOf(4 to 40.0),12.0)
        val v=s.snapshot(400); assertEquals(listOf(4),v.currentIds); assertEquals(3,v.sampleCount)
        assertEquals(2,v.rows[0].hits); assertEquals(30.0,v.rows[0].meanEdgePx!!,0.0); assertEquals(1,v.rows[1].hits)
    }
    @Test fun emptyNewFrameClearsCurrentButRetainsAge() {
        val s=MarkerLiveStats(); s.add(10,mapOf(4 to 20.0),1.0); s.add(210,emptyMap(),1.0)
        assertTrue(s.snapshot(210).currentIds.isEmpty()); assertEquals(200L,s.snapshot(210).rows.single().lastSeenAgoMs)
    }
    @Test fun staleResultExpiresWithoutAnotherFrame() {
        val s=MarkerLiveStats(); s.add(100,mapOf(4 to 20.0),1.0)
        assertEquals(listOf(4),s.snapshot(900).currentIds); assertTrue(s.snapshot(901).currentIds.isEmpty())
        assertEquals(801L,s.snapshot(901).sampleAgeMs)
    }
    @Test fun rollingWindowEvictsWithoutNewResults() {
        val s=MarkerLiveStats(); s.add(0,mapOf(4 to 20.0),1.0); s.add(1000,emptyMap(),1.0)
        assertEquals(2,s.snapshot(3000).sampleCount); assertEquals(0,s.snapshot(3001).rows.single().hits)
        assertEquals(0,s.snapshot(4001).sampleCount); assertNull(s.snapshot(4001).rows.single().meanEdgePx)
    }
    @Test fun resetClearsAllSessionEvidence() {
        val s=MarkerLiveStats(); s.add(20,mapOf(4 to 20.0),1.0); s.clear()
        val v=s.snapshot(21); assertNull(v.sampleAgeMs); assertTrue(v.rows.isEmpty()); assertTrue(v.currentIds.isEmpty()); assertEquals(0,v.sampleCount)
    }
    @Test fun liveRangeIncludesNewPhysicalIdsAndBothBounds() {
        val stats = MarkerLiveStats()
        stats.add(200, listOf(-1, 0, 49, 50, 132, 249, 250).associateWith { 22.0 }, 2.0)
        val ids = stats.snapshot(200).currentIds
        assertEquals(listOf(0, 49, 50, 132, 249), ids)
        assertEquals(listOf("000", "049", "050", "132", "249"), ids.map { MarkerLiveStats.displayId(it) })
    }
    @Test fun oldResultsAndInvalidMeasurementsDoNotBecomeCurrent() {
        val s=MarkerLiveStats(); s.add(200,mapOf(4 to 20.0),1.0); s.add(100,mapOf(6 to 30.0),1.0)
        assertEquals(listOf(4),s.snapshot(200).currentIds)
        s.add(400,mapOf(6 to Double.NaN,250 to 20.0,7 to -1.0),1.0); assertTrue(s.snapshot(400).currentIds.isEmpty())
    }
}
