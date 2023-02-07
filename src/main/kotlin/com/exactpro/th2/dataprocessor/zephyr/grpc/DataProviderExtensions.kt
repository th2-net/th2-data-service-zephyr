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

package com.exactpro.th2.dataprocessor.zephyr.grpc

import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.message.toJson
import com.exactpro.th2.dataprocessor.zephyr.GrpcEvent
import com.exactpro.th2.dataprovider.lw.grpc.AsyncDataProviderService
import com.exactpro.th2.dataprovider.lw.grpc.EventResponse
import com.exactpro.th2.dataprovider.lw.grpc.EventResponseOrBuilder
import io.grpc.stub.StreamObserver
import mu.KotlinLogging
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

suspend fun AsyncDataProviderService.getEventSuspend(eventId: EventID): GrpcEvent {
    return suspendCoroutine<EventResponse> {
        getEvent(eventId, CoroutineSingleStreamObserver(it) { data -> data.eventId.toJson() })
    }.toEvent()
}

fun EventResponseOrBuilder.toEvent(): GrpcEvent = GrpcEvent.newBuilder().apply {
    if (hasEventId()) { id = eventId }
    if (hasParentEventId()) { parentId = parentEventId }
    name = eventName
    type = eventType
    // start timestamp should be equal as id.startTimestamp
    if (hasEndTimestamp()) { this.endTimestamp = this@toEvent.endTimestamp }
    this.status = this@toEvent.status
    this.body = this@toEvent.body
    addAllAttachedMessageIds(attachedMessageIdList)
}.build()


private class CoroutineSingleStreamObserver<T>(
    private val cont: Continuation<T>,
    private val toShortString: (T) -> String
) : StreamObserver<T> {
    override fun onNext(value: T) {
        LOGGER.trace { "Continuation resumed with value ${toShortString(value)}" }
        cont.resume(value)
    }

    override fun onError(t: Throwable) {
        LOGGER.trace(t) { "Continuation resumed with error ${t.message}" }
        cont.resumeWithException(t)
    }

    override fun onCompleted() {
    }

    companion object {
        private val LOGGER = KotlinLogging.logger {  }
    }
}