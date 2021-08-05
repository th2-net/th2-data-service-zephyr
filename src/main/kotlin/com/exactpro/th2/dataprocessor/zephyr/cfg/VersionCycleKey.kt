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

import com.fasterxml.jackson.databind.util.StdConverter
import java.util.stream.Collectors

class VersionCycleKey(
    val version: String,
    val cycle: String
) {
    init {
        require(version.isNotBlank()) { "version cannot be blank" }
        require(cycle.isNotBlank()) { "cycle cannot be blank" }
    }
}

class IssuesForVersionAndCycle(
    val version: String,
    val cycle: String,
    val issues: Set<String>
)

class DefaultIssueForVersionAndCycleConverter : StdConverter<List<IssuesForVersionAndCycle>, Map<VersionCycleKey, Set<String>>>() {
    override fun convert(value: List<IssuesForVersionAndCycle>?): Map<VersionCycleKey, Set<String>> {
        return value?.stream()
            ?.collect(Collectors.toUnmodifiableMap(
                { VersionCycleKey(it.version, it.cycle) },
                { it.issues },
                { old, new -> old + new }
            )) ?: emptyMap()
    }
}
