package com.example.arassemblydemo

internal data class AssemblyBinding(val part: String, val slot: Int, val marker: Int)

/** Identity only. Slot numbers do not assert a measured marker pose. */
internal object AssemblyMarkerBindings {
    fun validate(entries: List<AssemblyBinding>, parts: Set<String>): Map<String,List<Int>> {
        require(entries.size <= 128) { "标签数量超出这套模型的128个预留位置" }
        require(entries.all { it.part in parts && it.slot in 0..7 && it.marker in 0..249 }) { "标签编号或所属零件无效" }
        require(entries.map { it.marker }.distinct().size == entries.size) { "同一编号不能贴在不同位置" }
        require(entries.map { it.part to it.slot }.distinct().size == entries.size) { "同一位置重复登记" }
        return entries.groupBy { it.part }.mapValues { (_,values)->values.map { it.marker }.sorted() }
    }
}
