/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.toml.intentions

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiParserFacade
import com.intellij.psi.util.parentOfType
import org.rust.cargo.CargoConstants
import org.rust.lang.core.psi.ext.endOffset
import org.rust.toml.isDependencyListHeader
import org.toml.lang.psi.TomlInlineTable
import org.toml.lang.psi.TomlKeyValue
import org.toml.lang.psi.TomlPsiFactory
import org.toml.lang.psi.TomlTable

class ExtractDependencySpecificationIntention : TomlElementBaseIntentionAction<TomlKeyValue>() {
    override fun getText() = "Extract dependency in separate table"
    override fun getFamilyName(): String = text

    override fun findApplicableContextInternal(project: Project, editor: Editor, element: PsiElement): TomlKeyValue? {
        if (element.containingFile.name != CargoConstants.MANIFEST_FILE) return null

        var keyValue = element.parentOfType<TomlKeyValue>() ?: return null

        // cursor can be either inside dependency property itself or in the inline table value describing it
        val parent = keyValue.parent

        val dependencyTable = when (parent) {
            is TomlTable -> parent
            is TomlInlineTable -> parent.parentOfType()
            else -> null
        } ?: return null

        if (!dependencyTable.header.isDependencyListHeader) return null

        if (parent != dependencyTable) {
            keyValue = parent.parent as TomlKeyValue
        }

        if (keyValue.value !is TomlInlineTable) return null

        return keyValue
    }

    override fun invoke(project: Project, editor: Editor, ctx: TomlKeyValue) {
        val inlineTable = ctx.value as? TomlInlineTable ?: return
        val table = ctx.parent as? TomlTable ?: return
        val dependencyTableName = table.header.key?.text.orEmpty()
        val crateName = ctx.key.text
        val newTable = TomlPsiFactory(project).createTable("$dependencyTableName.$crateName")

        val psiParserFacade = PsiParserFacade.SERVICE.getInstance(project)
        for (keyValue in inlineTable.entries) {
            newTable.add(psiParserFacade.createWhiteSpaceFromText("\n"))
            newTable.add(keyValue)
        }
        newTable.add(psiParserFacade.createWhiteSpaceFromText("\n"))

        ctx.delete()
        table.add(psiParserFacade.createWhiteSpaceFromText("\n\n"))

        val newTableOffset = table.addAfter(newTable, psiParserFacade.createWhiteSpaceFromText("\n")).endOffset
        editor.caretModel.moveToOffset(newTableOffset)
    }
}
