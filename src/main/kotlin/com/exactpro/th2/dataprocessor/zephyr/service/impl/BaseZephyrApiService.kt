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

package com.exactpro.th2.dataprocessor.zephyr.service.impl

import com.exactpro.th2.dataprocessor.zephyr.cfg.BaererAuth
import com.exactpro.th2.dataprocessor.zephyr.cfg.BaseAuth
import com.exactpro.th2.dataprocessor.zephyr.cfg.Credentials
import com.exactpro.th2.dataprocessor.zephyr.cfg.HttpLoggingConfiguration
import com.exactpro.th2.dataprocessor.zephyr.cfg.JwtAuth
import com.exactpro.th2.dataprocessor.zephyr.service.JwtAuthentication
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.features.auth.Auth
import io.ktor.client.features.auth.providers.BasicAuthCredentials
import io.ktor.client.features.auth.providers.BearerTokens
import io.ktor.client.features.auth.providers.basic
import io.ktor.client.features.auth.providers.bearer
import io.ktor.client.features.json.JacksonSerializer
import io.ktor.client.features.json.Json
import io.ktor.client.features.logging.Logging
import java.net.URI

abstract class BaseZephyrApiService(
    url: String,
    credentials: Credentials,
    httpLogging: HttpLoggingConfiguration,
    apiPrefix: String,
) {
    private val baseUrl: String = url.run { if (endsWith('/')) this else "$this/" }
    protected val client = HttpClient(Java) {
        when (credentials) {
            is BaseAuth -> Auth {
                basic {
                    credentials {
                        BasicAuthCredentials(credentials.username, credentials.key)
                    }
                    sendWithoutRequest { true }
                }
            }

            is BaererAuth -> Auth {
                bearer {
                    val tokens = BearerTokens(credentials.token, credentials.token)
                    loadTokens { tokens }
                    refreshTokens { tokens }
                    sendWithoutRequest { true }
                }
            }

            is JwtAuth -> install(JwtAuthentication) {
                accessKey = credentials.accessKey
                secretKey = credentials.secretKey
                accountId = requireNotNull(credentials.accountId) { "accountId must be set" }
                baseUrl = URI.create(this@BaseZephyrApiService.baseUrl)
            }
        }
        Json {
            serializer = JacksonSerializer()
        }
        Logging {
            level = httpLogging.level
        }
    }
    protected val baseApiUrl: String = "$baseUrl/${apiPrefix}"
}