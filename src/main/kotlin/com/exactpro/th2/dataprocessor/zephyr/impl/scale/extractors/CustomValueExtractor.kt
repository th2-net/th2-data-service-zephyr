/*
 * Copyright 2020-2023 Exactpro (Exactpro Systems Limited)
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

package com.exactpro.th2.dataprocessor.zephyr.impl.scale.extractors

import com.exactpro.th2.dataprocessor.zephyr.cfg.ConstantValue
import com.exactpro.th2.dataprocessor.zephyr.cfg.CustomFieldExtraction
import com.exactpro.th2.dataprocessor.zephyr.cfg.EventValue
import com.exactpro.th2.dataprocessor.zephyr.cfg.EventValueKey
import com.exactpro.th2.dataprocessor.zephyr.cfg.JiraValue
import com.exactpro.th2.dataprocessor.zephyr.cfg.JiraValueKey
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.AccountInfo
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.Version
import com.exactpro.th2.dataprovider.grpc.EventResponse

class ExtractionContext(
    val event: EventResponse,
    val accountInfo: AccountInfo,
    val version: Version,
)

interface CustomValueExtractor {
    fun extract(context: ExtractionContext): Any
}

fun createCustomValueExtractors(customFields: Map<String, CustomFieldExtraction>): Map<String, CustomValueExtractor> {
    return customFields.mapValues { (_, cfg) ->
        when (cfg) {
            is ConstantValue -> ConstantExtractor(cfg.value)
            is EventValue -> EventExtractor(cfg.extract)
            is JiraValue -> JiraExtractor(cfg.extract)
        }
    }
}

class ConstantExtractor(
    val value: Any,
) : CustomValueExtractor {
    override fun extract(context: ExtractionContext): Any = value
}

class EventExtractor(
    val type: EventValueKey,
) : CustomValueExtractor {
    override fun extract(context: ExtractionContext): Any {
        return when(type) {
            EventValueKey.ID -> context.event.eventId.id
            EventValueKey.NAME -> context.event.eventName
        }
    }
}

class JiraExtractor(
    val type: JiraValueKey,
) : CustomValueExtractor {
    override fun extract(context: ExtractionContext): Any {
        return when(type) {
            JiraValueKey.VERSION -> context.version.name
            JiraValueKey.ACCOUNT_ID -> requireNotNull(context.accountInfo.accountId) { "no account ID" }
            JiraValueKey.ACCOUNT_NAME -> requireNotNull(context.accountInfo.name) { "no account name" }
        }
    }
}