package com.example.arassemblydemo

import org.junit.Assert.*
import org.junit.Test

class ShelfSessionTest {
    @Test fun allLayersAndEitherFaceIdentifyTheirDesignatedPair() {
        for (layer in 1..5) for (face1 in 0..1) for (face2 in 0..1) {
            val base = 4 + (layer - 1) * 4
            val result = ShelfSession.start(layer).scan(listOf(base + face1, base + 2 + face2))
            assertTrue(result.readyForGuidance)
            assertFalse(result.confirmed)
            assertEquals(setOf(1, 2), result.seenRoles)
        }
    }

    @Test fun sequentialScanningAccumulatesOnlyDistinctRoles() {
        val first = ShelfSession.start(1).scan(listOf(4))
        assertEquals(setOf(1), first.seenRoles)
        assertFalse(first.scan(listOf(5)).readyForGuidance)
        assertTrue(first.scan(listOf(7)).readyForGuidance)
    }

    @Test fun bothFacesOfOneHalfAreNotAPair() {
        assertFalse(ShelfSession.start(1).scan(listOf(4, 5)).readyForGuidance)
        assertEquals(setOf(1), ShelfRules.evaluate(1, listOf(4, 5)).roles)
        assertEquals(ShelfRules.marker(4)!!.instanceId, ShelfRules.marker(5)!!.instanceId)
    }

    @Test fun invalidScanClearsPreviousValidSelections() {
        val ready = ShelfSession.start(1).scan(listOf(4, 6))
        for (bad in listOf(emptyList(), listOf(46), listOf(4, 4), listOf(4, 8), listOf(8, 10))) {
            val rejected = ready.scan(bad)
            assertFalse(rejected.readyForGuidance)
            assertTrue(rejected.seenRoles.isEmpty())
            assertFalse(rejected.confirm().confirmed)
            assertFalse(rejected.scan(listOf(6)).readyForGuidance)
        }
    }

    @Test fun wrongLayerReportsRegisteredColorNotShapeFailure() {
        val rejected = ShelfSession.start(1).scan(listOf(8))
        assertTrue(rejected.message.contains("蓝色"))
        assertTrue(rejected.message.contains("紫色"))
    }

    @Test fun completionRequiresPairAndExplicitAction() {
        val start = ShelfSession.start(1)
        assertFalse(start.confirm().confirmed)
        assertFalse(start.scan(listOf(4)).confirm().confirmed)
        val ready = start.scan(listOf(4, 6))
        assertFalse(ready.confirmed)
        val done = ready.confirm()
        assertTrue(done.confirmed)
        assertFalse(done.readyForGuidance)
        assertTrue(done.seenRoles.isEmpty())
        assertSame(done, done.confirm())
        assertSame(done, done.scan(listOf(4, 6)))
    }

    @Test fun undoReleasesPairAndRequiresFreshScan() {
        val undone = ShelfSession.start(1).scan(listOf(4, 6)).confirm().undo()
        assertFalse(undone.confirmed)
        assertFalse(undone.readyForGuidance)
        assertTrue(undone.seenRoles.isEmpty())
        assertTrue(undone.scan(listOf(5, 7)).confirm().confirmed)
    }

    @Test fun reopeningRestoresConfirmationButNeverObservation() {
        for (confirmed in listOf(false, true)) {
            val restored = ShelfSession.restore(ShelfRules.CONTENT_VERSION, "run-a", 3, confirmed)!!
            assertEquals("run-a", restored.runId)
            assertEquals(3, restored.layer)
            assertEquals(confirmed, restored.confirmed)
            assertTrue(restored.seenRoles.isEmpty())
            assertFalse(restored.readyForGuidance)
        }
    }

    @Test fun invalidationClearsUnconfirmedEvidenceAndKeepsConfirmation() {
        val ready = ShelfSession.start(1).scan(listOf(4, 6))
        assertFalse(ready.invalidate().readyForGuidance)
        assertTrue(ready.confirm().invalidate().confirmed)
    }

    @Test fun mismatchedOrCorruptStoredRunCannotBeResumed() {
        assertNull(ShelfSession.restore("older-content", "run", 1, true))
        assertNull(ShelfSession.restore(ShelfRules.CONTENT_VERSION, "", 1, true))
        for (layer in listOf(0, 6, Int.MAX_VALUE)) {
            assertNull(ShelfSession.restore(ShelfRules.CONTENT_VERSION, "run", layer, true))
        }
    }

    @Test fun registryExcludesOldBoardSamplesAndFutureSideFrames() {
        for (id in listOf(-1, 0, 3, 24, 35, 46, 47, Int.MAX_VALUE)) assertNull(ShelfRules.marker(id))
        assertEquals("绿色", ShelfRules.marker(23)!!.colorName)
    }
}
