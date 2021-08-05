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

import com.exactpro.th2.dataprocessor.zephyr.cfg.ZephyrSynchronizationCfg

fun ZephyrSynchronizationCfg.validate(): List<String> {
    return arrayListOf<String>().also {
        checkConnectionsUniqueness(it)
        checkSyncParametersDestinations(it)
    }
}

private fun ZephyrSynchronizationCfg.checkConnectionsUniqueness(errors: MutableList<String>) {
    val groupByName = connections.groupBy { it.name }
    if (groupByName.size != connections.size) {
        groupByName.forEach { (name, conns) ->
            if (conns.size > 1) {
                errors += "Configuration has ${conns.size} connections with the same name $name"
            }
        }
    }
}

fun ZephyrSynchronizationCfg.checkSyncParametersDestinations(errors: MutableList<String>) {
    val destinationDoesNotExist = syncParameters.filter { connections.none { conn -> conn.name == it.destination } }
    if (destinationDoesNotExist.isEmpty()) {
        return
    }
    val knownConnections = connections.joinToString(", ") { it.name }
    destinationDoesNotExist.forEach {
        errors += "Synchronization parameters with destination ${it.destination} does not have matched connection. Known connections: $knownConnections"
    }
}
