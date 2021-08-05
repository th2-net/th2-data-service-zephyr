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

import com.exactpro.th2.common.event.EventUtils.toEventID
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.grpc.EventStatus
import com.exactpro.th2.dataprovider.grpc.AsyncDataProviderService
import com.exactpro.th2.dataprovider.grpc.EventData
import com.exactpro.th2.dataprocessor.zephyr.JiraApiService
import com.exactpro.th2.dataprocessor.zephyr.RelatedIssuesStrategiesStorage
import com.exactpro.th2.dataprocessor.zephyr.ZephyrApiService
import com.exactpro.th2.dataprocessor.zephyr.cfg.ConnectionCfg
import com.exactpro.th2.dataprocessor.zephyr.cfg.EventProcessorCfg
import com.exactpro.th2.dataprocessor.zephyr.model.Cycle
import com.exactpro.th2.dataprocessor.zephyr.model.Execution
import com.exactpro.th2.dataprocessor.zephyr.model.ExecutionStatus
import com.exactpro.th2.dataprocessor.zephyr.model.Folder
import com.exactpro.th2.dataprocessor.zephyr.model.Issue
import com.exactpro.th2.dataprocessor.zephyr.model.Project
import com.exactpro.th2.dataprocessor.zephyr.model.Version
import com.exactpro.th2.dataprocessor.zephyr.strategies.RelatedIssuesStrategy
import com.exactpro.th2.dataprocessor.zephyr.strategies.RelatedIssuesStrategyConfiguration
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argThat
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.inOrder
import com.nhaarman.mockitokotlin2.isNull
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.same
import com.nhaarman.mockitokotlin2.whenever
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant

@ExperimentalCoroutinesApi
class TestZephyrEventProcessorImplWithStrategies {
    private val version = Version(1, "1.0.0")

    private val projectTest = Project(
        1,
        "TEST",
        "TEST",
        listOf(version)
    )

    private val projectBar = Project(
        1,
        "BAR",
        "BAR",
        listOf(version)
    )

    private val jira = mock<JiraApiService> {
        onBlocking { issueByKey(any()) }.then {
            val key = it.arguments[0] as String
            Issue(1, key, key.split('-').first())
        }
        onBlocking { projectByKey(eq("TEST")) }.thenReturn(projectTest)
        onBlocking { projectByKey(eq("BAR")) }.thenReturn(projectBar)
    }
    private val zephyr = mock<ZephyrApiService> {
        onBlocking { getExecutionStatuses() }.thenReturn(listOf(ExecutionStatus(1, "PASS", 1), ExecutionStatus(2, "WIP", 2)))
    }
    private val dataProvider = mock<AsyncDataProviderService> { }
    private val strategy: RelatedIssuesStrategy = mock { }
    private val strategyConfig: RelatedIssuesStrategyConfiguration = mock { }
    private val strategiesStorage: RelatedIssuesStrategiesStorage = mock {
        on { get(same(strategyConfig)) }.thenReturn(strategy)
    }

    private val processor = ZephyrEventProcessorImpl(
        configuration = EventProcessorCfg(
            "TEST_\\d+",
            statusMapping = mapOf(
                EventStatus.FAILED to "WIP",
                EventStatus.SUCCESS to "PASS",
            ),
            relatedIssuesStrategies = listOf(strategyConfig)
        ),
        mapOf(ConnectionCfg.DEFAULT_NAME to ServiceHolder(jira, zephyr)), dataProvider, strategiesStorage
    )

