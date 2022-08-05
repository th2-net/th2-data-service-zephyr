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

package com.exactpro.th2.dataprocessor.zephyr.impl

import com.exactpro.th2.common.grpc.EventStatus
import com.exactpro.th2.common.message.toJson
import com.exactpro.th2.dataprocessor.zephyr.ZephyrEventProcessor
import com.exactpro.th2.dataprocessor.zephyr.cfg.EventProcessorCfg
import com.exactpro.th2.dataprocessor.zephyr.cfg.VersionCycleKey
import com.exactpro.th2.dataprocessor.zephyr.grpc.impl.getEventSuspend
import com.exactpro.th2.dataprocessor.zephyr.service.api.JiraApiService
import com.exactpro.th2.dataprovider.grpc.AsyncDataProviderService
import com.exactpro.th2.dataprovider.grpc.EventData
import mu.KotlinLogging

abstract class AbstractZephyrProcessor<ZEPHYR : AutoCloseable>(
    private val configurations: List<EventProcessorCfg>,
    private val connections: Map<String, ServiceHolder<ZEPHYR>>,
    protected val dataProvider: AsyncDataProviderService,
) : ZephyrEventProcessor {
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
            val eventStatus: EventStatus = gatherExecutionStatus(event)
            val services: ServiceHolder<ZEPHYR> = checkNotNull(connections[connectionName]) { "Cannot find the connected services for name $connectionName" }
            EventProcessorContext(services, processorCfg).processEvent(eventName, event, eventStatus)
        }
        return true
    }

    protected abstract suspend fun EventProcessorContext<ZEPHYR>.processEvent(eventName: String, event: EventData, eventStatus: EventStatus)

    protected suspend fun EventData.findParent(match: (EventData) -> Boolean): EventData? {
        if (!hasParentEventId()) {
            return null
        }
        var curEvent: EventData = this
        while (curEvent.hasParentEventId()) {
            curEvent = dataProvider.getEventSuspend(curEvent.parentEventId)
            if (match(curEvent)) {
                return curEvent
            }
        }
        return null
    }

    protected suspend fun EventData.findRoot(): EventData? = findParent { !it.hasParentEventId() }

    protected fun matchesIssue(eventName: String): List<EventProcessorCfg> {
        return configurations.filter { it.issueRegexp.matches(eventName) }
    }

    protected fun EventProcessorContext<*>.getCycleNameAndVersionFromCfg(key: String): VersionCycleKey {
        val versionAndCycle = configuration.defaultCycleAndVersions.asSequence()
            .find { it.value.contains(key) }
            ?.key
        checkNotNull(versionAndCycle) { "Cannot find the version and cycle in the configuration for issue $versionAndCycle" }
        return versionAndCycle
    }

    protected fun String.toIssueKey(): String = replace('_', '-')

    protected val EventData.shortString: String
        get() = "id: ${eventId.toJson()}; name: $eventName"

    protected open suspend fun gatherExecutionStatus(event: EventData): EventStatus {
        // TODO: check relations by messages
        return event.successful
    }

    protected class EventProcessorContext<out ZEPHYR : AutoCloseable>(
        val services: ServiceHolder<ZEPHYR>,
        val configuration: EventProcessorCfg
    ) {
        val jira: JiraApiService
            get() = services.jira
        val zephyr: ZEPHYR
            get() = services.zephyr
    }

    companion object {
        private val LOGGER = KotlinLogging.logger { }
    }
}