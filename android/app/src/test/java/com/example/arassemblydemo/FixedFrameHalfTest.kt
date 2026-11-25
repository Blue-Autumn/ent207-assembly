package com.example.arassemblydemo

import org.junit.Assert.assertEquals
import org.junit.Test

class FixedFrameHalfTest {
    @Test fun separateHalvesAndSingleHalfAliases() {
        assertEquals("侧框1：A、B 两件同时可见。", FixedFrameHalf.assemblyHint(listOf(127,116)))
        val mixed=FixedFrameHalf.assemblyHint(listOf(127,128,106))
        org.junit.Assert.assertFalse(mixed.contains("两件同时可见"))
        org.junit.Assert.assertTrue(mixed.contains("侧框1") && mixed.contains("侧框2"))
    }

    @Test fun newFieldFramesIdentifyHalvesOnceEvenWithPartialReadsAndLooseTags() {
        assertEquals(listOf(FixedFrameHalf.F2A, FixedFrameHalf.F2B),
            FixedFrameHalf.visible(listOf(107,129,6,4,106,118)))
        assertEquals(FixedFrameHalf.entries.toList(), FixedFrameHalf.visible(
            listOf(129,128,5,117,107,4,13,10,11,17,118,8,127,0,2,21,7)))
        assertEquals(listOf(FixedFrameHalf.F2A), FixedFrameHalf.visible(listOf(106)))
        assertEquals(emptyList<FixedFrameHalf>(), FixedFrameHalf.visible(listOf(4,5,6,7)))
    }
}
