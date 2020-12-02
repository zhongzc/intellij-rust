/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.toml.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.util.findDescendantOfType
import com.intellij.util.ProcessingContext
import org.rust.lang.core.psi.ext.ancestorOrSelf
import org.rust.toml.containingDependencyKey
import org.rust.toml.crates.local.CratesLocalIndexService
import org.toml.lang.psi.TomlArray
import org.toml.lang.psi.TomlKeyValue
import org.toml.lang.psi.TomlLiteral
import org.toml.lang.psi.ext.TomlLiteralKind
import org.toml.lang.psi.ext.kind

/**
 * Consider `Cargo.toml` with dependency from crates.io:
 * ```
 * [dependencies]
 * foo = { version = "*", features = ["<caret>"] }
 *                                    #^ Provides completion here
 *
 * [dependencies.foo]
 * features = ["<caret>"]
 *             #^ Provides completion here
 * ```
 *
 * @see [org.rust.toml.completion.MetadataCargoTomlDependencyFeaturesCompletionProvider]
 */
class LocalIndexCargoTomlFeatureDependencyCompletionProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
        val containingArray = parameters.position.ancestorOrSelf<TomlArray>() ?: return
        val pkgName = containingArray.containingDependencyKey?.text ?: return
        val parentParent = containingArray.parent?.parent ?: return

        val cratesService = CratesLocalIndexService.getInstance()

        val crate = cratesService.getCrate(pkgName) ?: return
        val pkgVersion = ((parentParent.findDescendantOfType<TomlKeyValue> {
            it.key.text == "version"
        }?.value as? TomlLiteral)?.kind as? TomlLiteralKind.String)?.value ?: return

        val features = crate.findSatisfyingVersion(pkgVersion)?.features ?: return

        result.addAllElements(features.map { feature ->
            LookupElementBuilder.create(feature)
        })
    }
}
