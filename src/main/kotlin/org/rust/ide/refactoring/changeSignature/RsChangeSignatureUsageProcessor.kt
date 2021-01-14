/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring.changeSignature

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.refactoring.changeSignature.ChangeInfo
import com.intellij.refactoring.changeSignature.ChangeSignatureUsageProcessor
import com.intellij.refactoring.rename.ResolveSnapshotProvider
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import org.jetbrains.annotations.NotNull
import org.rust.RsBundle
import org.rust.ide.presentation.getPresentation
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.RsImplItem
import org.rust.lang.core.psi.ext.*

class RsChangeSignatureUsageProcessor : ChangeSignatureUsageProcessor {
    override fun findUsages(changeInfo: ChangeInfo?): Array<UsageInfo> {
        val rsChangeInfo = changeInfo as? RsSignatureChangeInfo ?: return emptyArray()
        val function = rsChangeInfo.config.function
        val usages = findFunctionUsages(function).toMutableList()
        if (function.owner is RsAbstractableOwner.Trait) {
            function.searchForImplementations().filterIsInstance<RsFunction>().forEach { method ->
                usages.add(RsFunctionUsage.MethodImplementation(method))
                usages.addAll(findFunctionUsages(method))
            }
        }

        return usages.toTypedArray()
    }

    override fun findConflicts(changeInfo: ChangeInfo?, refUsages: Ref<Array<UsageInfo>>): MultiMap<PsiElement, String> {
        val rsChangeInfo = changeInfo as? RsSignatureChangeInfo ?: return MultiMap.empty()
        val map = MultiMap.create<PsiElement, String>()
        val config = rsChangeInfo.config
        val function = config.function

        findNameConflicts(function, config, map)
        findVisibilityConflicts(function, config, refUsages.get(), map)

        return map
    }

    override fun processUsage(
        changeInfo: ChangeInfo?,
        usageInfo: UsageInfo?,
        beforeMethodChange: Boolean,
        usages: Array<out UsageInfo>?
    ): Boolean {
        if (beforeMethodChange) return false

        val rsChangeInfo = changeInfo as? RsSignatureChangeInfo ?: return false
        val usage = usageInfo as? RsFunctionUsage ?: return false
        val config = rsChangeInfo.config
        if (usage is RsFunctionUsage.MethodImplementation) {
            processFunction(config.function.project, config, usage.overriddenMethod)
        }
        else {
            processFunctionUsage(config, usage)
        }

        return false
    }

    override fun processPrimaryMethod(changeInfo: ChangeInfo?): Boolean {
        val rsChangeInfo = changeInfo as? RsSignatureChangeInfo ?: return false
        val config = rsChangeInfo.config
        val function = config.function
        val project = function.project

        processFunction(project, config, function)
        return false
    }

    override fun shouldPreviewUsages(changeInfo: ChangeInfo?, usages: Array<out UsageInfo>?): Boolean = false

    override fun setupDefaultValues(
        changeInfo: ChangeInfo?,
        refUsages: Ref<Array<UsageInfo>>?,
        project: Project?
    ): Boolean {
        return true
    }

    override fun registerConflictResolvers(
        snapshots: MutableList<ResolveSnapshotProvider.ResolveSnapshot>?,
        resolveSnapshotProvider: ResolveSnapshotProvider,
        usages: Array<out UsageInfo>?,
        changeInfo: ChangeInfo?
    ) {
    }
}

private fun findVisibilityConflicts(
    function: RsFunction,
    config: RsChangeFunctionSignatureConfig,
    usages: Array<UsageInfo>,
    map: @NotNull MultiMap<PsiElement, String>
) {
    val functionUsages = usages.filterIsInstance<RsFunctionUsage>()
    val clone = function.copy() as RsFunction
    changeVisibility(clone, config)

    for (usage in functionUsages) {
        val sourceModule = (usage.element as? RsElement)?.containingMod ?: continue
        if (!clone.isVisibleFrom(sourceModule)) {
            val moduleName = sourceModule.qualifiedName.orEmpty()
            map.putValue(function, RsBundle.message("refactoring.change.signature.visibility.conflict", moduleName))
        }
    }
}

private fun findNameConflicts(
    function: RsFunction,
    config: RsChangeFunctionSignatureConfig,
    map: @NotNull MultiMap<PsiElement, String>
) {
    val (owner, items) = when (val owner = function.owner) {
        is RsAbstractableOwner.Impl -> owner.impl to owner.impl.expandedMembers
        is RsAbstractableOwner.Trait -> owner.trait to owner.trait.expandedMembers
        else -> {
            val parent = function.parent as RsItemsOwner
            parent to parent.itemsAndMacros.filterIsInstance<RsItemElement>().toList()
        }
    }
    for (item in items) {
        if (item == function) continue
        if (item.name == config.name) {
            val presentation = getPresentation(owner)
            val prefix = if (owner is RsImplItem) "impl " else ""
            val ownerName = "${prefix}${presentation.presentableText.orEmpty()} ${presentation.locationString.orEmpty()}"
            map.putValue(function, RsBundle.message("refactoring.change.signature.name.conflict", config.name, ownerName))
        }
    }
}
