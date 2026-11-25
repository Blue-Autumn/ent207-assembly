package com.example.arassemblydemo

import kotlin.math.*

/** Photo-to-photo planar registration, not a metric pose or assembly-completion estimator.
 * Pixels and ordered ArUco corners must belong to the same cropped/oriented photograph.
 * The caller owns camera/configuration, step, binding-revision and storage invalidation.
 * Raised printed plates and lens distortion violate perfect coplanarity; field validation
 * is still required even when these numerical checks pass.
 */
internal object PlanarJointMapper {
    data class XY(val x: Double, val y: Double)
    data class Tag(val id: Int, val corners: List<XY>)
    data class Reference(val part: String, val face: String, val allowedIds: Set<Int>,
                         val width: Int, val height: Int, val tags: List<Tag>, val target: XY)
    data class Result(val point: XY? = null, val reason: String, val usedIds: List<Int> = emptyList(),
                      val rmsPx: Double? = null, val maxErrorPx: Double? = null,
                      val extrapolation: Double? = null)

    fun validateReference(reference: Reference): Result {
        if (reference.part.isBlank() || reference.face.isBlank() || reference.allowedIds.isEmpty() ||
            reference.allowedIds.any { it !in 0..249 }) return Result(reason = "INVALID_REFERENCE")
        if (!validImage(reference.width, reference.height)) return Result(reason = "INVALID_IMAGE")
        if (!inImage(reference.target, reference.width, reference.height)) return Result(reason = "TARGET_OUTSIDE_IMAGE")
        if (reference.tags.any { it.id !in reference.allowedIds }) return Result(reason = "WRONG_REFERENCE_PART")
        if (reference.tags.map { it.id }.distinct().size != reference.tags.size) return Result(reason = "DUPLICATE_ID")
        if (reference.tags.size < 3) return Result(reason = "TOO_FEW_REFERENCE_MARKERS")
        if (reference.tags.size > 4) return Result(reason = "TOO_MANY_REFERENCE_MARKERS")
        for (tag in reference.tags) geometryReason(tag, reference.width, reference.height)?.let { return Result(reason = it) }
        val points = reference.tags.flatMap { it.corners }
        if (!wellSpread(points, reference.width, reference.height)) return Result(reason = "POOR_LAYOUT")
        val outside = extrapolation(reference.target, points)
        if (outside > MAX_EXTRAPOLATION) return Result(reason = "TARGET_TOO_FAR", extrapolation = outside)
        return Result(reference.target, "OK_REFERENCE", reference.tags.map { it.id }.sorted(), extrapolation = outside)
    }

