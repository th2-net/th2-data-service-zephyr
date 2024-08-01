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

package com.exactpro.th2.dataprocessor.zephyr.cfg

import java.time.LocalTime
import java.util.concurrent.TimeUnit

class CachesConfiguration(
    val cycles: CacheConfiguration = CacheConfiguration(
        expireAfterSeconds = TimeUnit.DAYS.toSeconds(1),
        size = 100,
    )
)

class CacheConfiguration(
    val expireAfterSeconds: Long,
    val size: Int = 100,
    val invalidateAt: LocalTime? = null,
)