    @Test
    fun `adds executions for related issues`() {
        TestCoroutineScope().runBlockingTest {
            val root = EventData.newBuilder()
                .setEventId(toEventID("1"))
                .setEventName("1.0.0|TestCycle|${Instant.now()}")
                .build()
            val folderEvent = EventData.newBuilder()
                .setEventId(toEventID("2"))
                .setParentEventId(root.eventId)
                .setEventName("TestFolder")
                .build()
            val issue = EventData.newBuilder()
                .setEventId(toEventID("3"))
                .setParentEventId(folderEvent.eventId)
                .setEventName("TEST_1234")
                .build()
            val eventsById = arrayOf(root, folderEvent, issue).associateBy { it.eventId }
            whenever(dataProvider.getEvent(any(), any())).then {
                val id = it.arguments[0] as EventID
                val observer = it.arguments[1] as StreamObserver<EventData>
                eventsById[id]?.let {
                    observer.onNext(it)
                    observer.onCompleted()
                } ?: run { observer.onError(RuntimeException("Unknown id $id")) }
            }
            val cycle1 = Cycle("1", "TestCycle", 1, 1)
            val cycle2 = Cycle("2", "TestCycle", 1, 1)
            whenever(zephyr.getCycle(eq("TestCycle"), same(projectTest), same(version)))
                .thenReturn(cycle1)
            whenever(zephyr.getCycle(eq("TestCycle"), same(projectBar), same(version)))
                .thenReturn(cycle2)
            val folder1 = Folder("1", "TestFolder", 1, 1, "1")
            whenever(zephyr.getFolder(same(cycle1), eq("TestFolder")))
                .thenReturn(folder1)
            val folder2 = Folder("2", "TestFolder", 1, 1, "1")
            whenever(zephyr.getFolder(same(cycle2), eq("TestFolder")))
                .thenReturn(folder2)
            val execution1 = Execution("1", 1, 1, "1", 1, ExecutionStatus(-1, "NotTested", 1))
            val execution2 = Execution("2", 1, 1, "1", 1, ExecutionStatus(-1, "NotTested", 1))
            val execution3 = Execution("3", 1, 1, "1", 1, ExecutionStatus(-1, "NotTested", 1))
            whenever(zephyr.findExecution(same(projectTest), same(version), same(cycle1), same(folder1), argThat { key == "TEST-1234" }))
                .thenReturn(execution1)
            whenever(zephyr.findExecution(same(projectTest), same(version), same(cycle1), same(folder1), argThat { key == "TEST-42" }))
                .thenReturn(execution2)
            whenever(zephyr.findExecution(same(projectBar), same(version), same(cycle2), same(folder2), argThat { key == "BAR-42" }))
                .thenReturn(execution3)
            whenever(strategy.findRelatedFor(any(), argThat { key == "TEST-1234" })).thenReturn(
                listOf(Issue(1, "TEST-42", "TEST"), Issue(1, "BAR-42", "BAR"))
            )

            val processed = processor.onEvent(issue)
            Assertions.assertTrue(processed) { "The event for issue was not processed" }

            inOrder(jira, zephyr) {
                verify(zephyr).getExecutionStatuses()

                verify(jira).issueByKey("TEST-1234")
                verify(jira).projectByKey("TEST")
                verify(zephyr).getCycle(eq("TestCycle"), any(), any())
                verify(zephyr).getFolder(same(cycle1), any())
                verify(zephyr).findExecution(same(projectTest), same(version), same(cycle1), same(folder1), any())
                verify(zephyr).updateExecution(argThat {
                    status?.id == 1.toLong()
                })

                // related issues for project TEST
                verify(jira).projectByKey("TEST")
                verify(zephyr).getCycle(eq("TestCycle"), any(), any())
                verify(zephyr).getFolder(same(cycle1), any())
                verify(zephyr).findExecution(same(projectTest), same(version), same(cycle1), same(folder1), any())
                verify(zephyr).updateExecution(argThat {
                    status?.id == 1.toLong()
                })

                // related issues for project BAR
                verify(jira).projectByKey("BAR")
                verify(zephyr).getCycle(eq("TestCycle"), any(), any())
                verify(zephyr).getFolder(same(cycle2), any())
                verify(zephyr).findExecution(same(projectBar), same(version), same(cycle2), same(folder2), any())
                verify(zephyr).updateExecution(argThat {
                    status?.id == 1.toLong()
                })

                verifyNoMoreInteractions()
            }
        }
    }
}