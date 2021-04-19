/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.toolchain.wsl

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.wsl.WSLCommandLineOptions
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WSLUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.io.isFile
import com.intellij.util.io.systemIndependentPath
import org.rust.cargo.toolchain.RsToolchain
import org.rust.stdext.toPath
import java.io.File
import java.nio.file.Path

class RsWslToolchain(
    private val wslLocation: Path,
    private val distribution: WSLDistribution
) : RsToolchain(distribution.getLocalPath(wslLocation) ?: wslLocation) {

    override val fileSeparator: String = "/"

    override val executionTimeoutInMilliseconds: Int = 5000

    override fun patchCommandLine(commandLine: GeneralCommandLine): GeneralCommandLine {
        val parameters = commandLine.parametersList.list.map { toRemotePath(it) }
        commandLine.parametersList.clearAll()
        commandLine.parametersList.addAll(parameters)

        commandLine.environment.forEach { (k, v) ->
            val paths = v.split(File.pathSeparatorChar)
            commandLine.environment[k] = paths.joinToString(":") { toRemotePath(it) }
        }

        commandLine.workDirectory?.let {
            if (it.path.startsWith(fileSeparator)) {
                commandLine.workDirectory = File(toLocalPath(it.path))
            }
        }

        val remoteWorkDir = commandLine.workDirectory?.absolutePath
            ?.let { toRemotePath(it) }
        val options = WSLCommandLineOptions()
            .setRemoteWorkingDirectory(remoteWorkDir)
            .addInitCommand("export PATH=\"${wslLocation.systemIndependentPath}:\$PATH\"")
        return distribution.patchCommandLine(commandLine, null, options)
    }

    override fun toLocalPath(remotePath: String): String =
        distribution.getLocalPath(remotePath) ?: remotePath

    override fun toRemotePath(localPath: String): String {
        // BACKCOMPAT: 2020.3
        if (FileUtil.toSystemDependentName(localPath).startsWith(WSLDistribution.UNC_PREFIX)) {
            val path = StringUtil.trimStart(FileUtil.toSystemDependentName(localPath), WSLDistribution.UNC_PREFIX)
            val index = path.indexOf('\\')
            if (index == -1) return localPath
            val distName = path.substring(0, index)
            require(distName.equals(distribution.msId, ignoreCase = true)) {
                "Trying to get WSL path from a different WSL distribution"
            }
            return FileUtil.toSystemIndependentName(path.substring(index))
        }

        return distribution.getWslPath(localPath) ?: localPath
    }

    override fun expandUserHome(remotePath: String): String =
        distribution.expandUserHome(remotePath)

    override fun getExecutableName(toolName: String): String = toolName

    override fun pathToExecutable(toolName: String): Path = wslLocation.pathToExecutableOnWsl(toolName)

    override fun hasExecutable(exec: String): Boolean =
        distribution.getLocalPath(pathToExecutable(exec))?.isFile() == true

    override fun hasCargoExecutable(exec: String): Boolean =
        distribution.getLocalPath(pathToCargoExecutable(exec))?.isFile() == true

    companion object {

        // BACKCOMPAT: 2020.3
        // Replace with [WSLDistribution.getWindowsPath]
        @Suppress("DEPRECATION")
        private fun WSLDistribution.getLocalPath(wslPath: String): String? =
            if (wslPath.startsWith(mntRoot)) {
                WSLUtil.getWindowsPath(wslPath, mntRoot)
            } else {
                "$uncRoot${FileUtil.toSystemDependentName(wslPath)}"
            }

        private fun WSLDistribution.getLocalPath(wslPath: Path): Path? =
            getLocalPath(wslPath.toString())?.toPath()
    }
}
