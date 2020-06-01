/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring.move

import com.intellij.psi.PsiElement
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.refactoring.util.RefactoringUIUtil
import com.intellij.util.containers.MultiMap
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*

class RsMoveConflictsDetector(
    private val elementsToMove: List<ElementToMove>,
    private val targetMod: RsMod
) {

    val itemsToMakePublic: MutableSet<RsElement> = mutableSetOf()

    fun detectInsideReferencesVisibilityProblems(
        conflicts: MultiMap<PsiElement, String>,
        insideReferences: List<RsMoveReferenceInfo>
    ) {
        for (reference in insideReferences) {
            val pathOld = reference.pathOldOriginal
            if (reference.pathNewAccessible == null) {
                addVisibilityConflict(conflicts, pathOld, reference.target)
            }

            val usageMod = pathOld.containingMod
            val isSelfReference = pathOld.isInsideMovedElements(elementsToMove)
            if (!isSelfReference && !usageMod.superMods.contains(targetMod)) {
                itemsToMakePublic.add(reference.target)
            }
        }
    }

    fun detectOutsideReferencesVisibilityProblems(
        conflicts: MultiMap<PsiElement, String>,
        outsideReferences: List<RsMoveReferenceInfo>
    ) {
        for (reference in outsideReferences) {
            if (reference.pathNewAccessible == null) {
                addVisibilityConflict(conflicts, reference.pathOldOriginal, reference.target)
            }
        }

        detectPrivateStructFieldOutsideReferences(conflicts)
    }

    private fun detectPrivateStructFieldOutsideReferences(conflicts: MultiMap<PsiElement, String>) {
        fun checkStructFieldVisibility(fieldReference: RsMandatoryReferenceElement) {
            val field = fieldReference.reference.resolve() as? RsVisible ?: return
            if (!field.isInsideMovedElements(elementsToMove)) checkVisibility(conflicts, fieldReference, field)
        }

        // todo also use this for inside references
        loop@ for (element in movedElementsDeepDescendantsOfType<RsElement>(elementsToMove)) {
            when (element) {
                is RsDotExpr -> {
                    val fieldReference = element.fieldLookup ?: element.methodCall ?: continue@loop
                    checkStructFieldVisibility(fieldReference)
                }
                is RsStructLiteralField -> {
                    val field = element.resolveToDeclaration() ?: continue@loop
                    if (!field.isInsideMovedElements(elementsToMove)) checkVisibility(conflicts, element, field)
                }
                is RsPatField -> {
                    val patBinding = element.patBinding ?: continue@loop
                    checkStructFieldVisibility(patBinding)
                }
                is RsPatTupleStruct -> {
                    // it is ok to use `resolve` and not `deepResolve` here
                    // because type aliases can't be used in destructuring tuple struct:
                    val struct = element.path.reference?.resolve() as? RsStructItem ?: continue@loop
                    if (struct.isInsideMovedElements(elementsToMove)) continue@loop
                    val fields = struct.tupleFields?.tupleFieldDeclList ?: continue@loop
                    if (!fields.all { it.isVisibleFrom(targetMod) }) {
                        addVisibilityConflict(conflicts, element, struct)
                    }
                }
            }
        }
    }

    private fun checkVisibility(
        conflicts: MultiMap<PsiElement, String>,
        referenceElement: RsReferenceElement,
        target: RsVisible
    ) {
        if (!target.isVisibleFrom(targetMod)) {
            addVisibilityConflict(conflicts, referenceElement, target)
        }
    }

    private fun addVisibilityConflict(
        conflicts: MultiMap<PsiElement, String>,
        reference: RsElement,
        target: RsElement
    ) {
        val referenceDescription = RefactoringUIUtil.getDescription(reference.containingMod, true)
        val targetDescription = RefactoringUIUtil.getDescription(target, true)
        val message = "$referenceDescription uses $targetDescription which will be inaccessible after move"
        conflicts.putValue(reference, CommonRefactoringUtil.capitalize(message))
    }
}
