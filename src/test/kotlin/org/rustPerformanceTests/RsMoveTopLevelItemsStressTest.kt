/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rustPerformanceTests

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.refactoring.BaseRefactoringProcessor.ConflictsInTestsException
import org.rust.cargo.project.settings.rustSettings
import org.rust.cargo.project.settings.toolchain
import org.rust.cargo.toolchain.ExternalLinter
import org.rust.ide.annotator.RsExternalLinterUtils
import org.rust.ide.refactoring.move.RsMoveTopLevelItemsHandler
import org.rust.ide.refactoring.move.RsMoveTopLevelItemsProcessor
import org.rust.ide.refactoring.move.collectRelatedImplItems
import org.rust.lang.RsFileType
import org.rust.lang.core.macros.MacroExpansionScope
import org.rust.lang.core.macros.macroExpansionManager
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.RsModItem
import org.rust.lang.core.psi.ext.RsItemElement
import org.rust.lang.core.psi.ext.RsMod
import org.rust.lang.core.psi.ext.descendantsOfType
import org.rust.openapiext.pathAsPath
import org.rust.openapiext.toPsiFile
import org.rust.openapiext.withWorkDirectory

@ExperimentalStdlibApi
class RsMoveTopLevelItemsStressTest : RsRealProjectTestBase() {

    fun `test move in Empty`() = doTest(EMPTY, repeats = 2)
    fun `test move in Cargo`() = doTest(CARGO, repeats = 100)
    fun `test move in Clap`() = doTest(CLAP, repeats = 100)

    override fun tearDown() {}

    // multiple times move random item to random mod
    private fun doTest(info: RealProjectInfo, repeats: Int) {
        Disposer.register(
            testRootDisposable,
            project.macroExpansionManager.setUnitTestExpansionModeAndDirectory(MacroExpansionScope.ALL, name)
        )

        println("Opening the project")
        val base = openRealProject(info) ?: return
        println(base.pathAsPath)

        println("Collecting mods")
        val allMods = base
            .findDescendants { it.fileType == RsFileType }
            .map { it.toPsiFile(project) }
            .filterIsInstance<RsFile>()
            .filter { it.crateRoot != null && it.cargoWorkspace != null }
            .flatMap { (it.descendantsOfType<RsModItem>() + it) as List<RsMod> }
        check(allMods.size > 1)

        repeat(repeats) { i ->
            println("Moving random item ($i)")
            ensureCargoCheckSuccess(base)
            do {
                val moved = moveRandomItem(allMods)
            } while (!moved)
            PsiDocumentManager.getInstance(project).commitAllDocuments()

            println("Running cargo check")
            ensureCargoCheckSuccess(base)
            gitHardReset(base)
        }

        println("Done")
    }

    private fun moveRandomItem(allMods: List<RsMod>, moveToSameCrate: Boolean = true): Boolean {
        val sourceMod = allMods.random()
        val targetMod = allMods.random()
        if (sourceMod === targetMod || moveToSameCrate && sourceMod.crateRoot != targetMod.crateRoot) return false

        // todo random subset
        val itemToMove = sourceMod.children
            .filterIsInstance<RsItemElement>()
            .filter { RsMoveTopLevelItemsHandler.canMoveElement(it) }
            .randomOrNull()
            ?: return false
        if (itemToMove.containingMod.isCrateRoot && itemToMove is RsFunction && itemToMove.name == "main") return false
        val itemsToMove = collectRelatedImplItems(sourceMod, listOf(itemToMove)) + itemToMove
        return try {
            RsMoveTopLevelItemsProcessor(project, itemsToMove, targetMod, true).run()
            true
        } catch (e: ConflictsInTestsException) {
            // todo move even if conflicts, and check that `cargo check` report errors after move ?
            false
        }
    }

    private fun ensureCargoCheckSuccess(base: VirtualFile) {
        assertEquals(ExternalLinter.CARGO_CHECK, project.rustSettings.externalLinter)
        val checkResult = RsExternalLinterUtils.checkWrapped(
            project.toolchain!!,
            project,
            testRootDisposable,
            base.pathAsPath,
            null
        )!!
        val errorMessages = checkResult.messages
            .filter { it.message.level == "error" }
        assertEmpty(errorMessages)
    }

    private fun gitHardReset(base: VirtualFile) {
        println("Restoring repository state")
        val path = base.pathAsPath
        check(!path.toString().contains("intellij-rust"))
        GeneralCommandLine("git", "reset", "--hard")
            .withWorkDirectory(path)
            .createProcess()
            .waitFor()
        // PsiDocumentManager.getInstance(project).commitAllDocuments()
        // saveAllDocuments()
        // fullyRefreshDirectoryInUnitTests(base)
        // todo GitBrancher
    }
}
