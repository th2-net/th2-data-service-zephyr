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

package com.exactpro.th2.dataprocessor.zephyr.service

import com.atlassian.jwt.JwtConstants
import com.atlassian.jwt.SigningAlgorithm
import com.atlassian.jwt.core.TimeUtil
import com.atlassian.jwt.core.writer.JsonSmartJwtJsonBuilder
import com.atlassian.jwt.core.writer.JwtClaimsBuilder
import com.atlassian.jwt.core.writer.NimbusJwtWriterFactory
import com.atlassian.jwt.httpclient.CanonicalHttpUriRequest
import io.ktor.client.HttpClient
import io.ktor.client.features.HttpClientFeature
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.util.AttributeKey
import org.apache.commons.lang3.StringUtils
import org.apache.http.message.BasicHeaderValueParser
import org.apache.http.message.ParserCursor
import org.apache.http.util.CharArrayBuffer
import java.io.UnsupportedEncodingException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class JwtAuthentication internal constructor(
    private val baseUrl: URI,
    private val accessKey: String,
    private val secretKey: String,
    private val accountId: String,
    private val expireWindowSeconds: Long
) {
    private fun encodeJwt(method: HttpMethod, requestPath: String): String {
        val jsonBuilder = JsonSmartJwtJsonBuilder()
            .issuedAt(TimeUtil.currentTimeSeconds())
            .expirationTime(TimeUtil.currentTimePlusNSeconds(expireWindowSeconds))
            .issuer(accessKey)
            .subject(accountId)
        val uriWithoutProductContext: URI = URI.create(requestPath).let { requestUri ->
            val pathWithoutProductContext = requestUri.path.substring(baseUrl.path.length)
            with(requestUri) { URI(scheme, userInfo, host, port, pathWithoutProductContext, query, fragment) }
        }
        val parameterMap = constructParameterMap(uriWithoutProductContext)
        CanonicalHttpUriRequest(
            method.value,
            uriWithoutProductContext.path,
            "", // empty context // TODO: we probably should use it instead of manipulation with URI above
            parameterMap.mapValues { it.value.toTypedArray() }
        ).also {
            JwtClaimsBuilder.appendHttpRequestClaims(jsonBuilder, it)
        }
        return NimbusJwtWriterFactory()
            .macSigningWriter(SigningAlgorithm.HS256, secretKey)
            .jsonToJwt(jsonBuilder.build())
    }

    @Throws(UnsupportedEncodingException::class)
    private fun constructParameterMap(uri: URI): Map<String?, List<String?>> {
        val query = uri.query ?: return emptyMap()
        val queryParams: MutableMap<String?, MutableList<String?>> = HashMap()
        val buffer = CharArrayBuffer(query.length)
        buffer.append(query)
        val cursor = ParserCursor(0, buffer.length)
        while (!cursor.atEnd()) {
            val nameValuePair = BasicHeaderValueParser.INSTANCE.parseNameValuePair(buffer, cursor, QUERY_DELIMITERS)
            if (!StringUtils.isEmpty(nameValuePair.name)) {
                val decodedName = urlDecode(nameValuePair.name)
                val decodedValue = urlDecode(nameValuePair.value)
                queryParams.computeIfAbsent(decodedName) { arrayListOf() }.add(decodedValue)
            }
        }
        return queryParams
    }

    @Throws(UnsupportedEncodingException::class)
    private fun urlDecode(content: String?): String? {
        return if (null == content) null else URLDecoder.decode(content, "UTF-8")
    }

    class Config {
        lateinit var baseUrl: URI
        lateinit var accessKey: String
        lateinit var secretKey: String
        lateinit var accountId: String
        var expireWindowSeconds: Long = TimeUnit.MINUTES.toSeconds(2)

        internal fun validate() {
            check(::baseUrl.isInitialized) { "baseUrl must be set" }
            check(::accessKey.isInitialized) { "accessKey must be set" }
            check(::secretKey.isInitialized) { "secretKey must be set" }
            check(::accountId.isInitialized) { "accountId must be set" }
            check(expireWindowSeconds > 0) { "expireWindowSeconds must be a positive integer" }
        }
    }
    companion object Feature : HttpClientFeature<Config, JwtAuthentication> {
        private val QUERY_DELIMITERS = charArrayOf('$')
        private const val ZapiAccessKey = "zapiAccessKey"
        private val HttpHeaders.ZapiAccessKey: String
            get() = Feature.ZapiAccessKey
        override val key: AttributeKey<JwtAuthentication> = AttributeKey("JwtAuthentication")

        override fun prepare(block: Config.() -> Unit): JwtAuthentication {
            val cfg = Config().apply(block).apply { validate() }
            return with(cfg) {
                JwtAuthentication(
                    baseUrl,
                    accessKey,
                    secretKey,
                    accountId,
                    expireWindowSeconds
                )
            }
        }

        override fun install(feature: JwtAuthentication, scope: HttpClient) {
            scope.requestPipeline.intercept(HttpRequestPipeline.State) {
                val requestPath = context.url.buildString()
                val jwt = feature.encodeJwt(context.method, requestPath)
                with(context) {
                    header(HttpHeaders.Authorization, JwtConstants.HttpRequests.JWT_AUTH_HEADER_PREFIX + jwt)
                    header(HttpHeaders.ZapiAccessKey, feature.accessKey)
                }
            }
        }

    }
}