/*
 * Copyright 2020-2021 Exactpro (Exactpro Systems Limited)
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

package com.exactpro.th2.dataprocessor.zephyr.model

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
class Cycle(
    val id: String,
    name: String,
    projectId: Long,
    versionId: Long
) : BaseCycle(name, projectId, versionId)

@JsonIgnoreProperties(ignoreUnknown = true)
open class BaseCycle(
    val name: String,
    val projectId: Long,
    val versionId: Long
)

class CyclesById {
    var recordsCount: Int = -1
    private val _cycles: MutableMap<String, BaseCycle> = hashMapOf()
    @field:JsonIgnore
    val cycles: Map<String, BaseCycle> = _cycles

    @JsonAnySetter
    fun setCycle(id: String, cycle: BaseCycle) {
        _cycles[id] = cycle
    }
}

data class CycleCreateResponse(
    val id: String,
    val responseMessage: String
)