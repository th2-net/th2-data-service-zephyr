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

import com.exactpro.th2.common.grpc.EventOrBuilder
import com.exactpro.th2.common.grpc.EventStatus
import com.exactpro.th2.common.message.toJson
import com.exactpro.th2.dataprocessor.zephyr.GrpcEvent
import com.exactpro.th2.dataprocessor.zephyr.cfg.EventProcessorCfg
import com.exactpro.th2.dataprocessor.zephyr.cfg.VersionCycleKey
import com.exactpro.th2.dataprocessor.zephyr.grpc.getEventSuspend
import com.exactpro.th2.dataprocessor.zephyr.service.api.JiraApiService
import com.exactpro.th2.dataprovider.lw.grpc.AsyncDataProviderService
import mu.KotlinLogging

abstract class AbstractZephyrProcessor<ZEPHYR : AutoCloseable>(
    private val configurations: List<EventProcessorCfg>,
    private val connections: Map<String, ServiceHolder<ZEPHYR>>,
    protected val dataProvider: AsyncDataProviderService,
) {
    suspend fun onEvent(event: GrpcEvent): Boolean {
        val eventName = event.name
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

    protected abstract suspend fun EventProcessorContext<ZEPHYR>.processEvent(eventName: String, event: GrpcEvent, eventStatus: EventStatus)

    protected suspend fun GrpcEvent.findParent(match: (GrpcEvent) -> Boolean): GrpcEvent? {
        if (!hasParentId()) {
            return null
        }
        var curEvent: GrpcEvent = this
        while (curEvent.hasParentId()) {
            curEvent = dataProvider.getEventSuspend(curEvent.parentId)
            if (match(curEvent)) {
                return curEvent
            }
        }
        return null
    }

    protected suspend fun GrpcEvent.findRoot(): GrpcEvent? = findParent { !it.hasParentId() }

    private fun matchesIssue(eventName: String): List<EventProcessorCfg> {
        return configurations.filter { it.issueRegexp.matches(eventName) }
    }

    protected fun EventProcessorContext<*>.getCycleNameAndVersionFromCfg(key: String): VersionCycleKey {
        val versionAndCycle = configuration.defaultCycleAndVersions.asSequence()
            .find { it.value.contains(key) }
            ?.key
        checkNotNull(versionAndCycle) { "Cannot find the version and cycle in the configuration for issue $key" }
        return versionAndCycle
    }

    protected open suspend fun gatherExecutionStatus(event: GrpcEvent): EventStatus {
        // TODO: check relations by messages
        return event.status
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

        @JvmStatic
        protected fun String.toIssueKey(): String = replace('_', '-')
        @JvmStatic
        protected val EventOrBuilder.shortString: String
            get() = "id: ${id.toJson()}; name: $name"
    }
}