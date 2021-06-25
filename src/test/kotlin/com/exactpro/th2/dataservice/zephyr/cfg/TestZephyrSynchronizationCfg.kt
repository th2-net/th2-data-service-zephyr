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

import com.exactpro.th2.common.grpc.EventStatus
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.features.logging.LogLevel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TestZephyrSynchronizationCfg {
    val mapper = jacksonObjectMapper()

    @Test
    fun deserialization() {
        val data = """
        {
          "connection": {
            "baseUrl": "https://your.jira.address.com",
            "jira": {
              "username": "jira-user",
              "key": "your password"
            }
          },
          "dataService": {
            "name": "ZephyrService",
            "versionMarker": "0.0.1"
          },
          "syncParameters": {
            "issueFormat": "QAP_\\d+",
            "delimiter": "|",
            "statusMapping": {
              "SUCCESS": "PASS",
              "FAILED": "WIP"
            },
            "jobAwaitTimeout": 1000
          },
          "httpLogging": {
            "level": "ALL"
          }
        }
        """.trimIndent()

        val cfg = mapper.readValue<ZephyrSynchronizationCfg>(data)
        with(cfg.connection) {
            assertEquals("https://your.jira.address.com", baseUrl)
            assertEquals("jira-user", jira.username)
            assertEquals("your password", jira.key)
        }
        with(cfg.dataService) {
            assertEquals("ZephyrService", name)
            assertEquals("0.0.1", versionMarker)
        }
        with(cfg.syncParameters) {
            assertEquals("QAP_\\d+", issueRegexp.pattern())
            assertEquals('|', delimiter)
            assertEquals("PASS", statusMapping[EventStatus.SUCCESS])
            assertEquals("WIP", statusMapping[EventStatus.FAILED])
            assertEquals(1000, jobAwaitTimeout)
        }
        with(cfg.httpLogging) {
            assertEquals(LogLevel.ALL, level)
        }
    }
}