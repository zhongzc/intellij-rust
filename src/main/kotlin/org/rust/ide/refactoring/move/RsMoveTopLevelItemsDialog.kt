/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring.move

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.refactoring.RefactoringBundle.message
import com.intellij.refactoring.ui.RefactoringDialog
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.ui.components.JBTextField
import com.intellij.ui.layout.panel
import org.apache.commons.lang.StringEscapeUtils
import org.rust.ide.docs.signature
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.RsItemElement
import org.rust.lang.core.psi.ext.RsMod
import org.rust.openapiext.pathToRsFileTextField
import org.rust.openapiext.toPsiFile
import org.rust.stdext.mapToSet
import java.awt.Dimension
import java.io.File
import javax.swing.Icon
import javax.swing.JComponent

class RsMoveTopLevelItemsDialog(
    project: Project,
    private val itemsToMove: Set<RsItemElement>,
    private val sourceMod: RsMod
) : RefactoringDialog(project, false) {

    private val sourceFilePath: String = sourceMod.containingFile.virtualFile.path
    private val sourceFileField: JBTextField = JBTextField(sourceFilePath).apply { isEnabled = false }
    private val targetFileChooser: TextFieldWithBrowseButton = createTargetFileChooser(project)
    private val memberPanel: RsMoveMemberSelectionPanel = createMemberSelectionPanel()

    private var searchForReferences: Boolean = true

    init {
        super.init()
        title = "Move Module Items"
        validateButtons()
    }

    private fun createTargetFileChooser(project: Project): TextFieldWithBrowseButton {
        return pathToRsFileTextField(disposable, "Choose Destination File", project, this::validateButtons)
            .also {
                it.text = sourceFilePath
                it.textField.caretPosition = sourceFilePath.removeSuffix(".rs").length
                it.textField.moveCaretPosition(sourceFilePath.lastIndexOf('/') + 1)
            }
    }

    private fun createMemberSelectionPanel(): RsMoveMemberSelectionPanel {
        val topLevelItems = getTopLevelItems()

        val nodesGroupedWithImpls = groupImplsByStructOrTrait(sourceMod, topLevelItems)
            .map { RsMoveItemAndImplsInfo(it.key, it.value) }
        val itemsGroupedWithImpls = nodesGroupedWithImpls.flatMap { it.children }.map { it.member }

        val nodesWithoutGrouping = topLevelItems.subtract(itemsGroupedWithImpls).map { RsMoveMemberInfo(it) }
        val nodesAll = nodesGroupedWithImpls + nodesWithoutGrouping

        val nodesSelected = nodesAll
            .flatMap {
                when (it) {
                    is RsMoveItemAndImplsInfo -> it.children
                    is RsMoveMemberInfo -> listOf(it)
                    else -> error("unexpected node info type: $it")
                }
            }
            .filter { it.member in itemsToMove }

        return RsMoveMemberSelectionPanel(project, "Items to move", nodesAll, nodesSelected)
            .also { it.tree.setInclusionListener { validateButtons() } }
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("From:") {
                sourceFileField(growX).withLargeLeftGap()
            }
            row("To:") {
                targetFileChooser(growX).withLargeLeftGap().focused()
            }
            row {
                memberPanel(grow, pushY)
            }
            row {
                cell(isFullWidth = true) {
                    checkBox(message("search.for.references"), searchForReferences)
                }
            }
        }.also { it.preferredSize = Dimension(600, 400) }
    }

    private fun getTopLevelItems(): List<RsItemElement> {
        return sourceMod.children
            .filterIsInstance<RsItemElement>()
            .filter { RsMoveTopLevelItemsHandler.canMoveElement(it) }
    }

    override fun areButtonsValid(): Boolean {
        // `memberPanel` is initialized after `createTargetFileChooser`,
        // which triggers `areButtonsValid` check
        @Suppress("SENSELESS_COMPARISON")
        if (memberPanel == null) return false

        return sourceFilePath != targetFileChooser.text && getSelectedItems().isNotEmpty()
    }

    private fun getSelectedItems(): Set<RsItemElement> =
        memberPanel.tree.includedSet
            .filterIsInstance<RsMoveMemberInfo>()
            .mapToSet { it.member }

    override fun doAction() {
        val itemsToMove = getSelectedItems()
        val targetFilePath = targetFileChooser.text

        val targetMod = findTargetMod(targetFilePath)
        if (targetMod == null) {
            val message = "Target file must be Rust file"
            CommonRefactoringUtil.showErrorMessage(message("error.title"), message, null, project)
            return
        }

        try {
            val processor = RsMoveTopLevelItemsProcessor(project, itemsToMove, targetMod, searchForReferences)
            invokeRefactoring(processor)
        } catch (e: Exception) {
            CommonRefactoringUtil.showErrorMessage(message("error.title"), e.message, null, project)
        }
    }

    private fun findTargetMod(targetFilePath: String): RsMod? {
        val targetFile = LocalFileSystem.getInstance().findFileByIoFile(File(targetFilePath))
        return targetFile?.toPsiFile(project) as? RsMod
    }
}

class RsMoveMemberInfo(val member: RsItemElement) : RsMoveNodeInfo() {
    override val description: String =
        if (member is RsModItem) {
            "mod ${member.modName}"
        } else {
            val descriptionHTML = buildString { member.signature(this) }
            val description = StringEscapeUtils.unescapeHtml(StringUtil.removeHtmlTags(descriptionHTML))
            description.replace("(?U)\\s+".toRegex(), " ")
        }
    override val icon: Icon = member.getIcon(0)
}

class RsMoveItemAndImplsInfo(
    val item: RsItemElement, // struct or trait
    val impls: List<RsImplItem>
) : RsMoveNodeInfo() {
    override val description: String = run {
        val name = item.name
        val keyword = when (item) {
            is RsStructItem -> "struct"
            is RsEnumItem -> "enum"
            is RsTypeAlias -> "type"
            is RsTraitItem -> "trait"
            else -> null
        }
        if (name != null && keyword != null) "$keyword $name and impls" else "item and impls"
    }
    override val icon: Icon? = null
    override val children: List<RsMoveMemberInfo> = mutableListOf(RsMoveMemberInfo(item))
        .also { children -> impls.mapTo(children) { RsMoveMemberInfo(it) } }
}
