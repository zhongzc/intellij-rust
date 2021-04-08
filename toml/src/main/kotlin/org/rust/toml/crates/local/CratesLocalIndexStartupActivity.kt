/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.toml.crates.local

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapiext.isUnitTestMode
import org.rust.cargo.project.model.CargoProjectsService
import org.rust.cargo.project.model.cargoProjects
import org.rust.ide.experiments.RsExperiments
import org.rust.openapiext.isFeatureEnabled

/**
 * [CratesLocalIndexService] initializer.
 * Tries to get a service instance and binds crates local index updates to cargo project changes topic.
 */
class CratesLocalIndexStartupActivity : StartupActivity.Background {
    override fun runActivity(project: Project) {
        if (isUnitTestMode) return
        if (!project.cargoProjects.hasAtLeastOneValidProject) return

        if (isFeatureEnabled(RsExperiments.CRATES_LOCAL_INDEX)) {
            val cratesService = CratesLocalIndexService.getInstance()

            project.messageBus.connect().subscribe(CargoProjectsService.CARGO_PROJECTS_TOPIC, CargoProjectsService.CargoProjectsListener { _, _ ->
                cratesService.updateIfNeeded()
            })
        }
    }
}
