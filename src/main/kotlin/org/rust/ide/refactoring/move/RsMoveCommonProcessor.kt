/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring.move

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.DummyHolder
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import com.intellij.refactoring.RefactoringBundle.message
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import org.rust.ide.annotator.fixes.MakePublicFix
import org.rust.ide.inspections.import.RsImportHelper
import org.rust.ide.inspections.import.insertUseItem
import org.rust.ide.refactoring.RsImportOptimizer
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*

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

data class MoveElementsResult(
    val elementsNew: List<ElementToMove>,
    // todo remove
    val oldToNewMap: Map<RsElement, RsElement>
)

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
    private val conflictsDetector: RsMoveConflictsDetector = RsMoveConflictsDetector(elementsToMove, targetMod)

    private val useSpecksToOptimize: MutableList<RsUseSpeck> = mutableListOf()

    fun convertToMoveUsages(usages: Array<UsageInfo>): Array<UsageInfo> {
        return usages
            .mapNotNull { usage ->
                val element = usage.element
                val reference = usage.reference
                val target = reference?.resolve()
                // todo text usages
                if (element == null || reference == null || target == null) return@mapNotNull null

                when {
                    element is RsModDeclItem -> RsModDeclUsage(element, target as RsFile)
                    element is RsPath && target is RsQualifiedNamedElement -> RsPathUsage(element, reference, target)
                    else -> usage
                }
            }
            .toTypedArray()
    }

    fun preprocessUsages(usages: Array<UsageInfo>, conflicts: MultiMap<PsiElement, String>): Boolean {
        // todo maybe add usages which needs adding element to list
        //  then insert new element without progress at all, and again search for usages
        return ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                runReadAction {
                    // todo two threads, one for inside, one for outside ?
                    //  also parallelize `detectVisibilityProblems`
                    val outsideReferences = collectOutsideReferences()
                    val insideReferences = preprocessInsideReferences(usages)

                    ProgressManager.getInstance().progressIndicator.text = message("detecting.possible.conflicts")
                    conflictsDetector.detectOutsideReferencesVisibilityProblems(conflicts, outsideReferences)
                    conflictsDetector.detectInsideReferencesVisibilityProblems(conflicts, insideReferences)

                    preprocessOutsideReferencesToTraitMethods(conflicts)
                    preprocessInsideReferencesToTraitMethods(conflicts)
                }
            },
            message("refactoring.preprocess.usages.progress"),
            true,
            project
        )
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
        }
        return originalReferences
    }

    private fun createInsideReferenceInfo(pathOriginal: RsPath, target: RsQualifiedNamedElement): RsMoveReferenceInfo {
        val path = pathOriginal.removeTypeArguments(codeFragmentFactory)

        val isSelfReference = pathOriginal.isInsideMovedElements(elementsToMove)
        if (isSelfReference) {
            // todo cleanup if refactoring is cancelled ?
            pathOriginal.putCopyableUserData(RS_PATH_BEFORE_MOVE_KEY, pathOriginal)
            // after move path will be in `targetMod`
            // so we can refer to moved item just with its name
            // todo ещё же надо проверять что target.containingMod == targetMod ?
            if (path.containingMod == sourceMod) {
                val pathNew = target.name?.toRsPath(codeFragmentFactory, targetMod)
                if (pathNew != null) return RsMoveReferenceInfo(path, pathOriginal, pathNew, pathNew, target)
            }
        }

        val pathNewAccessible = pathHelper.findPathAfterMove(path, target)
        val pathNewFallback = target.qualifiedNameRelativeTo(path.containingMod)
            ?.toRsPath(codeFragmentFactory, path)
        // reference from items in old mod to moved item
        val forceAddImport = !path.hasColonColon
            && target.containingMod == sourceMod
            && path.containingMod == sourceMod
        return RsMoveReferenceInfo(path, pathOriginal, pathNewAccessible, pathNewFallback, target, forceAddImport)
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
        val pathOld = pathOldOriginal.removeTypeArguments(codeFragmentFactory)
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

        return RsMoveReferenceInfo(
            pathOld,
            pathOldOriginal,
            pathNewAccessible,
            pathNewFallback,
            target,
            reference.forceAddImport
        )
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
        return references
    }

    private fun createOutsideReferenceInfo(
        pathOriginal: RsPath,
        target: RsQualifiedNamedElement
    ): RsMoveReferenceInfo? {
        val path = pathOriginal.removeTypeArguments(codeFragmentFactory)

        // after move both `path` and its target will belong to `targetMod`
        // so we can refer to item in `targetMod` just with its name
        if (path.containingMod == sourceMod && target.containingMod == targetMod) {
            val pathNew = target.name?.toRsPath(psiFactory)
            if (pathNew != null) {
                return RsMoveReferenceInfo(path, pathOriginal, pathNew, pathNew, target, forceReplaceDirectly = true)
            }
        }

        if (path.isAbsolute()) {
            val pathNew = codeFragmentFactory.createPath(path.text, targetMod)
            if (pathNew.resolvesToAndAccessible(target)) return null  // not needed to change path
        }

        // todo ? extract function findOutsideReferencePathNew(..): Pair<RsPath, RsPath>
        val pathNewFallbackText = target.qualifiedNameInCrate(path)
        val pathNewFallback = if (path.containingMod == sourceMod) {
            // after move `path` will belong to `targetMod`
            pathNewFallbackText?.toRsPath(codeFragmentFactory, targetMod)
        } else {
            // todo use `codeFragmentFactory.createPathInTmpMod`?
            pathNewFallbackText?.toRsPath(psiFactory)
        }
        // todo проверка resolvesToAndAccessible не работает, так как pathNewFallback внутри dummy file
        val pathNewAccessible = if (pathNewFallback.resolvesToAndAccessible(target)) {
            pathNewFallback
        } else {
            RsImportHelper.findPath(targetMod, target)?.toRsPath(psiFactory)
        }

        val isReferenceFromMovedItemToItemInOldMod = !path.hasColonColon
            && target.containingMod == sourceMod
            && path.containingMod == sourceMod
        val forceAddImport = isReferenceFromMovedItemToItemInOldMod || path.isInsideMetaItem(target)
        return RsMoveReferenceInfo(path, pathOriginal, pathNewAccessible, pathNewFallback, target, forceAddImport)
    }

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

    fun performRefactoring(usages: Array<out UsageInfo>, moveElements: () -> MoveElementsResult) {
        // todo what to do with other usages?
        //  наверно нужно передать их в `super.performRefactoring`
        //  но не работает для common

        updateOutsideReferencesInVisRestrictions()

        val (elementsToMove, _) = moveElements()
        this.elementsToMove = elementsToMove

        updateOutsideReferences()

        addTraitImportsForOutsideReferences()
        addTraitImportsForInsideReferences()

        // todo filter self usages (especially when moving file with submodules)
        val insideReferences = usages
            .filterIsInstance<RsPathUsage>()
            .map { it.referenceInfo }
        updateInsideReferenceInfosIfNeeded(insideReferences)
        retargetReferences(insideReferences)
        useSpecksToOptimize.forEach { optimizeUseSpeck(it) }
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
            addImport(psiFactory, methodCall, traitUsePath)
        }
    }

    private fun updateOutsideReferences() {
        val outsideReferences = movedElementsDeepDescendantsOfType<RsPath>(elementsToMove)
            .mapNotNull { pathOldOriginal ->
                val reference = pathOldOriginal.getCopyableUserData(RS_PATH_WRAPPER_KEY) ?: return@mapNotNull null
                pathOldOriginal.putCopyableUserData(RS_PATH_WRAPPER_KEY, null)
                // because after move new `RsElement`s are created
                reference.pathOldOriginal = pathOldOriginal
                reference.pathOld = pathOldOriginal.removeTypeArguments(codeFragmentFactory)
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

        val pathMapping = createMapping(RS_PATH_BEFORE_MOVE_KEY, RsPath::class.java)
        val targetMapping = createMapping(RS_TARGET_BEFORE_MOVE_KEY, RsQualifiedNamedElement::class.java)
        for (reference in references) {
            pathMapping[reference.pathOldOriginal]?.let { pathOldOriginal ->
                reference.pathOldOriginal = pathOldOriginal
                reference.pathOld = pathOldOriginal.removeTypeArguments(codeFragmentFactory)
            }
            reference.target = targetMapping[reference.target] ?: reference.target
        }
    }

    // todo extract to separate file/class all functions related to retargetReferences ?
    private fun retargetReferences(referencesAll: List<RsMoveReferenceInfo>) {
        val references = referencesAll.filter {
            if (it.isInsideUseDirective || it.forceReplaceDirectly) {
                retargetReferenceDirectly(it)
                false
            } else {
                true
            }
        }

        val referencesByMod = references
            .filterNot { it.pathOld.resolvesToAndAccessible(it.target) }
            .groupBy { it.pathOld.containingMod }
        for ((mod, referencesFromMod) in referencesByMod) {
            retargetReferencesFromMod(mod, referencesFromMod)
        }
    }

    private fun retargetReferencesFromMod(mod: RsMod, references: List<RsMoveReferenceInfo>) {
        val referencesRemaining = references.filterNot {
            tryRetargetIdiomaticReference(it)
        }
        retargetNonIdiomaticReferences(mod, referencesRemaining)
    }

    private fun tryRetargetIdiomaticReference(reference: RsMoveReferenceInfo): Boolean {
        val pathOld = reference.pathOld
        val pathNew = reference.pathNew ?: return false
        check(pathOld.parentOfType<RsUseItem>() == null)  // todo remove ?
        if (!pathNew.hasColonColon) {
            replacePathOld(reference, pathNew)
            return true
        }

        val target = reference.target
        check(target !is RsMod)  // because of `convertToFullReference`
        if (!reference.forceAddImport && !pathOld.isIdiomatic(target)) return false
        return when (target) {
            // handled by `convertToFullReference`
            is RsMod -> false
            is RsFunction -> replacePathWithIdiomaticImport(reference, pathNew, target)
            // structs/enums/...
            else -> {
                // todo duplicate code
                // todo `replacePathOld(..., addImport = false/true)` ?
                val targetName = target.name ?: return false
                addImport(psiFactory, pathOld, pathNew.text)

                val pathNewShort = targetName.toRsPath(psiFactory)!!
                replacePathOld(reference, pathNewShort)
                return true
            }
        }
    }

    private fun replacePathWithIdiomaticImport(
        reference: RsMoveReferenceInfo,
        pathNew: RsPath,
        target: RsFunction
    ): Boolean {
        val targetName = target.name ?: return false
        check(pathNew.text.endsWith("::$targetName"))
        val pathImport = pathNew.text.removeSuffix("::$targetName")
        addImport(psiFactory, reference.pathOld, pathImport)

        val pathNewShort = pathNew.text
            .split("::")
            .takeLast(2)
            .joinToString("::")
            .toRsPath(psiFactory)!!
        replacePathOld(reference, pathNewShort)
        return true
    }

    private fun retargetReferencesDirectly(references: List<RsMoveReferenceInfo>) {
        for (reference in references) {
            retargetReferenceDirectly(reference)
        }
    }

    // todo inline ?
    private fun retargetReferenceDirectly(reference: RsMoveReferenceInfo) {
        val pathOld = reference.pathOld
        val pathNew = reference.pathNew ?: return
        // todo more smart imports handling
        if (pathOld.parent is RsUseSpeck && pathOld.parent.parent is RsUseItem && !pathNew.hasColonColon) {
            pathOld.parent.parent.delete()
            return
        }

        replacePathOld(reference, pathNew)
    }

    // few references with same path in file => replace path with absolute
    // todo (может не надо, это всё-таки не idiomatic references?)
    // many references => add new idiomatic import
    @Suppress("UNUSED_PARAMETER")  // todo
    private fun retargetNonIdiomaticReferences(mod: RsMod, references: List<RsMoveReferenceInfo>) {
        fun retargetReferencesGroup(references: List<RsMoveReferenceInfo>, target: RsQualifiedNamedElement): Boolean {
            if (references.size <= NUMBER_USAGES_THRESHOLD_FOR_ADDING_IMPORT) return false
            val targetName = (target as? RsMod)?.modName ?: target.name ?: return false

            val context = PsiTreeUtil.findCommonParent(references.map { it.pathOld }) as RsElement
            val usePathFull = references.first().pathNew?.text ?: return false
            // todo just log error
            check(usePathFull.endsWith("::$targetName"))

            val (usePath, pathNewText) = if (target is RsFunction) {
                // todo reuse code from `replacePathWithIdiomaticImport`
                // todo use `RsPath::qualifier` instead ?
                val usePathSegments = usePathFull.split("::")
                val usePath = usePathSegments.dropLast(1).joinToString("::")
                val pathNew = usePathSegments.takeLast(2).joinToString("::")
                usePath to pathNew
            } else {
                usePathFull to targetName
            }

            addImport(psiFactory, context, usePath)
            val pathNew = psiFactory.tryCreatePath(pathNewText)!!
            for (reference in references) {
                replacePathOld(reference, pathNew)
            }
            return true
        }

        val referencesGrouped = references.groupBy { it.target }
        val referencesRemaining = referencesGrouped
            .filterNot { retargetReferencesGroup(it.value, it.key) }
            .values.flatten()

        retargetReferencesDirectly(referencesRemaining)
    }

    private fun replacePathOld(reference: RsMoveReferenceInfo, pathNew: RsPath) {
        val pathOld = reference.pathOld
        val pathOldOriginal = reference.pathOldOriginal
        if (pathOld != pathOldOriginal) run {
            // `mod1::func::<T>`
            //  ^~~~~~~~~^ pathOld
            //  ^~~~~~~~~~~~~~^ pathOldOriginal
            if (!pathOldOriginal.text.startsWith(pathOld.text)) return@run  // todo log error
            val pathNewOriginalText = pathOldOriginal.text.replaceFirst(pathOld.text, pathNew.text)
            val pathNewOriginal = pathNewOriginalText.toRsPath(psiFactory) ?: return@run
            pathOldOriginal.replace(pathNewOriginal)
            return
        }

        if (tryReplacePathOldInUseGroup(pathOldOriginal, pathNew)) return

        if (pathOldOriginal.text == pathNew.text) return
        pathOldOriginal.replace(pathNew)
    }

    // consider we move `foo1` and `foo2` from `mod1` to `mod2`
    // this methods replaces `use mod1::{foo1, foo2}` to `use mod2::{foo1, foo2}`
    //
    // todo improve for complex use groups, e.g.:
    //  `use mod1::{foo1, foo2::{inner1, inner2}}` (currently we create two imports)
    private fun tryReplacePathOldInUseGroup(pathOld: RsPath, pathNew: RsPath): Boolean {
        val useSpeck = pathOld.parentOfType<RsUseSpeck>() ?: return false
        val useGroup = useSpeck.parent as? RsUseGroup ?: return false

        pathOld.replace(pathNew)
        useSpeck.containingMod.insertUseItem(psiFactory, useSpeck.text)

        deleteUseSpeckInUseGroup(useSpeck)
        // todo group paths by RsUseItem and handle together ?
        useSpecksToOptimize += useGroup.parentUseSpeck
        return true
    }

    private fun optimizeUseSpeck(useSpeck: RsUseSpeck) {
        RsImportOptimizer.optimizeUseSpeck(psiFactory, useSpeck)

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

    fun updateMovedItemVisibility(item: RsItemElement, itemToCheckMakePublic: RsElement) {
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
        // todo change to 1 ?
        private const val NUMBER_USAGES_THRESHOLD_FOR_ADDING_IMPORT: Int = 2
        private val RS_PATH_WRAPPER_KEY: Key<RsMoveReferenceInfo> = Key.create("RS_PATH_WRAPPER_KEY")
        private val RS_PATH_BEFORE_MOVE_KEY: Key<RsPath> = Key.create("RS_PATH_BEFORE_MOVE_KEY")
        private val RS_TARGET_BEFORE_MOVE_KEY: Key<RsQualifiedNamedElement> = Key.create("RS_TARGET_BEFORE_MOVE_KEY")
        private val RS_METHOD_CALL_TRAIT_USE_PATH: Key<Pair<RsTraitItem, String>> = Key.create("RS_METHOD_CALL_TRAIT_USE_PATH")
    }
}
