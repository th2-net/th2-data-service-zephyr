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

import com.exactpro.th2.common.grpc.EventStatus
import com.exactpro.th2.common.message.toJson
import com.exactpro.th2.dataprovider.grpc.AsyncDataProviderService
import com.exactpro.th2.dataprovider.grpc.EventData
import com.exactpro.th2.dataservice.zephyr.JiraApiService
import com.exactpro.th2.dataservice.zephyr.ZephyrApiService
import com.exactpro.th2.dataservice.zephyr.ZephyrEventProcessor
import com.exactpro.th2.dataservice.zephyr.cfg.EventProcessorCfg
import com.exactpro.th2.dataservice.zephyr.cfg.VersionCycleKey
import com.exactpro.th2.dataservice.zephyr.grpc.impl.getEventSuspend
import com.exactpro.th2.dataservice.zephyr.model.BaseExecutionStatus
import com.exactpro.th2.dataservice.zephyr.model.Cycle
import com.exactpro.th2.dataservice.zephyr.model.Execution
import com.exactpro.th2.dataservice.zephyr.model.ExecutionUpdate
import com.exactpro.th2.dataservice.zephyr.model.Folder
import com.exactpro.th2.dataservice.zephyr.model.Issue
import com.exactpro.th2.dataservice.zephyr.model.Project
import com.exactpro.th2.dataservice.zephyr.model.Version
import com.exactpro.th2.dataservice.zephyr.model.extensions.findVersion
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import java.util.EnumMap

class ZephyrEventProcessorImpl(
    private val configuration: EventProcessorCfg,
    private val jira: JiraApiService,
    private val zephyr: ZephyrApiService,
    private val dataProvider: AsyncDataProviderService
) : ZephyrEventProcessor {
    private val statusMapping: Map<EventStatus, BaseExecutionStatus>
    init {
        statusMapping = runBlocking {
            val statuses = zephyr.getExecutionStatuses().associateBy { it.name }
            EnumMap<EventStatus, BaseExecutionStatus>(EventStatus::class.java).apply {
                configuration.statusMapping.forEach { (eventStatus, zephyrStatusName) ->
                    put(eventStatus, requireNotNull(statuses[zephyrStatusName]) {
                        "Cannot find status $zephyrStatusName in Zephyr. Known statuses: ${statuses.values}"
                    })
                }
            }
        }
    }

    override suspend fun onEvent(event: EventData): Boolean {
        val eventName = event.eventName
        LOGGER.trace { "Processing event ${event.shortString}" }
        if (!isIssue(eventName)) {
            return false
        }
        LOGGER.trace { "Gathering status for run based on event ${event.shortString}" }
        val runStatus: EventStatus = gatherExecutionStatus(event)
        val executionStatus = checkNotNull(statusMapping[runStatus]) {
            "Cannot find the status mapping for $runStatus"
        }

        LOGGER.trace { "Getting information project and versions for event ${event.shortString}" }
        val issue: Issue = getIssue(eventName)
        val rootEvent: EventData? = findRootEvent(event)
        val project: Project = getProject(issue)
        val folderEvent: EventData? = if (event.hasParentEventId() && event.parentEventId != rootEvent?.parentEventId) {
            dataProvider.getEventSuspend(event.parentEventId)
        } else {
            null
        }
        val (cycleName: String, version: Version) = getCycleNameAndVersion(rootEvent, project, issue)

        LOGGER.trace { "Getting cycle for event ${event.shortString}" }
        val cycle: Cycle = getOrCreateCycle(cycleName, project, version)

        LOGGER.trace { "Getting folder for event ${event.shortString}" }
        val folder: Folder = getOrCreateFolder(cycle, folderEvent?.eventName, issue)

        LOGGER.trace { "Getting execution for event ${event.shortString}" }
        val execution = getOrCreateExecution(project, version, cycle, folder, issue)
        checkNotNull(execution) { "Cannot find and create the execution for test ${issue.key} in project ${project.name}, version ${version.name}" }

        LOGGER.debug { "Updating execution for event ${event.shortString} with status $executionStatus" }
        zephyr.updateExecution(
            ExecutionUpdate(
                id = execution.id,
                status = executionStatus,
                comment = "Updated by th2 because of event with id: ${event.eventId.id}"
            )
        )
        return true
    }

    private suspend fun findRootEvent(event: EventData): EventData? {
        if (!event.hasParentEventId()) {
            return null
        }
        var curEvent: EventData = event
        while (curEvent.hasParentEventId()) {
            curEvent = dataProvider.getEventSuspend(curEvent.parentEventId)
        }
        return curEvent
    }

    private suspend fun getOrCreateExecution(
        project: Project,
        version: Version,
        cycle: Cycle,
        folder: Folder,
        issue: Issue
    ): Execution? {
        return zephyr.findExecution(project, version, cycle, folder, issue) ?: run {
            LOGGER.debug { "Adding the test ${issue.key} to folder ${folder.name}" }
            val jobToken = zephyr.addTestToFolder(folder, issue)
            withTimeout(configuration.jobAwaitTimeout) {
                zephyr.awaitJobDone(jobToken)
            }
            zephyr.findExecution(project, version, cycle, folder, issue)
        }
    }

    private suspend fun getProject(issue: Issue): Project {
        return jira.projectByKey(issue.projectKey)
    }

    private suspend fun getOrCreateCycle(cycleName: String, project: Project, version: Version): Cycle {
        return zephyr.getCycle(cycleName, project, version) ?: run {
            LOGGER.debug { "Crating cycle $cycleName for project ${project.name} version ${version.name}" }
            zephyr.createCycle(cycleName, project, version)
        }
    }

    private suspend fun getIssue(eventName: String): Issue {
        return jira.issueByKey(eventName.toIssueKey())
    }

    private fun gatherExecutionStatus(event: EventData): EventStatus {
        // TODO: check relations by messages
        return event.successful
    }

    private fun getCycleNameAndVersion(parent: EventData?, project: Project, issue: Issue): Pair<String, Version> {
        val key = if (parent == null) {
            getCycleNameAndVersionFromCfg(issue)
        } else {
            val split = parent.eventName.split(configuration.delimiter)
            check(split.size >= 2) { "The parent event's name ${parent.shortString} has incorrect format" }
            val (version: String, cycleName: String) = split
            VersionCycleKey(version, cycleName)
        }
        return with(key) {
            cycle to checkNotNull(project.findVersion(version)) {
                "Cannot find version $version for project ${project.name}"
            }
        }
    }

    private fun getCycleNameAndVersionFromCfg(issue: Issue): VersionCycleKey {
        val key = configuration.defaultCycleAndVersions.asSequence()
            .find { it.value.contains(issue.key) }
            ?.key
        checkNotNull(key) { "Cannot find the version and cycle in the configuration for issue ${issue.key}" }
        return key
    }

    private suspend fun getOrCreateFolder(cycle: Cycle, name: String?, issue: Issue): Folder {
        val folderName = name ?: configuration.folders.asSequence()
            .filter { it.value.contains(issue.key) }
            .map { it.key }
            .first()
        return zephyr.getFolder(cycle, folderName) ?: run {
            LOGGER.debug { "Creating folder $folderName for cycle ${cycle.name}" }
            zephyr.createFolder(cycle, folderName)
        }
    }

    private fun isIssue(eventName: String): Boolean {
        return configuration.issueRegexp.matcher(eventName).matches()
    }

    private fun String.toIssueKey(): String = replace('_', '-')

    companion object {
        private val LOGGER = KotlinLogging.logger { }
        private val EventData.shortString: String
            get() = "id: ${eventId.toJson()}; name: $eventName"
    }
}