    fun project(reference: Reference, current: List<Tag>, width: Int, height: Int): Result {
        val validation = validateReference(reference)
        if (validation.point == null) return validation
        if (!validImage(width, height)) return Result(reason = "INVALID_IMAGE")
        // Ignore other physical entities. Never pool different moving boards to fill a missing corner.
        val relevant = current.filter { it.id in reference.allowedIds }
        if (relevant.map { it.id }.distinct().size != relevant.size) return Result(reason = "DUPLICATE_ID")
        val refById = reference.tags.associateBy { it.id }
        val matched = relevant.filter { it.id in refById }.sortedBy { it.id }
        if (matched.size < 3) return Result(reason = "TOO_FEW_MATCHES", usedIds = matched.map { it.id })
        val ids = matched.map { it.id }
        for (tag in matched) geometryReason(tag, width, height)?.let { return Result(reason = it, usedIds = ids) }
        val from = matched.flatMap { refById.getValue(it.id).corners }
        val to = matched.flatMap { it.corners }
        if (!wellSpread(from, reference.width, reference.height) || !wellSpread(to, width, height))
            return Result(reason = "POOR_LAYOUT", usedIds = ids)
        val sourceOutside = extrapolation(reference.target, from)
        if (sourceOutside > MAX_EXTRAPOLATION) return Result(reason = "TARGET_TOO_FAR", usedIds = ids, extrapolation = sourceOutside)
        val transform = fit(from, to) ?: return Result(reason = "UNSTABLE_TRANSFORM", usedIds = ids)
        val projected = from.map { transform.map(it) ?: return Result(reason = "UNSTABLE_TRANSFORM", usedIds = ids) }
        val errors = projected.zip(to).map { (a, b) -> distance(a, b) }
        val rms = sqrt(errors.sumOf { it * it } / errors.size)
        val maxError = errors.maxOrNull() ?: Double.POSITIVE_INFINITY
        // Provisional image-space thresholds, not a millimetre accuracy certificate.
        val tolerance = max(2.5, min(width, height) * .004)
        if (!rms.isFinite() || rms > tolerance * .7 || maxError > tolerance * 2)
            return Result(reason = "INCONSISTENT_MARKERS", usedIds = ids, rmsPx = rms, maxErrorPx = maxError)
        val target = transform.map(reference.target) ?: return Result(reason = "UNSTABLE_TRANSFORM", usedIds = ids)
        if (!inImage(target, width, height)) return Result(reason = "TARGET_OUTSIDE_IMAGE", usedIds = ids)
        val outside = max(sourceOutside, extrapolation(target, to))
        if (outside > MAX_EXTRAPOLATION) return Result(reason = "TARGET_TOO_FAR", usedIds = ids, extrapolation = outside)
        // A homography can fit a small cluster exactly while its remote target drifts badly.
        // Withhold each *whole marker*, then check both its corners and the actual target.
        for (held in matched.indices) {
            val indices = from.indices.filter { it / 4 != held }
            val sub = fit(indices.map { from[it] }, indices.map { to[it] })
                ?: return Result(reason = "UNSTABLE_TRANSFORM", usedIds = ids, rmsPx = rms, maxErrorPx = maxError)
            val heldError = (held * 4 until held * 4 + 4).maxOf { i ->
                sub.map(from[i])?.let { distance(it, to[i]) } ?: Double.POSITIVE_INFINITY
            }
            val targetDifference = sub.map(reference.target)?.let { distance(it, target) } ?: Double.POSITIVE_INFINITY
            if (heldError > tolerance * 3 || targetDifference > tolerance * 2)
                return Result(reason = "UNSTABLE_TRANSFORM", usedIds = ids, rmsPx = rms, maxErrorPx = maxError, extrapolation = outside)
        }
        return Result(target, "OK", ids, rms, maxError, outside)
    }

    private const val MAX_EXTRAPOLATION = .16 // Outside hull distance / hull diameter; permits nearby edge joints.
    private fun validImage(w: Int, h: Int) = w in 32..16384 && h in 32..16384
    private fun inImage(p: XY, w: Int, h: Int) = p.x.isFinite() && p.y.isFinite() && p.x >= 0 && p.y >= 0 && p.x < w && p.y < h
    private fun distance(a: XY, b: XY) = hypot(a.x - b.x, a.y - b.y)
    private fun cross(a: XY, b: XY, c: XY) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    private fun geometryReason(tag: Tag, w: Int, h: Int): String? {
        val p = tag.corners
        if (p.size != 4 || p.any { !inImage(it, w, h) }) return "INVALID_MARKER"
        if ((0..3).any { cross(p[it], p[(it + 1) % 4], p[(it + 2) % 4]) <= 1e-6 }) return "INVALID_MARKER"
        if ((0..3).any { distance(p[it], p[(it + 1) % 4]) < 10.0 } || area(p) < 100.0) return "MARKERS_TOO_SMALL"
        return null
    }

    private fun area(p: List<XY>) = abs(p.indices.sumOf { i ->
        val q = p[(i + 1) % p.size]; p[i].x * q.y - q.x * p[i].y
    }) / 2

    private fun hull(points: List<XY>): List<XY> {
        val sorted = points.distinct().sortedWith(compareBy<XY> { it.x }.thenBy { it.y })
        if (sorted.size < 3) return sorted
        fun half(items: List<XY>): List<XY> {
            val result = ArrayList<XY>()
            for (p in items) {
                while (result.size > 1 && cross(result[result.size - 2], result.last(), p) <= 0) result.removeAt(result.lastIndex)
                result.add(p)
            }
            return result.dropLast(1)
        }
        return half(sorted) + half(sorted.reversed())
    }

    private fun wellSpread(points: List<XY>, w: Int, h: Int): Boolean {
        val outline = hull(points)
        if (outline.size < 3 || area(outline) < w.toDouble() * h * .006) return false
        val mx = points.map { it.x }.average(); val my = points.map { it.y }.average()
        val xx = points.sumOf { (it.x - mx).pow(2) }; val yy = points.sumOf { (it.y - my).pow(2) }
        val xy = points.sumOf { (it.x - mx) * (it.y - my) }
        val delta = hypot(xx - yy, 2 * xy)
        return xx + yy > 1e-6 && (xx + yy - delta) / (xx + yy + delta) >= .015
    }

