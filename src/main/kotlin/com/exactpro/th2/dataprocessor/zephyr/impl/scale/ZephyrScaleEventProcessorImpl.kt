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

package com.exactpro.th2.dataprocessor.zephyr.impl.scale

import com.exactpro.th2.common.grpc.EventStatus
import com.exactpro.th2.dataprocessor.zephyr.cfg.EventProcessorCfg
import com.exactpro.th2.dataprocessor.zephyr.cfg.TestExecutionMode
import com.exactpro.th2.dataprocessor.zephyr.impl.AbstractZephyrProcessor
import com.exactpro.th2.dataprocessor.zephyr.impl.scale.extractors.CustomValueExtractor
import com.exactpro.th2.dataprocessor.zephyr.impl.scale.extractors.ExtractionContext
import com.exactpro.th2.dataprocessor.zephyr.impl.scale.extractors.createCustomValueExtractors
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.AccountInfo
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.Project
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.Version
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.extensions.findVersion
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.ZephyrScaleApiService
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.model.BaseCycle
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.model.Cycle
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.model.ExecutionStatus
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.model.TestCase
import com.exactpro.th2.dataprovider.grpc.AsyncDataProviderService
import com.exactpro.th2.dataprovider.grpc.EventResponse
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import org.apache.commons.collections4.map.LRUMap
import javax.annotation.concurrent.GuardedBy

