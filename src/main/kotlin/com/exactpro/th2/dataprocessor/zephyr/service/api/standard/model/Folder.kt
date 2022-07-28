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

package com.exactpro.th2.dataprocessor.zephyr.service.api.standard.model

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
open class BaseFolder(
    @JsonAlias("folderName") val name: String,
    val projectId: Long,
    val versionId: Long,
    val cycleId: String,
    val description: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FolderCreateResponse(
    val id: String,
    val responseMessage: String,
    val projectId: Long,
    val versionId: Long,
    val cycleId: String,
) {
    fun toFolder(name: String): Folder = Folder(id, name, projectId, versionId, cycleId)
}

@JsonIgnoreProperties(ignoreUnknown = true)
class Folder(
    @JsonAlias("folderId") val id: String,
    @JsonAlias("folderName") name: String,
    projectId: Long,
    versionId: Long,
    cycleId: String,
    description: String? = null
) : BaseFolder(name, projectId, versionId, cycleId, description)
