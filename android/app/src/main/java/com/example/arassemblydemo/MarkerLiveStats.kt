package com.example.arassemblydemo

import java.util.ArrayDeque

/** Monotonic milliseconds, main-thread owned. Rates count completed sampled detections. */
class MarkerLiveStats(private val windowMs: Long = 3000, private val staleMs: Long = 800) {
    companion object {
        fun displayId(id: Int): String = id.toString().padStart(3, '0')
    }

    data class Sample(val at: Long, val edges: Map<Int, Double>, val detectionMs: Double)
    data class Row(val id: Int, val hits: Int, val samples: Int, val lastSeenAgoMs: Long, val meanEdgePx: Double?)
    data class Snapshot(val currentIds: List<Int>, val sampleAgeMs: Long?, val sampleCount: Int,
                        val rows: List<Row>, val detectionMs: Double?)
    private val samples = ArrayDeque<Sample>()
    private val lastSeen = mutableMapOf<Int, Long>()
    private var latest: Sample? = null

    fun clear() { samples.clear(); lastSeen.clear(); latest = null }
    fun add(at: Long, edges: Map<Int, Double>, detectionMs: Double) {
        if (latest?.let { at < it.at } == true) return
        val clean = edges.filter { it.key in 0..249 && it.value.isFinite() && it.value > 0 }.toMap()
        val sample = Sample(at, clean, detectionMs)
        samples.addLast(sample); latest = sample
        clean.keys.forEach { lastSeen[it] = at }
        trim(at)
    }
    private fun trim(now: Long) { while (samples.isNotEmpty() && now - samples.first.at > windowMs) samples.removeFirst() }
    fun snapshot(now: Long): Snapshot {
        trim(now)
        val age = latest?.let { (now - it.at).coerceAtLeast(0) }
        val rows = lastSeen.keys.sorted().map { id ->
            val edges = samples.mapNotNull { it.edges[id] }
            Row(id, edges.size, samples.size, (now - lastSeen.getValue(id)).coerceAtLeast(0),
                edges.takeIf { it.isNotEmpty() }?.average())
        }
        return Snapshot(if (age != null && age <= staleMs) latest!!.edges.keys.sorted() else emptyList(),
            age, samples.size, rows, latest?.detectionMs)
    }
}
