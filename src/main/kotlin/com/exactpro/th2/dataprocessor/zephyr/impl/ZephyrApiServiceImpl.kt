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

package com.exactpro.th2.dataprocessor.zephyr.impl

import com.exactpro.th2.dataprocessor.zephyr.ZephyrApiService
import com.exactpro.th2.dataprocessor.zephyr.cfg.BaseAuth
import com.exactpro.th2.dataprocessor.zephyr.cfg.Credentials
import com.exactpro.th2.dataprocessor.zephyr.cfg.HttpLoggingConfiguration
import com.exactpro.th2.dataprocessor.zephyr.cfg.JwtAuth
import com.exactpro.th2.dataprocessor.zephyr.model.BaseCycle
import com.exactpro.th2.dataprocessor.zephyr.model.BaseFolder
import com.exactpro.th2.dataprocessor.zephyr.model.Cycle
import com.exactpro.th2.dataprocessor.zephyr.model.CycleCreateResponse
import com.exactpro.th2.dataprocessor.zephyr.model.CyclesById
import com.exactpro.th2.dataprocessor.zephyr.model.Execution
import com.exactpro.th2.dataprocessor.zephyr.model.ExecutionRequest
import com.exactpro.th2.dataprocessor.zephyr.model.ExecutionResponse
import com.exactpro.th2.dataprocessor.zephyr.model.ExecutionSearchResponse
import com.exactpro.th2.dataprocessor.zephyr.model.ExecutionStatus
import com.exactpro.th2.dataprocessor.zephyr.model.ExecutionUpdate
import com.exactpro.th2.dataprocessor.zephyr.model.ExecutionUpdateRequest
import com.exactpro.th2.dataprocessor.zephyr.model.ExecutionUpdateResponse
import com.exactpro.th2.dataprocessor.zephyr.model.Folder
import com.exactpro.th2.dataprocessor.zephyr.model.FolderCreateResponse
import com.exactpro.th2.dataprocessor.zephyr.model.Issue
import com.exactpro.th2.dataprocessor.zephyr.model.JobResult
import com.exactpro.th2.dataprocessor.zephyr.model.JobToken
import com.exactpro.th2.dataprocessor.zephyr.model.JobType
import com.exactpro.th2.dataprocessor.zephyr.model.Project
import com.exactpro.th2.dataprocessor.zephyr.model.TestRequest
import com.exactpro.th2.dataprocessor.zephyr.model.Version
import com.exactpro.th2.dataprocessor.zephyr.model.ZephyrJob
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.features.auth.Auth
import io.ktor.client.features.auth.providers.BasicAuthCredentials
import io.ktor.client.features.auth.providers.basic
import io.ktor.client.features.json.JacksonSerializer
import io.ktor.client.features.json.Json
import io.ktor.client.features.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import mu.KotlinLogging
import java.net.URI
import kotlin.coroutines.coroutineContext

