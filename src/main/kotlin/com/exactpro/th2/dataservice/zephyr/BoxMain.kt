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

@file:JvmName("BoxMain")
package com.exactpro.th2.dataservice.zephyr

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.event.EventUtils
import com.exactpro.th2.common.event.EventUtils.createMessageBean
import com.exactpro.th2.common.grpc.EventBatch
import com.exactpro.th2.common.message.toJson
import com.exactpro.th2.common.metrics.liveness
import com.exactpro.th2.common.metrics.readiness
import com.exactpro.th2.common.schema.factory.CommonFactory
import com.exactpro.th2.common.schema.factory.extensions.getCustomConfiguration
import com.exactpro.th2.dataprovider.grpc.AsyncDataProviderService
import com.exactpro.th2.dataprovider.grpc.EventData
import com.exactpro.th2.dataservice.zephyr.cfg.ZephyrSynchronizationCfg
import com.exactpro.th2.dataservice.zephyr.grpc.impl.ZephyrServiceImpl
import com.exactpro.th2.dataservice.zephyr.impl.JiraApiServiceImpl
import com.exactpro.th2.dataservice.zephyr.impl.ZephyrApiServiceImpl
import com.exactpro.th2.dataservice.zephyr.impl.ZephyrEventProcessorImpl
import mu.KotlinLogging
import java.util.Deque
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private val LOGGER = KotlinLogging.logger { }

fun main(args: Array<String>) {
    LOGGER.info { "Starting the zephyr data service" }
    // Here is an entry point to the th2-box.

    // Configure shutdown hook for closing all resources
    // and the lock condition to await termination.
    //
    // If you use the logic that doesn't require additional threads
    // and you can run everything on main thread
    // you can omit the part with locks (but please keep the resources queue)
    val resources: Deque<AutoCloseable> = ConcurrentLinkedDeque()
    val lock = ReentrantLock()
    val condition: Condition = lock.newCondition()
    configureShutdownHook(resources, lock, condition)

    try {
        // You need to initialize the CommonFactory

        // You can use custom paths to each config that is required for the CommonFactory
        // If args are empty the default path will be chosen.
        val factory = CommonFactory.createFromArguments(*args)
        // do not forget to add resource to the resources queue
        resources += factory

        val cfg = factory.getCustomConfiguration<ZephyrSynchronizationCfg>()

        // The BOX is alive
        liveness = true

        val eventRouter = factory.eventBatchRouter
        val root = Event.start().endTimestamp()
            .name("Zephyr data service root event")
            .description("Will contain all events with errors and information about processed events")
            .type("Microservice")
            .toProto(null/* no parent, the root event */)
        eventRouter.send(
            EventBatch.newBuilder()
                .addEvents(root)
                .build()
        )

        val dataProvider = factory.grpcRouter.getService(AsyncDataProviderService::class.java)

        val connection = cfg.connection
        val jiraApi = JiraApiServiceImpl(
            connection.baseUrl,
            connection.jira,
            cfg.httpLogging
        )
        resources += jiraApi

        val zephyrApi = ZephyrApiServiceImpl(
            connection.baseUrl,
            connection.zephyr,
            cfg.httpLogging
        )
        resources += zephyrApi

        val processor = ZephyrEventProcessorImpl(cfg.syncParameters, jiraApi, zephyrApi, dataProvider)
        val onInfo: (Event) -> Unit = { event ->
            runCatching {
                eventRouter.send(
                    EventBatch.newBuilder()
                        .addEvents(event.toProto(root.id))
                        .build()
                )
            }.onFailure { LOGGER.error(it) { "Cannot send event ${event.id}" } }
        }

        val onError: (EventData?, Throwable) -> Unit = { event, t ->
            runCatching {
                eventRouter.send(
                    EventBatch.newBuilder()
                        .addEvents(
                            Event.start().endTimestamp()
                                .status(Event.Status.FAILED)
                                .name(event?.run { "Error during processing event ${eventId.id}" }
                                    ?: "Error during processing event request")
                                .type(if (event == null) "RequestProcessingError" else "EventProcessingError")
                                .bodyData(createMessageBean(event?.run { "Cannot process event ${eventId.id}" }
                                    ?: "Cannot process event request"))
                                .exception(t, true)
                                .toProto(root.id)
                        )
                        .build()
                )
            }
        }

        val handler = ZephyrServiceImpl(cfg.dataService, processor, onInfo, onError)
        resources += handler

        val server = factory.grpcRouter.startServer(handler)
            .start()
        resources += AutoCloseable {
            LOGGER.info { "Shutting down Zephyr gRPC server" }
            val unit = TimeUnit.SECONDS
            val timeout: Long = 5
            if (server.shutdown().awaitTermination(timeout, unit)) {
                LOGGER.warn { "Cannot shutdown server in ${unit.toMillis(timeout)} millis. Shutdown now" }
                server.shutdownNow()
            }
        }

        // The BOX is ready to work
        readiness = true

        awaitShutdown(lock, condition)
    } catch (ex: Exception) {
        LOGGER.error(ex) { "Cannot start the box" }
        exitProcess(1)
    }
}

private fun configureShutdownHook(resources: Deque<AutoCloseable>, lock: ReentrantLock, condition: Condition) {
    Runtime.getRuntime().addShutdownHook(thread(
        start = false,
        name = "Shutdown hook"
    ) {
        LOGGER.info { "Shutdown start" }
        readiness = false
        try {
            lock.lock()
            condition.signalAll()
        } finally {
            lock.unlock()
        }
        resources.descendingIterator().forEachRemaining { resource ->
            try {
                resource.close()
            } catch (e: Exception) {
                LOGGER.error(e) { "Cannot close resource ${resource::class}" }
            }
        }
        liveness = false
        LOGGER.info { "Shutdown end" }
    })
}

@Throws(InterruptedException::class)
private fun awaitShutdown(lock: ReentrantLock, condition: Condition) {
    try {
        lock.lock()
        LOGGER.info { "Wait shutdown" }
        condition.await()
        LOGGER.info { "App shutdown" }
    } finally {
        lock.unlock()
    }
}