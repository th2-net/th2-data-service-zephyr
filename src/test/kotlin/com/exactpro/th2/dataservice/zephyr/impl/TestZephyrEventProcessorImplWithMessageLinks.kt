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

import com.exactpro.th2.common.event.EventUtils.toEventID
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.grpc.EventStatus
import com.exactpro.th2.common.grpc.MessageID
import com.exactpro.th2.common.message.message
import com.exactpro.th2.dataprovider.grpc.AsyncDataProviderService
import com.exactpro.th2.dataprovider.grpc.EventData
import com.exactpro.th2.dataprovider.grpc.EventIds
import com.exactpro.th2.dataprovider.grpc.EventSearchRequest
import com.exactpro.th2.dataprovider.grpc.Events
import com.exactpro.th2.dataprovider.grpc.MessageData
import com.exactpro.th2.dataprovider.grpc.MessageSearchRequest
import com.exactpro.th2.dataprovider.grpc.StreamResponse
import com.exactpro.th2.dataservice.zephyr.JiraApiService
import com.exactpro.th2.dataservice.zephyr.RelatedIssuesStrategiesStorage
import com.exactpro.th2.dataservice.zephyr.ZephyrApiService
import com.exactpro.th2.dataservice.zephyr.cfg.ConnectionCfg
import com.exactpro.th2.dataservice.zephyr.cfg.EventProcessorCfg
import com.exactpro.th2.dataservice.zephyr.model.Cycle
import com.exactpro.th2.dataservice.zephyr.model.Execution
import com.exactpro.th2.dataservice.zephyr.model.ExecutionStatus
import com.exactpro.th2.dataservice.zephyr.model.Folder
import com.exactpro.th2.dataservice.zephyr.model.Issue
import com.exactpro.th2.dataservice.zephyr.model.Project
import com.exactpro.th2.dataservice.zephyr.model.Version
import com.exactpro.th2.dataservice.zephyr.strategies.RelatedIssuesStrategy
import com.exactpro.th2.dataservice.zephyr.strategies.RelatedIssuesStrategyConfiguration
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.time.Instant

@ExperimentalCoroutinesApi
class TestZephyrEventProcessorImplWithMessageLinks {
    private val version = Version(1, "1.0.0")

    private val project = Project(
        1,
        "TEST",
        "TEST",
        listOf(version)
    )

