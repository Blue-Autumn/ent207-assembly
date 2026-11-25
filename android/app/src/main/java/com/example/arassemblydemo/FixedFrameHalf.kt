package com.example.arassemblydemo

internal enum class FixedFrameHalf(val label: String, val ids: Set<Int>) {
    F1A("侧框一 · 半件 A（127 / 128）", setOf(127, 128)),
    F1B("侧框一 · 半件 B（116 / 117）", setOf(116, 117)),
    F2A("侧框二 · 半件 A（106 / 107）", setOf(106, 107)),
    F2B("侧框二 · 半件 B（118 / 129）", setOf(118, 129));

    companion object {
        fun assemblyHint(ids: List<Int>): String {
            val halves = visible(ids)
            if (halves.isEmpty()) return "将当前侧框的两个半件一起放入画面。"
            val groups = listOf(listOf(F1A,F1B), listOf(F2A,F2B))
            return groups.mapIndexedNotNull { index, pair ->
                val seen = pair.filter { it in halves }
                when (seen.size) {
                    0 -> null
                    1 -> "侧框${index+1}：看到 " + seen.single().label.substringAfter(" · ") +
                        "。请找同框的另一半。"
                    else -> "侧框${index+1}：A、B 两件同时可见。"
                }
            }.joinToString("\n")
        }

        fun visible(ids: List<Int>) = entries.filter { half -> ids.any { it in half.ids } }
    }
}