    private fun extrapolation(point: XY, points: List<XY>): Double {
        val outline = hull(points)
        if (outline.size < 3) return Double.POSITIVE_INFINITY
        if (outline.indices.all { cross(outline[it], outline[(it + 1) % outline.size], point) >= -1e-7 }) return 0.0
        val diameter = outline.maxOf { a -> outline.maxOf { b -> distance(a, b) } }
        val closest = outline.indices.minOf { i ->
            val a = outline[i]; val b = outline[(i + 1) % outline.size]
            val dx = b.x - a.x; val dy = b.y - a.y
            val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            distance(point, XY(a.x + t * dx, a.y + t * dy))
        }
        return closest / diameter
    }

    private data class Normalization(val x: Double, val y: Double, val scale: Double) {
        fun apply(p: XY) = XY((p.x - x) * scale, (p.y - y) * scale)
        fun undo(p: XY) = XY(p.x / scale + x, p.y / scale + y)
    }
    private fun normalize(p: List<XY>): Normalization? {
        val x = p.map { it.x }.average(); val y = p.map { it.y }.average()
        val variance = p.sumOf { (it.x - x).pow(2) + (it.y - y).pow(2) } / p.size
        return if (variance.isFinite() && variance > 1e-8) Normalization(x, y, sqrt(2 / variance)) else null
    }
    private data class Transform(val source: Normalization, val destination: Normalization, val h: DoubleArray) {
        fun map(point: XY): XY? {
            val p = source.apply(point)
            val denominator = h[6] * p.x + h[7] * p.y + 1.0
            // The normalized source centroid is in front (denominator=1). Do not cross a projective horizon.
            if (!denominator.isFinite() || denominator <= .05) return null
            val result = destination.undo(XY((h[0] * p.x + h[1] * p.y + h[2]) / denominator,
                (h[3] * p.x + h[4] * p.y + h[5]) / denominator))
            return result.takeIf { it.x.isFinite() && it.y.isFinite() }
        }
    }

    /** Hartley-like normalization followed by eight-parameter least squares via Householder QR.
     * This uses a bounded h33=1 chart; unsafe/ill-conditioned charts are rejected, not extrapolated.
     */
    private fun fit(source: List<XY>, destination: List<XY>): Transform? {
        if (source.size != destination.size || source.size < 4) return null
        val ns = normalize(source) ?: return null; val nd = normalize(destination) ?: return null
        val a = Array(source.size * 2) { DoubleArray(8) }; val b = DoubleArray(a.size)
        for (i in source.indices) {
            val s = ns.apply(source[i]); val d = nd.apply(destination[i])
            a[2 * i] = doubleArrayOf(s.x, s.y, 1.0, 0.0, 0.0, 0.0, -d.x * s.x, -d.x * s.y)
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, s.x, s.y, 1.0, -d.y * s.x, -d.y * s.y)
            b[2 * i] = d.x; b[2 * i + 1] = d.y
        }
        val h = leastSquares(a, b) ?: return null
        return Transform(ns, nd, h)
    }

    private fun leastSquares(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val rows = a.size; val cols = 8
        val diagonal = DoubleArray(cols)
        for (k in 0 until cols) {
            var norm = 0.0
            for (i in k until rows) norm = hypot(norm, a[i][k])
            if (!norm.isFinite() || norm < 1e-8) return null
            if (a[k][k] < 0) norm = -norm
            for (i in k until rows) a[i][k] /= norm
            a[k][k] += 1.0
            for (j in k + 1 until cols) {
                var s = 0.0
                for (i in k until rows) s += a[i][k] * a[i][j]
                s = -s / a[k][k]
                for (i in k until rows) a[i][j] += s * a[i][k]
            }
            var s = 0.0
            for (i in k until rows) s += a[i][k] * b[i]
            s = -s / a[k][k]
            for (i in k until rows) b[i] += s * a[i][k]
            diagonal[k] = -norm
        }
        if (diagonal.maxOf { abs(it) } / diagonal.minOf { abs(it) } > 1e7) return null
        val answer = b.copyOf(cols)
        for (k in cols - 1 downTo 0) {
            answer[k] /= diagonal[k]
            for (i in 0 until k) answer[i] -= answer[k] * a[i][k]
        }
        return answer.takeIf { it.all { v -> v.isFinite() } }
    }
}
