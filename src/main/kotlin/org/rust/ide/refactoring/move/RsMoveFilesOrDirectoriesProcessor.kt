/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring.move

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.refactoring.move.MoveCallback
import com.intellij.refactoring.move.MoveMultipleElementsViewDescriptor
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewDescriptor
import com.intellij.util.IncorrectOperationException
import com.intellij.util.containers.MultiMap
import org.rust.ide.inspections.import.lastElement
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*

// todo move comment to common ?
/**
 * Move refactoring currently supports moving a single file without submodules.
 * It consists of these steps:
 * - Check visibility conflicts (target of any reference should remain accessible after move)
 * - Update `pub(in path)` visibility modifiers in moved file if necessary
 * - Move mod-declaration to new parent mod
 * - Update relative paths in moved file
 * - Update necessary imports in other files
 * - Update necessary paths in other files (some usages could still remain invalid because of glob-imports)
 *     We replace path with absolute if there are few usages of this path in the file, otherwise add new import
 */
class RsMoveFilesOrDirectoriesProcessor(
    private val project: Project,
    private val filesToMove: Array<RsFile>,
    private val newParent: PsiDirectory,
    private val targetMod: RsMod,
    private val moveCallback: MoveCallback?,
    doneCallback: Runnable
) : MoveFilesOrDirectoriesProcessor(
    project,
    filesToMove,
    newParent,
    true,
    true,
    true,
    null,  // we use `moveCallback` directly in `performRefactoring`
    doneCallback
) {

    private val elementsToMove = filesToMove.map { ModToMove(it) }
    private val commonProcessor: RsMoveCommonProcessor = RsMoveCommonProcessor(project, elementsToMove, targetMod)

    init {
        for (file in filesToMove) {
            val modName = file.modName ?: continue
            if (targetMod.getChildModule(modName) != null) {
                throw IncorrectOperationException("Cannot move. Mod with same crate relative path already exists")
            }
        }
    }

    override fun findUsages(): Array<out UsageInfo> {
        val usages = super.findUsages()
        return commonProcessor.convertToMoveUsages(usages)
    }

    override fun preprocessUsages(refUsages: Ref<Array<UsageInfo>>): Boolean {
        val usages = refUsages.get()
        val conflicts = MultiMap<PsiElement, String>()
        checkSingleModDeclaration(usages)
        return commonProcessor.preprocessUsages(usages, conflicts) && showConflicts(conflicts, usages)
    }

    private fun checkSingleModDeclaration(usages: Array<UsageInfo>) {
        val modDeclarationsByFile = usages
            .filterIsInstance<RsModDeclUsage>()
            .groupBy { it.file }
        for (file in filesToMove) {
            val modDeclarations = modDeclarationsByFile[file]
                ?: error("Can't move ${file.name}.\nIt is not included in module tree")
            if (modDeclarations.size > 1) {
                error("Can't move ${file.name}.\nIt is declared in more than one parent modules")
            }
        }
    }

    override fun performRefactoring(usages: Array<out UsageInfo>) {
        val oldModDeclarations = usages.filterIsInstance<RsModDeclUsage>()
        commonProcessor.performRefactoring(usages) {
            moveFilesAndModDeclarations(oldModDeclarations)
            // after move `RsFile`s remain valid
            elementsToMove
        }
        moveCallback?.refactoringCompleted()
    }

    private fun moveFilesAndModDeclarations(oldModDeclarations: List<RsModDeclUsage>) {
        moveModDeclaration(oldModDeclarations)
        super.performRefactoring(emptyArray())

        for (file in filesToMove) {
            check(!file.crateRelativePath.isNullOrEmpty())
            { "${file.name} had correct crateRelativePath before moving mod-declaration, but empty/null after move" }
        }
    }

    private fun moveModDeclaration(oldModDeclarationsAll: List<RsModDeclUsage>) {
        val psiFactory = RsPsiFactory(project)
        for ((file, oldModDeclarations) in oldModDeclarationsAll.groupBy { it.file }) {
            val oldModDeclaration = oldModDeclarations.single().element
            commonProcessor.updateMovedItemVisibility(oldModDeclaration, file)
            val newModDeclaration = oldModDeclaration.copy()
            oldModDeclaration.delete()
            targetMod.insertModDecl(psiFactory, newModDeclaration)
        }
    }

    override fun createUsageViewDescriptor(usages: Array<out UsageInfo>): UsageViewDescriptor =
        MoveMultipleElementsViewDescriptor(filesToMove, newParent.name)
}

private fun RsMod.insertModDecl(psiFactory: RsPsiFactory, modDecl: PsiElement) {
    val anchor = childrenOfType<RsModDeclItem>().lastElement ?: childrenOfType<RsUseItem>().lastElement
    if (anchor != null) {
        addAfter(modDecl, anchor)
    } else {
        val firstItem = itemsAndMacros.firstOrNull { it !is RsAttr && it !is RsVis }
            ?: (this as? RsModItem)?.rbrace
        addBefore(modDecl, firstItem)
    }

    if (modDecl.nextSibling == null) {
        addAfter(psiFactory.createNewline(), modDecl)
    }
}
