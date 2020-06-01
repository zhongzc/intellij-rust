/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring.move

import com.intellij.openapi.project.Project
import org.rust.ide.inspections.import.RsImportHelper
import org.rust.lang.core.psi.RsCodeFragmentFactory
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.RsPath
import org.rust.lang.core.psi.ext.*

/**
 * Consider we move item with name `foo` from `mod1` to `mod2`
 * before move we should check visibility conflicts (whether we can update all references to moved item after move)
 * so for each reference (RsPath) we should find replacement (new RsPath)
 * this helper checks possible new paths in the following order:
 * 1. find any public item in `mod2`, find path to it using RsImportHelper, and replace path last segment
 * 2. find path to `mod2` using RsImportHelper, and add last segment (with moved item name)
 *
 * It will not work if there is glob reexport for items in `mod2` and `mod2` has no public items
 * But it is somewhat strange case (why someone would reexport everything from `mod2` if there are no pub items?)
 * This can be fixed by adding new item to `mod2` in such cases
 * (Though it is unclear how to use writeAction only for small amount of time and not for all preprocess usages stage)
 */
class RsMovePathHelper(project: Project, private val mod: RsMod) {

    private val codeFragmentFactory: RsCodeFragmentFactory = RsCodeFragmentFactory(project)
    private val existingPublicItem: RsQualifiedNamedElement? =
        mod.childrenOfType<RsQualifiedNamedElement>()
            .firstOrNull { it is RsVisibilityOwner && it.visibility == RsVisibility.Public && it.name != null }

    fun findPathAfterMove(context: RsElement, element: RsQualifiedNamedElement): RsPath? {
        val elementName = (element as? RsFile)?.modName ?: element.name ?: return null
        if (context.containingMod == mod) return codeFragmentFactory.createPath(elementName, context)

        return findPathAfterMoveUsingOtherItemInMod(context, element)
            ?: findPathAfterMoveUsingMod(context, element)
    }

    private fun findPathAfterMoveUsingOtherItemInMod(context: RsElement, element: RsQualifiedNamedElement): RsPath? {
        val elementName = (element as? RsFile)?.modName ?: element.name ?: return null
        val secondaryElement = existingPublicItem ?: return null
        val secondaryElementName = secondaryElement.name ?: return null
        val secondaryPathText = findPath(context, secondaryElement)
            ?: return null
        val secondaryPath = codeFragmentFactory.createPath(secondaryPathText, context) ?: return null
        if (secondaryPath.reference!!.resolve() != secondaryElement) return null

        if (!secondaryPathText.endsWith("::$secondaryElementName")) return null
        val pathText = secondaryPathText.removeSuffix(secondaryElementName) + elementName
        return codeFragmentFactory.createPath(pathText, context)
    }

    private fun findPathAfterMoveUsingMod(context: RsElement, element: RsQualifiedNamedElement): RsPath? {
        val elementName = (element as? RsFile)?.modName ?: element.name ?: return null
        val modPath = findPath(context, mod) ?: return null
        val elementPath = "$modPath::$elementName"
        return codeFragmentFactory.createPath(elementPath, context)
    }

    // basically it returns `element.crateRelativePath`
    // but if `element.crateRelativePath`, is inaccessible in context (e.g. because of reexports),
    // then tries to find other path using RsImportHelper
    private fun findPath(context: RsElement, element: RsQualifiedNamedElement): String? {
        val pathSimple = findPathSimple(context, element)
        if (pathSimple != null) return pathSimple

        val path = RsImportHelper.findPath(context, element) ?: return null
        return convertPathToRelativeIfPossible(context.containingMod, path)
    }

    // returns `element.crateRelativePath` if it is accessible from `context`
    private fun findPathSimple(context: RsElement, element: RsQualifiedNamedElement): String? {
        val contextMod = context.containingMod
        val path = element.qualifiedNameRelativeTo(contextMod)
            ?.toRsPath(codeFragmentFactory, context)
            ?: return null
        return if (path.resolvesToAndAccessible(element)) path.text else null
    }
}
