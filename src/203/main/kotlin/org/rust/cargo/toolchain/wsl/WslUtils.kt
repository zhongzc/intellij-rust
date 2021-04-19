/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.toolchain.wsl

import com.intellij.execution.wsl.WSLDistribution
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapiext.isDispatchThread
import org.rust.openapiext.computeWithCancelableProgress

val WSLDistribution.userHome: String?
    get() = if (isDispatchThread) {
        val project = ProjectManager.getInstance().defaultProject
        project.computeWithCancelableProgress("Loading Coverage Data...") {
            environment["HOME"]
        }
    } else {
        environment["HOME"]
    }
