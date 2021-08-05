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
import com.exactpro.th2.common.grpc.MessageID
import com.exactpro.th2.common.message.toJson
import com.exactpro.th2.dataprovider.grpc.AsyncDataProviderService
import com.exactpro.th2.dataprovider.grpc.EventData
import com.exactpro.th2.dataservice.zephyr.JiraApiService
import com.exactpro.th2.dataservice.zephyr.RelatedIssuesStrategiesStorage
import com.exactpro.th2.dataservice.zephyr.ZephyrApiService
import com.exactpro.th2.dataservice.zephyr.ZephyrEventProcessor
import com.exactpro.th2.dataservice.zephyr.cfg.EventProcessorCfg
import com.exactpro.th2.dataservice.zephyr.cfg.VersionCycleKey
import com.exactpro.th2.dataservice.zephyr.grpc.impl.findEventsForParent
import com.exactpro.th2.dataservice.zephyr.grpc.impl.getEventSuspend
import com.exactpro.th2.dataservice.zephyr.grpc.impl.getEventsSuspend
import com.exactpro.th2.dataservice.zephyr.grpc.impl.getMessageSuspend
import com.exactpro.th2.dataservice.zephyr.grpc.impl.searchEvents
import com.exactpro.th2.dataservice.zephyr.model.BaseExecutionStatus
import com.exactpro.th2.dataservice.zephyr.model.Cycle
import com.exactpro.th2.dataservice.zephyr.model.Execution
import com.exactpro.th2.dataservice.zephyr.model.ExecutionUpdate
import com.exactpro.th2.dataservice.zephyr.model.Folder
import com.exactpro.th2.dataservice.zephyr.model.Issue
import com.exactpro.th2.dataservice.zephyr.model.Project
import com.exactpro.th2.dataservice.zephyr.model.Version
import com.exactpro.th2.dataservice.zephyr.model.ZephyrJob
import com.exactpro.th2.dataservice.zephyr.model.extensions.findVersion
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import java.util.EnumMap

