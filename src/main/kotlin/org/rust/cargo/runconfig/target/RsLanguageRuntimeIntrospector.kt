/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.runconfig.target

import com.intellij.execution.target.LanguageRuntimeType
import com.intellij.util.text.nullize
import java.util.concurrent.CompletableFuture

class RsLanguageRuntimeIntrospector(val config: RsLanguageRuntimeConfiguration) :
    LanguageRuntimeType.Introspector<RsLanguageRuntimeConfiguration> {

    override fun introspect(
        subject: LanguageRuntimeType.Introspectable
    ): CompletableFuture<RsLanguageRuntimeConfiguration> {
        val toolchainPromise = if (config.cargoPath.isBlank()) {
            subject.promiseEnvironmentVariable("CARGO_HOME")
                .thenApply { acceptCargoPath(it) }
        } else {
            LanguageRuntimeType.Introspector.DONE
        }

        return CompletableFuture.allOf(toolchainPromise).thenApply { config }
    }

    private fun acceptCargoPath(toolchainPath: String?) {
        if (config.cargoPath.isNotBlank()) return
        val toolchainPathString = toolchainPath.nullize(true) ?: return
        // TODO: use system-dependent path/name
        config.cargoPath = "$toolchainPathString/bin/cargo"
    }
}
