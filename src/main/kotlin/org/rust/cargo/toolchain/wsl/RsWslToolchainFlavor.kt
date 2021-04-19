/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.toolchain.wsl

import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WSLUtil
import com.intellij.execution.wsl.WslDistributionManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapiext.isDispatchThread
import com.intellij.util.io.isDirectory
import org.rust.cargo.toolchain.flavors.RsToolchainFlavor
import org.rust.ide.experiments.RsExperiments
import org.rust.openapiext.computeWithCancelableProgress
import org.rust.openapiext.isFeatureEnabled
import org.rust.stdext.toPath
import java.nio.file.Path

class RsWslToolchainFlavor : RsToolchainFlavor() {

    override fun getHomePathCandidates(): Sequence<Path> = sequence {

        fun <T> compute(title: String, getter: () -> T): T = if (isDispatchThread) {
            val project = ProjectManager.getInstance().defaultProject
            project.computeWithCancelableProgress(title, getter)
        } else {
            getter()
        }

        val distributions = compute("Getting installed distributions...") {
            WslDistributionManager.getInstance().installedDistributions
        }
        for (distro in distributions) {
            // BACKCOMPAT: 2020.3
            // Replace with `distro.uncRootPath`
            @Suppress("DEPRECATION")
            val root = distro.uncRoot.path.toPath()

            val environment = compute("Getting environment variables...") { distro.environment }

            val home = environment["HOME"]
            val remoteCargoPath = home?.let { "$it/.cargo/bin" }
            val localCargoPath = remoteCargoPath?.let { root.resolve(it) }
            if (localCargoPath?.isDirectory() == true) {
                yield(localCargoPath)
            }

            val sysPath = environment["PATH"]
            for (remotePath in sysPath.orEmpty().split(":")) {
                if (remotePath.isEmpty()) continue
                val localPath = root.resolve(remotePath)
                if (!localPath.isDirectory()) continue
                yield(localPath)
            }

            for (remotePath in listOf("/usr/local/bin", "/usr/bin")) {
                val localPath = root.resolve(remotePath)
                if (!localPath.isDirectory()) continue
                yield(localPath)
            }
        }
    }

    override fun isApplicable(): Boolean =
        WSLUtil.isSystemCompatible() && isFeatureEnabled(RsExperiments.WSL_TOOLCHAIN)

    // BACKCOMPAT: 2020.3
    // Replace with [WslDistributionManager.isWslPath]
    override fun isValidToolchainPath(path: Path): Boolean =
        path.toString().startsWith(WSLDistribution.UNC_PREFIX) && super.isValidToolchainPath(path)

    override fun hasExecutable(path: Path, toolName: String): Boolean = path.hasExecutableOnWsl(toolName)

    override fun pathToExecutable(path: Path, toolName: String): Path = path.pathToExecutableOnWsl(toolName)
}
