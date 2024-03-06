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
import com.exactpro.th2.common.message.toJson
import com.exactpro.th2.dataprocessor.zephyr.cfg.Credentials
import com.exactpro.th2.dataprocessor.zephyr.cfg.HttpLoggingConfiguration
import com.exactpro.th2.dataprocessor.zephyr.cfg.ZephyrSynchronizationCfg
import com.exactpro.th2.dataprocessor.zephyr.cfg.ZephyrType
import com.exactpro.th2.dataprocessor.zephyr.cfg.util.validate
import com.exactpro.th2.dataprocessor.zephyr.impl.AbstractZephyrProcessor
import com.exactpro.th2.dataprocessor.zephyr.impl.ServiceHolder
import com.exactpro.th2.dataprocessor.zephyr.impl.scale.ScaleServiceHolder
import com.exactpro.th2.dataprocessor.zephyr.impl.scale.ZephyrScaleEventProcessorImpl
import com.exactpro.th2.dataprocessor.zephyr.impl.standard.RelatedIssuesStrategiesStorageImpl
import com.exactpro.th2.dataprocessor.zephyr.impl.standard.StandardServiceHolder
import com.exactpro.th2.dataprocessor.zephyr.impl.standard.ZephyrEventProcessorImpl
import com.exactpro.th2.dataprocessor.zephyr.service.api.JiraApiService
import com.exactpro.th2.dataprocessor.zephyr.service.impl.JiraApiServiceImpl
import com.exactpro.th2.dataprocessor.zephyr.service.impl.scale.cloud.ZephyrScaleCloudApiServiceImpl
import com.exactpro.th2.dataprocessor.zephyr.service.impl.scale.server.ZephyrScaleServerApiService
import com.exactpro.th2.dataprocessor.zephyr.service.impl.standard.ZephyrApiServiceImpl
import com.exactpro.th2.dataprovider.lw.grpc.AsyncDataProviderService
import com.exactpro.th2.processor.api.IProcessor
import com.exactpro.th2.processor.api.IProcessorFactory
import com.exactpro.th2.processor.api.IProcessorSettings
import com.exactpro.th2.processor.api.ProcessorContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.google.auto.service.AutoService
import mu.KotlinLogging
import java.time.Instant

typealias GrpcEvent = com.exactpro.th2.common.grpc.Event

@Suppress("unused")
@AutoService(IProcessorFactory::class)
class ZephyrEventProcessorFactory : IProcessorFactory {

    private val strategiesStorageImpl = RelatedIssuesStrategiesStorageImpl()

    override fun registerModules(configureMapper: ObjectMapper) {
        with(configureMapper) {
            registerModule(SimpleModule().addAbstractTypeMapping(IProcessorSettings::class.java, ZephyrSynchronizationCfg::class.java))
            enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        }
        strategiesStorageImpl.registerTypes(configureMapper)
    }

    override fun create(context: ProcessorContext): IProcessor {
        val cfg = context.settings
        check(cfg is ZephyrSynchronizationCfg) {
            "Settings type mismatch expected: ${ZephyrSynchronizationCfg::class}, actual: ${ cfg?.let{ it::class } }"
        }

        val errors: List<String> = cfg.validate()
        if (errors.isNotEmpty()) {
            K_LOGGER.error { "Configuration errors found:" }
            errors.forEach { K_LOGGER.error(it) }
            error("Zephyr event processor configuration error")
        }

        val dataProvider = context.commonFactory.grpcRouter.getService(AsyncDataProviderService::class.java)

        fun createSquad(): Pair<Map<String, ServiceHolder<*>>, AbstractZephyrProcessor<*>> {
            val connections: Map<String, StandardServiceHolder> = cfg.createConnections(::StandardServiceHolder, ::ZephyrApiServiceImpl)
            val processor = ZephyrEventProcessorImpl(cfg.syncParameters, connections, dataProvider, strategiesStorageImpl)
            return connections to processor
        }

        fun createScale(): Pair<Map<String, ServiceHolder<*>>, AbstractZephyrProcessor<*>> {
            val connections: Map<String, ScaleServiceHolder> = cfg.createConnections(::ScaleServiceHolder, ::ZephyrScaleServerApiService)
            val processor = ZephyrScaleEventProcessorImpl(cfg.syncParameters, connections, dataProvider)
            return connections to processor
        }

        fun createScaleCloud(): Pair<Map<String, ServiceHolder<*>>, AbstractZephyrProcessor<*>> {
            val connections: Map<String, ScaleServiceHolder> = cfg.createConnections(::ScaleServiceHolder, ::ZephyrScaleCloudApiServiceImpl)
            val processor = ZephyrScaleEventProcessorImpl(cfg.syncParameters, connections, dataProvider)
            return connections to processor
        }

        val onInfo: (Event) -> Unit = { event ->
            runCatching {
                context.eventBatcher.onEvent(event.toProto(context.processorEventId))
            }.onFailure { K_LOGGER.error(it) { "Cannot send event ${event.id}" } }
        }

        val onError: (GrpcEvent?, Throwable) -> Unit = { event, t ->
            runCatching {
                context.eventBatcher.onEvent(
                    Event.start().endTimestamp()
                        .status(Event.Status.FAILED)
                        .name(event?.run { "Error during processing event ${id.toJson()}" }
                            ?: "Error during processing event request")
                        .type(if (event == null) "RequestProcessingError" else "EventProcessingError")
                        .bodyData(EventUtils.createMessageBean(event?.run { "Cannot process event ${id.toJson()}" }
                            ?: "Cannot process event request"))
                        .exception(t, true)
                        .toProto(context.processorEventId)
                )
            }
        }

        val (connections: Map<String, ServiceHolder<*>>, processor) = when (cfg.zephyrType) {
            ZephyrType.SQUAD -> createSquad()
            ZephyrType.SCALE_SERVER -> createScale()
            ZephyrType.SCALE_CLOUD -> createScaleCloud()
        }
        return ZephyrEventProcessor(connections, processor, onInfo, onError)
    }

    override fun createProcessorEvent(): Event = Event.start().endTimestamp()
        .name("Zephyr data service processor event ${Instant.now()}")
        .description("Will contain all events with errors and information about processed events")
        .type("Microservice")


    companion object {
        private val K_LOGGER = KotlinLogging.logger { }

        private fun <T : AutoCloseable, H : ServiceHolder<T>> ZephyrSynchronizationCfg.createConnections(
            serviceHolder: (JiraApiService, T) -> H,
            zephyrSupplier: (baseUrl: String, cred: Credentials, httpCfg: HttpLoggingConfiguration) -> T
        ): Map<String, H> =
            connections.associate { connection ->
                val jiraApi = JiraApiServiceImpl(
                    connection.baseUrl,
                    connection.jira,
                    httpLogging
                )

                val zephyrApi = zephyrSupplier(
                    connection.baseUrl,
                    connection.zephyr,
                    httpLogging
                )

                connection.name to serviceHolder(jiraApi, zephyrApi)
            }
    }

}