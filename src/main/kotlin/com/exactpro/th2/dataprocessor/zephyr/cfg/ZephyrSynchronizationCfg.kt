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

package com.exactpro.th2.dataprocessor.zephyr.cfg

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.features.logging.LogLevel

class ZephyrSynchronizationCfg(
    val dataService: DataServiceCfg,
    @JsonAlias("connection") val connections: List<ConnectionCfg>,
    val syncParameters: List<EventProcessorCfg>,
    val httpLogging: HttpLoggingConfiguration = HttpLoggingConfiguration(),
    val zephyrType: ZephyrType = ZephyrType.SQUAD,
) {
    init {
        require(connections.isNotEmpty()) { "at least one connection must be specified" }
    }
    companion object {
        val MAPPER: ObjectMapper = jacksonObjectMapper()
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    }
}

enum class ZephyrType {
    SQUAD, SCALE_SERVER, SCALE_CLOUD
}

class DataServiceCfg(
    val name: String = "Zephyr",
    val versionMarker: String
) {
    init {
        require(name.isNotBlank()) { "data service's name cannot be blank" }
        require(versionMarker.isNotBlank()) { "data service's versionMarker cannot be blank" }
    }
}

class HttpLoggingConfiguration(
    val level: LogLevel = LogLevel.INFO
)

class ConnectionCfg(
    val name: String = DEFAULT_NAME,
    val baseUrl: String,
    val jira: Credentials,
    val zephyr: Credentials = jira,
    val zephyrUrl: String = baseUrl,
) {
    companion object {
        const val DEFAULT_NAME = "DefaultConnection"
    }
}

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type",
    defaultImpl = BaseAuth::class
)
@JsonSubTypes(
    JsonSubTypes.Type(name = "standard", value = BaseAuth::class),
    JsonSubTypes.Type(name = "jwt", value = JwtAuth::class),
    JsonSubTypes.Type(name = "bearer", value = BearerAuth::class),
)
sealed class Credentials

class BaseAuth(
    val username: String,
    val key: String
) : Credentials() {
    init {
        require(username.isNotBlank()) { "username cannot be blank" }
        require(key.isNotBlank()) { "key cannot be blank" }
    }
}

class JwtAuth(
    val baseApiUrl: String,
    val accessKey: String,
    val secretKey: String,
) : Credentials() {
    init {
        require(baseApiUrl.isNotBlank()) { "baseApiUrl cannot be blank" }
        require(accessKey.isNotBlank()) { "accessKey cannot be blank" }
        require(secretKey.isNotBlank()) { "secretKey cannot be blank" }
    }
    var accountId: String? = null
}

class BearerAuth(
    val token: String,
) : Credentials() {
    init {
        require(token.isNotBlank()) { "token cannot be blank" }
    }
}