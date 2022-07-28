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
import com.exactpro.th2.dataprovider.grpc.EventResponse
import com.exactpro.th2.dataprocessor.zephyr.service.api.JiraApiService
import com.exactpro.th2.dataprocessor.zephyr.service.api.standard.ZephyrApiService
import com.exactpro.th2.dataprocessor.zephyr.cfg.ConnectionCfg
import com.exactpro.th2.dataprocessor.zephyr.cfg.EventProcessorCfg
import com.exactpro.th2.dataprocessor.zephyr.service.api.standard.model.Cycle
import com.exactpro.th2.dataprocessor.zephyr.service.api.standard.request.Execution
import com.exactpro.th2.dataprocessor.zephyr.service.api.standard.model.ExecutionStatus
import com.exactpro.th2.dataprocessor.zephyr.service.api.standard.model.Folder
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.Issue
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.Project
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.Version
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
class TestZephyrEventProcessorImpl {
    private val version = Version(1, "1.0.0")

    private val project = Project(
        1,
        "TEST",
        "TEST",
        listOf(version)
    )

    private val jira = mock<JiraApiService> {
        onBlocking { issueByKey(argThat { startsWith("TEST-") }) }.then {
            val key = it.arguments[0] as String
            Issue(1, key, "TEST")
        }
        onBlocking { projectByKey(eq("TEST")) }.thenReturn(project)
    }
    private val zephyr = mock<ZephyrApiService> {
        onBlocking { getExecutionStatuses() }.thenReturn(listOf(ExecutionStatus(1, "PASS", 1), ExecutionStatus(2, "WIP", 2)))
    }
    private val dataProvider = mock<AsyncDataProviderService> { }
    private val processor = ZephyrEventProcessorImpl(
        configuration = EventProcessorCfg(
            "TEST_\\d+",
            statusMapping = mapOf(
                EventStatus.FAILED to "WIP",
                EventStatus.SUCCESS to "PASS",
            )),
        mapOf(ConnectionCfg.DEFAULT_NAME to ServiceHolder(jira, zephyr)), dataProvider, mock { }
    )

    @ParameterizedTest
    @EnumSource(value = EventStatus::class, names = ["UNRECOGNIZED"], mode = EnumSource.Mode.EXCLUDE)
    fun `creates all required structure for event`(issueStatus: EventStatus) {
        TestCoroutineScope().runBlockingTest {
            val root = EventResponse.newBuilder()
                .setEventId(toEventID("1"))
                .setEventName("1.0.0|TestCycle|${Instant.now()}")
                .build()
            val anotherLevel = EventResponse.newBuilder()
                .setEventId(toEventID("2"))
                .setParentEventId(root.eventId)
                .setEventName("Level")
                .build()
            val folderEvent = EventResponse.newBuilder()
                .setEventId(toEventID("3"))
                .setParentEventId(anotherLevel.eventId)
                .setEventName("TestFolder")
                .build()
            val issue = EventResponse.newBuilder()
                .setEventId(toEventID("4"))
                .setParentEventId(folderEvent.eventId)
                .setEventName("TEST_1234")
                .setStatus(issueStatus)
                .build()
            val eventsById = arrayOf(root, anotherLevel, folderEvent, issue).associateBy { it.eventId }
            whenever(dataProvider.getEvent(any(), any())).then {
                val id: EventID = it.getArgument(0)
                val observer: StreamObserver<EventResponse> = it.getArgument(1)
                eventsById[id]?.let { event ->
                    observer.onNext(event)
                    observer.onCompleted()
                } ?: run { observer.onError(RuntimeException("Unknown id $id")) }
            }
            val cycle = Cycle("1", "TestCycle", 1, 1)
            whenever(zephyr.getCycle(eq("TestCycle"), same(project), same(version)))
                .thenReturn(null)
            whenever(zephyr.createCycle(eq("TestCycle"), same(project), same(version)))
                .thenReturn(cycle)
            whenever(zephyr.getFolder(same(cycle), eq("TestFolder")))
                .thenReturn(null)
            val folder = Folder("1", "TestFolder", 1, 1, "1")
            whenever(zephyr.createFolder(same(cycle), eq("TestFolder")))
                .thenReturn(folder)
            val execution = Execution("1", 1, 1, "1", 1, ExecutionStatus(-1, "NotTested", 1))
            whenever(zephyr.findExecution(same(project), same(version), same(cycle), same(folder), argThat { key == "TEST-1234" }))
                .thenReturn(null, execution)

            val processed = processor.onEvent(issue)
            Assertions.assertTrue(processed) { "The event for issue was not processed" }

            inOrder(jira, zephyr) {
                verify(zephyr).getExecutionStatuses()

                verify(jira).issueByKey("TEST-1234")
                verify(jira).projectByKey("TEST")
                verify(zephyr).getCycle(eq("TestCycle"), any(), any())
                verify(zephyr).createCycle(eq("TestCycle"), any(), any())
                verify(zephyr).getFolder(any(), any())
                verify(zephyr).createFolder(any(), any())
                verify(zephyr).findExecution(any(), any(), any(), any(), any())
                verify(zephyr).addTestToFolder(same(folder), any())
                verify(zephyr).findExecution(any(), any(), any(), any(), any())
                verify(zephyr).updateExecution(argThat {
                    status?.id == (issueStatus.number + 1).toLong()
                })
                verifyNoMoreInteractions()
            }
        }
    }

