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

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonUnwrapped

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExecutionRequest(
    val projectId: Long,
    val issueId: Long,
    val cycleId: String,
    val versionId: Long,
    val folderId: String? = null,
    val status: BaseExecutionStatus? = null
)

data class ExecutionUpdate(
    @get:JsonIgnore val id: String,
    val status: BaseExecutionStatus? = null,
    val comment: String? = null,
    val defects: List<String> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExecutionUpdateResponse(
    val id: String,
    val projectId: Long,
    val issueId: Long,
    val cycleId: String,
    val versionId: Long,
    val executionStatus: Long
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class ExecutionUpdateRequest(
    val status: Long? = null,
    val comment: String? = null,
    val defects: List<String> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
class ExecutionResponse {
    @JsonUnwrapped
    var executionById: Map<String, ExecutionUpdateResponse> = emptyMap()
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExecutionSearchResponse(
    val executions: List<Execution>,
    val currentIndex: Int = -1,
    val totalCount: Int = -1,
    val maxResultAllowed: Int = -1
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Execution(
    val id: String,
    val projectId: Long,
    val issueId: Long,
    val cycleId: String,
    val versionId: Long,
    val status: ExecutionStatus
)
