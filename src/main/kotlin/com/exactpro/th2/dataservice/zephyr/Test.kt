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

package com.exactpro.th2.dataservice.zephyr

import com.exactpro.th2.dataservice.zephyr.cfg.BaseAuth
import com.exactpro.th2.dataservice.zephyr.cfg.HttpLoggingConfiguration
import com.exactpro.th2.dataservice.zephyr.impl.JiraApiServiceImpl
import com.exactpro.th2.dataservice.zephyr.impl.ZephyrApiServiceImpl
import com.exactpro.th2.dataservice.zephyr.model.ExecutionUpdate
import com.exactpro.th2.dataservice.zephyr.model.extensions.findVersion
import io.ktor.client.features.logging.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

private val LOGGER = KotlinLogging.logger { }
fun main() {
    val baseUrl = "https://support.quodfinancial.com/jira"//""https://exactpro.atlassian.net"
    val httpLogging = HttpLoggingConfiguration(LogLevel.ALL)

    val jira = JiraApiServiceImpl(
        baseUrl,
        BaseAuth("osmirnov"/*"oleg.smirnov@exactprosystems.com"*/,
        "I!Hate!Passwords96"/*"Sn8k4MCijmPlwrpRMcvkE946"*/),
        httpLogging
    )
    val zephyr = ZephyrApiServiceImpl(
        baseUrl,
        BaseAuth("osmirnov", "I!Hate!Passwords96"),
        httpLogging
    )
    try {
        runBlocking {
            launch {
                LOGGER.info { "Start" }
                val issue = jira.issueByKey("QAP-4464")
                println(issue)
                val project = jira.projectByKey(issue.projectKey)
                println(project.name)
//                val project = jira.projectByKey("QAP")
//                val issue = jira.issueByKey("QAP-4465")
//                val version = project.findVersion("1.22.333.444")!!
//                val cycle = zephyr.getCycle("Th2TestCycle", project, version)!!
//                println(cycle.id)
//                val folder = zephyr.getFolder(cycle, "TestFolder")!!
//                println(folder.id + " " + folder.name)
////                val jobToken = zephyr.addTestToCycle(cycle, issue)
////                zephyr.awaitJobDone(jobToken)
//                val execution = zephyr.findExecution(project, version, cycle, folder, issue)
//                println(execution)
//                val statuses = zephyr.getExecutionStatuses()
//                println(statuses)
//                val status = statuses.first { it.name == "WIP" }
//                val response = zephyr.updateExecution(
//                    ExecutionUpdate(
//                        execution!!.id,
//                        status = status,
//                        comment = "Update by th2"
//                    )
//                )
//                println(response)

//                val response = zephyr.createExecution(
//                    ExecutionRequest(
//                        projectId = project.id,
//                        issueId = issue.id,
//                        cycleId = cycle.id,
//                        versionId = version.id,
//                        folderId = folder.id
//                    )
//                )
//                println(response)
                LOGGER.info { "End" }
            }.apply {
                invokeOnCompletion {
                    when (it) {
                        null -> LOGGER.info { "Completed" }
                        is CancellationException -> LOGGER.info { "Canceled" }
                        else -> LOGGER.error(it) { "error" }
                    }
                }
            }
        }
    } finally {
        runCatching { jira.close() }
        runCatching { zephyr.close() }
    }
}
