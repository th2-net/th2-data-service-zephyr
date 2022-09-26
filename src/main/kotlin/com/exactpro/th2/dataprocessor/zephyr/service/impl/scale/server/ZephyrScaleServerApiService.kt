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

package com.exactpro.th2.dataprocessor.zephyr.service.impl.scale.server

import com.exactpro.th2.dataprocessor.zephyr.cfg.Credentials
import com.exactpro.th2.dataprocessor.zephyr.cfg.HttpLoggingConfiguration
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.Project
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.Version
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.ZephyrScaleApiService
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.model.BaseCycle
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.model.BaseFolder
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.model.Cycle
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.model.ExecutionStatus
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.model.TestCase
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.server.request.CreateExecution
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.server.request.ExecutionCreatedResponse
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.server.request.ExecutionPreservedFields
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.server.request.UpdateExecution
import com.exactpro.th2.dataprocessor.zephyr.service.impl.BaseZephyrApiService
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.http.ContentType
import io.ktor.http.contentType
import mu.KotlinLogging
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.server.model.Cycle as ServerCycle

class ZephyrScaleServerApiService(
    url: String,
    credentials: Credentials,
    httpLogging: HttpLoggingConfiguration
) : BaseZephyrApiService(url, credentials, httpLogging, "rest/atm/1.0"), ZephyrScaleApiService {
    override suspend fun getExecutionsStatuses(project: Project): List<ExecutionStatus> {
        return client.get("$baseUrl/rest/tests/1.0/project/${project.id}/testresultstatus")
    }

    override suspend fun getTestCase(key: String): TestCase {
        require(key.isNotBlank()) { "test case key cannot be blank" }
        LOGGER.trace { "Getting test case $key" }
        return client.get("$baseApiUrl/testcase/$key")
    }

    override suspend fun getCycle(project: Project, version: Version, folder: BaseFolder?, name: String): Cycle? {
        val maxResults = 100
        var currentIndex: Int? = null
        do {
            val cycles = searchCycles(maxResults, currentIndex, project)
            cycles.forEach { cycle ->
                if (cycle.name == name && cycle.version == version.name && folder?.let { it.name == cycle.folder } != false) {
                    return cycle.toCommonModel()
                }
            }
            currentIndex = (currentIndex ?: -1) + cycles.size
        } while (cycles.isNotEmpty())
        return null
    }

    override suspend fun getCycle(baseCycle: BaseCycle): Cycle {
        LOGGER.trace { "Getting cycle by key ${baseCycle.key}" }
        return client.get<ServerCycle>("$baseApiUrl/testrun/${baseCycle.key}") {
            parameter("fields", "key,name,version")
        }.toCommonModel()
    }

    private suspend fun getLastExecution(testRunKey: String): ExecutionPreservedFields = client
        .get<List<ExecutionPreservedFields>>("$baseApiUrl/testrun/$testRunKey/testresults")
        .maxByOrNull { it.id }
        ?: throw NoSuchElementException("Test Results for Test Run ($testRunKey) not found.")

    override suspend fun updateExecution(
        project: Project,
        version: Version,
        cycle: BaseCycle,
        testCase: TestCase,
        status: ExecutionStatus,
        comment: String?,
        executedBy: String?
    ) {
        LOGGER.trace { "Updating execution for test case ${testCase.key} with status ${status.name} in cycle ${cycle.key}" }

        val lastExecution = getLastExecution(cycle.key)

        val result = client.put<ExecutionCreatedResponse>("${baseApiUrl}/testrun/${cycle.key}/testcase/${testCase.key}/testresult") {
            contentType(ContentType.Application.Json)
            body = UpdateExecution(
                status = status.name,
                version = version.name,
                comment = comment,
                executedBy = executedBy,
                assignedTo = lastExecution.assignedTo,
                environment = lastExecution.environment
            )
        }

        LOGGER.trace { "Execution id: ${result.id}" }
    }

    override suspend fun createExecution(
        project: Project,
        version: Version,
        cycle: BaseCycle,
        testCase: TestCase,
        status: ExecutionStatus,
        comment: String?,
        executedBy: String?
    ) {
        LOGGER.trace { "Creating execution for test case ${testCase.key} with status ${status.name} in cycle ${cycle.key}" }
        val result = client.post<ExecutionCreatedResponse>("${baseApiUrl}/testrun/${cycle.key}/testcase/${testCase.key}/testresult") {
            contentType(ContentType.Application.Json)
            body = CreateExecution(
                status = status.name,
                version = version.name,
                comment = comment,
                executedBy = executedBy,
            )
        }
        LOGGER.trace { "Execution id: ${result.id}" }
    }

    private suspend fun searchCycles(masResults: Int, startAt: Int?, project: Project): List<ServerCycle> {
        LOGGER.trace { "Requesting cycles for project ${project.key}: maxResults=$masResults, startAt=$startAt" }
        return client.get("$baseApiUrl/testrun/search") {
            parameter("query", "projectKey=\"${project.key}\"")
            parameter("fields", "key,name,version,folder")
            parameter("maxResults", masResults)
            parameter("startAt", startAt)
        }
    }

    companion object {
        private val LOGGER = KotlinLogging.logger { }
    }
}

private fun ServerCycle.toCommonModel(): Cycle = Cycle(null, key, name, version ?: "")