class ZephyrApiServiceImpl(
    url: String,
    credentials: Credentials,
    private val httpLogging: HttpLoggingConfiguration
) : ZephyrApiService {
    private val baseUrl: String = url.run { if (endsWith('/')) this else "$this/" }
    private val client = HttpClient(Java) {
        when (credentials) {
            is BaseAuth -> Auth {
                basic {
                    credentials {
                        BasicAuthCredentials(credentials.username, credentials.key)
                    }
                    sendWithoutRequest { true }
                }
            }
            is JwtAuth -> install(JwtAuthentication) {
                accessKey = credentials.accessKey
                secretKey = credentials.secretKey
                accountId = requireNotNull(credentials.accountId) { "accountId must be set" }
                baseUrl = URI.create(this@ZephyrApiServiceImpl.baseUrl)
            }
        }
        Json {
            serializer = JacksonSerializer()
        }
        Logging {
            level = httpLogging.level
        }
    }
    private val baseApiUrl: String = "$baseUrl/$API_PREFIX"

    override suspend fun getCycle(cycleName: String, project: Project, version: Version): Cycle? {
        LOGGER.trace { "Getting cycle for with name '$cycleName'" }
        val cycles = client.get<CyclesById>("$baseApiUrl/cycle") {
            parameter(PROJECT_ID_PARAMETER, project.id.toString())
            parameter(VERSION_ID_PARAMETER, version.id.toString())
        }.cycles
        LOGGER.debug { "Found ${cycles.size} cycle(s) for project ${project.key} and version $version" }
        return cycles
            .asSequence()
            .map { with(it.value) { Cycle(it.key, name, projectId, versionId) } }
            .find { it.name == cycleName }
    }

    override suspend fun getFolder(cycle: Cycle, folderName: String): Folder? {
        require(folderName.isNotEmpty()) { "Folder name must not be empty" }
        LOGGER.trace { "Getting folder $folderName for project ${cycle.projectId}, version ${cycle.versionId} and cycle ${cycle.name}" }
        val folders = client.get<List<Folder>>("$baseApiUrl/cycle/${cycle.id}/folders") {
            parameter(PROJECT_ID_PARAMETER, cycle.projectId.toString())
            parameter(VERSION_ID_PARAMETER, cycle.versionId.toString())
        }
        LOGGER.debug { "Found ${folders.size} folder(s) for cycle ${cycle.name} in project ${cycle.projectId} and version ${cycle.versionId}" }
        return folders.find { it.name == folderName }
    }

    override suspend fun createCycle(cycleName: String, project: Project, version: Version): Cycle {
        LOGGER.trace { "Creating cycle $cycleName for project ${project.key} and version $version" }
        val response = client.post<CycleCreateResponse>(Url("$baseApiUrl/cycle")) {
            contentType(ContentType.Application.Json)
            body = BaseCycle(
                name = cycleName,
                projectId = project.id,
                versionId = version.id
            )
        }
        LOGGER.debug { "Folder with id ${response.id} created" }
        return client.get(Url("$baseApiUrl/cycle/${response.id}"))
    }

    override suspend fun getExecutionStatuses(): List<ExecutionStatus> {
        LOGGER.trace { "Getting execution statuses" }
        return client.get(Url("$baseApiUrl/util/testExecutionStatus"))
    }

    override suspend fun createExecution(request: ExecutionRequest): ExecutionResponse {
        LOGGER.trace { "Creating execution $request" }
        return client.post(Url("$baseApiUrl/execution")) {
            contentType(ContentType.Application.Json)
            body = request
        }
    }

    override suspend fun updateExecution(update: ExecutionUpdate): ExecutionUpdateResponse {
        LOGGER.trace { "Updating execution $update" }
        return client.put(Url("$baseApiUrl/execution/${update.id}/execute")) {
            contentType(ContentType.Application.Json)
            body = with(update) {
                ExecutionUpdateRequest(
                    status = status?.id,
                    comment = comment,
                    defects = defects
                )
            }
        }
    }

    override suspend fun addTestToCycle(cycle: Cycle, test: Issue): ZephyrJob {
        LOGGER.trace { "Adding test $test to cycle ${cycle.name}" }
        val token = client.post<JobToken>(Url("$baseApiUrl/execution/addTestsToCycle")) {
            contentType(ContentType.Application.Json)
            body = TestRequest(
                issues = listOf(test.key),
                projectId = cycle.projectId,
                versionId = cycle.versionId,
                method = TestRequest.BY_KEYS,
                cycleId = cycle.id,
            )
        }
        return ZephyrJob(token, JobType.ADD_TEST_TO_CYCLE)
    }

    override suspend fun addTestToFolder(folder: Folder, test: Issue): ZephyrJob {
        LOGGER.trace { "Adding test $test to folder ${folder.name}" }
        val token = client.post<JobToken>(Url("$baseApiUrl/execution/addTestsToCycle")) {
            contentType(ContentType.Application.Json)
            body = TestRequest(
                issues = listOf(test.key),
                projectId = folder.projectId,
                versionId = folder.versionId,
                method = TestRequest.BY_KEYS,
                cycleId = folder.cycleId,
                folderId = folder.id,
            )
        }
        return ZephyrJob(token, JobType.ADD_TEST_TO_CYCLE)
    }

    override suspend fun awaitJobDone(job: ZephyrJob) {
        LOGGER.trace { "Awaiting job ${job.token} with type $job.type is done" }
        while (coroutineContext.isActive) {
            val result = client.get<JobResult>("$baseApiUrl/execution/jobProgress/${job.token.jobProgressToken}") {
                parameter(JOB_TYPE_PARAMETER, job.type.value)
            }
            // TODO:
            //  the response contains information if the job done successfully or not.
            //  But it is in HTML format and probably might change from request to request
            //  We should check the response and report an error if it is done but with exception
            if (result.progress >= 1.0) {
                break
            }
            val delayTime: Long = 100
            LOGGER.trace { "Job is not complete yet. Current result: $result. Next try in $delayTime millis" }
            delay(delayTime)
        }
    }

    override suspend fun findExecution(project: Project, version: Version, cycle: Cycle, folder: Folder?, test: Issue): Execution? {
        LOGGER.trace { "Searching for execution of issue ${test.key} for version $version in cycle ${cycle.name}${folder?.run { " folder $name" } ?: ""}" }
        val response = client.get<ExecutionSearchResponse>("$baseApiUrl/zql/executeSearch") {
                val query = buildString {
                    append("""project = "${project.name}" AND cycleName = "${cycle.name}" AND issue = "${test.key}" AND fixVersion = ${version.name}""")
                    if (folder != null) {
                        append(""" AND folderName = "${folder.name}"""")
                    } else {
                        append(" AND folderName is EMPTY")
                    }
                }
                parameter(ZQL_QUERY_PARAMETER, query)
        }
        val executions: List<Execution> = response.executions
        LOGGER.debug { "Found ${executions.size} execution(s)" }
        check(executions.size < 2) {
            "Found more than one execution (${executions.size}) " +
                "for specified parameters: project=${project.name} version=${version.name} test=${test.key} cycle=${cycle.name}" +
                (folder?.run { " folder=${folder.name}" } ?: "")
        }
        return executions.firstOrNull()
    }

    override suspend fun createFolder(cycle: Cycle, folderName: String): Folder {
        require(folderName.isNotBlank()) { "folderName cannot be blank" }
        LOGGER.trace { "Creating folder $folderName in cycle ${cycle.name}" }
        return client.post<FolderCreateResponse>(Url("$baseApiUrl/folder/create")) {
            contentType(ContentType.Application.Json)
            body = BaseFolder(
                name = folderName,
                projectId = cycle.projectId,
                versionId = cycle.versionId,
                cycleId = cycle.id
            )
        }.toFolder(folderName)
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
        private const val CYCLE_ID_PARAMETER = "cycleId"
        private const val JOB_TYPE_PARAMETER = "type"
        private const val ZQL_QUERY_PARAMETER = "zqlQuery"
        private const val API_PREFIX = "rest/zapi/latest" // TODO: make configurable
    }
}

