package com.example.arassemblydemo

import java.util.UUID

/** Identity-only pilot registry. A decoded label does not prove placement or completed assembly. */
data class ShelfMarker(val id: Int, val layer: Int, val role: Int, val face: String) {
    val instanceId: String get() = "B%02d-H%d".format(layer, role)
    val colorName: String get() = ShelfRules.colorName(layer)
}

object ShelfRules {
    const val CONTENT_VERSION = "shelf-pair-pilot-v1"
    private val colors = listOf("紫色", "蓝色", "肉色", "黄色", "绿色")

    fun colorName(layer: Int): String {
        require(layer in 1..5)
        return colors[layer - 1]
    }

    fun marker(id: Int): ShelfMarker? = ShelfMarkerRegistry.pilot.marker(id)

    /** Same-instance A/B labels contribute a single role, never a second half. */
    fun evaluate(layer: Int, ids: List<Int>): ShelfScanResult {
        require(layer in 1..5)
        if (ids.isEmpty()) return ShelfScanResult(emptySet(), "未看到标签，请重新扫码。", false)
        if (ids.distinct().size != ids.size) {
            return ShelfScanResult(emptySet(), "看到重复编号，请只保留每个编号的一枚标签后重扫。", false)
        }
        val markers = ids.map { marker(it) }
        if (markers.any { it == null }) {
            return ShelfScanResult(emptySet(), "发现未登记的标签，请移开其他标签后重扫。", false)
        }
        val wrong = markers.filterNotNull().firstOrNull { it.layer != layer }
        if (wrong != null) return ShelfScanResult(emptySet(),
            "这是${wrong.colorName}层的半片，本步需要${colorName(layer)}层；请重新扫描本层两半。", false)
        val roles = markers.filterNotNull().map { it.role }.toSet()
        return ShelfScanResult(roles, "已找到${colorName(layer)}层的${roles.sorted().joinToString("、")}号半片。", true)
    }
}

data class ShelfScanResult(val roles: Set<Int>, val message: String, val accepted: Boolean)

/** Persistent fields: version, runId, layer, confirmed. Never persist seenRoles across reopening. */
class ShelfSession private constructor(
    val contentVersion: String,
    val runId: String,
    val layer: Int,
    val confirmed: Boolean,
    private val roleMask: Int,
    val message: String,
) {
    // Return a fresh set so a caller cannot mutate session state by casting a collection.
    val seenRoles: Set<Int> get() = (1..2).filter { roleMask and (1 shl it) != 0 }.toSet()
    val readyForGuidance: Boolean get() = !confirmed && roleMask == 6

    fun scan(ids: List<Int>): ShelfSession {
        if (confirmed) return this
        val result = ShelfRules.evaluate(layer, ids)
        if (!result.accepted) return withState(false, 0, result.message)
        val combined = result.roles.fold(roleMask) { mask, role -> mask or (1 shl role) }
        val text = if (combined == 6) "本层两半已找到，请查看指导；装好后由你确认。"
            else "已找到${ShelfRules.colorName(layer)}层的一半，请扫描另一半。"
        return withState(false, combined, text)
    }

    fun invalidate(): ShelfSession = withState(confirmed, 0,
        if (confirmed) "本层已由你确认完成。" else "请重新扫描本层两半。")

    /** Called only by an explicit user completion action, never by a scan or AI answer. */
    fun confirm(): ShelfSession {
        if (confirmed) return this
        if (!readyForGuidance) return withState(false, roleMask, "请先扫描本层两半并查看指导，再确认完成。")
        return withState(true, 0, "本层已由你确认完成。")
    }

    fun undo(): ShelfSession = withState(false, 0, "已撤销完成，请重新扫描本层两半。")

    private fun withState(done: Boolean, mask: Int, text: String) =
        ShelfSession(contentVersion, runId, layer, done, mask, text)

    companion object {
        fun start(layer: Int, runId: String = UUID.randomUUID().toString()): ShelfSession {
            require(layer in 1..5)
            require(runId.isNotBlank())
            return ShelfSession(ShelfRules.CONTENT_VERSION, runId, layer, false, 0, "请扫描本层两半的标签。")
        }

        /** A mismatch must be surfaced to the user; do not silently reuse a run with new content. */
        fun restore(storedVersion: String, runId: String, layer: Int, confirmed: Boolean): ShelfSession? {
            if (storedVersion != ShelfRules.CONTENT_VERSION || runId.isBlank() || layer !in 1..5) return null
            return ShelfSession(storedVersion, runId, layer, confirmed, 0,
                if (confirmed) "本层已由你确认完成。" else "已恢复本层进度，请重新扫码。")
        }
    }
}
