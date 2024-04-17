package com.exactpro.th2.dataprocessor.zephyr.impl.cache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.util.function.LongSupplier

class LRUCacheTest {
    @Test
    fun `returns null if not value`() {
        val cache = createCache()
        assertNull(cache["key"], "unexpected value")
    }

    @Test
    fun `returns value if not expired yet`() {
        val cache = createCache()
        cache["key"] = 42
        assertEquals(42, cache["key"], "unexpected value")
    }

    @Test
    fun `does not return value if it is expired`() {
        val expireAfterMillis: Long = 1000
        val time = System.currentTimeMillis()
        val timeSource = mock<LongSupplier> {
            on { asLong } doReturn time doReturn time + expireAfterMillis + 1
        }
        val cache = LRUCache<String, Int>(size = 10, expireAfterMillis = expireAfterMillis, timeSource)
        cache["key"] = 42
        assertNull(cache["key"], "value should be expired")
    }

    private fun createCache(size: Int = 10, expireAfterMillis: Long = 1000): LRUCache<String, Int> {
        val cache = LRUCache<String, Int>(size = size, expireAfterMillis = expireAfterMillis)
        return cache
    }
}