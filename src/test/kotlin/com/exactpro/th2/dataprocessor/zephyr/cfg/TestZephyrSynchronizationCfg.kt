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

import com.exactpro.th2.common.grpc.EventStatus
import com.exactpro.th2.dataprocessor.zephyr.impl.standard.RelatedIssuesStrategiesStorageImpl
import com.exactpro.th2.dataprocessor.zephyr.strategies.linked.LinkedIssuesStrategyConfiguration
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.features.logging.LogLevel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TestZephyrSynchronizationCfg {
    private val mapper: ObjectMapper = ZephyrSynchronizationCfg.MAPPER
        .also { RelatedIssuesStrategiesStorageImpl().registerTypes(it) }

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
        assertEquals(1, cfg.connections.size) { "Deserialized connections: ${cfg.connections}" }
        with(cfg.connections.first()) {
            assertEquals(ConnectionCfg.DEFAULT_NAME, name)
            assertEquals("https://your.jira.address.com", baseUrl)
            assertTrue(jira is BaseAuth) { "unexpected type of credentials: ${jira::class}" }
            val baseAuth = jira as BaseAuth
            assertEquals("jira-user", baseAuth.username)
            assertEquals("your password", baseAuth.key)
        }
        with(cfg.dataService) {
            assertEquals("ZephyrService", name)
            assertEquals("0.0.1", versionMarker)
        }
        assertEquals(1, cfg.syncParameters.size)
        with(cfg.syncParameters.first()) {
            assertEquals("QAP_\\d+", issueRegexp.pattern)
            assertEquals('|', delimiter)
            assertEquals("PASS", statusMapping[EventStatus.SUCCESS])
            assertEquals("WIP", statusMapping[EventStatus.FAILED])
            assertEquals(1000, jobAwaitTimeout)
        }
        with(cfg.httpLogging) {
            assertEquals(LogLevel.ALL, level)
        }
    }

    @Test
    fun `deserialize strategies`() {
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
            "jobAwaitTimeout": 1000,
            "relatedIssuesStrategies": [
              {
                "type": "linked",
                "trackLinkedIssues": [
                   {
                    "linkName": "is cloned by",
                    "whitelist": [
                      {
                        "projectKey": "P1",
                        "issues": ["TEST-1", "TEST-2","TEST-3"]
                      },
                      {
                        "projectName": "P2 Project",
                        "issues": ["TEST-1", "TEST-2","TEST-4"]
                      }
                    ]
                   }
                 ]
              }
            ]
          }
        }
        """.trimIndent()
        val cfg = mapper.readValue<ZephyrSynchronizationCfg>(data)
        assertEquals(1, cfg.syncParameters.size)
        val eventProcessorCfg = cfg.syncParameters.first()

        assertEquals(1, eventProcessorCfg.relatedIssuesStrategies.size)
        val strategyConfiguration = eventProcessorCfg.relatedIssuesStrategies.first()

        assertTrue(strategyConfiguration is LinkedIssuesStrategyConfiguration) { "Unexpected type: ${strategyConfiguration::class.java}" }
        strategyConfiguration as LinkedIssuesStrategyConfiguration

        with(strategyConfiguration) {
            assertEquals(1, trackLinkedIssues.size)

            with(trackLinkedIssues.first()) {
                assertEquals("is cloned by", linkName)
                assertFalse(disable)
                assertNull(direction)
                assertEquals(2, whitelist.size)
                with(whitelist[0]) {
                    assertEquals("P1", projectKey)
                    assertNull(projectName)
                    assertEquals(setOf("TEST-1", "TEST-2","TEST-3"), issues)
                }
                with(whitelist[1]) {
                    assertNull(projectKey)
                    assertEquals("P2 Project", projectName)
                    assertEquals(setOf("TEST-1", "TEST-2","TEST-4"), issues)
                }
            }
        }
    }

    @Test
    fun `deserialize custom fields`() {
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
            "jobAwaitTimeout": 1000,
            "customFields": {
              "Const": {
                "value": 42
              },
              "FromEvent": {
                "type": "event",
                "extract": "NAME"
              },
              "FromJira": {
                "type": "jira",
                "extract": "VERSION"
              }
            }
          },
          "httpLogging": {
            "level": "ALL"
          }
        }
        """.trimIndent()

        val cfg = mapper.readValue<ZephyrSynchronizationCfg>(data)
        assertEquals(1, cfg.connections.size) { "Deserialized connections: ${cfg.connections}" }
        with(cfg.connections.first()) {
            assertEquals(ConnectionCfg.DEFAULT_NAME, name)
            assertEquals("https://your.jira.address.com", baseUrl)
            assertTrue(jira is BaseAuth) { "unexpected type of credentials: ${jira::class}" }
            val baseAuth = jira as BaseAuth
            assertEquals("jira-user", baseAuth.username)
            assertEquals("your password", baseAuth.key)
        }
        with(cfg.dataService) {
            assertEquals("ZephyrService", name)
            assertEquals("0.0.1", versionMarker)
        }
        assertEquals(1, cfg.syncParameters.size)
        with(cfg.syncParameters.first()) {
            assertEquals("QAP_\\d+", issueRegexp.pattern)
            assertEquals('|', delimiter)
            assertEquals("PASS", statusMapping[EventStatus.SUCCESS])
            assertEquals("WIP", statusMapping[EventStatus.FAILED])
            assertEquals(1000, jobAwaitTimeout)
            assertTrue(customFields.isNotEmpty(), "not custom fields")
            customFields.assertKey("Const").apply {
                assertTrue(this is ConstantValue) { "should be constant but was ${this::class}" }
                this as ConstantValue
                assertEquals(42, value, "unexpected value")
            }

            customFields.assertKey("FromEvent").apply {
                assertTrue(this is EventValue) { "should be event value but was ${this::class}" }
                this as EventValue
                assertEquals(EventValueKey.NAME, extract, "unexpected extraction type")
            }

            customFields.assertKey("FromJira").apply {
                assertTrue(this is JiraValue) { "should be jira value but was ${this::class}" }
                this as JiraValue
                assertEquals(JiraValueKey.VERSION, extract, "unexpected extraction type")
            }
        }
        with(cfg.httpLogging) {
            assertEquals(LogLevel.ALL, level)
        }
    }
}

private fun <K, V> Map<K, V>.assertKey(key: K): V {
    assertNotNull(get(key)) { "no value for key $key" }
    return getValue(key)
}