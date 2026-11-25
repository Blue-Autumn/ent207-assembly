package com.example.arassemblydemo

import org.junit.Assert.*
import org.junit.Test
import com.example.arassemblydemo.PlanarJointMapper.XY
import com.example.arassemblydemo.PlanarJointMapper.Tag
import com.example.arassemblydemo.PlanarJointMapper.Reference
import kotlin.math.*

class PlanarJointMapperTest {
    private fun tag(id: Int, x: Double, y: Double, size: Double = 32.0) = Tag(id, listOf(XY(x, y), XY(x + size, y), XY(x + size, y + size), XY(x, y + size)))
    private val tags = listOf(tag(4, 120.0, 120.0), tag(5, 530.0, 120.0), tag(24, 530.0, 610.0), tag(25, 120.0, 610.0))
    private fun ref(target: XY = XY(510.0, 360.0), t: List<Tag> = tags) = Reference("层1板-1", "4,5,24,25", setOf(4, 5, 24, 25, 26, 27, 28, 29), 900, 900, t, target)
    // Independent explicit projective camera-plane mapping used to generate observed points.
    private fun warp(p: XY): XY {
        val w = .00022 * p.x - .00010 * p.y + 1.0
        return XY((.91 * p.x - .17 * p.y + 135.0) / w, (.12 * p.x + .83 * p.y + 55.0) / w)
    }
    private fun warped(t: List<Tag> = tags) = t.map { it.copy(corners = it.corners.map(::warp)) }

    @Test fun exactPerspectiveTransfersAnIndependentJoint() {
        val r = PlanarJointMapper.project(ref(), warped(), 900, 900)
        assertEquals("OK", r.reason)
        assertEquals(warp(ref().target).x, r.point!!.x, 1e-7)
        assertEquals(warp(ref().target).y, r.point.y, 1e-7)
        assertTrue(r.rmsPx!! < 1e-7)
    }

    @Test fun independentPinholeCameraSweepTransfersCoplanarJoint() {
        // Generate images from explicit 3D rotations/pinhole projection, without using mapper internals.
        for (tiltX in listOf(-20.0, 0.0, 20.0)) for (tiltY in listOf(-30.0, 0.0, 30.0))
            for (depth in listOf(500.0, 650.0)) {
                val ax = Math.toRadians(tiltX); val ay = Math.toRadians(tiltY)
                fun camera(p: XY): XY {
                    val x = (p.x - 450.0) * .5; val y = (p.y - 450.0) * .5
                    val rotatedX = cos(ay) * x + sin(ay) * sin(ax) * y
                    val rotatedY = cos(ax) * y
                    val rotatedZ = -sin(ay) * x + cos(ay) * sin(ax) * y + depth
                    return XY(450.0 + 1000.0 * rotatedX / rotatedZ, 450.0 + 1000.0 * rotatedY / rotatedZ)
                }
                val observed = tags.map { it.copy(corners = it.corners.map(::camera)) }
                val r = PlanarJointMapper.project(ref(), observed, 900, 900)
                assertEquals("tilt $tiltX/$tiltY, depth $depth: ${r.reason}", "OK", r.reason)
                assertEquals(camera(ref().target).x, r.point!!.x, 1e-7)
                assertEquals(camera(ref().target).y, r.point.y, 1e-7)
            }
    }

    @Test fun referenceAcceptsAnEdgeJointSlightlyOutsideTagHull() {
        val edge = ref(XY(590.0, 390.0))
        assertEquals("OK_REFERENCE", PlanarJointMapper.validateReference(edge).reason)
        assertEquals("OK", PlanarJointMapper.project(edge, warped(), 900, 900).reason)
    }

    @Test fun changingVisibleTagsRetainsTheSameJointWithThreeMarkers() {
        val center = ref(XY(340.0, 380.0))
        for (missing in tags.indices) {
            val r = PlanarJointMapper.project(center, warped().filterIndexed { i, _ -> i != missing }, 900, 900)
            assertEquals("missing $missing: ${r.reason}", "OK", r.reason)
            assertEquals(warp(center.target).x, r.point!!.x, 1e-7)
        }
    }

    @Test fun smallDeterministicCornerNoiseDoesNotClaimExactFit() {
        val noisy = warped().mapIndexed { i, t -> t.copy(corners = t.corners.mapIndexed { j, p ->
            XY(p.x + .16 * sin((i * 4 + j).toDouble()), p.y + .16 * cos((i * 4 + j).toDouble()))
        }) }
        val r = PlanarJointMapper.project(ref(), noisy, 900, 900)
        assertEquals("OK", r.reason)
        assertTrue(r.rmsPx!! > .01)
        assertTrue(hypot(r.point!!.x - warp(ref().target).x, r.point.y - warp(ref().target).y) < 1.0)
    }

