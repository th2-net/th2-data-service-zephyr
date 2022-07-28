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

import com.exactpro.th2.dataprocessor.zephyr.service.api.JiraApiService
import com.exactpro.th2.dataprocessor.zephyr.service.api.standard.ZephyrApiService
import com.exactpro.th2.dataprocessor.zephyr.impl.ServiceHolder
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.Issue
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.IssueLink
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.LinkType
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.Project
import com.exactpro.th2.dataprocessor.zephyr.strategies.linked.TrackingWhiteList.Companion.ALL_ISSUES
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.mock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@ExperimentalCoroutinesApi
class TestLinkedIssuesStrategy {
    private val jira: JiraApiService = mock {
        onBlocking { issueByKey(any()) }.then {
            val issueKey = it.arguments[0] as String
            Issue(1, issueKey, issueKey.split('-').first())
        }
        onBlocking { projectByKey(any()) }.then {
            val projectKey = it.arguments[0] as String
            Project(0, projectKey, "$projectKey Project", emptyList())
        }
    }
    private val zephyr: ZephyrApiService = mock { }

    @Test
    fun `finds linked issues`() {
        val strategy = LinkedIssuesStrategy(
            LinkedIssuesStrategyConfiguration(
                listOf(
                    TrackingLink(
                        linkName = "is cloned by",
                        whitelist = listOf(
                            TrackingWhiteList(
                                projectKey = "TEST",
                                issues = setOf("TMP-42")
                            )
                        )
                    )
                )
            )
        )

        runBlockingTest {
            val issues = strategy.findRelatedFor(
                ServiceHolder(jira, zephyr),
                Issue(
                    1, "TMP-42", "TMP",
                    listOf(
                        IssueLink("TEST-123", LinkType("Clones", "is cloned by", LinkType.LinkDirection.INWARD)),
                        IssueLink("TEST-124", LinkType("Clones", "is cloned by", LinkType.LinkDirection.INWARD)),
                    )
                )
            )
            assertEquals(2, issues.size) { "Unexpected issues count: $issues" }
            with(issues[0]) {
                assertEquals("TEST-123", key)
            }
            with(issues[1]) {
                assertEquals("TEST-124", key)
            }
        }
    }

    @Test
    fun `finds linked issues for project by its name`() {
        val strategy = LinkedIssuesStrategy(
            LinkedIssuesStrategyConfiguration(
                listOf(
                    TrackingLink(
                        linkName = "is cloned by",
                        whitelist = listOf(
                            TrackingWhiteList(
                                projectName = "TEST Project",
                                issues = setOf("TMP-42")
                            )
                        )
                    )
                )
            )
        )

        runBlockingTest {
            val issues = strategy.findRelatedFor(
                ServiceHolder(jira, zephyr),
                Issue(
                    1, "TMP-42", "TMP",
                    listOf(
                        IssueLink("TEST-123", LinkType("Clones", "is cloned by", LinkType.LinkDirection.INWARD)),
                        IssueLink("TEST-124", LinkType("Clones", "is cloned by", LinkType.LinkDirection.INWARD)),
                    )
                )
            )
            assertEquals(2, issues.size) { "Unexpected issues count: $issues" }
            with(issues[0]) {
                assertEquals("TEST-123", key)
            }
            with(issues[1]) {
                assertEquals("TEST-124", key)
            }
        }
    }

    @Test
    fun `ignores links if the issue is not in white list`() {
        val strategy = LinkedIssuesStrategy(
            LinkedIssuesStrategyConfiguration(
                listOf(
                    TrackingLink(
                        linkName = "is cloned by",
                        whitelist = listOf(
                            TrackingWhiteList(
                                projectKey = "TEST",
                                issues = setOf("TMP-42")
                            )
                        )
                    )
                )
            )
        )

        runBlockingTest {
            val issues = strategy.findRelatedFor(
                ServiceHolder(jira, zephyr),
                Issue(
                    1, "TMP-43", "TMP",
                    listOf(
                        IssueLink("TEST-123", LinkType("Clones", "is cloned by", LinkType.LinkDirection.INWARD)),
                        IssueLink("TEST-124", LinkType("Clones", "is cloned by", LinkType.LinkDirection.INWARD)),
                    )
                )
            )
            assertTrue(issues.isEmpty()) { "Unexpected issues: $issues" }
        }
    }

    @Test
    fun `ignores disabled links`() {
        val strategy = LinkedIssuesStrategy(
            LinkedIssuesStrategyConfiguration(
                listOf(
                    TrackingLink(
                        linkName = "is cloned by",
                        disable = true,
                        whitelist = listOf(
                            TrackingWhiteList(
                                projectKey = "TEST",
                                issues = setOf("TMP-42")
                            )
                        )
                    )
                )
            )
        )

        runBlockingTest {
            val issues = strategy.findRelatedFor(
                ServiceHolder(jira, zephyr),
                Issue(
                    1, "TMP-42", "TMP",
                    listOf(
                        IssueLink("TEST-123", LinkType("Clones", "is cloned by", LinkType.LinkDirection.INWARD)),
                        IssueLink("TEST-124", LinkType("Clones", "is cloned by", LinkType.LinkDirection.INWARD)),
                    )
                )
            )
            assertTrue(issues.isEmpty()) { "Unexpected issues: $issues" }
        }
    }

