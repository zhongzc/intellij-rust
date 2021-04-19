/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.toolchain

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import org.rust.cargo.util.hasExecutable
import org.rust.cargo.util.pathToExecutable
import org.rust.stdext.isExecutable
import java.io.File
import java.nio.file.Path

open class RsLocalToolchain(location: Path) : RsToolchain(location) {
    override val fileSeparator: String get() = File.separator

    override val executionTimeoutInMilliseconds: Int = 1000

    override fun patchCommandLine(commandLine: GeneralCommandLine): GeneralCommandLine = commandLine

    override fun toLocalPath(remotePath: String): String = remotePath

    override fun toRemotePath(localPath: String): String = localPath

    override fun expandUserHome(remotePath: String): String = FileUtil.expandUserHome(remotePath)

    override fun getExecutableName(toolName: String): String = if (SystemInfo.isWindows) "$toolName.exe" else toolName

    override fun pathToExecutable(toolName: String): Path = location.pathToExecutable(toolName)

    override fun hasExecutable(exec: String): Boolean = location.hasExecutable(exec)

    override fun hasCargoExecutable(exec: String): Boolean = pathToCargoExecutable(exec).isExecutable()
}
