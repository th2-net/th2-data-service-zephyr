/*
 * Copyright 2020-2022 Exactpro (Exactpro Systems Limited)
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

package com.exactpro.th2.dataprocessor.zephyr.service.api.scale

import com.exactpro.th2.dataprocessor.zephyr.service.api.model.AccountInfo
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.Project
import com.exactpro.th2.dataprocessor.zephyr.service.api.model.Version
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.model.BaseCycle
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.model.BaseFolder
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.model.Cycle
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.model.ExecutionStatus
import com.exactpro.th2.dataprocessor.zephyr.service.api.scale.model.TestCase

interface ZephyrScaleApiService : AutoCloseable {
    suspend fun getExecutionsStatuses(project: Project): List<ExecutionStatus>

    suspend fun getTestCase(key: String): TestCase

    suspend fun getCycle(project: Project, version: Version, folder: BaseFolder?, name: String): Cycle?

    suspend fun getCycle(baseCycle: BaseCycle): Cycle

    suspend fun updateExecution(
        project: Project,
        version: Version,
        cycle: BaseCycle,
        testCase: TestCase,
        status: ExecutionStatus,
        comment: String? = null,
        accountInfo: AccountInfo? = null,
        customFields: Map<String, Any> = emptyMap(),
    )

    suspend fun createExecution(
        project: Project,
        version: Version,
        cycle: BaseCycle,
        testCase: TestCase,
        status: ExecutionStatus,
        comment: String? = null,
        accountInfo: AccountInfo? = null,
        customFields: Map<String, Any> = emptyMap(),
    )
}