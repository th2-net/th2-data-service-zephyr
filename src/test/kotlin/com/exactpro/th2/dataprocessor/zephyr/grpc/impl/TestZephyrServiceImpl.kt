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

package com.exactpro.th2.dataprocessor.zephyr.grpc.impl

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.event.EventUtils
import com.exactpro.th2.crawler.dataprocessor.grpc.CrawlerId
import com.exactpro.th2.crawler.dataprocessor.grpc.CrawlerInfo
import com.exactpro.th2.crawler.dataprocessor.grpc.DataProcessorInfo
import com.exactpro.th2.crawler.dataprocessor.grpc.EventDataRequest
import com.exactpro.th2.crawler.dataprocessor.grpc.EventResponse
import com.exactpro.th2.crawler.dataprocessor.grpc.Status
import com.exactpro.th2.dataprovider.grpc.EventData
import com.exactpro.th2.dataprocessor.zephyr.ZephyrEventProcessor
import com.exactpro.th2.dataprocessor.zephyr.cfg.DataServiceCfg
import com.nhaarman.mockitokotlin2.argThat
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.inOrder
import com.nhaarman.mockitokotlin2.isA
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.same
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.verifyZeroInteractions
import com.nhaarman.mockitokotlin2.whenever
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Test

@ExperimentalCoroutinesApi
class TestZephyrServiceImpl {
    private val testScope = TestCoroutineScope()
    private val crawlerInfo = CrawlerInfo.newBuilder()
        .setId(CrawlerId.newBuilder().setName("testCrawler"))
        .build()
    private val processorMock = mock<ZephyrEventProcessor> { }

    private val onInfoMock: (Event) -> Unit = mock { }

    private val onErrorMock: (EventData?, Throwable) -> Unit = mock { }

    private val service = createService(testScope)

    private fun createService(scope: CoroutineScope) =
        ZephyrServiceImpl(
            DataServiceCfg("test", "1"),
            processorMock,
            onInfoMock,
            onErrorMock,
            scope
        )

    @Test
    internal fun `correctly handle handshake`() {
        val responseObserver: StreamObserver<DataProcessorInfo> = mock { }
        service.crawlerConnect(crawlerInfo, responseObserver)
        inOrder(responseObserver) {
            verify(responseObserver).onNext(eq(DataProcessorInfo.newBuilder().setName("test").setVersion("1").build()))
            verify(responseObserver).onCompleted()
            verifyNoMoreInteractions()
        }
    }

    @Test
    internal fun `does not process events if crawler is unknown`() {
        val responseObserver: StreamObserver<EventResponse> = mock { }
        service.sendEvent(
            EventDataRequest.newBuilder()
                .setId(crawlerInfo.id)
                .addEventData(EventData.getDefaultInstance())
                .build(), responseObserver
        )

        inOrder(responseObserver) {
            val response = EventResponse.newBuilder()
                .setStatus(Status.newBuilder().setHandshakeRequired(true))
                .build()
            verify(responseObserver).onNext(eq(response))
            verify(responseObserver).onCompleted()
            verifyNoMoreInteractions()
        }
        verifyZeroInteractions(processorMock, onInfoMock, onErrorMock)
    }

    @Test
    internal fun `returns correct last id on another handshake for same crawler`() {
        testScope.runBlockingTest {
            val eventData = EventData.newBuilder().setEventId(EventUtils.toEventID("123")).build()
            whenever(processorMock.onEvent(same(eventData))).thenReturn(false)

            service.crawlerConnect(crawlerInfo, mock { })
            service.sendEvent(EventDataRequest.newBuilder()
                .setId(crawlerInfo.id)
                .addEventData(eventData)
                .build(), mock { })
            runCurrent()


            val responseObserver: StreamObserver<DataProcessorInfo> = mock { }
            service.crawlerConnect(crawlerInfo, responseObserver)
            inOrder(responseObserver) {
                verify(responseObserver).onNext(eq(DataProcessorInfo.newBuilder().setName("test").setVersion("1").setEventId(eventData.eventId).build()))
                verify(responseObserver).onCompleted()
                verifyNoMoreInteractions()
            }
        }
    }

    @Test
    internal fun `returns correct id in response`() {
        testScope.runBlockingTest {
            val eventData = EventData.newBuilder().setEventId(EventUtils.toEventID("123")).build()
            whenever(processorMock.onEvent(same(eventData))).thenReturn(false)

            service.crawlerConnect(crawlerInfo, mock { })

            val responseObserver: StreamObserver<EventResponse> = mock { }
            service.sendEvent(
                EventDataRequest.newBuilder()
                    .setId(crawlerInfo.id)
                    .addEventData(eventData)
                    .build(), responseObserver
            )
            runCurrent()


            inOrder(responseObserver) {
                verify(responseObserver).onNext(
                    eq(
                        EventResponse.newBuilder()
                            .setId(eventData.eventId)
                            .build()
                    )
                )
                verify(responseObserver).onCompleted()
                verifyNoMoreInteractions()
            }
        }
    }

    @Test
    internal fun `calls onInfo on processed event`() {
        testScope.runBlockingTest {
            val eventData = EventData.newBuilder().setEventId(EventUtils.toEventID("123")).build()
            whenever(processorMock.onEvent(same(eventData))).thenReturn(true)

            service.crawlerConnect(crawlerInfo, mock { })

            val responseObserver: StreamObserver<EventResponse> = mock { }
            service.sendEvent(
                EventDataRequest.newBuilder()
                    .setId(crawlerInfo.id)
                    .addEventData(eventData)
                    .build(), responseObserver
            )
            runCurrent()


            inOrder(responseObserver) {
                verify(responseObserver).onNext(
                    eq(
                        EventResponse.newBuilder()
                            .setId(eventData.eventId)
                            .build()
                    )
                )
                verify(responseObserver).onCompleted()
                verifyNoMoreInteractions()
            }
            verify(onInfoMock).invoke(argThat {
                toProto(null).run {
                    type == "ZephyrProcessedEventData" && name.startsWith("Updated test status in zephyr because of event")
                }
            })
            verifyZeroInteractions(onErrorMock)
        }
    }

    @Test
    internal fun `calls onError on exception thrown`() {
        testScope.runBlockingTest {
            val eventData = EventData.newBuilder().setEventId(EventUtils.toEventID("123")).build()
            whenever(processorMock.onEvent(same(eventData))).thenThrow(IllegalStateException::class.java)

            service.crawlerConnect(crawlerInfo, mock { })

            val responseObserver: StreamObserver<EventResponse> = mock { }
            service.sendEvent(
                EventDataRequest.newBuilder()
                    .setId(crawlerInfo.id)
                    .addEventData(eventData)
                    .build(), responseObserver
            )
            runCurrent()


            inOrder(responseObserver) {
                verify(responseObserver).onNext(
                    eq(
                        EventResponse.newBuilder()
                            .setId(eventData.eventId)
                            .build()
                    )
                )
                verify(responseObserver).onCompleted()
                verifyNoMoreInteractions()
            }
            verify(onErrorMock).invoke(same(eventData), isA<IllegalStateException>())
            verifyZeroInteractions(onInfoMock)
        }
    }
}