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
import com.exactpro.th2.common.message.toJson
import com.exactpro.th2.dataprovider.grpc.AsyncDataProviderService
import com.exactpro.th2.dataprovider.grpc.EventData
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

suspend fun AsyncDataProviderService.getEventSuspend(eventId: EventID): EventData {
    return suspendCoroutine {
        getEvent(eventId, CoroutineSingleStreamObserver(it) { data -> data.eventId.toJson() })
    }
}

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