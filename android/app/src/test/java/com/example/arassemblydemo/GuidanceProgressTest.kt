package com.example.arassemblydemo

import org.junit.Assert.*
import org.junit.Test

class GuidanceProgressTest {
    @Test fun navigationNeverLeavesChecklist() {
        var state = GuidanceProgress.restore("preparation-v1", 2, null, 0)
        repeat(10) { state = state.previous() }
        assertEquals(0, state.currentIndex)
        assertFalse(state.canGoPrevious)
        repeat(10) { state = state.next() }
        assertEquals(1, state.currentIndex)
        assertFalse(state.canGoNext)
    }

    @Test fun matchingContentRestoresPositionAcrossSessions() {
        val before = GuidanceProgress.restore("preparation-v1", 2, null, 0).next()
        val restored = GuidanceProgress.restore("preparation-v1", 2, before.contentVersion, before.currentIndex)
        assertEquals(before, restored)
    }

    @Test fun preparationPositionCannotBecomeTutorialProgress() {
        val state = GuidanceProgress.restore("tutorial-v1", 8, "preparation-v1", 1)
        assertEquals(0, state.currentIndex)
        assertEquals("tutorial-v1", state.contentVersion)
    }

    @Test fun corruptStoredPositionResetsToFirstItem() {
        for (badIndex in listOf(-1, 2, Int.MAX_VALUE)) {
            assertEquals(0, GuidanceProgress.restore("v1", 2, "v1", badIndex).currentIndex)
        }
    }

    @Test fun resetAndSingleItemContentRemainValid() {
        assertEquals(0, GuidanceProgress.restore("v1", 2, "v1", 1).reset().currentIndex)
        val single = GuidanceProgress.restore("single", 1, null, 0)
        assertFalse(single.canGoNext)
        assertFalse(single.canGoPrevious)
        assertEquals(single, single.next().previous())
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyContentIsRejected() {
        GuidanceProgress.restore("v1", 0, null, 0)
    }
}
