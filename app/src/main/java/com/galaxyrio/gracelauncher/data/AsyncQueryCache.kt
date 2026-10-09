/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 逐字拷贝（P4），原路径 data/AsyncQueryCache.kt；ShortcutRepository 的查询缓存依赖。
 */

package com.galaxyrio.gracelauncher.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A small, shared cache: cancelling a gesture does not cancel its useful prefetch. */
internal class AsyncQueryCache<K, V : Any>(
    private val ttlMillis: Long,
    private val shouldCache: (V) -> Boolean,
    private val load: suspend (K) -> V,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxEntries: Int = 64,
    parallelism: Int = 3,
) : AutoCloseable {
    private data class Entry<V>(val value: V, val expiresAt: Long)

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(parallelism)
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    private val pending = mutableMapOf<K, Deferred<V>>()

    fun peek(key: K): V? = synchronized(lock) {
        val entry = entries[key] ?: return@synchronized null
        if (entry.expiresAt <= clock()) {
            entries.remove(key)
            null
        } else {
            entry.value
        }
    }

    suspend fun get(key: K): V {
        val query = synchronized(lock) {
            peek(key)?.let { return it }
            pending.getOrPut(key) {
                scope.async(start = CoroutineStart.LAZY) {
                    val job = currentCoroutineContext()[Job]
                    try {
                        val value = permits.withPermit { load(key) }
                        synchronized(lock) {
                            // An invalidation may have started a newer query for this key.
                            if (pending[key] === job && shouldCache(value)) {
                                entries[key] = Entry(value, clock() + ttlMillis)
                                while (entries.size > maxEntries) {
                                    entries.remove(entries.keys.first())
                                }
                            }
                        }
                        value
                    } finally {
                        synchronized(lock) {
                            if (pending[key] === job) pending.remove(key)
                        }
                    }
                }
            }
        }
        query.start()
        return query.await()
    }

    fun invalidate(matches: (K) -> Boolean = { true }) = synchronized(lock) {
        entries.keys.removeAll(matches)
        // Let existing callers finish, but never allow an obsolete result to refill the cache.
        pending.keys.removeAll(matches)
    }

    override fun close() {
        scope.cancel()
        invalidate()
    }
}
