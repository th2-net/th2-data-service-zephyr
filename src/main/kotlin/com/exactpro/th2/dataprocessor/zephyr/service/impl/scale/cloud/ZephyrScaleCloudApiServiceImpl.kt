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

package com.exactpro.th2.dataprocessor.zephyr.service.impl.scale.cloud

import com.exactpro.th2.dataprocessor.zephyr.cfg.Credentials
import com.exactpro.th2.dataprocessor.zephyr.cfg.HttpLoggingConfiguration
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.Issue
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.Project
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.Version
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.ZephyrScaleApiService
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.cloud.model.Execution
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.cloud.model.Folder
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.cloud.model.ZephyrProject
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.cloud.request.CreateCycle
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.cloud.request.CreateExecution
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.cloud.request.CreateFolder
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.cloud.request.SearchResult
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.model.BaseCycle
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.model.BaseFolder
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.model.Cycle
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.model.ExecutionStatus
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.model.TestCase
import com.exactpro.th2.dataprocessor.zephyr.service.impl.BaseZephyrApiService
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.http.ContentType
import io.ktor.http.contentType
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.cloud.model.BaseCycle as CloudBaseCycle
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.cloud.model.BaseFolder as CloudBaseFolder
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.cloud.model.Cycle as CloudCycle
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.cloud.model.TestCase as CloudTestCase

class ZephyrScaleCloudApiServiceImpl(
    url: String,
    credentials: Credentials,
    httpLogging: HttpLoggingConfiguration,
) : BaseZephyrApiService(url, credentials, httpLogging, API_PREFIX), ZephyrScaleApiService {

    override suspend fun getExecutionsStatuses(project: Project): List<ExecutionStatus> {
        return executeSearch(
            urlPath = "statuses",
            params = mapOf(
                "projectKey" to project.key,
                "statusType" to "TEST_EXECUTION",
            ),
        )
    }

    override suspend fun getTestCase(key: String): TestCase {
        require(key.isNotBlank()) { "key cannot be blank" }
        return client.get<CloudTestCase>("$baseApiUrl/testcases/$key").run {
            val project = client.get<ZephyrProject>(project.self)
            toCommonModel(project.key)
        }
    }

    suspend fun getFolder(project: Project, parent: Folder?, name: String): Folder? {
        return find(
            urlPath = "folders",
            params = mapOf(
                "projectKey" to project.key,
                "folderType" to "TEST_CYCLE",
            ),
        ) { folder ->
            folder.name == name && folder.parentId == parent?.id
        }
    }

    suspend fun createFolder(project: Project, parent: BaseFolder?, name: String): BaseFolder {
        return client.post("$baseApiUrl/folders") {
            contentType(ContentType.Application.Json)
            body = CreateFolder(
                parentId = parent?.id,
                name = name,
                projectKey = project.key,
                folderType = CreateFolder.Type.TEST_CYCLE,
            )
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    override suspend fun getCycle(project: Project, version: Version, folder: BaseFolder?, name: String): Cycle? {
        val cloudFolder = folder?.toCloud()
        return find<CloudCycle>(
            urlPath = "testcycles",
            params = buildMap {
                put("projectKey", project.key)
                folder?.apply { put("folderId", id.toString()) }
            }
        ) { cycle ->
            cycle.folder == cloudFolder && cycle.jiraProjectVersion.id == version.id
        }?.toCommonModel()
    }

    override suspend fun getCycle(baseCycle: BaseCycle): Cycle {
        requireNotNull(baseCycle.id) { "cycle id cannot be null" }
        return client.get<CloudCycle>("$baseApiUrl/testcycles/${baseCycle.id}").toCommonModel()
    }

    suspend fun createCycle(project: Project, version: Version, folder: BaseFolder?, name: String): BaseCycle {
        return client.post<CloudBaseCycle>("$baseApiUrl/testcycles") {
            contentType(ContentType.Application.Json)
            body = CreateCycle(
                projectKey = project.key,
                name = name,
                jiraProjectVersion = version.id,
                folderId = folder?.id,
            )
        }.toCommonModule()
    }

    override suspend fun updateExecution(
        project: Project,
        version: Version,
        cycle: BaseCycle,
        testCase: TestCase,
        status: ExecutionStatus,
        comment: String?,
        executedBy: String?
    ) {
        return client.post("$baseApiUrl/testexecutions") {
            contentType(ContentType.Application.Json)
            body = CreateExecution(
                projectKey = project.key,
                testCaseKey = testCase.key,
                testCycleKey = cycle.key,
                statusName = status.name,
                version = version.name,
                comment = comment,
            )
        }
    }

    suspend fun findExecutions(project: Project, cycle: CloudBaseCycle, issue: Issue): List<Execution> {
        return executeSearch(
            urlPath = "testexecutions",
            params = mapOf(
                "projectKey" to project.key,
                "testCycle" to cycle.key,
                "testCase" to issue.key,
            ),
        )
    }

    private suspend inline fun <reified T> executeSearch(
        urlPath: String,
        params: Map<String, String>,
    ): List<T> {
        var result: SearchResult<T> = client.get("$baseApiUrl/$urlPath") { params.forEach(this::parameter) }
        if (result.values.isEmpty()) {
            return emptyList()
        }

        val results: MutableList<T> = ArrayList()

        do {
            results += result.values
            if (result.isLast) {
                break
            }
            result = client.get(result.next ?: error("result $result must contain the link to next page but does not"))
        } while (!result.isLast)

        return results
    }

    private suspend inline fun <reified T : Any> find(
        urlPath: String,
        params: Map<String, String>,
        match: (T) -> Boolean
    ): T? {
        var result: SearchResult<T> = client.get("$baseApiUrl/$urlPath") { params.forEach(this::parameter) }
        if (result.values.isEmpty()) {
            return null
        }

        do {
            for (value in result.values) {
                if (match(value)) {
                    return value
                }
            }
            result = client.get(result.next ?: error("result $result must contain the link to next page but does not"))
        } while (!result.isLast)

        return null
    }

    companion object {
        private const val API_PREFIX = ""
    }
}

private fun CloudBaseCycle.toCommonModule(): BaseCycle = BaseCycle(id, key)

private fun CloudCycle.toCommonModel(): Cycle = Cycle(id, key, name, jiraProjectVersion.id.toString())

private fun BaseFolder.toCloud(): CloudBaseFolder = CloudBaseFolder(requireNotNull(id) { "cannot be null" })

private fun CloudTestCase.toCommonModel(projectKey: String): TestCase = TestCase(id, key, projectKey)
