package com.example.arassemblydemo

import org.opencv.objdetect.Objdetect

/** Identity assignment exists; no measured marker-to-part transform has been registered. */
internal enum class ShelfMetricInstallation { UNREGISTERED }

/** locationId names an allocation slot, not a measured position or placement instruction. */
internal data class ShelfMarkerRegistration(
    val identity: ShelfMarker,
    val locationId: String,
    val metricInstallation: ShelfMetricInstallation = ShelfMetricInstallation.UNREGISTERED,
)

/** Immutable explicit correspondence. Distinct locations on one face must have distinct IDs. */
internal class ShelfMarkerRegistry(registrations: List<ShelfMarkerRegistration>) {
    private val byId: Map<Int, ShelfMarkerRegistration>

    init {
        registrations.forEach { entry ->
            val marker = entry.identity
            require(marker.id in 0..MAX_MARKER_ID) { "ID outside $DICTIONARY" }
            require(marker.layer in 1..5 && marker.role in 1..2) { "Invalid shelf identity" }
            require(marker.face == "A" || marker.face == "B") { "Invalid shelf face" }
            require(entry.locationId.isNotBlank()) { "Missing location slot" }
        }
        require(registrations.map { it.identity.id }.distinct().size == registrations.size) {
            "A marker ID must identify exactly one allocation"
        }
        require(registrations.map { Triple(it.identity.instanceId, it.identity.face, it.locationId) }.distinct().size == registrations.size) {
            "An instance face location must have exactly one marker ID"
        }
        // Own the map: later mutation of a caller's input list cannot alter assignments.
        byId = registrations.associateBy { it.identity.id }
    }

    fun registration(id: Int): ShelfMarkerRegistration? = byId[id]
    fun marker(id: Int): ShelfMarker? = registration(id)?.identity

    companion object {
        const val DICTIONARY = "DICT_4X4_250"
        const val OPENCV_DICTIONARY_ID = Objdetect.DICT_4X4_250
        const val MAX_MARKER_ID = 249

        /** Previously issued identity mapping, unchanged. No new tags or metric placements. */
        val pilot = ShelfMarkerRegistry(listOf(
            ShelfMarkerRegistration(ShelfMarker(4, 1, 1, "A"), "initial-label"),
            ShelfMarkerRegistration(ShelfMarker(5, 1, 1, "A"), "outer-edge-second"),
            ShelfMarkerRegistration(ShelfMarker(6, 1, 2, "A"), "initial-label"),
            ShelfMarkerRegistration(ShelfMarker(7, 1, 2, "A"), "outer-edge-second"),
            ShelfMarkerRegistration(ShelfMarker(8, 2, 1, "A"), "initial-label"),
            ShelfMarkerRegistration(ShelfMarker(9, 2, 1, "B"), "initial-label"),
            ShelfMarkerRegistration(ShelfMarker(10, 2, 2, "A"), "initial-label"),
            ShelfMarkerRegistration(ShelfMarker(11, 2, 2, "B"), "initial-label"),
            ShelfMarkerRegistration(ShelfMarker(12, 3, 1, "A"), "initial-label"),
            ShelfMarkerRegistration(ShelfMarker(13, 3, 1, "B"), "initial-label"),
            ShelfMarkerRegistration(ShelfMarker(14, 3, 2, "A"), "initial-label"),
            ShelfMarkerRegistration(ShelfMarker(15, 3, 2, "B"), "initial-label"),
            ShelfMarkerRegistration(ShelfMarker(16, 4, 1, "A"), "initial-label"),
            ShelfMarkerRegistration(ShelfMarker(17, 4, 1, "B"), "initial-label"),
            ShelfMarkerRegistration(ShelfMarker(18, 4, 2, "A"), "initial-label"),
            ShelfMarkerRegistration(ShelfMarker(19, 4, 2, "B"), "initial-label"),
            ShelfMarkerRegistration(ShelfMarker(20, 5, 1, "A"), "initial-label"),
            ShelfMarkerRegistration(ShelfMarker(21, 5, 1, "B"), "initial-label"),
            ShelfMarkerRegistration(ShelfMarker(22, 5, 2, "A"), "initial-label"),
            ShelfMarkerRegistration(ShelfMarker(23, 5, 2, "B"), "initial-label"),
        ))
    }
}
