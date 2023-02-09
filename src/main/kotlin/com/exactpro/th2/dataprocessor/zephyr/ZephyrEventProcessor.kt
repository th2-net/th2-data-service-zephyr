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

package com.exactpro.th2.dataprocessor.zephyr

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.message.toJson
import com.exactpro.th2.dataprocessor.zephyr.impl.AbstractZephyrProcessor
import com.exactpro.th2.dataprocessor.zephyr.impl.ServiceHolder
import com.exactpro.th2.processor.api.IProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging

class ZephyrEventProcessor internal constructor(
    private val connections: Map<String, ServiceHolder<*>>,
    private val processor: AbstractZephyrProcessor<*>,
    private val onInfo: (Event) -> Unit,
    private val onError: (GrpcEvent?, Throwable) -> Unit,
    private val scope: CoroutineScope
) : IProcessor {
    constructor(
        connections: Map<String, ServiceHolder<*>>,
        processor: AbstractZephyrProcessor<*>,
        onInfo: (Event) -> Unit,
        onError: (GrpcEvent?, Throwable) -> Unit,
    ) : this(connections, processor, onInfo, onError,  CoroutineScope(CoroutineName("ZephyrService") + SupervisorJob()))
    override fun handle(intervalEventId: EventID, event: GrpcEvent) {
        scope.launch {
            coroutineScope {
                try {
                    try {
                        if (processor.onEvent(event)) {
                            onInfo(
                                Event.start().endTimestamp()
                                    .name("Updated test status in zephyr because of event ${event.name} (${event.id.toJson()})")
                                    .type("ZephyrProcessedEventData")
                                // TODO: add link to the event in future
                            )
                        }
                    } catch (ex: CancellationException) {
                        throw CancellationException("Event processing was canceled", ex)
                    } catch (ex: Exception) {
                        K_LOGGER.error(ex) { "Error during processing event with id ${event.id.toJson()}" }
                        onError(event, ex)
                        // TODO: should the processing stop here?
                    }
                } catch (ex: CancellationException) {
                    K_LOGGER.info { "Request was canceled" }
                } catch (ex: Exception) {
                    K_LOGGER.error(ex) { "Cannot execute request" }
                    onError(null, ex)
                } finally {
                    coroutineContext.cancelChildren()
                }
            }
        }
    }

    override fun serializeState(): ByteArray? {
        // TODO: Await all coroutines
        return null
    }
    override fun close() {
        K_LOGGER.info { "Canceling all tasks in the Zephyr service scope" }
        scope.cancel("Closing the Zephyr service")

        connections.forEach { (name, services) ->
            K_LOGGER.info { "Closing $name connection" }
            runCatching { services.jira.close() }.onFailure { K_LOGGER.error(it) { "Cannot close the JIRA service for connection named $name" } }
            runCatching { services.zephyr.close() }.onFailure { K_LOGGER.error(it) { "Cannot close the Zephyr service for connection named $name" } }
        }
    }
    companion object {
        private val K_LOGGER = KotlinLogging.logger { }
    }
}