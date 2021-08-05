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

package com.exactpro.th2.dataprocessor.zephyr.impl

import com.exactpro.th2.dataprocessor.zephyr.RelatedIssuesStrategiesStorage
import com.exactpro.th2.dataprocessor.zephyr.strategies.RelatedIssuesStrategy
import com.exactpro.th2.dataprocessor.zephyr.strategies.RelatedIssuesStrategyConfiguration
import com.exactpro.th2.dataprocessor.zephyr.strategies.RelatedIssuesStrategyFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.NamedType
import mu.KotlinLogging
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

class RelatedIssuesStrategiesStorageImpl : RelatedIssuesStrategiesStorage {
    @Suppress("UNCHECKED_CAST")
    private val strategyFactories: List<RelatedIssuesStrategyFactory<RelatedIssuesStrategyConfiguration>> =
        ServiceLoader.load(RelatedIssuesStrategyFactory::class.java).asSequence().map {
            it as RelatedIssuesStrategyFactory<RelatedIssuesStrategyConfiguration>
        }.toList().also {
            checkForDuplicates(it)
            LOGGER.info { "Registered ${it.size} strategy(ies). Types: ${it.joinToString { strat -> strat.type }}" }
        }

    private val strategiesByCfg: MutableMap<RelatedIssuesStrategyConfiguration, RelatedIssuesStrategy> = ConcurrentHashMap()

    fun registerTypes(mapper: ObjectMapper) {
        val types = strategyFactories.map { NamedType(it.configurationClass, it.type) }.toTypedArray()
        mapper.registerSubtypes(*types)
    }

    override fun get(cfg: RelatedIssuesStrategyConfiguration): RelatedIssuesStrategy {
        return strategiesByCfg.computeIfAbsent(cfg) {
            val factory: RelatedIssuesStrategyFactory<RelatedIssuesStrategyConfiguration>? =
                strategyFactories.find { factory -> factory.configurationClass == cfg::class.java }
            checkNotNull(factory) { "cannot find factory for settings ${it::class.java}" }
            LOGGER.debug { "Creating strategy with type ${factory.type}" }
            factory.create(it)
        }
    }

    companion object {
        private val LOGGER = KotlinLogging.logger { }

        private fun checkForDuplicates(strategies: List<RelatedIssuesStrategyFactory<RelatedIssuesStrategyConfiguration>>) {
            val duplicates = strategies.groupBy { it.type }.filterValues { it.size > 1 }
            if (duplicates.isNotEmpty()) {
                throw IllegalStateException("Duplicated strategies found: ${duplicates.keys}")
            }
        }
    }
}