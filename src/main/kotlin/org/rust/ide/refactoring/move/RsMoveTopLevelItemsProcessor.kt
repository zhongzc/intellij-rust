/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring.move

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.move.MoveMultipleElementsViewDescriptor
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewDescriptor
import com.intellij.util.containers.MultiMap
import org.rust.lang.core.psi.RsModItem
import org.rust.lang.core.psi.ext.RsItemElement
import org.rust.lang.core.psi.ext.RsMod
import org.rust.lang.core.psi.ext.expandedItemsExceptImplsAndUses

class RsMoveTopLevelItemsProcessor(
    private val project: Project,
    private val itemsToMove: List<RsItemElement>,
    private val targetMod: RsMod,
    private val searchForReferences: Boolean
) : BaseRefactoringProcessor(project) {

    private val commonProcessor: RsMoveCommonProcessor = run {
        val elementsToMove = itemsToMove.map { ElementToMove.fromItem(it) }
        RsMoveCommonProcessor(project, elementsToMove, targetMod)
    }

    override fun findUsages(): Array<UsageInfo> {
        if (!searchForReferences) return UsageInfo.EMPTY_ARRAY
        val usages = doFindUsages()
        return commonProcessor.convertToMoveUsages(usages)
    }

    private fun doFindUsages(): Array<UsageInfo> {
        return itemsToMove
            .flatMap { ReferencesSearch.search(it, GlobalSearchScope.projectScope(project)) }
            .filterNotNull()
            .map { UsageInfo(it) }
            .toTypedArray()
    }

    private fun checkNoItemsWithSameName(conflicts: MultiMap<PsiElement, String>) {
        if (!searchForReferences) return
        val targetModItems = targetMod.expandedItemsExceptImplsAndUses.associateBy { it.name }
        for (item in itemsToMove) {
            val name = item.name ?: continue
            val targetModItem = targetModItems[name]
            // actually it is allowed to e.g. have function, struct and child mod with same name in one file
            // but not allowed to e.g. have struct and enum with same name
            // so this check is not very accurate
            if (targetModItem != null) {
                conflicts.putValue(targetModItem, "Target file already contains item with name $name")
            }
        }
    }

    override fun preprocessUsages(refUsages: Ref<Array<UsageInfo>>): Boolean {
        val usages = refUsages.get()
        val conflicts = MultiMap<PsiElement, String>()
        checkNoItemsWithSameName(conflicts)
        return commonProcessor.preprocessUsages(usages, conflicts) && showConflicts(conflicts, usages)
    }

    override fun performRefactoring(usages: Array<out UsageInfo>) {
        commonProcessor.performRefactoring(usages, this::moveItems)
    }

    private fun moveItems(): List<ElementToMove> {
        return itemsToMove.map { item ->
            val space = item.nextSibling as? PsiWhiteSpace

            val itemNew = targetMod.addInner(item) as RsItemElement
            if (space != null) targetMod.addInner(space)
            commonProcessor.updateMovedItemVisibility(itemNew, item)

            space?.delete()
            item.delete()

            ElementToMove.fromItem(itemNew)
        }
    }

    override fun createUsageViewDescriptor(usages: Array<out UsageInfo>): UsageViewDescriptor =
        MoveMultipleElementsViewDescriptor(itemsToMove.toTypedArray(), targetMod.name ?: "")

    override fun getCommandName(): String = "Move items"
}

// like PsiElement::add, but works correctly for RsModItem
private fun RsMod.addInner(element: PsiElement): PsiElement =
    addBefore(element, if (this is RsModItem) rbrace else null)
