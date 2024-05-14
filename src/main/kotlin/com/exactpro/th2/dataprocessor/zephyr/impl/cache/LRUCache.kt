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

import mu.KotlinLogging
import org.apache.commons.collections4.map.LRUMap
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

internal class LRUCache<K, V>(
    size: Int,
    private val expireAfterMillis: Long,
    private val timeSource: Clock = Clock.systemUTC(),
    invalidateAt: LocalTime? = null,
) {
    @Volatile
    private var nextInvalidateAt: Instant
    init {
        require(size > 0) { "size must be positive but was $size" }
        require(expireAfterMillis > 0) { "expireAfterMillis must be positive but was $expireAfterMillis" }
        nextInvalidateAt = if (invalidateAt == null) {
            Instant.MAX
        } else {
            val now = timeSource.instant()
            val next = invalidateAt.atDate(LocalDate.ofInstant(now, ZoneOffset.UTC))
                .toInstant(ZoneOffset.UTC)
            if (next <= now) {
                next.plus(1, ChronoUnit.DAYS)
            } else {
                next
            }
        }
    }
    private class TimestampedValue<T>(val value: T, val createdAt: Instant)

    private val cache = LRUMap<K, TimestampedValue<V>>(size)

    operator fun get(key: K): V? {
        val now = timeSource.instant()
        if (now >= nextInvalidateAt) {
            invalidateAll()
        }
        return cache[key]?.takeUnless {
            Duration.between(it.createdAt, now).toMillis() > expireAfterMillis
        }?.value
    }

    operator fun set(key: K, value: V) {
        cache[key] = TimestampedValue(value, timeSource.instant())
    }

    private fun invalidateAll() {
        cache.clear()
        val currentInvalidateAt = nextInvalidateAt
        var next: Instant = nextInvalidateAt
        val now = timeSource.instant()
        do {
            next = next.plus(1, ChronoUnit.DAYS)
        } while (next < now)
        nextInvalidateAt = next
        LOGGER.info { "Cache invalidated. Now: $now, Invalidate at: $currentInvalidateAt, Next invalidate at: $nextInvalidateAt" }
    }

    private companion object {
        private val LOGGER = KotlinLogging.logger { }
    }
}