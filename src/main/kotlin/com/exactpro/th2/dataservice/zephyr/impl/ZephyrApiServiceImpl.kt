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

package com.exactpro.th2.dataservice.zephyr.impl

import com.exactpro.th2.dataservice.zephyr.ZephyrApiService
import com.exactpro.th2.dataservice.zephyr.model.BaseFolder
import com.exactpro.th2.dataservice.zephyr.model.Cycle
import com.exactpro.th2.dataservice.zephyr.model.ExecutionRequest
import com.exactpro.th2.dataservice.zephyr.model.ExecutionResponse
import com.exactpro.th2.dataservice.zephyr.model.ExecutionStatus
import com.exactpro.th2.dataservice.zephyr.model.ExecutionUpdate
import com.exactpro.th2.dataservice.zephyr.model.Folder
import com.exactpro.th2.dataservice.zephyr.model.Issue
import com.exactpro.th2.dataservice.zephyr.model.JobResult
import com.exactpro.th2.dataservice.zephyr.model.Project
import com.exactpro.th2.dataservice.zephyr.model.TestRequest
import com.exactpro.th2.dataservice.zephyr.model.Version
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.json.JacksonSerializer
import io.ktor.client.features.json.Json
import io.ktor.client.features.logging.LogLevel
import io.ktor.client.features.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentType
import mu.KotlinLogging
import java.net.URI

class ZephyrApiServiceImpl(
    url: String,
    private val accessKey: String,
    private val secretKey: String,
    private val accountId: String
) : ZephyrApiService {
    private val baseUrl: String = url.run { if (endsWith('/')) this else "$this/" }
    private val client = HttpClient(CIO) {
        install(JwtAuthentication) {
            accessKey = this@ZephyrApiServiceImpl.accessKey
            secretKey = this@ZephyrApiServiceImpl.secretKey
            accountId = this@ZephyrApiServiceImpl.accountId
            baseUrl = URI.create(this@ZephyrApiServiceImpl.baseUrl)
        }
        Json {
            serializer = JacksonSerializer()
        }
        Logging {
            level = LogLevel.INFO
        }
    }
    private val baseApiUrl: String = "$baseUrl/$API_PREFIX"

    override suspend fun getCycle(cycleName: String, project: Project, version: Version): Cycle {
        LOGGER.trace { "Getting cycle for with name '$cycleName'" }
        val cycles = client.get<List<Cycle>>(URLBuilder("$baseApiUrl/cycles/search").apply {
            with(parameters) {
                append(PROJECT_ID_PARAMETER, project.id.toString())
                append(VERSION_ID_PARAMETER, version.id.toString())
            }
        }.build())
        LOGGER.debug { "Found ${cycles.size} cycle(s) for project ${project.key} and version $version" }
        return requireNotNull(cycles.find { it.name == cycleName }) {
            "Cannot find cycle with name $cycleName"
        }
    }

    override suspend fun getExecutionStatuses(): List<ExecutionStatus> {
        LOGGER.trace { "Getting execution statuses" }
        return client.get(Url("$baseApiUrl/execution/statuses"))
    }

    override suspend fun createExecution(request: ExecutionRequest): ExecutionResponse {
        LOGGER.trace { "Creating execution $request" }
        return client.post(Url("$baseApiUrl/execution")) {
            contentType(ContentType.Application.Json)
            body = request
        }
    }

    override suspend fun updateExecution(update: ExecutionUpdate): ExecutionResponse {
        LOGGER.trace { "Updating execution $update" }
        return client.put(Url("$baseApiUrl/execution/${update.id}")) {
            contentType(ContentType.Application.Json)
            body = update
        }
    }

    override suspend fun addTestToCycle(cycle: Cycle, test: Issue): JobResult {
        LOGGER.trace { "Adding test $test to cycle ${cycle.name}" }
        return client.post(Url("$baseApiUrl/executions/add/cycle/${cycle.id}")) {
            contentType(ContentType.Application.Json)
            body = TestRequest(
                issues = listOf(test.key),
                projectId = cycle.projectId,
                versionId = cycle.versionId,
                method = TestRequest.BY_KEYS
            )
        }
    }

    override suspend fun addTestToFolder(folder: Folder, test: Issue): JobResult {
        LOGGER.trace { "Adding test $test to folder ${folder.name}" }
        return client.post(Url("$baseApiUrl/executions/add/folder/${folder.id}")) {
            contentType(ContentType.Application.Json)
            body = TestRequest(
                issues = listOf(test.key),
                projectId = folder.projectId,
                versionId = folder.versionId,
                method = TestRequest.BY_KEYS
            )
        }
    }

    override suspend fun createFolder(cycle: Cycle, folderName: String): Folder {
        LOGGER.trace { "Creating folder $folderName in cycle ${cycle.name}" }
        return client.post(Url("$baseApiUrl/folder")) {
            contentType(ContentType.Application.Json)
            body = BaseFolder(
                name = folderName,
                projectId = cycle.projectId,
                versionId = cycle.versionId,
                cycleId = cycle.id
            )
        }
    }

    override fun close() {
        LOGGER.info { "Disposing resources for Zephyr service" }
        runCatching { client.close() }
            .onFailure { LOGGER.error(it) { "Cannot close the Zephyr HTTP client" } }
    }

    companion object {
        private val LOGGER = KotlinLogging.logger { }

        private const val PROJECT_ID_PARAMETER = "projectId"
        private const val VERSION_ID_PARAMETER = "versionId"
        private const val API_PREFIX = "public/rest/api/1.0" // TODO: make configurable
    }
}

