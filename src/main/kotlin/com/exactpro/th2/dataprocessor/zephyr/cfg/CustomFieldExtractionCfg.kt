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

package com.exactpro.th2.dataprocessor.zephyr.cfg

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type",
    defaultImpl = ConstantValue::class
)
@JsonSubTypes(
    JsonSubTypes.Type(name = "const", value = ConstantValue::class),
    JsonSubTypes.Type(name = "event", value = EventValue::class),
    JsonSubTypes.Type(name = "jira", value = JiraValue::class),
)
sealed class CustomFieldExtraction

class ConstantValue(
    val value: Any,
) : CustomFieldExtraction()

enum class EventValueKey {
    ID, NAME
}

class EventValue(
    val extract: EventValueKey,
) : CustomFieldExtraction()

enum class JiraValueKey {
    VERSION, ACCOUNT_ID, ACCOUNT_NAME
}

class JiraValue(
    val extract: JiraValueKey,
) : CustomFieldExtraction()