class ZephyrEventProcessorImpl(
    private val configurations: List<EventProcessorCfg>,
    private val connections: Map<String, ServiceHolder>,
    private val dataProvider: AsyncDataProviderService,
    private val strategies: RelatedIssuesStrategiesStorage,
) : ZephyrEventProcessor {
    constructor(
        configuration: EventProcessorCfg,
        connections: Map<String, ServiceHolder>,
        dataProvider: AsyncDataProviderService,
        knownStrategies: RelatedIssuesStrategiesStorage,
    ) : this(listOf(configuration), connections, dataProvider, knownStrategies)

    private val statusMapping: Map<String, Map<EventStatus, BaseExecutionStatus>> = runBlocking {
        configurations.associate { cfg ->
            val services: ServiceHolder = requireNotNull(connections[cfg.destination]) { "not connection with name ${cfg.destination}" }
            LOGGER.info { "Requesting statuses for connection named ${cfg.destination}" }
            val statuses = services.zephyr.getExecutionStatuses().associateBy { it.name }
            val mapping = EnumMap<EventStatus, BaseExecutionStatus>(EventStatus::class.java).apply {
                cfg.statusMapping.forEach { (eventStatus, zephyrStatusName) ->
                    put(eventStatus, requireNotNull(statuses[zephyrStatusName]) {
                        "Cannot find status $zephyrStatusName in Zephyr from connection ${cfg.destination}. Known statuses: ${statuses.values}"
                    })
                }
            }
            cfg.destination to mapping
        }
    }

    override suspend fun onEvent(event: EventData): Boolean {
        val eventName = event.eventName
        LOGGER.trace { "Processing event ${event.toJson()}" }
        val matchesIssue: List<EventProcessorCfg> = matchesIssue(eventName)
        if (matchesIssue.isEmpty()) {
            return false
        }

        LOGGER.trace { "Found ${matchesIssue.size} match(es) to process event ${event.shortString}" }
        for (processorCfg in matchesIssue) {
            val connectionName = processorCfg.destination
            LOGGER.trace { "Gathering status for run based on event ${event.shortString}" }
            val eventStatus: EventStatus = gatherExecutionStatus(event, processorCfg.followMessageLinks, processorCfg.doNotFollowMessageLinksFromEvents)
            val services: ServiceHolder = checkNotNull(connections[connectionName]) { "Cannot find the connected services for name $connectionName" }
            val executionStatus: BaseExecutionStatus = getExecutionStatusForEvent(connectionName, eventStatus)
            EventProcessorContext(services, processorCfg).processEvent(eventName, event, executionStatus)
        }
        return true
    }

    private fun getExecutionStatusForEvent(
        connectionName: String,
        eventStatus: EventStatus
    ): BaseExecutionStatus {
        return checkNotNull(statusMapping[connectionName]?.get(eventStatus)) {
            "Cannot find the status mapping for $eventStatus"
        }
    }

    private suspend fun EventProcessorContext.processEvent(eventName: String, event: EventData, executionStatus: BaseExecutionStatus) {
        LOGGER.trace { "Getting information project and versions for event ${event.shortString}" }
        val issue: Issue = getIssue(eventName)
        val rootEvent: EventData? = findRootEvent(event)
        val folderEvent: EventData? = if (event.hasParentEventId() && event.parentEventId != rootEvent?.eventId) {
            dataProvider.getEventSuspend(event.parentEventId)
        } else {
            null
        }
        val folderName: String? = folderEvent?.eventName ?: configuration.folders.asSequence()
            .filter { it.value.contains(issue.key) }
            .map { it.key }
            .firstOrNull()
        val versionCycleKey: VersionCycleKey = extractVersionCycleKey(rootEvent, issue)

        updateOrCreateExecution(event, issue, versionCycleKey, folderName, executionStatus)

        configuration.relatedIssuesStrategies.forEach {
            val strategy = strategies[it]
            LOGGER.info { "Extracting related issues with strategy ${strategy::class.java.canonicalName}" }
            strategy.findRelatedFor(services, issue).forEach { relatedIssue ->
                updateOrCreateExecution(event, relatedIssue, versionCycleKey, folderName, executionStatus)
            }
        }
    }

    private suspend fun EventProcessorContext.updateOrCreateExecution(
        event: EventData,
        issue: Issue,
        versionCycleKey: VersionCycleKey,
        folderName: String?,
        executionStatus: BaseExecutionStatus
    ) {
        val project: Project = getProject(issue)
        val (cycleName: String, version: Version) = with(versionCycleKey) {
            cycle to checkNotNull(project.findVersion(version)) {
                "Cannot find version $version for project ${project.name}"
            }
        }

        LOGGER.trace { "Getting cycle $cycleName for event ${event.shortString}" }
        val cycle: Cycle = getOrCreateCycle(cycleName, project, version)

        val folder: Folder? = folderName?.let {
            LOGGER.trace { "Getting folder $it for event ${event.shortString}" }
            getOrCreateFolderIfNeeded(cycle, it)
        }

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

    private suspend fun EventProcessorContext.getOrCreateExecution(
        project: Project,
        version: Version,
        cycle: Cycle,
        folder: Folder?,
        issue: Issue
    ): Execution? {
        return zephyr.findExecution(project, version, cycle, folder, issue) ?: run {
            val job = if (folder == null) {
                addTestToCycle(issue, cycle)
            } else {
                addTestToFolder(issue, folder)
            }
            withTimeout(configuration.jobAwaitTimeout) {
                zephyr.awaitJobDone(job)
            }
            zephyr.findExecution(project, version, cycle, folder, issue)
        }
    }

    private suspend fun EventProcessorContext.addTestToFolder(issue: Issue, folder: Folder): ZephyrJob {
        LOGGER.debug { "Adding the test ${issue.key} to folder ${folder.name}" }
        return zephyr.addTestToFolder(folder, issue)
    }

    private suspend fun EventProcessorContext.addTestToCycle(issue: Issue, cycle: Cycle): ZephyrJob {
        LOGGER.debug { "Adding the test ${issue.key} to cycle ${cycle.name}" }
        return zephyr.addTestToCycle(cycle, issue)
    }

    private suspend fun EventProcessorContext.getProject(issue: Issue): Project {
        return jira.projectByKey(issue.projectKey)
    }

    private suspend fun EventProcessorContext.getOrCreateCycle(cycleName: String, project: Project, version: Version): Cycle {
        return zephyr.getCycle(cycleName, project, version) ?: run {
            LOGGER.debug { "Crating cycle $cycleName for project ${project.name} version ${version.name}" }
            zephyr.createCycle(cycleName, project, version)
        }
    }

    private suspend fun EventProcessorContext.getIssue(eventName: String): Issue {
        return jira.issueByKey(eventName.toIssueKey())
    }

    private suspend fun gatherExecutionStatus(event: EventData, followMessageLinks: Boolean, skipEvents: Set<String>): EventStatus {
        val status: EventStatus = event.successful
        return if (followMessageLinks && status != EventStatus.FAILED) {
            LOGGER.info { "Gathering status for event ${event.shortString}" }
            val searchResult = findFailedEventByMessageLink(event, skipEvents)
            LOGGER.info { "Processed ${searchResult.processedEvents} events when following message links for event ${event.shortString}" }
            searchResult.result?.let { (messageId, event) ->
                LOGGER.debug { "Event ${event.shortString} has linked event ${event.shortString} reachable by linked message ${messageId.toJson()}" }
                event.successful
            } ?:run {
                LOGGER.info { "Did not find any failed events by linked messages for event ${event.shortString}" }
                EventStatus.SUCCESS
            }
        } else {
            status
        }
    }

    private class SearchResult(
        val processedEvents: Int,
        val result: Pair<MessageID, EventData>? = null
    ) {
        companion object {
            val EMPTY = SearchResult(0)
        }
    }

    private suspend fun findFailedEventByMessageLink(
        originalEvent: EventData,
        skipEvents: Set<String>,
    ): SearchResult {
        var processedEvents = 0
        findFailedEventByLink(originalEvent).also {
            processedEvents += it.processedEvents
            if (it.result != null) return it
        }
        // TODO: use trace level
        LOGGER.info { "Checking child events for event ${originalEvent.shortString}" }
        var resume: EventData? = null
        do {
            val events = dataProvider.searchEvents(findEventsForParent(originalEvent, resume)).toList()
            for (data in events) {
                if (data.eventName in skipEvents) {
                    continue
                }
                findFailedEventByMessageLink(data, skipEvents).also {
                    processedEvents += it.processedEvents
                    if (it.result != null) return SearchResult(processedEvents, it.result)
                }
            }
            events.lastOrNull()?.also { resume = it }
        } while (events.isNotEmpty())
        return if (processedEvents == 0) SearchResult.EMPTY else SearchResult(processedEvents)
    }

    private suspend fun findFailedEventByLink(event: EventData): SearchResult {
        if (event.attachedMessageIdsCount == 0) {
            return SearchResult.EMPTY
        }
        // TODO: use trace level
        LOGGER.info { "Checking message IDs attached to event ${event.shortString}" }
        var processedEvents = 0
        for (messageID in event.attachedMessageIdsList) {
            val messageData = dataProvider.getMessageSuspend(messageID).takeIf { it.attachedEventIdsCount > 1 } ?: continue
            processedEvents += messageData.attachedEventIdsCount
            LOGGER.info { "Requesting events for message ${messageData.messageId.toJson()}: ${messageData.attachedEventIdsList.joinToString { it.toJson() }}" }
            messageData.attachedEventIdsList.chunked(64).forEach { ids ->
                dataProvider.getEventsSuspend(ids).firstOrNull {
                    it.successful != EventStatus.SUCCESS
                }?.also { return SearchResult(processedEvents, messageData.messageId to it) }
            }
        }
        return SearchResult(processedEvents)
    }

    private fun EventProcessorContext.extractVersionCycleKey(
        parent: EventData?,
        issue: Issue,
    ): VersionCycleKey {
        return if (parent == null) {
            getCycleNameAndVersionFromCfg(issue)
        } else {
            val split = parent.eventName.split(configuration.delimiter)
            check(split.size >= 2) { "The parent event's name ${parent.shortString} has incorrect format" }
            val (version: String, cycleName: String) = split
            VersionCycleKey(version, cycleName)
        }
    }

    private fun EventProcessorContext.getCycleNameAndVersionFromCfg(issue: Issue): VersionCycleKey {
        val key = configuration.defaultCycleAndVersions.asSequence()
            .find { it.value.contains(issue.key) }
            ?.key
        checkNotNull(key) { "Cannot find the version and cycle in the configuration for issue ${issue.key}" }
        return key
    }

    private suspend fun EventProcessorContext.getOrCreateFolderIfNeeded(cycle: Cycle, folderName: String): Folder {
        return zephyr.getFolder(cycle, folderName) ?: run {
            LOGGER.debug { "Creating folder $folderName for cycle ${cycle.name}" }
            zephyr.createFolder(cycle, folderName)
        }
    }

    private fun matchesIssue(eventName: String): List<EventProcessorCfg> {
        return configurations.filter { it.issueRegexp.matches(eventName) }
    }

    private fun String.toIssueKey(): String = replace('_', '-')

    private class EventProcessorContext(
        val services: ServiceHolder,
        val configuration: EventProcessorCfg
    ) {
        val jira: JiraApiService
            get() = services.jira
        val zephyr: ZephyrApiService
            get() = services.zephyr
    }

    companion object {
        private val LOGGER = KotlinLogging.logger { }
        private val EventData.shortString: String
            get() = "id: ${eventId.toJson()}; name: $eventName"
    }
}
