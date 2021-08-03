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

import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.grpc.MessageID
import com.exactpro.th2.common.message.toJson
import com.exactpro.th2.dataprovider.grpc.AsyncDataProviderService
import com.exactpro.th2.dataprovider.grpc.EventData
import com.exactpro.th2.dataprovider.grpc.EventIds
import com.exactpro.th2.dataprovider.grpc.EventSearchRequest
import com.exactpro.th2.dataprovider.grpc.Events
import com.exactpro.th2.dataprovider.grpc.MessageData
import com.exactpro.th2.dataprovider.grpc.StreamResponse
import com.exactpro.th2.dataprovider.grpc.TimeRelation
import com.google.protobuf.Timestamp
import io.grpc.Context
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.sendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import mu.KotlinLogging
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

suspend fun AsyncDataProviderService.getEventSuspend(eventId: EventID): EventData = suspendCoroutine {
        getEvent(eventId, CoroutineSingleStreamObserver(it) { data -> data.eventId.toJson() })
    }

suspend fun AsyncDataProviderService.getEventsSuspend(eventIDs: List<EventID>): List<EventData> = suspendCoroutine<Events> { cont ->
        getEvents(
            EventIds.newBuilder().addAllIds(eventIDs).build(),
            CoroutineSingleStreamObserver(cont) { data -> data.eventsList.joinToString { it.eventId.toJson() } })
    }.eventsList

suspend fun AsyncDataProviderService.getMessageSuspend(messageID: MessageID): MessageData = suspendCoroutine {
        getMessage(messageID, CoroutineSingleStreamObserver(it) { data -> data.messageId.toJson() })
    }

@OptIn(ExperimentalCoroutinesApi::class)
fun AsyncDataProviderService.searchEvents(request: EventSearchRequest): Flow<EventData> {
    return callbackFlow {
        // TODO: use trace level
        LOGGER.info { "Start request ${request.toJson()}" }
        val observer = object : StreamObserver<StreamResponse> {
            override fun onNext(value: StreamResponse) {
                if (value.hasEvent()) {
                    value.event.runCatching { sendBlocking(this) }.onFailure {
                        LOGGER.error(it) { "Cannot send the response ${value.toJson()}" }
                    }.onSuccess {
                        LOGGER.trace { "Stream response sent: ${value.toJson()}" }
                    }
                }
            }

            override fun onError(t: Throwable) {
                cancel("gRPC error", t)
            }

            override fun onCompleted() {
                LOGGER.trace { "Completed request ${request.toJson()}" }
                channel.close()
            }
        }
        this@searchEvents.searchEvents(request, observer)
        awaitClose { LOGGER.trace { "Request ${request.toJson()} is closed" } }
    }
}

fun findEventsForParent(parent: EventData, lastEvent: EventData? = null): EventSearchRequest = EventSearchRequest.newBuilder()
    .setParentEvent(parent.eventId)
    .setStartTimestamp(Timestamp.newBuilder().setSeconds(parent.startTimestamp.seconds))
    .setSearchDirection(TimeRelation.NEXT)
    .apply {
        lastEvent?.also {
            resumeFromId = it.eventId
        }
    }
    .build()

private val LOGGER = KotlinLogging.logger { }

private class CoroutineSingleStreamObserver<T>(
    private val cont: Continuation<T>,
    private val toShortString: (T) -> String
) : StreamObserver<T> {
    @Volatile private var completed: Boolean = false
    override fun onNext(value: T) {
        LOGGER.trace { "Continuation resumed with value ${toShortString(value)}" }
        completed = true
        cont.resume(value)
    }

    override fun onError(t: Throwable) {
        LOGGER.trace(t) { "Continuation resumed with error ${t.message}" }
        cont.resumeWithException(t)
    }

    override fun onCompleted() {
        check(completed) { "Did not get any value but completed" }
    }

    companion object {
        private val LOGGER = KotlinLogging.logger {  }
    }
}