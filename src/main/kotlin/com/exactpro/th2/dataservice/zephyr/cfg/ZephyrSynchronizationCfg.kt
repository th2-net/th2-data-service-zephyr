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

package com.exactpro.th2.dataservice.zephyr.cfg

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.ktor.client.features.logging.LogLevel

class ZephyrSynchronizationCfg(
    val dataService: DataServiceCfg,
    val connection: ConnectionCfg,
    val syncParameters: EventProcessorCfg,
    val httpLogging: HttpLoggingConfiguration = HttpLoggingConfiguration()
)

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
    val baseUrl: String,
    val jira: BaseAuth,
    val zephyr: Credentials = jira
)

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type",
    defaultImpl = BaseAuth::class
)
@JsonSubTypes(
    JsonSubTypes.Type(name = "base", value = BaseAuth::class),
    JsonSubTypes.Type(name = "jwt", value = JwtAuth::class)
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
