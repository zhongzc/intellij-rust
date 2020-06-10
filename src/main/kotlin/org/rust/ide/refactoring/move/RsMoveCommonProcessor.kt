/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring.move

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapiext.isUnitTestMode
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.DummyHolder
import com.intellij.psi.util.parentOfType
import com.intellij.refactoring.RefactoringBundle.message
import com.intellij.usageView.UsageInfo
import com.intellij.util.IncorrectOperationException
import com.intellij.util.containers.MultiMap
import org.rust.ide.annotator.fixes.MakePublicFix
import org.rust.ide.inspections.import.RsImportHelper
import org.rust.ide.inspections.import.insertUseItem
import org.rust.ide.refactoring.RsImportOptimizer
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.openapiext.computeWithCancelableProgress

sealed class ElementToMove {
    companion object {
        fun fromItem(item: RsItemElement): ElementToMove = when (item) {
            is RsModItem -> ModToMove(item)
            else -> ItemToMove(item)
        }
    }
}

class ItemToMove(val item: RsItemElement) : ElementToMove()
class ModToMove(val mod: RsMod) : ElementToMove()

// ? todo extract everything related to common to its directory `/common`?
class RsMoveCommonProcessor(
    private val project: Project,
    private var elementsToMove: List<ElementToMove>,
    private val targetMod: RsMod
) {

    private val psiFactory: RsPsiFactory = RsPsiFactory(project)
    private val codeFragmentFactory: RsCodeFragmentFactory = RsCodeFragmentFactory(project)

    private val sourceMod: RsMod = elementsToMove
        .map {
            when (it) {
                is ModToMove -> (it.mod as? RsFile)?.declaration ?: it.mod
                is ItemToMove -> it.item
            }
        }
        .map { it.containingMod }
        .distinct()
        .singleOrNull()
        ?: error("Elements to move must belong to single parent mod")

    private val pathHelper: RsMovePathHelper = RsMovePathHelper(project, targetMod)
    private lateinit var conflictsDetector: RsMoveConflictsDetector

    private val useSpecksToOptimize: MutableList<RsUseSpeck> = mutableListOf()
    private val filesToOptimizeImports: MutableSet<RsFile> = mutableSetOf()

    init {
        if (elementsToMove.isEmpty()) throw IncorrectOperationException("No items to move")
        if (targetMod == sourceMod) throw IncorrectOperationException("Source and destination modules should be different")
    }

    fun convertToMoveUsages(usages: Array<UsageInfo>): Array<UsageInfo> {
        return usages
            .mapNotNull { usage ->
                val element = usage.element
                val reference = usage.reference
                val target = reference?.resolve()
                // todo text usages
                if (element == null || reference == null || target == null) return@mapNotNull null

                when {
                    element is RsModDeclItem && target is RsFile -> RsModDeclUsage(element, target)
                    element is RsPath && target is RsQualifiedNamedElement -> RsPathUsage(element, reference, target)
                    else -> null
                }
            }
            // sorting is needed for stable results in tests
            .sortedWith(compareBy({ it.element.containingMod.crateRelativePath }, { it.element.startOffset }))
            .toTypedArray()
    }

    fun preprocessUsages(usages: Array<UsageInfo>, conflicts: MultiMap<PsiElement, String>): Boolean {
        // todo maybe add usages which needs adding element to list
        //  then insert new element without progress at all, and again search for usages

        val title = message("refactoring.preprocess.usages.progress")
        try {
            // we need to use `computeWithCancelableProgress` and not `runWithCancelableProgress`
            // because otherwise any exceptions will be silently ignored
            project.computeWithCancelableProgress(title) {
                runReadAction {
                    // todo three threads:
                    // - collectOutsideReferences + conflicts
                    // - preprocessInsideReferences + conflicts
                    // - checkImpls   ? +preprocess*TraitMethods
                    val outsideReferences = collectOutsideReferences()
                    val insideReferences = preprocessInsideReferences(usages)
                    preprocessOutsideReferencesToTraitMethods(conflicts)
                    preprocessInsideReferencesToTraitMethods(conflicts)

                    if (!isUnitTestMode) {
                        ProgressManager.getInstance().progressIndicator.text = message("detecting.possible.conflicts")
                    }
                    conflictsDetector = RsMoveConflictsDetector(conflicts, elementsToMove, sourceMod, targetMod)
                    conflictsDetector.detectOutsideReferencesVisibilityProblems(outsideReferences)
                    conflictsDetector.detectInsideReferencesVisibilityProblems(insideReferences)
                    conflictsDetector.checkImpls()
                }
            }
            return true
        } catch (e: ProcessCanceledException) {
            return false
        }
    }

    private fun collectOutsideReferences(): List<RsMoveReferenceInfo> {
        // we should collect:
        // * absolute references (starts with "::", "crate" or some crate name)
        // * references which starts with "super"
        // * references from old mod scope:
        //     - to items in old mod
        //     - to something which is imported in old mod

        val references = mutableListOf<RsMoveReferenceInfo>()
        for (path in movedElementsDeepDescendantsOfType<RsPath>(elementsToMove)) {
            if (path.parent is RsVisRestriction) continue
            if (path.containingMod != sourceMod  // path inside nested mod of moved element
                && !path.isAbsolute()
                && !path.startsWithSuper()
            ) continue
            if (!isSimplePath(path)) continue
            // TODO: support references to macros
            //  is is complicated: https://doc.rust-lang.org/reference/macros-by-example.html#scoping-exporting-and-importing
            //  also RsImportHelper currently does not work for macros: https://github.com/intellij-rust/intellij-rust/issues/4073
            val macroCall = path.parentOfType<RsMacroCall>()
            if (macroCall != null && macroCall.path.isAncestorOf(path)) continue

            // `use path1::{path2, path3}`
            //              ~~~~~  ~~~~~ todo don't ignore such paths
            if (path.parentOfType<RsUseGroup>() != null) continue

            val target = path.reference?.resolve() as? RsQualifiedNamedElement ?: continue
            // ignore relative references from child modules of moved file
            // because we handle them as inside references (in `preprocessInsideReferences`)
            if (target.isInsideMovedElements(elementsToMove)) continue

            val reference = createOutsideReferenceInfo(path, target) ?: continue
            path.putCopyableUserData(RS_PATH_WRAPPER_KEY, reference)
            references += reference
        }
        for (path in movedElementsShallowDescendantsOfType<RsPatIdent>(elementsToMove)) {
            val target = path.patBinding.reference.resolve() as? RsQualifiedNamedElement ?: continue
            if (target !is RsStructItem && target !is RsEnumVariant && target !is RsConstant) continue
            val reference = createOutsideReferenceInfo(path, target) ?: continue
            path.putCopyableUserData(RS_PATH_WRAPPER_KEY, reference)
            references += reference
        }
        return references
    }

    private fun createOutsideReferenceInfo(
        pathOriginal: RsElement,
        target: RsQualifiedNamedElement
    ): RsMoveReferenceInfo? {
        val path = convertFromPathOriginal(pathOriginal, codeFragmentFactory)

        // after move both `path` and its target will belong to `targetMod`
        // so we can refer to item in `targetMod` just with its name
        if (path.containingMod == sourceMod && target.containingMod == targetMod) {
            val pathNew = target.name?.toRsPath(psiFactory)
            if (pathNew != null) {
                return RsMoveReferenceInfo(path, pathOriginal, pathNew, pathNew, target, forceReplaceDirectly = true)
            }
        }

        if (path.isAbsolute()) {
            val pathNew = path.text.toRsPath(codeFragmentFactory, targetMod)
            if (pathNew.resolvesToAndAccessible(target)) return null  // not needed to change path
        }

        // todo ? extract function findOutsideReferencePathNew(..): Pair<RsPath, RsPath>
        val pathNewFallback = if (path.containingMod == sourceMod) {
            // after move `path` will belong to `targetMod`
            target.qualifiedNameRelativeTo(targetMod)?.toRsPath(codeFragmentFactory, targetMod)
        } else {
            target.qualifiedNameInCrate(path)?.toRsPathInEmptyTmpMod(codeFragmentFactory, targetMod)
        }
        val pathNewAccessible = RsImportHelper.findPath(targetMod, target)?.toRsPath(psiFactory)

        return RsMoveReferenceInfo(path, pathOriginal, pathNewAccessible, pathNewFallback, target)
    }

    private fun preprocessInsideReferences(usages: Array<UsageInfo>): List<RsMoveReferenceInfo> {
        val pathUsages = usages.filterIsInstance<RsPathUsage>()
        for (usage in pathUsages) {
            usage.referenceInfo = createInsideReferenceInfo(usage.element, usage.target)
        }

        val originalReferences = pathUsages.map { it.referenceInfo }
        for (usage in pathUsages) {
            usage.referenceInfo = convertToFullReference(usage.referenceInfo) ?: usage.referenceInfo

            val target = usage.referenceInfo.target
            target.putCopyableUserData(RS_TARGET_BEFORE_MOVE_KEY, target)

            val pathOldOriginal = usage.referenceInfo.pathOldOriginal
            if (pathOldOriginal.isInsideMovedElements(elementsToMove)) {
                // todo cleanup if refactoring is cancelled ?
                pathOldOriginal.putCopyableUserData(RS_PATH_BEFORE_MOVE_KEY, pathOldOriginal)
            }
        }
        return originalReferences
    }

    private fun createInsideReferenceInfo(pathOriginal: RsPath, target: RsQualifiedNamedElement): RsMoveReferenceInfo {
        val path = convertFromPathOriginal(pathOriginal, codeFragmentFactory)

        val isSelfReference = pathOriginal.isInsideMovedElements(elementsToMove)
        if (isSelfReference) {
            // after move path will be in `targetMod`
            // so we can refer to moved item just with its name
            check(target.containingMod == sourceMod)  // any inside reference is reference to moved item
            if (path.containingMod == sourceMod) {
                val pathNew = target.name?.toRsPath(codeFragmentFactory, targetMod)
                if (pathNew != null) return RsMoveReferenceInfo(path, pathOriginal, pathNew, pathNew, target)
            }
        }

        val pathNewAccessible = pathHelper.findPathAfterMove(path, target)
        val pathNewFallback = target.qualifiedNameRelativeTo(path.containingMod)
            ?.toRsPath(codeFragmentFactory, path)
        return RsMoveReferenceInfo(path, pathOriginal, pathNewAccessible, pathNewFallback, target)
    }

    // this method is needed in order to work with references to `RsItem`s, and not with references to `RsMod`s
    // it is needed when one of moved elements is `RsMod`
    private fun convertToFullReference(reference: RsMoveReferenceInfo): RsMoveReferenceInfo? {
        // Examples:
        // `mod1::mod2::mod3::Struct::<T>::func::<R>();`
        //  ^~~~~~~~~^ reference.pathOld
        //  ^~~~~~~~~~~~~~~~~~~~~~~~~~~~^ pathOldOriginal
        //  ^~~~~~~~~~~~~~~~~~~~~~~^ pathOld
        //
        // `use mod1::mod2::mod3;`
        //      ^~~~~~~~~^ reference.pathOld
        //      ^~~~~~~~~~~~~~~^ pathOldOriginal == pathOld

        if (isSimplePath(reference.pathOld) || reference.isInsideUseDirective) return null
        val pathOldOriginal = reference.pathOldOriginal.ancestors
            .takeWhile { it is RsPath }
            .map { it as RsPath }
            .firstOrNull { isSimplePath(it) }
            ?: return null
        val pathOld = convertFromPathOriginal(pathOldOriginal, codeFragmentFactory)
        if (!pathOld.text.startsWith(reference.pathOld.text)) return null  // todo log error

        check(pathOld.containingFile !is DummyHolder)
        val target = pathOld.reference?.resolve() as? RsQualifiedNamedElement ?: return null

        fun convertPathToFull(path: RsPath): RsPath? {
            val pathFullText = pathOld.text.replaceFirst(reference.pathOld.text, path.text)
            val pathFull = pathFullText.toRsPath(codeFragmentFactory, path) ?: return null
            check(pathFull.containingFile !is DummyHolder)
            return pathFull
        }

        val pathNewAccessible = reference.pathNewAccessible?.let { convertPathToFull(it) }
        val pathNewFallback = reference.pathNewFallback?.let { convertPathToFull(it) }

        return RsMoveReferenceInfo(pathOld, pathOldOriginal, pathNewAccessible, pathNewFallback, target)
    }

    // todo ? extract everything related to preprocess*TraitMethods to separate file
    private fun preprocessOutsideReferencesToTraitMethods(conflicts: MultiMap<PsiElement, String>) {
        val methodCalls = movedElementsShallowDescendantsOfType<RsMethodCall>(elementsToMove)
            .filter { it.containingMod == sourceMod }
        preprocessReferencesToTraitMethods(
            methodCalls,
            conflicts,
            { trait -> RsImportHelper.findPath(targetMod, trait) },
            { trait -> !trait.isInsideMovedElements(elementsToMove) }
        )
    }

    private fun preprocessInsideReferencesToTraitMethods(conflicts: MultiMap<PsiElement, String>) {
        val traitsToMove = elementsToMove
            .filterIsInstance<ItemToMove>()
            .map { it.item }
            .filterIsInstance<RsTraitItem>()
            .toSet()
        if (traitsToMove.isEmpty()) return
        val methodCalls = sourceMod.descendantsOfType<RsMethodCall>()
            .filter { !it.isInsideMovedElements(elementsToMove) }
        preprocessReferencesToTraitMethods(
            methodCalls,
            conflicts,
            { trait -> pathHelper.findPathAfterMove(sourceMod, trait)?.text },
            { trait -> trait in traitsToMove }
        )
    }

    private fun preprocessReferencesToTraitMethods(
        methodCalls: List<RsMethodCall>,
        conflicts: MultiMap<PsiElement, String>,
        findTraitUsePath: (RsTraitItem) -> String?,
        shouldProcessTrait: (RsTraitItem) -> Boolean
    ) {
        for (methodCall in methodCalls) {
            val method = methodCall.reference.resolve() as? RsAbstractable ?: continue
            val trait = method.getTrait() ?: continue
            if (!shouldProcessTrait(trait)) continue

            val traitUsePath = findTraitUsePath(trait)
            if (traitUsePath == null) {
                addVisibilityConflict(conflicts, methodCall, method.superItem ?: trait)
            } else {
                methodCall.putCopyableUserData(RS_METHOD_CALL_TRAIT_USE_PATH, Pair(trait, traitUsePath))
            }
        }
    }

    fun performRefactoring(usages: Array<out UsageInfo>, moveElements: () -> List<ElementToMove>) {
        // todo what to do with other usages?
        //  наверно нужно передать их в `super.performRefactoring`
        //  но не работает для common

        updateOutsideReferencesInVisRestrictions()

        this.elementsToMove = moveElements()

        updateOutsideReferences()

        addTraitImportsForOutsideReferences()
        addTraitImportsForInsideReferences()

        // todo filter self usages (especially when moving file with submodules)
        val insideReferences = usages
            .filterIsInstance<RsPathUsage>()
            .map { it.referenceInfo }
        updateInsideReferenceInfosIfNeeded(insideReferences)
        retargetReferences(insideReferences)
        optimizeImports()
    }

    private fun updateOutsideReferencesInVisRestrictions() {
        // todo update after move ?
        //  visRestriction.putCopyableUserData(_, visRestriction.path.reference?.resolve())
        for (visRestriction in movedElementsDeepDescendantsOfType<RsVisRestriction>(elementsToMove)) {
            visRestriction.updateScopeIfNecessary(psiFactory, targetMod)
        }
    }

    private fun addTraitImportsForOutsideReferences() =
        addTraitImportsForReferences(movedElementsShallowDescendantsOfType(elementsToMove))

    private fun addTraitImportsForInsideReferences() =
        addTraitImportsForReferences(sourceMod.descendantsOfType())

    private fun addTraitImportsForReferences(methodCalls: Collection<RsMethodCall>) {
        for (methodCall in methodCalls) {
            val (trait, traitUsePath) = methodCall.getCopyableUserData(RS_METHOD_CALL_TRAIT_USE_PATH) ?: continue
            // can't check `methodCall.reference.resolve() != null`, because it is always not null
            if (listOf(trait).filterInScope(methodCall).isNotEmpty()) continue
            addImport(methodCall, traitUsePath)
        }
    }

    private fun updateOutsideReferences() {
        val outsideReferences = movedElementsDeepDescendantsOfType<RsElement>(elementsToMove)
            .mapNotNull { pathOldOriginal ->
                val reference = pathOldOriginal.getCopyableUserData(RS_PATH_WRAPPER_KEY) ?: return@mapNotNull null
                pathOldOriginal.putCopyableUserData(RS_PATH_WRAPPER_KEY, null)
                // because after move new `RsElement`s are created
                // todo make ReferenceInfo data class and use copy ?
                //  or maybe not, зачем лишний раз аллоцировать
                reference.pathOldOriginal = pathOldOriginal
                reference.pathOld = convertFromPathOriginal(pathOldOriginal, codeFragmentFactory)
                reference
            }
        retargetReferences(outsideReferences.toList())
    }

    // todo comment
    private fun updateInsideReferenceInfosIfNeeded(references: List<RsMoveReferenceInfo>) {
        fun <T : RsElement> createMapping(key: Key<T>, aClass: Class<T>): Map<T, T> {
            return movedElementsShallowDescendantsOfType(elementsToMove, aClass)
                .mapNotNull { element ->
                    val elementOld = element.getCopyableUserData(key) ?: return@mapNotNull null
                    element.putCopyableUserData(key, null)
                    elementOld to element
                }
                .toMap()
        }

        val pathMapping = createMapping(RS_PATH_BEFORE_MOVE_KEY, RsElement::class.java)
        val targetMapping = createMapping(RS_TARGET_BEFORE_MOVE_KEY, RsQualifiedNamedElement::class.java)
        for (reference in references) {
            pathMapping[reference.pathOldOriginal]?.let { pathOldOriginal ->
                reference.pathOldOriginal = pathOldOriginal
                reference.pathOld = convertFromPathOriginal(pathOldOriginal, codeFragmentFactory)
            }
            reference.target = targetMapping[reference.target] ?: reference.target
        }
    }

    // todo extract to separate file/class all functions related to retargetReferences ?
    private fun retargetReferences(referencesAll: List<RsMoveReferenceInfo>) {
        val (referencesDirectly, referencesOther) = referencesAll
            .partition { it.isInsideUseDirective || it.forceReplaceDirectly }

        for (reference in referencesDirectly) {
            retargetReferenceDirectly(reference)
        }
        for (reference in referencesOther) {
            val pathOld = reference.pathOld
            if (pathOld.resolvesToAndAccessible(reference.target)) continue
            val success = !pathOld.isAbsolute() && tryRetargetReferenceKeepExistingStyle(reference)
            if (!success) {
                retargetReferenceDirectly(reference)
            }
        }
    }

    // todo inline ?
    private fun retargetReferenceDirectly(reference: RsMoveReferenceInfo) {
        val pathNew = reference.pathNew ?: return
        replacePathOld(reference, pathNew)
    }

    // "keep existing style" means that
    // - if `pathOld` is `foo` then we keep it and add import for `foo`
    // - if `pathOld` is `mod1::foo`, then we change it to `mod2::foo` and add import for `mod2`
    // - etc for `outer1::mod1::foo`
    private fun tryRetargetReferenceKeepExistingStyle(reference: RsMoveReferenceInfo): Boolean {
        val pathOld = reference.pathOld
        val pathNew = reference.pathNew ?: return false

        val pathOldSegments = pathOld.text.split("::")
        val pathNewSegments = pathNew.text.split("::")
        if (pathOldSegments.size >= pathNewSegments.size && !pathOld.startsWithSuper()) return false

        val pathNewShortNumberSegments = adjustPathNewNumberSegments(reference, pathOldSegments.size)
        return doRetargetReferenceKeepExistingStyle(reference, pathNewSegments, pathNewShortNumberSegments)
    }

    private fun doRetargetReferenceKeepExistingStyle(
        reference: RsMoveReferenceInfo,
        pathNewSegments: List<String>,
        pathNewShortNumberSegments: Int
    ): Boolean {
        val pathNewShortText = pathNewSegments
            .takeLast(pathNewShortNumberSegments)
            .joinToString("::")
        val usePath = pathNewSegments
            .take(pathNewSegments.size - pathNewShortNumberSegments + 1)
            .joinToString("::")

        val containingMod = reference.pathOldOriginal.containingMod
        val pathNewShort = pathNewShortText.toRsPath(codeFragmentFactory, containingMod)
            ?: return false  // todo log error
        val containingModHasSameNameInScope = pathNewShortNumberSegments == 1
            && pathNewShort.reference?.resolve().let { it != null && it != reference.target }
        if (containingModHasSameNameInScope) {
            return doRetargetReferenceKeepExistingStyle(
                reference,
                pathNewSegments,
                pathNewShortNumberSegments + 1
            )
        }

        addImport(reference.pathOldOriginal, usePath)
        replacePathOld(reference, pathNewShort)
        return true
    }

    // if `target` is struct/enum/... then we add import for this item
    // if `target` is function then we add import for its `containingMod`
    // https://doc.rust-lang.org/book/ch07-04-bringing-paths-into-scope-with-the-use-keyword.html#creating-idiomatic-use-paths
    private fun adjustPathNewNumberSegments(reference: RsMoveReferenceInfo, numberSegments: Int): Int {
        val pathOldOriginal = reference.pathOldOriginal
        val target = reference.target

        // it is unclear how to replace relative reference starting with `super::` to keep its style
        // so lets always add full import for such references
        if (reference.pathOld.startsWithSuper()) {
            return if (target is RsFunction) 2 else 1
        }

        if (numberSegments != 1 || target !is RsFunction) return numberSegments
        val isReferenceBetweenElementsInSourceMod =
            // from item in source mod to moved item
            pathOldOriginal.containingMod == sourceMod && target.containingMod == targetMod
                // from moved item to item in source mod
                || pathOldOriginal.containingMod == targetMod && target.containingMod == sourceMod
        return if (isReferenceBetweenElementsInSourceMod) 2 else numberSegments
    }

    private fun replacePathOld(reference: RsMoveReferenceInfo, pathNew: RsPath) {
        val pathOld = reference.pathOld
        val pathOldOriginal = reference.pathOldOriginal

        if (pathOldOriginal !is RsPath) {
            replacePathOldInPatIdent(pathOldOriginal as RsPatIdent, pathNew)
            return
        }

        if (tryReplacePathOldInUseGroup(pathOldOriginal, pathNew)) return

        if (pathOld.text == pathNew.text) return
        if (pathOld != pathOldOriginal) run {
            if (!pathOldOriginal.text.startsWith(pathOld.text)) return@run  // todo log error
            if (replacePathOldWithTypeArguments(pathOldOriginal, pathNew.text)) return
        }

        // when moving `fn foo() { use crate::mod2::bar; ... }` to `mod2`, we can just delete this import
        if (pathOld.parent is RsUseSpeck && pathOld.parent.parent is RsUseItem && !pathNew.hasColonColon) {
            pathOld.parent.parent.delete()
            return
        }
        pathOldOriginal.replace(pathNew)
    }

    private fun replacePathOldInPatIdent(pathOldOriginal: RsPatIdent, pathNew: RsPath) {
        if (pathNew.coloncolon != null) return  // todo log error
        val patBindingNew = psiFactory.createIdentifier(pathNew.text)
        pathOldOriginal.patBinding.identifier.replace(patBindingNew)
    }

    private fun replacePathOldWithTypeArguments(pathOldOriginal: RsPath, pathNewText: String): Boolean {
        // `mod1::func::<T>`
        //  ^~~~~~~~~^ pathOld - replace by pathNewText
        //  ^~~~~~~~~~~~~~^ pathOldOriginal
        // we should accurately replace `pathOld`, so that all `PsiElement`s related to `T` remain valid
        // because T can contains `RsPath`s which also should be replaced later

        fun RsPath.getIdentifierActual(): PsiElement = listOfNotNull(identifier, self, `super`, cself, crate).single()

        with(pathOldOriginal) {
            check(typeArgumentList != null)
            path?.delete()
            coloncolon?.delete()
            getIdentifierActual().delete()
            check(typeArgumentList != null)
        }

        val pathNew = pathNewText.toRsPath(psiFactory) ?: return false
        val elements = listOfNotNull(pathNew.path, pathNew.coloncolon, pathNew.getIdentifierActual())
        for (element in elements.asReversed()) {
            pathOldOriginal.addAfter(element, null)
        }
        check(pathOldOriginal.text.startsWith(pathNewText))
        return true
    }

    // consider we move `foo1` and `foo2` from `mod1` to `mod2`
    // this methods replaces `use mod1::{foo1, foo2}` to `use mod2::{foo1, foo2}`
    //
    // todo improve for complex use groups, e.g.:
    //  `use mod1::{foo1, foo2::{inner1, inner2}}` (currently we create two imports)
    private fun tryReplacePathOldInUseGroup(pathOld: RsPath, pathNew: RsPath): Boolean {
        val useSpeck = pathOld.parentOfType<RsUseSpeck>() ?: return false
        val useGroup = useSpeck.parent as? RsUseGroup ?: return false
        val useItem = useGroup.parentOfType<RsUseItem>() ?: return false

        // Before:
        //   `use prefix::{mod1::foo::inner::{inner1, inner2}, other1, other2};`
        //                ^~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~^ useGroup
        //                 ^~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~^ useSpeck
        //                 ^~~~~~~~^ pathOld
        // After:
        //   `use prefix::{other1, other2};`
        //   `use prefix::mod1::foo::inner::{inner1, inner2};`
        pathOld.replace(pathNew)
        val useSpeckText = useSpeck.text
        // deletion should be before `insertUseItem`, otherwise `useSpeck` may be invalidated
        deleteUseSpeckInUseGroup(useSpeck)
        if (useSpeckText.contains("::")) {
            insertUseItemAndCopyAttributes(useSpeckText, useItem)
        }

        // todo group paths by RsUseItem and handle together ?
        // consider pathOld == `foo1` in `use mod1::{foo1, foo2};`
        // we just removed `foo1` from old use group and added new import: `use mod1::{foo2}; use mod2::foo1;`
        // we can't optimize use speck right now,
        // because otherwise `use mod1::{foo2};` becomes `use mod1::foo2;` which destroys`foo2` reference
        useSpecksToOptimize += useGroup.parentUseSpeck
        return true
    }

    private fun insertUseItemAndCopyAttributes(useSpeckText: String, existingUseItem: RsUseItem) {
        val containingMod = existingUseItem.containingMod
        if (existingUseItem.outerAttrList.isEmpty() && existingUseItem.vis == null) {
            containingMod.insertUseItem(psiFactory, useSpeckText)
        } else {
            val useItem = existingUseItem.copy() as RsUseItem
            val useSpeck = psiFactory.createUseSpeck(useSpeckText)
            useItem.useSpeck!!.replace(useSpeck)
            containingMod.addAfter(useItem, existingUseItem)
        }
        filesToOptimizeImports.add(containingMod.containingFile as RsFile)
    }

    fun addImport(context: RsElement, usePath: String) {
        addImport(psiFactory, context, usePath)
        filesToOptimizeImports.add(context.containingFile as RsFile)
    }

    private fun optimizeImports() {
        useSpecksToOptimize.forEach { optimizeUseSpeck(it) }

        filesToOptimizeImports.addAll(useSpecksToOptimize.mapNotNull { it.containingFile as? RsFile })
        filesToOptimizeImports.forEach { RsImportOptimizer().executeForUseItem(it) }
    }

    private fun optimizeUseSpeck(useSpeck: RsUseSpeck) {
        RsImportOptimizer.optimizeUseSpeck(psiFactory, useSpeck)

        // todo move to RsImportOptimizer
        val useSpeckList = useSpeck.useGroup?.useSpeckList ?: return
        if (useSpeckList.isEmpty()) {
            when (val parent = useSpeck.parent) {
                is RsUseItem -> parent.delete()
                is RsUseGroup -> deleteUseSpeckInUseGroup(useSpeck)
                else -> error("todo")
            }
        } else {
            useSpeckList.forEach { optimizeUseSpeck(it) }
        }
    }

    private fun deleteUseSpeckInUseGroup(useSpeck: RsUseSpeck) {
        check(useSpeck.parent is RsUseGroup)
        val nextComma = useSpeck.nextSibling.takeIf { it.elementType == RsElementTypes.COMMA }
        val prevComma = useSpeck.prevSibling.takeIf { it.elementType == RsElementTypes.COMMA }
        (nextComma ?: prevComma)?.delete()
        useSpeck.delete()
    }

    fun updateMovedItemVisibility(item: RsItemElement, itemToCheckMakePublic /* todo remove? */: RsElement) {
        when (item.visibility) {
            is RsVisibility.Private -> {
                if (conflictsDetector.itemsToMakePublic.contains(itemToCheckMakePublic)) {
                    val itemName = item.name
                    val containingFile = item.containingFile
                    if (item !is RsNameIdentifierOwner) return  // todo log error
                    MakePublicFix(item, itemName, withinOneCrate = false)
                        .invoke(project, null, containingFile)
                }
            }
            is RsVisibility.Restricted -> run {
                val visRestriction = item.vis?.visRestriction ?: return@run
                visRestriction.updateScopeIfNecessary(psiFactory, targetMod)
            }
            RsVisibility.Public -> {
                // already public, keep as is
            }
        }
    }

    companion object {
        private val RS_PATH_WRAPPER_KEY: Key<RsMoveReferenceInfo> = Key.create("RS_PATH_WRAPPER_KEY")
        private val RS_PATH_BEFORE_MOVE_KEY: Key<RsElement> = Key.create("RS_PATH_BEFORE_MOVE_KEY")
        private val RS_TARGET_BEFORE_MOVE_KEY: Key<RsQualifiedNamedElement> = Key.create("RS_TARGET_BEFORE_MOVE_KEY")
        private val RS_METHOD_CALL_TRAIT_USE_PATH: Key<Pair<RsTraitItem, String>> = Key.create("RS_METHOD_CALL_TRAIT_USE_PATH")
    }
}
