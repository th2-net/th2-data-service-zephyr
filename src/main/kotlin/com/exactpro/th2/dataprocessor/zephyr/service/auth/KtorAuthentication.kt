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

package com.exactpro.th2.dataprocessor.zephyr.service.auth

import com.exactpro.th2.dataprocessor.zephyr.cfg.BaseAuth
import com.exactpro.th2.dataprocessor.zephyr.cfg.BearerAuth
import com.exactpro.th2.dataprocessor.zephyr.cfg.Credentials
import com.exactpro.th2.dataprocessor.zephyr.cfg.JwtAuth
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.auth.providers.bearer
import java.net.URI

@Suppress("FunctionName")
fun HttpClientConfig<*>.AuthenticateWith(credentials: Credentials, baseUrl: String) {
    when (credentials) {
        is BaseAuth -> Auth {
            basic {
                credentials {
                    BasicAuthCredentials(credentials.username, credentials.key)
                }
                sendWithoutRequest { true }
            }
        }

        is BearerAuth -> Auth {
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
            this.baseUrl = URI.create(baseUrl)
        }
    }
}