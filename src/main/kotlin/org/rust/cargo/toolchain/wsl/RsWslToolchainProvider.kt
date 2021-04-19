/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.toolchain.wsl

import org.rust.cargo.toolchain.RsToolchain
import org.rust.cargo.toolchain.RsToolchainProvider
import java.nio.file.Path

class RsWslToolchainProvider : RsToolchainProvider {
    override fun getToolchain(homePath: Path): RsToolchain? {
        // BACKCOMPAT: 2020.3
        // Replace with [WslPath.parseWindowsUncPath]
        val (wslPath, distribution) = parseUncPath(homePath) ?: return null
        return RsWslToolchain(wslPath, distribution)
    }
}
