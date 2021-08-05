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

package com.exactpro.th2.dataprocessor.zephyr.strategies

import com.exactpro.th2.dataprocessor.zephyr.impl.ServiceHolder
import com.exactpro.th2.dataprocessor.zephyr.model.Issue
import com.fasterxml.jackson.annotation.JsonTypeInfo

typealias StrategyType = String

interface RelatedIssuesStrategyFactory<T : RelatedIssuesStrategyConfiguration> {
    /**
     * Type for strategy. Used to deserialize the correct configuration
     */
    val type: StrategyType
    val configurationClass: Class<T>
    fun create(configuration: T): RelatedIssuesStrategy
}

interface RelatedIssuesStrategy {
    suspend fun findRelatedFor(services: ServiceHolder, issue: Issue): List<Issue>
}

/**
 * This interface should be used to mark the configuration for [RelatedIssuesStrategy]
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
interface RelatedIssuesStrategyConfiguration