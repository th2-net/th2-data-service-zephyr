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

package com.exactpro.th2.dataservice.zephyr.grpc.impl

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.message.toJson
import com.exactpro.th2.dataprovider.grpc.EventData
import com.exactpro.th2.dataservice.grpc.Check2Info
import com.exactpro.th2.dataservice.grpc.CrawlerId
import com.exactpro.th2.dataservice.grpc.CrawlerInfo
import com.exactpro.th2.dataservice.grpc.DataServiceGrpc
import com.exactpro.th2.dataservice.grpc.EventDataRequest
import com.exactpro.th2.dataservice.grpc.EventResponse
import com.exactpro.th2.dataservice.zephyr.ZephyrEventProcessor
import com.exactpro.th2.dataservice.zephyr.cfg.DataServiceCfg
import io.grpc.Context
import io.grpc.Status
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.apache.commons.lang3.exception.ExceptionUtils
import java.util.concurrent.ConcurrentHashMap

class ZephyrServiceImpl(
    private val configuration: DataServiceCfg,
    private val processor: ZephyrEventProcessor,
    private val onInfo: (Event) -> Unit,
    private val onError: (EventData?, Throwable) -> Unit,
) : DataServiceGrpc.DataServiceImplBase(), AutoCloseable {
    private val scope = CoroutineScope(CoroutineName("ZephyrService") + SupervisorJob())
    private val knownCrawlers: MutableSet<CrawlerId> = ConcurrentHashMap.newKeySet()
    private val lastEvent: MutableMap<CrawlerId, EventID> = ConcurrentHashMap()

    override fun crawlerConnect(request: CrawlerInfo, responseObserver: StreamObserver<Check2Info>) {
        LOGGER.info { "Received handshake from crawler ${request.id.toJson()}" }
        knownCrawlers += request.id
        responseObserver.onNext(Check2Info.newBuilder()
            .setName(configuration.name)
            .setVersion(configuration.versionMarker)
            .apply {
                lastEvent[request.id]?.let {
                    eventId = it
                }
            }
            .build())
        responseObserver.onCompleted()
    }

    override fun sendEvent(request: EventDataRequest, responseObserver: StreamObserver<EventResponse>) {
        LOGGER.trace { "Received request: ${request.toJson()}" }
        if (!knownCrawlers.contains(request.id)) {
            LOGGER.warn { "Received request from unknown crawler with id ${request.id.toJson()}. Sending response with HandshakeRequired = true" }
            responseObserver.onNext(EventResponse.newBuilder()
                .setStatus(com.exactpro.th2.dataservice.grpc.Status.newBuilder().setHandshakeRequired(true))
                .build())
            responseObserver.onCompleted()
            return
        }
        val context = Context.current()
        scope.launch {
            coroutineScope {
                try {
                    startCheckingContext(context)
                    LOGGER.info { "Processing request from ${request.id.toJson()} with ${request.eventDataCount} events" }
                    request.eventDataList.forEach {
                        try {
                            if (processor.onEvent(it)) {
                                onInfo(
                                    Event.start().endTimestamp()
                                        .name("Updated test status in zephyr because of event ${it.eventName} (${it.eventId.id})")
                                        .type("ZephyrProcessedEventData")
                                // TODO: add link to the event in future
                                )
                            }
                        } catch (ex: CancellationException) {
                            throw CancellationException("Event processing was canceled", ex)
                        } catch (ex: Exception) {
                            LOGGER.error(ex) { "Error during processing event with id ${it.eventId.id}" }
                            onError(it, ex)
                            // TODO: should the processing stop here?
                        }
                    }
                    val lastEventId = request.eventDataList.last().eventId
                    lastEvent[request.id] = lastEventId
                    responseObserver.onNext(EventResponse.newBuilder()
                        .setId(lastEventId)
                        .build())
                    responseObserver.onCompleted()
                } catch (ex: CancellationException) {
                    LOGGER.info { "Request was canceled" }
                    responseObserver.onError((if (scope.isActive) Status.DEADLINE_EXCEEDED else Status.CANCELLED)
                        .withDescription(ex.message).asException())
                } catch (ex: Exception) {
                    LOGGER.error(ex) { "Cannot execute request" }
                    responseObserver.onError(Status.INTERNAL.withDescription(ExceptionUtils.getRootCauseMessage(ex)).asException())
                    onError(null, ex)
                } finally {
                    coroutineContext.cancelChildren()
                }
            }
        }
    }

    private fun CoroutineScope.startCheckingContext(context: Context) {
        launch {
            while (isActive) {
                if (context.isCancelled) {
                    LOGGER.info { "Context canceled. Canceling the request" }
                    this@startCheckingContext.cancel("Request was canceled")
                }
                delay(100)
            }
        }
    }

    override fun close() {
        LOGGER.info { "Canceling all tasks in the Zephyr service scope" }
        scope.cancel("Closing the Zephyr service")
    }

    companion object {
        private val LOGGER = KotlinLogging.logger { }
    }
}