    @Test fun rotatedPhotoPreservesCanonicalCornerIdentity() {
        val rotated = warped().map { it.copy(corners = it.corners.map { p -> XY(899 - p.y, p.x) }) }
        val r = PlanarJointMapper.project(ref(), rotated, 900, 900)
        assertEquals("OK", r.reason)
        assertEquals(899 - warp(ref().target).y, r.point!!.x, 1e-7)
        assertEquals(warp(ref().target).x, r.point.y, 1e-7)
    }

    @Test fun otherMovingBoardIsNeverPooledIntoReference() {
        val unrelated = listOf(tag(87, 50.0, 60.0), tag(88, 500.0, 470.0))
        val complete = PlanarJointMapper.project(ref(), warped() + unrelated, 900, 900)
        assertEquals("OK", complete.reason)
        assertEquals(tags.map { it.id }.sorted(), complete.usedIds)
        val incomplete = PlanarJointMapper.project(ref(), warped().take(2) + unrelated, 900, 900)
        assertEquals("TOO_FEW_MATCHES", incomplete.reason)
        assertNull(incomplete.point)
    }

    @Test fun oppositeFaceCannotReuseTheReference() {
        val back = tags.mapIndexed { i, t -> t.copy(id = 26 + i) }
        assertEquals("TOO_FEW_MATCHES", PlanarJointMapper.project(ref(), back, 900, 900).reason)
    }

    @Test fun duplicateKnownIdIsRejectedEvenWithMalformedDuplicate() {
        val current = warped() + Tag(4, emptyList())
        assertEquals("DUPLICATE_ID", PlanarJointMapper.project(ref(), current, 900, 900).reason)
        assertEquals("DUPLICATE_ID", PlanarJointMapper.validateReference(ref(t = tags + tags.first())).reason)
    }

    @Test fun corruptedMarkerIsRejectedWithoutChoosingConvenientSubset() {
        val corrupt = warped().map { if (it.id == 24) it.copy(corners = it.corners.map { p -> XY(p.x + 35, p.y - 20) }) else it }
        val r = PlanarJointMapper.project(ref(), corrupt, 900, 900)
        assertNull(r.point)
        assertTrue(r.reason in setOf("INCONSISTENT_MARKERS", "UNSTABLE_TRANSFORM"))
    }

    @Test fun canonicalCornerPermutationDoesNotCreateAValidJoint() {
        val corrupt = warped().map { if (it.id == 24) it.copy(corners = it.corners.drop(1) + it.corners.take(1)) else it }
        assertNull(PlanarJointMapper.project(ref(), corrupt, 900, 900).point)
    }

    @Test fun nearlyCollinearThreeMarkersAndFarTargetsAreRejected() {
        val line = listOf(tag(4, 120.0, 100.0), tag(5, 120.0, 360.0), tag(24, 120.0, 610.0))
        assertEquals("POOR_LAYOUT", PlanarJointMapper.validateReference(ref(t = line)).reason)
        assertEquals("TARGET_TOO_FAR", PlanarJointMapper.validateReference(ref(XY(860.0, 870.0))).reason)
    }

    @Test fun missingSmallClippedMirroredAndNonfiniteGeometryAreRejected() {
        assertEquals("TOO_FEW_REFERENCE_MARKERS", PlanarJointMapper.validateReference(ref(t = tags.take(2))).reason)
        assertEquals("MARKERS_TOO_SMALL", PlanarJointMapper.validateReference(ref(t = tags.map { tag(it.id, it.corners[0].x, it.corners[0].y, 6.0) })).reason)
        assertEquals("INVALID_MARKER", PlanarJointMapper.project(ref(), warped().map { it.copy(corners = it.corners.reversed()) }, 900, 900).reason)
        assertEquals("INVALID_MARKER", PlanarJointMapper.project(ref(), warped().map { it.copy(corners = it.corners.map { p -> XY(p.x + 1000, p.y) }) }, 900, 900).reason)
        assertEquals("TARGET_OUTSIDE_IMAGE", PlanarJointMapper.validateReference(ref(XY(Double.NaN, 10.0))).reason)
        assertEquals("INVALID_IMAGE", PlanarJointMapper.project(ref(), warped(), 0, 900).reason)
    }

    @Test fun missingCornerSupportDoesNotAllowRemoteExtrapolation() {
        val cornerTarget = ref(XY(560.0, 640.0))
        assertEquals("OK_REFERENCE", PlanarJointMapper.validateReference(cornerTarget).reason)
        val r = PlanarJointMapper.project(cornerTarget, warped().filter { it.id != 24 }, 900, 900)
        assertEquals("TARGET_TOO_FAR", r.reason)
        assertNull(r.point)
    }
}
