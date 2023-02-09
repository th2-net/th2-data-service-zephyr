/*
 * Copyright 2020-2022 Exactpro (Exactpro Systems Limited)
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

package com.exactpro.th2.dataprocessor.zephyr.service.api.scale.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
class TestCase(
    val id: Long? = null,
    val key: String,
    val projectKey: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
open class BaseExecutionStatus(
    val id: Long,
)

@JsonIgnoreProperties(ignoreUnknown = true)
class ExecutionStatus(
    id: Long,
    val name: String,
) : BaseExecutionStatus(id) {
    override fun toString(): String = name
}

open class BaseCycle(
    val id: Long?,
    val key: String,
)

class Cycle(
    id: Long?,
    key: String,
    val name: String,
    val version: String,
) : BaseCycle(id, key)

class BaseFolder(
    val id: Long?,
    val name: String?,
)