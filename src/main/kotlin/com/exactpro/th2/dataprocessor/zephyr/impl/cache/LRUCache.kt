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

import org.apache.commons.collections4.map.LRUMap
import java.util.function.LongSupplier

internal class LRUCache<K, V>(
    size: Int,
    private val expireAfterMillis: Long,
    private val timeSource: LongSupplier = LongSupplier(System::currentTimeMillis),
) {
    init {
        require(size > 0) { "size must be positive but was $size" }
        require(expireAfterMillis > 0) { "expireAfterMillis must be positive but was $expireAfterMillis" }
    }
    private class TimestampedValue<T>(val value: T, val createdAt: Long)

    private val cache = LRUMap<K, TimestampedValue<V>>(size)

    operator fun get(key: K): V? = cache[key]?.takeUnless {
        timeSource.asLong - it.createdAt > expireAfterMillis
    }?.value

    operator fun set(key: K, value: V) {
        cache[key] = TimestampedValue(value, timeSource.asLong)
    }
}