    @Test
    fun `does not create all structure again`() {
        TestCoroutineScope().runBlockingTest {
            val root = EventResponse.newBuilder()
                .setEventId(toEventID("1"))
                .setEventName("1.0.0|TestCycle|${Instant.now()}")
                .build()
            val folderEvent = EventResponse.newBuilder()
                .setEventId(toEventID("2"))
                .setParentEventId(root.eventId)
                .setEventName("TestFolder")
                .build()
            val issue = EventResponse.newBuilder()
                .setEventId(toEventID("3"))
                .setParentEventId(folderEvent.eventId)
                .setEventName("TEST_1234")
                .build()
            val eventsById = arrayOf(root, folderEvent, issue).associateBy { it.eventId }
            whenever(dataProvider.getEvent(any(), any())).then {
                val id: EventID = it.getArgument(0)
                val observer: StreamObserver<EventResponse> = it.getArgument(1)
                eventsById[id]?.let { event ->
                    observer.onNext(event)
                    observer.onCompleted()
                } ?: run { observer.onError(RuntimeException("Unknown id $id")) }
            }
            val cycle = Cycle("1", "TestCycle", 1, 1)
            whenever(zephyr.getCycle(eq("TestCycle"), same(project), same(version)))
                .thenReturn(cycle)
            val folder = Folder("1", "TestFolder", 1, 1, "1")
            whenever(zephyr.getFolder(same(cycle), eq("TestFolder")))
                .thenReturn(folder)
            val execution = Execution("1", 1, 1, "1", 1, ExecutionStatus(-1, "NotTested", 1))
            whenever(zephyr.findExecution(same(project), same(version), same(cycle), same(folder), argThat { key == "TEST-1234" }))
                .thenReturn(execution)

            val processed = processor.onEvent(issue)
            Assertions.assertTrue(processed) { "The event for issue was not processed" }

            inOrder(jira, zephyr) {
                verify(zephyr).getExecutionStatuses()

                verify(jira).issueByKey("TEST-1234")
                verify(jira).projectByKey("TEST")
                verify(zephyr).getCycle(eq("TestCycle"), any(), any())
                verify(zephyr).getFolder(any(), any())
                verify(zephyr).findExecution(any(), any(), any(), any(), any())
                verify(zephyr).updateExecution(argThat {
                    status?.id == 1.toLong()
                })
                verifyNoMoreInteractions()
            }
        }
    }

    @Test
    fun `adds test to cycle if not folder found`() {
        TestCoroutineScope().runBlockingTest {
            val root = EventResponse.newBuilder()
                .setEventId(toEventID("1"))
                .setEventName("1.0.0|TestCycle|${Instant.now()}")
                .build()
            val issue = EventResponse.newBuilder()
                .setEventId(toEventID("3"))
                .setParentEventId(root.eventId)
                .setEventName("TEST_1234")
                .build()
            val eventsById = arrayOf(root, issue).associateBy { it.eventId }
            whenever(dataProvider.getEvent(any(), any())).then {
                val id: EventID = it.getArgument(0)
                val observer: StreamObserver<EventResponse> = it.getArgument(1)
                eventsById[id]?.let { event ->
                    observer.onNext(event)
                    observer.onCompleted()
                } ?: run { observer.onError(RuntimeException("Unknown id $id")) }
            }
            val cycle = Cycle("1", "TestCycle", 1, 1)
            whenever(zephyr.getCycle(eq("TestCycle"), same(project), same(version)))
                .thenReturn(cycle)
            val execution = Execution("1", 1, 1, "1", 1, ExecutionStatus(-1, "NotTested", 1))
            whenever(zephyr.findExecution(same(project), same(version), same(cycle), isNull(), argThat { key == "TEST-1234" }))
                .thenReturn(null, execution)

            val processed = processor.onEvent(issue)
            Assertions.assertTrue(processed) { "The event for issue was not processed" }

            inOrder(jira, zephyr) {
                verify(zephyr).getExecutionStatuses()

                verify(jira).issueByKey("TEST-1234")
                verify(jira).projectByKey("TEST")
                verify(zephyr).getCycle(eq("TestCycle"), any(), any())
                verify(zephyr, never()).getFolder(same(cycle), any())
                verify(zephyr).findExecution(any(), any(), any(), isNull(), any())
                verify(zephyr).addTestToCycle(same(cycle), any())
                verify(zephyr).findExecution(any(), any(), any(), isNull(), any())
                verify(zephyr).updateExecution(argThat {
                    status?.id == (EventStatus.SUCCESS.number + 1).toLong()
                })
                verifyNoMoreInteractions()
            }
        }
    }
}