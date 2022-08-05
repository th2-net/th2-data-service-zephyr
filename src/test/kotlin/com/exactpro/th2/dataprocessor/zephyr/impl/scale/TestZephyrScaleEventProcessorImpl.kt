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

import com.exactpro.th2.common.event.EventUtils
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.grpc.EventStatus
import com.exactpro.th2.dataprocessor.zephyr.cfg.ConnectionCfg
import com.exactpro.th2.dataprocessor.zephyr.cfg.EventProcessorCfg
import com.exactpro.th2.dataprocessor.zephyr.service.api.JiraApiService
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.Issue
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.Project
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.Version
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.ZephyrScaleApiService
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.model.Cycle
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.model.ExecutionStatus
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.model.TestCase
import com.exactpro.th2.dataprovider.grpc.AsyncDataProviderService
import com.exactpro.th2.dataprovider.grpc.EventData
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argThat
import com.nhaarman.mockitokotlin2.doAnswer
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.inOrder
import com.nhaarman.mockitokotlin2.isNull
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.same
import com.nhaarman.mockitokotlin2.whenever
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant

@ExperimentalCoroutinesApi
internal class TestZephyrScaleEventProcessorImpl {
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
    private val zephyr = mock<ZephyrScaleApiService> {
        onBlocking { getExecutionsStatuses(eq(project)) }.thenReturn(listOf(ExecutionStatus(1, "PASS"), ExecutionStatus(2, "WIP")))
        onBlocking { getTestCase(argThat { startsWith("TEST-") }) } doAnswer {
            val key: String = it.getArgument(0)
            TestCase(1, key, project.key)
        }
    }
    private val dataProvider = mock<AsyncDataProviderService> { }
    private val statusMapping: Map<EventStatus, String> = mapOf(
        EventStatus.FAILED to "WIP",
        EventStatus.SUCCESS to "PASS",
    )
    private val processor = ZephyrScaleEventProcessorImpl(
        configuration = EventProcessorCfg(
            "TEST_T\\d+",
            statusMapping = statusMapping
        ),
        mapOf(ConnectionCfg.DEFAULT_NAME to ScaleServiceHolder(jira, zephyr)), dataProvider,
    )

    @ParameterizedTest
    @EnumSource(value = EventStatus::class, names = ["UNRECOGNIZED"], mode = EnumSource.Mode.EXCLUDE)
    fun `creates all required structure for event`(testCaseStatus: EventStatus) {
        TestCoroutineScope().runBlockingTest {
            val root = EventData.newBuilder()
                .setEventId(EventUtils.toEventID("1"))
                .setEventName("Root")
                .build()
            val cycleEvent = EventData.newBuilder()
                .setEventId(EventUtils.toEventID("2"))
                .setParentEventId(root.eventId)
                .setEventName("TestCycle | 1.0.0 |${Instant.now()}")
                .build()
            val intermediateEvent = EventData.newBuilder()
                .setEventId(EventUtils.toEventID("3"))
                .setParentEventId(cycleEvent.eventId)
                .setEventName("SomeEvent")
                .build()
            val testCase = EventData.newBuilder()
                .setEventId(EventUtils.toEventID("4"))
                .setParentEventId(intermediateEvent.eventId)
                .setEventName("TEST_T1234")
                .setSuccessful(testCaseStatus)
                .build()
            val eventsById = arrayOf(root, cycleEvent, intermediateEvent, testCase).associateBy { it.eventId }
            whenever(dataProvider.getEvent(any(), any())).then {
                val id: EventID = it.getArgument(0)
                val observer: StreamObserver<EventData> = it.getArgument(1)
                eventsById[id]?.let { event ->
                    observer.onNext(event)
                    observer.onCompleted()
                } ?: run { observer.onError(RuntimeException("Unknown id $id")) }
            }
            val cycle = Cycle(1, "TEST-C1", "TestCycle", version.name)
            whenever(zephyr.getCycle(same(project), same(version), isNull(), eq("TestCycle")))
                .thenReturn(cycle)

            val processed = processor.onEvent(testCase)
            Assertions.assertTrue(processed) { "The event for issue was not processed" }

            inOrder(jira, zephyr) {
                verify(zephyr).getTestCase(eq("TEST-T1234"))
                verify(jira).projectByKey("TEST")
                verify(zephyr).getExecutionsStatuses(same(project))
                verify(zephyr).getCycle(
                    same(project),
                    same(version),
                    isNull(),
                    eq("TestCycle"),
                )
                verify(zephyr).createExecution(
                    same(project),
                    same(version),
                    same(cycle),
                    argThat { key == "TEST-T1234" },
                    argThat { name == statusMapping[testCaseStatus] },
                    argThat { contains(testCase.eventId.id) },
                )
                verifyNoMoreInteractions()
            }
        }
    }
}