package dev.ryan.playerlist

import java.util.LinkedHashMap

// LRU (least-recently-used) bounded map used by Skylist name-styling transform caches.
// Backed by LinkedHashMap in access-order mode; evicts the oldest entry when the map
// exceeds maxEntries. All public methods are synchronized.
internal class NameStylerLruCache<K, V>(private val maxEntries: Int) : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
    @Synchronized
    fun getCached(key: K): V? = super.get(key)

    @Synchronized
    fun putCached(key: K, value: V) {
        super.put(key, value)
    }

    @Synchronized
    fun clearCache() {
        super.clear()
    }

    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
}

// Fixed-size identity cache using two-way open addressing.
// Keys are matched by JVM reference identity (===), not structural equality.
// Used only to skip redundant Skylist text-styling passes when the same
// Text or OrderedText object arrives multiple times in the same render frame.
// Not thread-safe; callers in NameStyler synchronize externally where required.
internal class NameStylerIdentityCache<K : Any, V : Any>(requestedSize: Int) {
    private val size = requestedSize.coerceAtLeast(2).nextPowerOfTwo()
    private val mask = size - 1
    private val primaryKeys = arrayOfNulls<Any>(size)
    private val primaryValues = arrayOfNulls<Any>(size)
    private val secondaryKeys = arrayOfNulls<Any>(size)
    private val secondaryValues = arrayOfNulls<Any>(size)

    fun get(key: K): V? {
        val primaryIndex = primaryIndex(key)
        if (primaryKeys[primaryIndex] === key) {
            @Suppress("UNCHECKED_CAST")
            return primaryValues[primaryIndex] as V
        }

        val secondaryIndex = secondaryIndex(key)
        if (secondaryKeys[secondaryIndex] === key) {
            @Suppress("UNCHECKED_CAST")
            return secondaryValues[secondaryIndex] as V
        }

        return null
    }

    fun put(key: K, value: V) {
        val primaryIndex = primaryIndex(key)
        if (primaryKeys[primaryIndex] === key || primaryKeys[primaryIndex] == null) {
            primaryKeys[primaryIndex] = key
            primaryValues[primaryIndex] = value
            return
        }

        val secondaryIndex = secondaryIndex(key)
        secondaryKeys[secondaryIndex] = key
        secondaryValues[secondaryIndex] = value
    }

    fun clear() {
        primaryKeys.fill(null)
        primaryValues.fill(null)
        secondaryKeys.fill(null)
        secondaryValues.fill(null)
    }

    // Maps a key to its primary slot via its JVM identity hash code.
    private fun primaryIndex(key: K): Int = System.identityHashCode(key) and mask

    // Maps a key to its secondary (fallback) slot using a simple deterministic
    // combination of the identity hash and a shifted copy. This is only for cache-slot
    // distribution; it is not cryptography, encoding, or payload construction.
    private fun secondaryIndex(key: K): Int {
        val identityHash = System.identityHashCode(key)
        val mixedHash = (identityHash * 31) + (identityHash ushr 4)
        return mixedHash and mask
    }

    private fun Int.nextPowerOfTwo(): Int {
        var value = this - 1
        value = value or (value ushr 1)
        value = value or (value ushr 2)
        value = value or (value ushr 4)
        value = value or (value ushr 8)
        value = value or (value ushr 16)
        return value + 1
    }
}
