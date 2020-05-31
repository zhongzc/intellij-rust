/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring.move

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.parentOfType
import com.intellij.refactoring.BaseRefactoringProcessor
import org.intellij.lang.annotations.Language
import org.rust.RsTestBase
import org.rust.TestProject
import org.rust.lang.core.psi.ext.RsItemElement
import org.rust.lang.core.psi.ext.RsMod
import org.rust.lang.core.psi.ext.childrenOfType
import org.rust.openapiext.document
import org.rust.openapiext.toPsiDirectory
import org.rust.openapiext.toPsiFile

abstract class RsMoveTopLevelItemsTestBase : RsTestBase() {

    // todo remove
    protected fun doTestIgnore(@Language("Rust") before: String, @Language("Rust") after: String) {}

    protected fun doTest(@Language("Rust") before: String, @Language("Rust") after: String) =
        checkByDirectory(before.trimIndent(), after.trimIndent(), false, ::performMove)

    protected fun doTestConflictsError(@Language("Rust") before: String) =
        expect<BaseRefactoringProcessor.ConflictsInTestsException> {
            checkByDirectory(before.trimIndent(), "", true, ::performMove)
        }

    private fun performMove(testProject: TestProject) {
        val sourceFile = myFixture.findFileInTempDir(testProject.fileWithCaret).toPsiFile(project)!!
        val itemsToMove = sourceFile.getElementsAtMarker().map { it.parentOfType<RsItemElement>()!! }

        val root = myFixture.findFileInTempDir(".").toPsiDirectory(project)!!
        val targetMod = searchElementInAllFiles(root) { it.getElementAtMarker(TARGET_MARKER) }
            ?.parentOfType<RsMod>()
            ?: error("Please add $TARGET_MARKER marker for target mod")

        RsMoveTopLevelItemsProcessor(
            project,
            itemsToMove,
            targetMod,
            searchForReferences = true
        ).run()
    }

    private fun <T> searchElementInAllFiles(root: PsiDirectory, searcher: (PsiFile) -> T?): T? {
        for (file in root.childrenOfType<PsiFile>()) {
            searcher(file)?.let { return it }
        }
        for (directory in root.childrenOfType<PsiDirectory>()) {
            searchElementInAllFiles(directory, searcher)?.let { return it }
        }
        return null
    }

    companion object {
        private const val TARGET_MARKER = "/*target*/"
    }
}

private fun PsiFile.getElementAtMarker(marker: String = "<caret>"): PsiElement? =
    getElementsAtMarker(marker).singleOrNull()

private fun PsiFile.getElementsAtMarker(marker: String = "<caret>"): List<PsiElement> =
    extractMultipleMarkerOffsets(project, marker).map { findElementAt(it)!! }

private fun PsiFile.extractMultipleMarkerOffsets(project: Project, marker: String): List<Int> =
    virtualFile.document!!.extractMultipleMarkerOffsets(project, marker)

private fun Document.extractMultipleMarkerOffsets(project: Project, marker: String): List<Int> {
    if (!text.contains(marker)) return emptyList()

    val offsets = mutableListOf<Int>()
    runWriteAction {
        val text = StringBuilder(text)
        while (true) {
            val offset = text.indexOf(marker)
            if (offset >= 0) {
                text.delete(offset, offset + marker.length)
                offsets += offset
            } else {
                break
            }
        }
        setText(text.toString())
    }

    PsiDocumentManager.getInstance(project).commitAllDocuments()
    PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(this)
    return offsets
}
