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

package com.exactpro.th2.dataprocessor.zephyr.cfg.util

import com.exactpro.th2.common.grpc.EventStatus
import com.exactpro.th2.dataprocessor.zephyr.cfg.BaseAuth
import com.exactpro.th2.dataprocessor.zephyr.cfg.ConnectionCfg
import com.exactpro.th2.dataprocessor.zephyr.cfg.DataServiceCfg
import com.exactpro.th2.dataprocessor.zephyr.cfg.EventProcessorCfg
import com.exactpro.th2.dataprocessor.zephyr.cfg.ZephyrSynchronizationCfg
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TestConfigurationUtilKt {
    @Test
    fun `does not report errors for valid configuration`() {
        val cfg = ZephyrSynchronizationCfg(
            DataServiceCfg("test", "1"),
            listOf(
                ConnectionCfg("A", "http://test.com", BaseAuth("user", "pwd")),
                ConnectionCfg("B", "http://test.com", BaseAuth("user", "pwd")),
            ),
            listOf(
                EventProcessorCfg(".*", "A", statusMapping = mapOf(
                    EventStatus.SUCCESS to "PASS",
                    EventStatus.FAILED to "WIP",
                )),
                EventProcessorCfg(".*", "B", statusMapping = mapOf(
                    EventStatus.SUCCESS to "PASS",
                    EventStatus.FAILED to "WIP",
                ))
            )
        )

        val errors = cfg.validate()
        assertTrue(errors.isEmpty()) { "Unexpected errors: $errors" }
    }

    @Test
    fun `does not report errors for valid configuration with default names`() {
        val cfg = ZephyrSynchronizationCfg(
            DataServiceCfg("test", "1"),
            listOf(
                ConnectionCfg(baseUrl =  "http://test.com", jira =  BaseAuth("user", "pwd")),
            ),
            listOf(
                EventProcessorCfg(".*", statusMapping = mapOf(
                    EventStatus.SUCCESS to "PASS",
                    EventStatus.FAILED to "WIP",
                ))
            )
        )

        val errors = cfg.validate()
        assertTrue(errors.isEmpty()) { "Unexpected errors: $errors" }
    }

    @Test
    fun `reports error for duplicated connections`() {
        val cfg = ZephyrSynchronizationCfg(
            DataServiceCfg("test", "1"),
            listOf(
                ConnectionCfg("A", "http://test.com", BaseAuth("user", "pwd")),
                ConnectionCfg("A", "http://test2.com", BaseAuth("user", "pwd")),
            ),
            listOf(
                EventProcessorCfg(".*", "A", statusMapping = mapOf(
                    EventStatus.SUCCESS to "PASS",
                    EventStatus.FAILED to "WIP",
                ))
            )
        )

        val errors = cfg.validate()
        assertEquals(
            listOf("Configuration has 2 connections with the same name A"),
            errors
        )
    }

    @Test
    fun `reports error for sync parameters with unknown destination`() {
        val cfg = ZephyrSynchronizationCfg(
            DataServiceCfg("test", "1"),
            listOf(
                ConnectionCfg("A", "http://test.com", BaseAuth("user", "pwd")),
                ConnectionCfg("C", "http://test.com", BaseAuth("user", "pwd"))
            ),
            listOf(
                EventProcessorCfg(".*", "A", statusMapping = mapOf(
                    EventStatus.SUCCESS to "PASS",
                    EventStatus.FAILED to "WIP",
                )),
                EventProcessorCfg(".*", "B", statusMapping = mapOf(
                    EventStatus.SUCCESS to "PASS",
                    EventStatus.FAILED to "WIP",
                ))
            )
        )

        val errors = cfg.validate()
        assertEquals(
            listOf("Synchronization parameters with destination B does not have matched connection. Known connections: A, C"),
            errors
        )
    }
}