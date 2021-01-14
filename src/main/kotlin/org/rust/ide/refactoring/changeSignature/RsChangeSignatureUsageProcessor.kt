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

class RsChangeSignatureUsageProcessor : ChangeSignatureUsageProcessor {
    override fun findUsages(changeInfo: ChangeInfo?): Array<UsageInfo> {
        val rsChangeInfo = changeInfo as? RsSignatureChangeInfo ?: return emptyArray()
        val function = rsChangeInfo.config.function
        return findFunctionUsages(function).toTypedArray()
    }

    override fun findConflicts(info: ChangeInfo?, refUsages: Ref<Array<UsageInfo>>?): MultiMap<PsiElement, String> =
        MultiMap.create()

    override fun processUsage(
        changeInfo: ChangeInfo?,
        usageInfo: UsageInfo?,
        beforeMethodChange: Boolean,
        usages: Array<out UsageInfo>?
    ): Boolean {
        if (beforeMethodChange) return false

        val rsChangeInfo = changeInfo as? RsSignatureChangeInfo ?: return false
        val usage = usageInfo as? RsFunctionUsage ?: return false
        processFunctionUsage(rsChangeInfo.config, usage)

        return false
    }

    override fun processPrimaryMethod(changeInfo: ChangeInfo?): Boolean {
        val rsChangeInfo = changeInfo as? RsSignatureChangeInfo ?: return false
        val config = rsChangeInfo.config
        val function = config.function
        val project = function.project

        /* TODO: solve properly
        if (function.owner is RsAbstractableOwner.Trait) {
            function.searchForImplementations().forEach {
                val overridden = (it as? RsFunction) ?: return@forEach
                processFunction(project, config, overridden, findFunctionUsages(overridden))
            }
        }*/

        processFunction(project, config, function)
        return false
    }

    override fun shouldPreviewUsages(changeInfo: ChangeInfo?, usages: Array<out UsageInfo>?): Boolean = true

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
