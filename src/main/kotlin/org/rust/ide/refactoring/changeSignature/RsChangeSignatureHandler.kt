/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring.changeSignature

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.changeSignature.ChangeSignatureHandler
import com.intellij.refactoring.util.CommonRefactoringUtil
import org.rust.RsBundle
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.ext.ancestorOrSelf
import org.rust.lang.core.psi.ext.rawValueParameters
import org.rust.lang.core.psi.ext.valueParameters
import org.rust.openapiext.editor
import org.rust.openapiext.elementUnderCaretInEditor

class RsChangeSignatureHandler : ChangeSignatureHandler {
    override fun getTargetNotFoundMessage(): String = "The caret should be positioned at a function or method"

    override fun findTargetMember(element: PsiElement): RsFunction? = element.ancestorOrSelf()

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        val function = elements.singleOrNull() as? RsFunction ?: return
        showRefactoringDialog(function, dataContext?.editor)
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext?) {
        val function = dataContext?.elementUnderCaretInEditor as? RsFunction ?: return
        showRefactoringDialog(function, editor)
    }

    private fun showRefactoringDialog(function: RsFunction, editor: Editor?) {
        val config = RsChangeFunctionSignatureConfig.create(function)
        val project = function.project

        val error = checkFunction(function)
        if (error == null) {
            showChangeFunctionSignatureDialog(project, config)
        } else if (editor != null) {
            showCannotRefactorErrorHint(project, editor, error)
        }
    }

    private fun showCannotRefactorErrorHint(project: Project, editor: Editor, message: String) {
        CommonRefactoringUtil.showErrorHint(project, editor,
            RefactoringBundle.getCannotRefactorMessage(message),
            RefactoringBundle.message("changeSignature.refactoring.name"),
            "refactoring.changeSignature"
        )
    }

    private fun checkFunction(function: RsFunction): String? {
        if (function.valueParameters != function.rawValueParameters) {
            return RsBundle.message("refactoring.change.signature.error.cfg.disabled.parameters")
        }
        return null
    }
}
