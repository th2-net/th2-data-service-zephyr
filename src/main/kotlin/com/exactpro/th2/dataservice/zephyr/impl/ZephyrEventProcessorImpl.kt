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

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.dataprovider.grpc.AsyncDataProviderService
import com.exactpro.th2.dataprovider.grpc.EventData
import com.exactpro.th2.dataservice.zephyr.JiraApiService
import com.exactpro.th2.dataservice.zephyr.ZephyrApiService
import com.exactpro.th2.dataservice.zephyr.ZephyrEventProcessor
import com.exactpro.th2.dataservice.zephyr.grpc.impl.getEventSuspend
import com.exactpro.th2.dataservice.zephyr.model.BaseExecutionStatus
import com.exactpro.th2.dataservice.zephyr.model.Cycle
import com.exactpro.th2.dataservice.zephyr.model.Issue
import com.exactpro.th2.dataservice.zephyr.model.Project
import com.exactpro.th2.dataservice.zephyr.model.Version

class ZephyrEventProcessorImpl(
    private val jira: JiraApiService,
    private val zephyr: ZephyrApiService,
    private val dataProvider: AsyncDataProviderService,
    private val statusMapping: Map<Event.Status, BaseExecutionStatus>
) : ZephyrEventProcessor {

    override suspend fun onEvent(event: EventData) {
        val eventName = event.eventName
        if (isIssue(eventName)) {
            val issue: Issue = getIssue(eventName)
            val parent: EventData? = if (event.hasParentEventId()) dataProvider.getEventSuspend(event.parentEventId) else null
            val (project: Project, version: Version) = getProjectAndVersion(parent, issue)
            val cycle: Cycle = getOrCreateCycle(getCycleName(), project, version)

        }
    }

    private fun getProjectAndVersion(parent: EventData?, issue: Issue): Pair<Project, Version> {
        if (parent == null) {
            return getProjectAndVersionFromCfg(issue)
        }
        TODO("Not yet implemented")
    }

    private fun getProjectAndVersionFromCfg(issue: Issue): Pair<Project, Version> {
        TODO("Not yet implemented")
    }

    private suspend fun getOrCreateCycle(cycleName: String, project: Project, version: Version): Cycle {
        return zephyr.getCycle(cycleName, project, version) ?: zephyr.createCycle(cycleName, project, version)
    }

    private suspend fun getIssue(eventName: String): Issue {
        return jira.issueByKey(eventName.toIssueKey())
    }

    private fun getCycleName(): String {
        TODO()
    }

    private fun isIssue(eventName: String): Boolean {
        TODO("Not yet implemented")
    }
    private fun String.toIssueKey(): String {
        TODO("Not yet implemented")
    }
}
