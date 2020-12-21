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

package com.exactpro.th2.dataservice.zephyr.model

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExecutionRequest(
    val projectId: Long,
    val issueId: Long,
    val cycleId: String,
    val versionId: Long = -1,
    val status: BaseExecutionStatus? = null
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class ExecutionUpdate(
    @get:JsonIgnore val id: String,
    val projectId: Long,
    val issueId: Long,
    val cycleId: String,
    val versionId: Long = -1,
    val status: BaseExecutionStatus? = null,
    val comment: String? = null,
    val defects: List<String> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExecutionResponse(
    val execution: Execution
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Execution(
    val id: String,
    val projectId: Long,
    val issueId: Long,
    val cycleId: String,
    val status: ExecutionStatus
)
