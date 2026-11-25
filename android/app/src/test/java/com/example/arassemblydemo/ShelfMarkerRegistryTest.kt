package com.example.arassemblydemo

import org.junit.Assert.*
import org.junit.Test
import org.opencv.objdetect.Objdetect

class ShelfMarkerRegistryTest {
    @Test fun issuedIdsMatchPhysicalPurpleAndLegacyOtherLayers() {
        // Independent compatibility oracle for the released v1 mapping.
        for (id in 4..23) {
            val offset = id - 4
            val expected = ShelfMarker(id, offset / 4 + 1, (offset % 4) / 2 + 1, if (id<=7 || offset % 2 == 0) "A" else "B")
            assertEquals(expected,ShelfRules.marker(id))
            assertEquals(expected.instanceId,ShelfRules.marker(id)!!.instanceId)
        }
        assertEquals("shelf-pair-pilot-v1",ShelfRules.CONTENT_VERSION)
        assertEquals("DICT_4X4_250",ShelfMarkerRegistry.DICTIONARY)
        assertEquals(Objdetect.DICT_4X4_250,ShelfMarkerRegistry.OPENCV_DICTIONARY_ID)
    }

    @Test fun identityAssignmentsDoNotClaimMetricRegistration() {
        for (id in 4..23) {
            val entry = ShelfMarkerRegistry.pilot.registration(id)!!
            assertEquals(if (id==5 || id==7) "outer-edge-second" else "initial-label",entry.locationId)
            assertEquals(ShelfMetricInstallation.UNREGISTERED,entry.metricInstallation)
        }
    }

    @Test fun unknownIdsRemainRejectedAndClearTheCurrentPair() {
        val ready = ShelfSession.start(1).scan(listOf(4,6))
        for (id in listOf(Int.MIN_VALUE,-1,0,3,24,35,46,47,49,50,106,127,249,250,Int.MAX_VALUE)) {
            assertNull(ShelfRules.marker(id))
            val rejected = ready.scan(listOf(id))
            assertFalse(rejected.readyForGuidance)
            assertTrue(rejected.seenRoles.isEmpty())
        }
    }

    @Test fun duplicateDetectedIdsRemainRejectedEvenWhenTheirIdentityMatches() {
        val result = ShelfRules.evaluate(1,listOf(4,4,6))
        assertFalse(result.accepted)
        assertTrue(result.roles.isEmpty())
        assertFalse(ShelfSession.start(1).scan(listOf(4,4,6)).readyForGuidance)
        assertEquals(setOf(1),ShelfRules.evaluate(1,listOf(4,5)).roles)
    }

    @Test fun separateLocationSlotsCanShareOneInstanceAndFaceWithoutChangingThePilot() {
        // Structural test only: ID24 is not issued or activated in the pilot registry.
        val registry = ShelfMarkerRegistry(listOf(
            ShelfMarkerRegistration(ShelfMarker(4,1,1,"A"),"initial-label"),
            ShelfMarkerRegistration(ShelfMarker(24,1,1,"A"),"candidate-edge"),
        ))
        assertEquals(registry.marker(4)!!.instanceId,registry.marker(24)!!.instanceId)
        assertEquals(registry.marker(4)!!.face,registry.marker(24)!!.face)
        assertNotEquals(registry.registration(4)!!.locationId,registry.registration(24)!!.locationId)
        assertEquals(ShelfMetricInstallation.UNREGISTERED,registry.registration(24)!!.metricInstallation)
        assertNull(ShelfRules.marker(24))
    }

    @Test fun fullPrintedDictionaryRangeCanBeRegisteredWithoutIssuingNewPilotIdentities() {
        // Structural coverage only. These assignments are not physical installation claims.
        val ids = listOf(0,49,50,106,127,249)
        val registry = ShelfMarkerRegistry(ids.map { id ->
            ShelfMarkerRegistration(ShelfMarker(id,1,1,"A"),"test-location-$id")
        })
        assertEquals(249,ShelfMarkerRegistry.MAX_MARKER_ID)
        for (id in ids) {
            assertEquals(id,registry.marker(id)!!.id)
            assertEquals(ShelfMetricInstallation.UNREGISTERED,registry.registration(id)!!.metricInstallation)
            assertNull(ShelfMarkerRegistry.pilot.marker(id))
        }
    }

    @Test fun idsOutsideThe250EntryDictionaryCannotBeRegistered() {
        for (id in listOf(Int.MIN_VALUE,-1,250,Int.MAX_VALUE)) {
            try {
                ShelfMarkerRegistry(listOf(ShelfMarkerRegistration(ShelfMarker(id,1,1,"A"),"test-location")))
                fail("Out-of-dictionary ID $id was accepted")
            } catch (_: IllegalArgumentException) { /* expected */ }
        }
    }

    @Test fun ambiguousAllocationsAndInvalidFieldsCannotEnterARegistry() {
        val first = ShelfMarkerRegistration(ShelfMarker(4,1,1,"A"),"initial-label")
        val invalid = listOf(
            listOf(first, first.copy(identity=ShelfMarker(4,2,1,"A"))),
            listOf(first, first.copy(identity=ShelfMarker(24,1,1,"A"))),
            listOf(first.copy(identity=ShelfMarker(250,1,1,"A"))),
            listOf(first.copy(identity=ShelfMarker(4,0,1,"A"))),
            listOf(first.copy(identity=ShelfMarker(4,1,3,"A"))),
            listOf(first.copy(identity=ShelfMarker(4,1,1,"C"))),
            listOf(first.copy(locationId=" ")),
        )
        invalid.forEach { rows ->
            try { ShelfMarkerRegistry(rows); fail("Invalid mapping was accepted") }
            catch (_: IllegalArgumentException) { /* expected */ }
        }
    }

    @Test fun mutatingTheInputListCannotReassignAnExistingRegistry() {
        val rows = mutableListOf(ShelfMarkerRegistration(ShelfMarker(4,1,1,"A"),"initial-label"))
        val registry = ShelfMarkerRegistry(rows)
        rows.clear()
        rows.add(ShelfMarkerRegistration(ShelfMarker(4,5,2,"B"),"elsewhere"))
        assertEquals(ShelfMarker(4,1,1,"A"),registry.marker(4))
    }
}