    private val jira = mock<JiraApiService> {
        onBlocking { issueByKey(any()) }.then {
            val key = it.getArgument<String>(0)
            Issue(1, key, key.split('-').first())
        }
        onBlocking { projectByKey(eq("TEST")) }.thenReturn(project)
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
            followMessageLinks = true,
        ),
        mapOf(ConnectionCfg.DEFAULT_NAME to ServiceHolder(jira, zephyr)), dataProvider, strategiesStorage
    )

    @Test
    fun `follows the message link to gather the result execution status`() {
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
            val message = message("test", Direction.SECOND, "test").build()
            val subEvent = EventData.newBuilder()
                .setEventName("SubEvent")
                .setEventId(toEventID("4"))
                .setParentEventId(issue.eventId)
                .addAttachedMessageIds(message.metadata.id)
                .build()
            val relatedEvent = EventData.newBuilder()
                .setEventName("Related")
                .setEventId(toEventID("5"))
                .setSuccessful(EventStatus.FAILED)
                .addAttachedMessageIds(message.metadata.id)
                .build()
            val events = arrayOf(root, folderEvent, issue, subEvent, relatedEvent)
            val eventsById = events.associateBy { it.eventId }
            val eventsByMessage: Map<MessageID, MessageData> = events.asSequence()
                .filter { it.attachedMessageIdsCount > 0 }
                .flatMap { it.attachedMessageIdsList.asSequence().map { messageID -> messageID to it } }
                .groupingBy { it.first }
                .aggregate<Pair<MessageID, EventData>, MessageID, MessageData.Builder> { key, accumulator, element, _ ->
                    (accumulator ?: MessageData.newBuilder()
                        .setMessageId(key))
                        .addAttachedEventIds(element.second.eventId)
                }
                .mapValues { it.value.build() }
            whenever(dataProvider.getEvent(any(), any())).then {
                val id = it.getArgument<EventID>(0)
                val observer = it.getArgument<StreamObserver<EventData>>(1)
                eventsById[id]?.let {
                    observer.onNext(it)
                    observer.onCompleted()
                } ?: run { observer.onError(RuntimeException("Unknown id $id")) }
            }
            whenever(dataProvider.getEvents(any(), any())).then {
                val ids = it.getArgument<EventIds>(0)
                val observer = it.getArgument<StreamObserver<Events>>(1)
                Events.newBuilder()
                    .also { ids.idsList.mapNotNull { eventsById[it] }.forEach(it::addEvents) }
                    .build().also {
                        observer.onNext(it)
                        observer.onCompleted()
                    }
            }
            whenever(dataProvider.getMessage(any(), any())).then {
                val id = it.getArgument<MessageID>(0)
                val observer = it.getArgument<StreamObserver<MessageData>>(1)
                eventsByMessage[id]?.let {
                    observer.onNext(it)
                    observer.onCompleted()
                } ?: run { observer.onError(RuntimeException("Unknown id $id")) }
            }
            whenever(dataProvider.searchEvents(any(), any())).then {
                val request = it.getArgument<EventSearchRequest>(0)
                val observer = it.getArgument<StreamObserver<StreamResponse>>(1)
                events.forEach {
                    if (it.parentEventId == request.parentEvent) {
                        observer.onNext(StreamResponse.newBuilder().setEvent(it).build())
                    }
                }
                observer.onCompleted()
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

            inOrder(jira, zephyr, dataProvider) {
                verify(zephyr).getExecutionStatuses()

                verify(jira).issueByKey("TEST-1234")
                verify(jira).projectByKey("TEST")
                verify(zephyr).getCycle(eq("TestCycle"), any(), any())
                verify(zephyr).getFolder(any(), any())
                verify(zephyr).findExecution(any(), any(), any(), any(), any())
                verify(zephyr).updateExecution(argThat {
                    status?.id == 2.toLong() // failed
                })
                verifyNoMoreInteractions()
            }
        }
    }

    @Test
    fun `stops when event does not have children`() {
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
            val message = message("test", Direction.SECOND, "test").build()
            val relatedEvent = EventData.newBuilder()
                .setEventName("Related")
                .setEventId(toEventID("5"))
                .setSuccessful(EventStatus.FAILED)
                .addAttachedMessageIds(message.metadata.id)
                .build()
            val events = arrayOf(root, folderEvent, issue, relatedEvent)
            val eventsById = events.associateBy { it.eventId }
            val eventsByMessage: Map<MessageID, MessageData> = events.asSequence()
                .filter { it.attachedMessageIdsCount > 0 }
                .flatMap { it.attachedMessageIdsList.asSequence().map { messageID -> messageID to it } }
                .groupingBy { it.first }
                .aggregate<Pair<MessageID, EventData>, MessageID, MessageData.Builder> { key, accumulator, element, _ ->
                    (accumulator ?: MessageData.newBuilder()
                        .setMessageId(key))
                        .addAttachedEventIds(element.second.eventId)
                }
                .mapValues { it.value.build() }
            whenever(dataProvider.getEvent(any(), any())).then {
                val id = it.getArgument<EventID>(0)
                val observer = it.getArgument<StreamObserver<EventData>>(1)
                eventsById[id]?.let {
                    observer.onNext(it)
                    observer.onCompleted()
                } ?: run { observer.onError(RuntimeException("Unknown id $id")) }
            }
            whenever(dataProvider.getEvents(any(), any())).then {
                val ids = it.getArgument<EventIds>(0)
                val observer = it.getArgument<StreamObserver<Events>>(1)
                Events.newBuilder()
                    .also { ids.idsList.mapNotNull { eventsById[it] }.forEach(it::addEvents) }
                    .build().also {
                        observer.onNext(it)
                        observer.onCompleted()
                    }
            }
            whenever(dataProvider.getMessage(any(), any())).then {
                val id = it.getArgument<MessageID>(0)
                val observer = it.getArgument<StreamObserver<MessageData>>(1)
                eventsByMessage[id]?.let {
                    observer.onNext(it)
                    observer.onCompleted()
                } ?: run { observer.onError(RuntimeException("Unknown id $id")) }
            }
            whenever(dataProvider.searchEvents(any(), any())).then {
                val request = it.getArgument<EventSearchRequest>(0)
                val observer = it.getArgument<StreamObserver<StreamResponse>>(1)
                events.forEach {
                    if (it.parentEventId == request.parentEvent) {
                        observer.onNext(StreamResponse.newBuilder().setEvent(it).build())
                    }
                }
                observer.onCompleted()
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

            inOrder(jira, zephyr, dataProvider) {
                verify(zephyr).getExecutionStatuses()

                verify(jira).issueByKey("TEST-1234")
                verify(jira).projectByKey("TEST")
                verify(zephyr).getCycle(eq("TestCycle"), any(), any())
                verify(zephyr).getFolder(any(), any())
                verify(zephyr).findExecution(any(), any(), any(), any(), any())
                verify(zephyr).updateExecution(argThat {
                    status?.id == 1.toLong() // passed
                })
                verifyNoMoreInteractions()
            }
        }
    }
}