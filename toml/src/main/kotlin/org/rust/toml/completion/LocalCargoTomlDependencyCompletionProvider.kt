/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.toml.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.completion.impl.RealPrefixMatchingWeigher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementWeigher
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.util.ProcessingContext
import org.rust.lang.core.completion.nextCharIs
import org.rust.lang.core.psi.ext.ancestorStrict
import org.rust.toml.StringValueInsertionHandler
import org.rust.toml.crates.local.CargoRegistryCrateVersion
import org.rust.toml.crates.local.CratesLocalIndexException
import org.rust.toml.crates.local.CratesLocalIndexService
import org.toml.lang.psi.TomlKeySegment
import org.toml.lang.psi.TomlKeyValue
import org.toml.lang.psi.TomlTable

class LocalCargoTomlDependencyCompletionProvider : TomlKeyValueCompletionProviderBase() {
    override fun completeKey(keyValue: TomlKeyValue, result: CompletionResultSet) {
        val keySegment = keyValue.key.segments.singleOrNull() ?: return
        val prefix = CompletionUtil.getOriginalElement(keySegment)?.text ?: return

        val crateNames = try {
            CratesLocalIndexService.getInstance().getAllCrateNames()
        } catch (e: CratesLocalIndexException) {
            return
        }

        val elements = crateNames.mapNotNull { crateName ->
            PrioritizedLookupElement.withPriority(
                LookupElementBuilder
                    .create(crateName)
                    .withIcon(AllIcons.Nodes.PpLib)
                    .withInsertHandler { ctx, _ ->
                        val alreadyHasValue = ctx.nextCharIs('=')

                        if (!alreadyHasValue) {
                            ctx.document.insertString(ctx.selectionEndOffset, " = \"\"")
                        }

                        EditorModificationUtil.moveCaretRelatively(ctx.editor, 4)

                        if (!alreadyHasValue) {
                            // Triggers dependency version completion
                            AutoPopupController.getInstance(ctx.project).scheduleAutoPopup(ctx.editor)
                        }
                    },
                (-crateName.length).toDouble()
            )
        }
        result.withPrefixMatcher(CargoDependenciesPrefixMatcher(prefix)).addAllElements(elements)
    }

    override fun completeValue(keyValue: TomlKeyValue, result: CompletionResultSet) {
        val keySegment = keyValue.key.segments.singleOrNull() ?: return
        val name = CompletionUtil.getOriginalElement(keySegment)?.text ?: return

        val crate = try {
            CratesLocalIndexService.getInstance().getCrate(name)
        } catch (e: CratesLocalIndexException) {
            return
        }
        val sortedVersions = crate?.sortedVersions ?: return
        val elements = makeVersionCompletions(sortedVersions, keyValue)
        result.withRelevanceSorter(versionsSorter).addAllElements(elements)
    }
}

class LocalCargoTomlSpecificDependencyHeaderCompletionProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
        val key = parameters.position.parent as? TomlKeySegment ?: return
        val crateNames = try {
            CratesLocalIndexService.getInstance().getAllCrateNames()
        } catch (e: CratesLocalIndexException) {
            return
        }

        val elements = crateNames.map { variant ->
            LookupElementBuilder.create(variant)
                .withIcon(AllIcons.Nodes.PpLib)
                .withInsertHandler { ctx, item ->
                    val table = key.ancestorStrict<TomlTable>() ?: return@withInsertHandler
                    if (table.entries.isEmpty()) {
                        ctx.document.insertString(
                            ctx.selectionEndOffset + 1,
                            "\nversion = \"\""
                        )

                        EditorModificationUtil.moveCaretRelatively(ctx.editor, 13)
                        AutoPopupController.getInstance(ctx.project).scheduleAutoPopup(ctx.editor)
                    }
                }
        }

        result.addAllElements(elements)
    }
}

class LocalCargoTomlSpecificDependencyVersionCompletionProvider : TomlKeyValueCompletionProviderBase() {
    override fun completeKey(keyValue: TomlKeyValue, result: CompletionResultSet) {
        result.addElement(
            LookupElementBuilder.create("version")
                .withInsertHandler { ctx, item ->
                    val alreadyHasValue = ctx.nextCharIs('=')

                    if (!alreadyHasValue) {
                        ctx.document.insertString(ctx.selectionEndOffset, " = \"\"")
                    }

                    EditorModificationUtil.moveCaretRelatively(ctx.editor, 4)

                    if (!alreadyHasValue) {
                        // Triggers dependency version completion
                        AutoPopupController.getInstance(ctx.project).scheduleAutoPopup(ctx.editor)
                    }
                }
        )
    }

    override fun completeValue(keyValue: TomlKeyValue, result: CompletionResultSet) {
        val dependencyNameKey = keyValue.getDependencyKeyFromTableHeader()
        val sortedVersions = try {
            CratesLocalIndexService.getInstance().getCrate(dependencyNameKey.text)?.sortedVersions ?: return
        } catch (e: CratesLocalIndexException) {
            return
        }
        val elements = makeVersionCompletions(sortedVersions, keyValue)
        result.withRelevanceSorter(versionsSorter).addAllElements(elements)
    }
}

fun TomlKeyValue.getDependencyKeyFromTableHeader(): TomlKeySegment {
    val table = this.parent as? TomlTable
        ?: error("PsiElementPattern must not allow keys outside of TomlTable")
    return table.header.key?.segments?.lastOrNull()
        ?: error("PsiElementPattern must not allow KeyValues in tables without header")
}

fun makeVersionCompletions(sortedVersions: List<CargoRegistryCrateVersion>, keyValue: TomlKeyValue): List<LookupElement> {
    return sortedVersions.mapIndexed { index, variant ->
        val lookupElement = LookupElementBuilder.create(variant.version)
            .withInsertHandler(StringValueInsertionHandler(keyValue))
            .withTailText(if (variant.isYanked) " yanked" else null)
        PrioritizedLookupElement.withPriority(lookupElement, index.toDouble())
    }
}

val versionsSorter: CompletionSorter = CompletionSorter.emptySorter()
    .weigh(RealPrefixMatchingWeigher())
    .weigh(object : LookupElementWeigher("priority", true, false) {
        override fun weigh(element: LookupElement): Double = (element as PrioritizedLookupElement<*>).priority
    })
