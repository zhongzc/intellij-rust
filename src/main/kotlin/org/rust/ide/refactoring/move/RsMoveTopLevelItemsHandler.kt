package org.rust.ide.refactoring.move

import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import com.intellij.refactoring.move.MoveCallback
import com.intellij.refactoring.move.MoveHandlerDelegate
import com.intellij.refactoring.util.CommonRefactoringUtil
import org.rust.ide.utils.collectElements
import org.rust.ide.utils.getElementRange
import org.rust.ide.utils.getTopmostParentInside
import org.rust.lang.RsLanguage
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.psi.ext.RsItemElement
import org.rust.lang.core.psi.ext.RsMod
import org.rust.lang.core.psi.ext.childrenOfType
import org.rust.lang.core.types.ty.TyAdt
import org.rust.lang.core.types.type

class RsMoveTopLevelItemsHandler : MoveHandlerDelegate() {

    override fun supportsLanguage(language: Language): Boolean = language.`is`(RsLanguage)

    override fun canMove(
        element: Array<out PsiElement>,
        targetContainer: PsiElement?,
        reference: PsiReference?
    ): Boolean {
        val containingMod = (element.firstOrNull() as? RsElement)?.containingMod ?: return false
        return element.all { canMoveElement(it) && it.parent == containingMod }
    }

    // looks like IntelliJ platform never calls this method (and always calls `tryToMove` instead)
    // but lets implement it just in case
    override fun doMove(
        project: Project,
        elements: Array<out PsiElement>,
        targetContainer: PsiElement?,
        moveCallback: MoveCallback?
    ) {
        doMove(project, elements, null)
    }

    override fun tryToMove(
        element: PsiElement,
        project: Project,
        dataContext: DataContext?,
        reference: PsiReference?,
        editor: Editor?
    ): Boolean {
        val elements = arrayOf(element)
        if (editor?.selectionModel?.hasSelection() != true && !canMove(elements, null, reference)) return false

        doMove(project, elements, editor)
        return true
    }

    private fun doMove(project: Project, elements: Array<out PsiElement>, editor: Editor?) {
        val (itemsToMove, containingMod) = editor?.let { collectSelectedItems(project, editor) }
            ?: run {
                val containingMod = PsiTreeUtil.findCommonParent(*elements)?.parentOfType<RsMod>() ?: return
                val items = elements.filterIsInstance<RsItemElement>().toList()
                items to containingMod
            }

        if (!CommonRefactoringUtil.checkReadOnlyStatusRecursively(project, itemsToMove, true)) return

        val relatedImplItems = collectRelatedImplItems(containingMod, itemsToMove)
        RsMoveTopLevelItemsDialog(project, itemsToMove + relatedImplItems, containingMod).show()
    }

    private fun collectSelectedItems(project: Project, editor: Editor): Pair<List<RsItemElement>, RsMod>? {
        val selection = editor.selectionModel
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return null

        if (!selection.hasSelection()) {
            val element = file.findElementAt(selection.selectionStart) ?: return null
            val containingMod = element.parentOfType<RsMod>() ?: return null
            val item = element.getTopmostParentInside(containingMod) as? RsItemElement ?: return null
            return listOf(item) to containingMod
        }

        val (element1, element2) = file.getElementRange(selection.selectionStart, selection.selectionEnd)
            ?: return null
        val containingMod = PsiTreeUtil.findCommonParent(element1, element2)?.parentOfType<RsMod>(true) ?: return null
        val item1 = element1.getTopmostParentInside(containingMod)
        val item2 = element2.getTopmostParentInside(containingMod)
        val items = collectElements(item1, item2.nextSibling) { it is RsItemElement }
            .map { it as RsItemElement }
        return items to containingMod
    }

    private fun collectRelatedImplItems(containingMod: RsMod, items: List<RsItemElement>): List<RsImplItem> {
        // For struct `Foo` we should collect:
        // * impl Foo { ... }
        // * impl ... for Foo { ... }
        // * Maybe also `impl From<Foo> for Bar { ... }`?
        //   if `Bar` belongs to same crate (but to different module from `Foo`)
        //
        // For trait `Foo` we should collect:
        // * impl Foo for ... { ... }

        return containingMod
            .childrenOfType<RsImplItem>()
            .filter {
                val trait = it.traitRef?.path?.reference?.resolve()
                val item = (it.typeReference?.type as? TyAdt)?.item
                trait != null && items.contains(trait)
                    || item != null && items.contains(item)
            }
    }

    companion object {
        fun canMoveElement(element: PsiElement): Boolean {
            return element is RsItemElement
                && element !is RsModDeclItem
                && element !is RsUseItem
                && element !is RsExternCrateItem
                && element !is RsForeignModItem
        }
    }
}
