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

package com.exactpro.th2.dataprocessor.zephyr.strategies.linked

import com.exactpro.th2.dataprocessor.zephyr.service.api.model.LinkType
import com.exactpro.th2.dataprocessor.zephyr.strategies.RelatedIssuesStrategyConfiguration

class LinkedIssuesStrategyConfiguration(
    val trackLinkedIssues: List<TrackingLink> = emptyList()
) : RelatedIssuesStrategyConfiguration

data class TrackingLink(
    val linkName: String,
    /**
     * Can be used to identify the link when the link name for both direction is the same.
     * If only one link matches the [linkName] this parameter will be ignored
     */
    val direction: LinkType.LinkDirection? = null,
    val disable: Boolean = false,
    /**
     * White list for searching. If the issues is not in the whitelist the strategy won't search links for it.
     * If the found related issue's project is not in the white list the related issue will be ignored
     */
    val whitelist: List<TrackingWhiteList>
) {
    init {
        require(linkName.isNotBlank()) { "'linkName' cannot be blank" }
    }
}

class TrackingWhiteList(
    val projectKey: String? = null,
    val issues: Set<String>,
    val projectName: String? = null
) {
    init {
        require((projectKey == null) xor (projectName == null)) { "only one of the parameters must be set ('projectKey' or 'projectName')" }
        require(projectKey?.isNotBlank() ?: true) { "'projectKey' cannot be blank" }
        require(projectName?.isNotBlank() ?: true) { "'projectName' cannot be blank" }
    }
    companion object {
        const val ALL_ISSUES = "*"
    }
}