    @Test
    fun `ignores linked issues for project that is not in whitelist`() {
        val strategy = LinkedIssuesStrategy(
            LinkedIssuesStrategyConfiguration(
                listOf(
                    TrackingLink(
                        linkName = "is cloned by",
                        whitelist = listOf(
                            TrackingWhiteList(
                                projectKey = "TEST",
                                issues = setOf("TMP-42")
                            ),
                            TrackingWhiteList(
                                projectKey = "BAR",
                                issues = setOf("TMP-42")
                            )
                        )
                    )
                )
            )
        )

        runBlockingTest {
            val issues = strategy.findRelatedFor(
                ServiceHolder(jira, zephyr),
                Issue(
                    1, "TMP-42", "TMP",
                    listOf(
                        IssueLink("TEST-123", LinkType("Clones", "is cloned by", LinkType.LinkDirection.INWARD)),
                        IssueLink("FOO-124", LinkType("Clones", "is cloned by", LinkType.LinkDirection.INWARD)),
                        IssueLink("BAR-124", LinkType("Clones", "is cloned by", LinkType.LinkDirection.INWARD)),
                    )
                )
            )
            assertEquals(2, issues.size) { "Unexpected issues count: $issues" }
            with(issues[0]) {
                assertEquals("TEST-123", key)
            }
            with(issues[1]) {
                assertEquals("BAR-124", key)
            }
        }
    }

    @Test
    fun `accepts any issue if wildcard in whitelist`() {
        val strategy = LinkedIssuesStrategy(
            LinkedIssuesStrategyConfiguration(
                listOf(
                    TrackingLink(
                        linkName = "is cloned by",
                        whitelist = listOf(
                            TrackingWhiteList(
                                projectKey = "TEST",
                                issues = setOf(ALL_ISSUES)
                            )
                        )
                    )
                )
            )
        )

        runBlockingTest {
            val issues = strategy.findRelatedFor(
                ServiceHolder(jira, zephyr),
                Issue(
                    1, "TMP-43", "TMP",
                    listOf(
                        IssueLink("TEST-123", LinkType("Clones", "is cloned by", LinkType.LinkDirection.INWARD)),
                        IssueLink("TEST-124", LinkType("Clones", "is cloned by", LinkType.LinkDirection.INWARD)),
                    )
                )
            )
            assertEquals(2, issues.size) { "Unexpected issues count: $issues" }
            with(issues[0]) {
                assertEquals("TEST-123", key)
            }
            with(issues[1]) {
                assertEquals("TEST-124", key)
            }
        }
    }

    @Test
    fun `tracks different links types`() {
        val strategy = LinkedIssuesStrategy(
            LinkedIssuesStrategyConfiguration(
                listOf(
                    TrackingLink(
                        linkName = "is cloned by",
                        whitelist = listOf(
                            TrackingWhiteList(
                                projectKey = "TEST",
                                issues = setOf("TMP-42")
                            )
                        )
                    ),
                    TrackingLink(
                        linkName = "is duplicated by",
                        whitelist = listOf(
                            TrackingWhiteList(
                                projectKey = "TEST",
                                issues = setOf("TMP-42")
                            )
                        )
                    )
                )
            )
        )

        runBlockingTest {
            val issues = strategy.findRelatedFor(
                ServiceHolder(jira, zephyr),
                Issue(
                    1, "TMP-42", "TMP",
                    listOf(
                        IssueLink("TEST-123", LinkType("Clones", "is cloned by", LinkType.LinkDirection.INWARD)),
                        IssueLink("TEST-124", LinkType("Duplicates", "is duplicated by", LinkType.LinkDirection.INWARD)),
                    )
                )
            )
            assertEquals(2, issues.size) { "Unexpected issues count: $issues" }
            with(issues[0]) {
                assertEquals("TEST-123", key)
            }
            with(issues[1]) {
                assertEquals("TEST-124", key)
            }
        }
    }

    @Test
    fun `correctly uses direction`() {
        val strategy = LinkedIssuesStrategy(
            LinkedIssuesStrategyConfiguration(
                listOf(
                    TrackingLink(
                        linkName = "relates to",
                        direction = LinkType.LinkDirection.INWARD,
                        whitelist = listOf(
                            TrackingWhiteList(
                                projectKey = "TEST",
                                issues = setOf("TMP-42")
                            )
                        )
                    )
                )
            )
        )

        runBlockingTest {
            val issues = strategy.findRelatedFor(
                ServiceHolder(jira, zephyr),
                Issue(
                    1, "TMP-42", "TMP",
                    listOf(
                        IssueLink("TEST-123", LinkType("Relation", "relates to", LinkType.LinkDirection.INWARD)),
                        IssueLink("TEST-124", LinkType("Relation", "relates to", LinkType.LinkDirection.OUTWARD)),
                    )
                )
            )
            assertEquals(1, issues.size) { "Unexpected issues count: $issues" }
            with(issues[0]) {
                assertEquals("TEST-123", key)
            }
        }
    }
}