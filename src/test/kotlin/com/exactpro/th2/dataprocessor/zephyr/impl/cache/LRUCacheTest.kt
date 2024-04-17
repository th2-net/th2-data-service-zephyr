/*
 * Copyright 2020-2024 Exactpro (Exactpro Systems Limited)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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