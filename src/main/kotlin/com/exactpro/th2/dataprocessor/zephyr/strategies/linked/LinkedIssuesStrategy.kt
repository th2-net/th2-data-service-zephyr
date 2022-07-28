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

import com.exactpro.th2.dataprocessor.zephyr.impl.ServiceHolder
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.Issue
import com.exactpro.th2.dataprocessor.zephyr.strategies.RelatedIssuesStrategy
import com.exactpro.th2.dataprocessor.zephyr.strategies.RelatedIssuesStrategyFactory
import com.exactpro.th2.dataprocessor.zephyr.strategies.StrategyType
import com.exactpro.th2.dataprocessor.zephyr.strategies.linked.TrackingWhiteList.Companion.ALL_ISSUES
import com.google.auto.service.AutoService

class LinkedIssuesStrategy(
    private val configuration: LinkedIssuesStrategyConfiguration
) : RelatedIssuesStrategy {
    override suspend fun findRelatedFor(services: ServiceHolder, issue: Issue): List<Issue> {
        val linkedIssues = arrayListOf<Issue>()
        return configuration.trackLinkedIssues
            .flatMapTo(linkedIssues) { cfg ->
                if (cfg.disable) {
                    return emptyList()
                }
                val projectsToSync = cfg.whitelist.filter { list -> list.issues.run { contains(issue.key) || contains(ALL_ISSUES) } }
                if (projectsToSync.isEmpty()) {
                    return emptyList()
                }
                val matchesLinkType = issue.links.filter { link ->
                    with(link.type) {
                        description == cfg.linkName
                            && cfg.direction?.let { dir -> direction == dir } ?: true
                    }
                }
                matchesLinkType.map { link -> services.jira.issueByKey(link.key) }
                    .filter { linkedIssue ->
                        projectsToSync.any {
                            it.projectKey.matches { linkedIssue.projectKey == this }
                                || it.projectName.matches { services.jira.projectByKey(linkedIssue.projectKey).name == this }
                        }
                    }
            }
    }
    private inline fun <T : Any> T?.matches(condition: T.() -> Boolean): Boolean = this?.run(condition) ?: false
}

@AutoService(RelatedIssuesStrategyFactory::class)
class LinkedIssuesStrategyFactory : RelatedIssuesStrategyFactory<LinkedIssuesStrategyConfiguration> {
    override val configurationClass: Class<LinkedIssuesStrategyConfiguration> = LinkedIssuesStrategyConfiguration::class.java
    override val type: StrategyType = TYPE
    override fun create(configuration: LinkedIssuesStrategyConfiguration): RelatedIssuesStrategy = LinkedIssuesStrategy(configuration)

    companion object {
        const val TYPE: StrategyType = "linked"
    }
}