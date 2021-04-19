/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.toolchain.wsl

import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WSLUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.io.isFile
import org.rust.stdext.toPath
import java.nio.file.Path

fun WSLDistribution.expandUserHome(path: String): String {
    if (!path.startsWith("~/")) return path
    val userHome = userHome ?: return path
    return "$userHome${path.substring(1)}"
}

// BACKCOMPAT: 2020.3
// Replace with [WslPath.parseWindowsUncPath]
@Suppress("DEPRECATION")
fun parseUncPath(uncPath: Path): Pair<Path, WSLDistribution>? {
    val uncPathText = uncPath.toString()
    if (!uncPathText.startsWith(WSLDistribution.UNC_PREFIX)) return null
    val path = FileUtil.toSystemIndependentName(uncPathText.removePrefix(WSLDistribution.UNC_PREFIX))
    val index = path.indexOf('/')
    if (index == -1) return null
    val wslPath = path.substring(index).toPath()
    val distName = path.substring(0, index)
    val distribution = WSLUtil.getDistributionByMsId(distName) ?: return null
    return wslPath to distribution
}

fun Path.hasExecutableOnWsl(toolName: String): Boolean = pathToExecutableOnWsl(toolName).isFile()

fun Path.pathToExecutableOnWsl(toolName: String): Path = resolve(toolName)