class ZephyrScaleEventProcessorImpl(
    configurations: List<EventProcessorCfg>,
    connections: Map<String, ScaleServiceHolder>,
    dataProvider: AsyncDataProviderService,
) : AbstractZephyrProcessor<ZephyrScaleApiService>(configurations, connections, dataProvider) {
    constructor(
        configuration: EventProcessorCfg,
        connections: Map<String, ScaleServiceHolder>,
        dataProvider: AsyncDataProviderService,
    ) : this(listOf(configuration), connections, dataProvider)

    private val defaultVersionPattern = "(((\\d+)|([a-zA-Z]+))\\.?)+"

    private val defaultCycleRegexp = ".*\\s*\\|\\s*$defaultVersionPattern\\s*(\\|.*)?".toRegex()

    private val accountInfoByConnection: Map<String, AccountInfo> = runBlocking {
        connections.mapValues { (_, holder) ->
            holder.jira.accountInfo()
        }
    }

    private class CustomFields(val mapping: Map<String, CustomValueExtractor>)

    private val customFieldExtractors: Map<String, CustomFields> = configurations.associate {
        it.destination to CustomFields(createCustomValueExtractors(it.customFields))
    }

    private data class CycleCacheKey(
        private val projectId: Long,
        private val version: String,
        private val name: String,
    )

    private val lock = Mutex()

    @GuardedBy("lock")
    private val cycleCache = LRUMap<CycleCacheKey, Cycle>(100)

    override suspend fun EventProcessorContext<ZephyrScaleApiService>.processEvent(
        eventName: String,
        event: EventResponse,
        eventStatus: EventStatus
    ) {
        val testCaseKey = eventName.toIssueKey()
        LOGGER.trace { "Checking for test case with key $testCaseKey" }
        val testCase: TestCase = zephyr.getTestCase(testCaseKey)
        val project: Project = jira.projectByKey(testCase.projectKey)
        val executionStatus: ExecutionStatus = findExecutionStatus(project, eventStatus)
        val cycleRegex = chooseCycleRegex()
        LOGGER.trace { "Extracting cycle and version from parents of event ${event.shortString}" }
        val (cycleName, versionName) = extractCycleAndVersionOrCfgValues(event, cycleRegex, testCase)
        val version: Version = findVersion(project, versionName)
        val cycle: Cycle = findCycle(project, version, cycleName, versionName)

        createExecution(project, version, cycle, testCase, executionStatus, event)
    }

    private fun EventProcessorContext<ZephyrScaleApiService>.chooseCycleRegex(): Regex =
        when (configuration.delimiter) {
            '|' -> defaultCycleRegexp
            else -> configuration.delimiter.let {
                val versionPattern = configuration.versionPattern ?: defaultVersionPattern
                ".*\\s*$it\\s*$versionPattern\\s*($it.*)?".toRegex()
            }
        }

    private suspend fun EventProcessorContext<ZephyrScaleApiService>.createExecution(
        project: Project,
        version: Version,
        cycle: Cycle,
        testCase: TestCase,
        executionStatus: ExecutionStatus,
        event: EventResponse
    ) {
        val action: suspend (
            ZephyrScaleApiService,
            Project,
            Version,
            BaseCycle,
            TestCase,
            ExecutionStatus,
            comment: String?,
            accountInfo: AccountInfo?,
            customFields: Map<String, Any>,
        ) -> Unit = when (configuration.testExecutionMode) {
            TestExecutionMode.UPDATE_LAST -> ZephyrScaleApiService::updateExecution
            TestExecutionMode.CREATE_NEW -> ZephyrScaleApiService::createExecution
        }
        val customFields: Map<String, Any> = collectCustomFields(event, version)
        action(zephyr,
            project, version, cycle, testCase, executionStatus,
            "Updated by th2 because of event with id: ${event.eventId.id}",
            accountInfoByConnection[configuration.destination],
            customFields,
        )
    }

    private fun EventProcessorContext<ZephyrScaleApiService>.collectCustomFields(
        event: EventResponse,
        version: Version,
    ): Map<String, Any> {
        val extractors: CustomFields = customFieldExtractors.getValue(configuration.destination)
        val customFields: Map<String, Any> = if (extractors.mapping.isEmpty()) {
            emptyMap()
        } else {
            val ctx = ExtractionContext(
                event = event,
                accountInfo = accountInfoByConnection.getValue(configuration.destination),
                version = version,
            )
            extractors.mapping.mapValues { (_, extractor) ->
                extractor.extract(ctx)
            }
        }
        return customFields
    }

    private suspend fun EventProcessorContext<ZephyrScaleApiService>.findCycle(
        project: Project,
        version: Version,
        cycleName: String,
        versionName: String
    ): Cycle {
        // We cache the result because the search for cycle by name takes a lot of time
        val cacheKey = CycleCacheKey(project.id, version.name, cycleName)
        return lock.withLock {
            val cachedCycle = cycleCache[cacheKey]
            cachedCycle?.apply { LOGGER.trace { "Cycle cache hit. Key: $cacheKey, Value: $key ($name)" } }
                ?: zephyr.getCycle(project, version, folder = null, cycleName)?.also { cycleCache[cacheKey] = it }
                ?: error("cannot find cycle $cycleName for project ${project.key} and version $versionName")
        }
    }

    private fun findVersion(
        project: Project,
        versionName: String
    ): Version = project.findVersion(versionName)
        ?: error("cannot find specified version $versionName for project $project. Known versions: ${project.versions}")

    private suspend fun EventProcessorContext<ZephyrScaleApiService>.extractCycleAndVersionOrCfgValues(
        event: EventResponse,
        cycleRegex: Regex,
        testCase: TestCase
    ): Pair<String, String> = event.findParent { cycleRegex.matches(it.eventName) }
        ?.eventName?.split(configuration.delimiter)?.run { get(0).trim() to get(1).trim() }
        ?: run {
            LOGGER.warn {
                "Handled event ${event.shortString} matches issue pattern but does not have parent event with cycle and version than matches ${cycleRegex.pattern}"
            }
            val fromCfg = getCycleNameAndVersionFromCfg(testCase.key)
            fromCfg.cycle to fromCfg.version
        }

    private suspend fun EventProcessorContext<ZephyrScaleApiService>.findExecutionStatus(
        project: Project,
        eventStatus: EventStatus
    ): ExecutionStatus {
        val projectStatuses: List<ExecutionStatus> = zephyr.getExecutionsStatuses(project)
        val zephyrStatusName = (configuration.statusMapping[eventStatus]
            ?: error("cannot find mapping for event status $eventStatus"))
        return (projectStatuses.find { it.name == zephyrStatusName }
            ?: error("cannot find status $zephyrStatusName in project statuses: $projectStatuses"))
    }

    companion object {
        private val LOGGER = KotlinLogging.logger { }
    }
}