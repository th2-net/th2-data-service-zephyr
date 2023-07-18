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
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.AccountInfo
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
import mu.KotlinLogging
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
        LOGGER.trace { "Getting statuses for project ${project.key}" }
        return executeSearch(
            urlPath = "statuses",
            params = mapOf(
                "projectKey" to project.key,
                "statusType" to "TEST_EXECUTION",
            ),
        )
    }

    override suspend fun getTestCase(key: String): TestCase {
        LOGGER.trace { "Getting test case with key $key" }
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

    override suspend fun getCycle(project: Project, version: Version, folder: BaseFolder?, name: String): Cycle? {
        LOGGER.trace { "Getting cycle for project ${project.key} with version ${version.name}, folder ${folder?.name} and name $name" }
        val cloudFolder = folder?.toCloud()
        return find<CloudCycle>(
            urlPath = "testcycles",
            params = buildMap {
                put("projectKey", project.key)
                put("maxResults", "1000")
                folder?.apply { put("folderId", id.toString()) }
            }
        ) { cycle ->
            cloudFolder?.let { it == cycle.folder } != false && cycle.jiraProjectVersion?.id == version.id && cycle.name == name
        }?.toCommonModel()
    }

    override suspend fun getCycle(baseCycle: BaseCycle): Cycle {
        LOGGER.trace { "Getting cycle for key ${baseCycle.key}" }
        val cycle = client.get<CloudCycle>("$baseApiUrl/testcycles/${baseCycle.key}")
        return cycle.toCommonModel()
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
        accountInfo: AccountInfo?
    ) {
        throw UnsupportedOperationException("the update execution is not supported by Zephyr Scale Cloud")
    }

    override suspend fun createExecution(
        project: Project,
        version: Version,
        cycle: BaseCycle,
        testCase: TestCase,
        status: ExecutionStatus,
        comment: String?,
        accountInfo: AccountInfo?
    ) {
        LOGGER.trace { "Creating execution: project ${project.key} with version ${version.name}, cycle ${cycle.key}, " +
            "test case ${testCase.key}, status $status, comment: $comment, accountInfo: $accountInfo" }
        return client.post("$baseApiUrl/testexecutions") {
            contentType(ContentType.Application.Json)
            body = CreateExecution(
                projectKey = project.key,
                testCaseKey = testCase.key,
                testCycleKey = cycle.key,
                statusName = status.name,
                version = version.name,
                comment = comment,
                executedById = accountInfo?.accountId,
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
        } while (true)

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
            if (result.isLast) {
                break
            }
            result = client.get(result.next ?: error("result $result must contain the link to next page but does not"))
        } while (true)

        return null
    }

    companion object {
        private const val API_PREFIX = "v2"
        private val LOGGER = KotlinLogging.logger { }
    }
}

private fun CloudBaseCycle.toCommonModule(): BaseCycle = BaseCycle(id, key)

private fun CloudCycle.toCommonModel(): Cycle = Cycle(id, key, name, jiraProjectVersion?.id.toString())

private fun BaseFolder.toCloud(): CloudBaseFolder = CloudBaseFolder(requireNotNull(id) { "cannot be null" })

private fun CloudTestCase.toCommonModel(projectKey: String): TestCase = TestCase(id, key, projectKey)
