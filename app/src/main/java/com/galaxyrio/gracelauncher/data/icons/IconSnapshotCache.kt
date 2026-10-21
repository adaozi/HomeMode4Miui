package com.galaxyrio.gracelauncher.data.icons

/** Keep only the latest revision of each icon, rather than every theme ever used. */
internal class IconSnapshotCache<S, V>(private val capacity: Int = 256,
    private val maxBytes: Int = Int.MAX_VALUE, private val sizeOf: (V) -> Int = { 0 }) {
    init { require(capacity > 0 && maxBytes > 0) }
    private data class Entry<S, V>(val signature: S, val value: V, val bytes: Int)
    private val entries = LinkedHashMap<String, Entry<S, V>>(16, 0.75f, true)
    private var bytes = 0L

    @Synchronized
    fun get(key: String, signature: S): V? = entries[key]?.takeIf { it.signature == signature }?.value

    @Synchronized
    fun put(key: String, signature: S, value: V) {
        val size = sizeOf(value).also { require(it >= 0) }
        remove(key)
        if (size > maxBytes) return
        // Preserve entries needed later in a full catalog scan by evicting the last
        // visited icon before inserting a miss. LRU would miss every icon once full.
        while (entries.isNotEmpty() && (entries.size >= capacity || bytes + size > maxBytes)) {
            remove(entries.keys.last())
        }
        entries[key] = Entry(signature, value, size)
        bytes += size
    }

    /** 模块补充：图源文件变更（导入/删除自定义图标）后必须整体失效。 */
    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0L
    }

    private fun remove(key: String) {
        entries.remove(key)?.let { bytes -= it.bytes }
    }
}
