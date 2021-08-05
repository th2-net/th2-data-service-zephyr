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

package com.exactpro.th2.dataprocessor.zephyr

import com.exactpro.th2.dataprocessor.zephyr.model.AccountInfo
import com.exactpro.th2.dataprocessor.zephyr.model.Issue
import com.exactpro.th2.dataprocessor.zephyr.model.Project


interface JiraApiService : AutoCloseable {
    suspend fun accountInfo(): AccountInfo
    suspend fun projectByKey(projectKey: String): Project
    suspend fun issueByKey(issueKey: String): Issue
    suspend fun search(jql: Jql, searchParameters: SearchParameters? = null): List<Issue>
}

data class SearchParameters(
    val limit: Int = 50,
    val startAt: Int = 0
) {
    init {
        require(limit > 0) { "'limit' must be a positive integer" }
        require(startAt >= 0) { "'startAt' must be a positive integer or zero" }
    }
}

typealias Jql = String
