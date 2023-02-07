/*
 * Copyright 2022 Exactpro (Exactpro Systems Limited)
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

package com.exactpro.th2.dataprocessor.zephyr

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.event.EventUtils
import com.exactpro.th2.dataprocessor.zephyr.impl.AbstractZephyrProcessor
import com.nhaarman.mockitokotlin2.argThat
import com.nhaarman.mockitokotlin2.isA
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.same
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.verifyZeroInteractions
import com.nhaarman.mockitokotlin2.whenever
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Test
import java.time.Instant

@ExperimentalCoroutinesApi
class TestZephyrEventProcessor {
    private val testScope = TestCoroutineScope()
    private val processorMock = mock<AbstractZephyrProcessor<*>> { }

    private val onInfoMock: (Event) -> Unit = mock { }

    private val onErrorMock: (GrpcEvent?, Throwable) -> Unit = mock { }

    private val service: ZephyrEventProcessor = createService(testScope)

    private fun createService(scope: CoroutineScope) =
        ZephyrEventProcessor(
            emptyMap(),
            processorMock,
            onInfoMock,
            onErrorMock,
            scope
        )

    @Test
    internal fun `calls onInfo on processed event`() {
        testScope.runBlockingTest {
            val event = GrpcEvent.newBuilder()
                .setId(EventUtils.toEventID(Instant.now(), "book", "scope", "123"))
                .build()
            whenever(processorMock.onEvent(same(event))).thenReturn(true)

            service.handle(INTERVAL_EVENT_ID, event)
            runCurrent()

            verify(onInfoMock).invoke(argThat {
                toProto(INTERVAL_EVENT_ID).run {
                    type == "ZephyrProcessedEventData" && name.startsWith("Updated test status in zephyr because of event")
                }
            })
            verifyZeroInteractions(onErrorMock)
        }
    }

    @Test
    internal fun `calls onError on exception thrown`() {
        testScope.runBlockingTest {
            val event = GrpcEvent.newBuilder()
                .setId(EventUtils.toEventID(Instant.now(), "book", "scope", "123"))
                .build()
            whenever(processorMock.onEvent(same(event))).thenThrow(IllegalStateException::class.java)

            service.handle(INTERVAL_EVENT_ID, event)
            runCurrent()

            verify(onErrorMock).invoke(same(event), isA<IllegalStateException>())
            verifyZeroInteractions(onInfoMock)
        }
    }

    companion object {
        private const val BOOK_NAME = "book"
        private const val SCOPE_NAME = "scope"

        private val INTERVAL_EVENT_ID = EventUtils.toEventID(Instant.now(), BOOK_NAME, SCOPE_NAME, "processor event id")
    }
}