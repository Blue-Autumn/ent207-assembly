package com.example.arassemblydemo

/** Navigation only. This state never certifies recognition or physical assembly completion. */
@ConsistentCopyVisibility
data class GuidanceProgress private constructor(
    val contentVersion: String,
    val itemCount: Int,
    val currentIndex: Int,
) {
    val canGoPrevious: Boolean get() = currentIndex > 0
    val canGoNext: Boolean get() = currentIndex < itemCount - 1

    fun previous() = copy(currentIndex = (currentIndex - 1).coerceAtLeast(0))
    fun next() = copy(currentIndex = (currentIndex + 1).coerceAtMost(itemCount - 1))
    fun reset() = copy(currentIndex = 0)

    companion object {
        fun restore(
            contentVersion: String,
            itemCount: Int,
            storedVersion: String?,
            storedIndex: Int,
        ): GuidanceProgress {
            require(contentVersion.isNotBlank())
            require(itemCount > 0)
            val index = if (storedVersion == contentVersion && storedIndex in 0 until itemCount) {
                storedIndex
            } else {
                0
            }
            return GuidanceProgress(contentVersion, itemCount, index)
        }
    }
}
