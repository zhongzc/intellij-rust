/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring.move

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.TextComponentAccessor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.refactoring.RefactoringBundle.message
import com.intellij.refactoring.ui.RefactoringDialog
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.ui.components.JBTextField
import com.intellij.ui.layout.panel
import com.intellij.util.IncorrectOperationException
import org.rust.lang.RsFileType
import org.rust.lang.core.psi.ext.RsItemElement
import org.rust.lang.core.psi.ext.RsMod
import org.rust.openapiext.toPsiFile
import java.awt.Dimension
import java.io.File
import javax.swing.JComponent

class RsMoveTopLevelItemsDialog(
    project: Project,
    private val itemsToMove: List<RsItemElement>,
    private val containingMod: RsMod
) : RefactoringDialog(project, false) {

    private val sourceFilePath: String = containingMod.containingFile.virtualFile.path
    private val sourceFileField: JBTextField = JBTextField(sourceFilePath).apply { isEnabled = false }
    private val targetFileChooser: TextFieldWithBrowseButton = createTargetFileChooser(project)
    private val memberPanel: RsMemberSelectionPanel = createMemberInfoPanel()

    private var searchForReferences: Boolean = true

    init {
        super.init()
        title = "Move Module Items"
    }

    private fun createTargetFileChooser(project: Project): TextFieldWithBrowseButton {
        val chooser = TextFieldWithBrowseButton(null, disposable)
        val fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFileDescriptor(RsFileType)
            .withRoots(project.guessProjectDir())
        chooser.addBrowseFolderListener("Choose Destination File", null, project,
            fileChooserDescriptor, TextComponentAccessor.TEXT_FIELD_WHOLE_TEXT)

        chooser.text = sourceFilePath
        chooser.textField.caretPosition = sourceFilePath.removeSuffix(".rs").length
        chooser.textField.moveCaretPosition(sourceFilePath.lastIndexOf('/') + 1)

        return chooser
    }

    override fun createCenterPanel(): JComponent {
        val panel = panel {
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
        }
        panel.preferredSize = Dimension(600, 400)
        return panel
    }

    private fun createMemberInfoPanel(): RsMemberSelectionPanel {
        val topLevelItems = getTopLevelItems()
        val itemsToMove = itemsToMove.toSet()

        val memberInfo = topLevelItems.map {
            val memberInfo = RsMemberInfo(it)
            memberInfo.isChecked = itemsToMove.contains(it)
            memberInfo
        }
        return RsMemberSelectionPanel("Items to move", memberInfo)
    }

    private fun getTopLevelItems(): List<RsItemElement> {
        return containingMod.children
            .filterIsInstance<RsItemElement>()
            .filter { RsMoveTopLevelItemsHandler.canMoveElement(it) }
    }

    override fun doAction() {
        val itemsToMove = memberPanel.table.selectedMemberInfos.map { it.member }
        val targetFilePath = targetFileChooser.text

        val targetMod = findTargetMod(targetFilePath)
        if (targetMod == null) {
            val message = "Target file must be Rust file"
            CommonRefactoringUtil.showErrorMessage(message("error.title"), message, null, project)
            return
        }

        try {
            val processor = RsMoveTopLevelItemsProcessor(
                project,
                itemsToMove,
                targetMod,
                searchForReferences
            )
            invokeRefactoring(processor)
        } catch (e: IncorrectOperationException) {
            CommonRefactoringUtil.showErrorMessage(message("error.title"), e.message, null, project)
        }
    }

    private fun findTargetMod(targetFilePath: String): RsMod? {
        val targetFile = LocalFileSystem.getInstance().findFileByIoFile(File(targetFilePath))
        return targetFile?.toPsiFile(project) as? RsMod
    }
}
