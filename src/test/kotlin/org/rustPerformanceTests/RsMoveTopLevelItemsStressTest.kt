/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rustPerformanceTests

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import org.rust.ide.refactoring.move.getCargoCheckMessages
import org.rust.ide.refactoring.move.gitHardReset
import org.rust.ide.refactoring.move.moveRandomItems
import org.rust.lang.RsFileType
import org.rust.lang.core.macros.MacroExpansionScope
import org.rust.lang.core.macros.macroExpansionManager
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.RsModItem
import org.rust.lang.core.psi.ext.RsMod
import org.rust.lang.core.psi.ext.descendantsOfType
import org.rust.openapiext.pathAsPath
import org.rust.openapiext.toPsiFile

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
        val allMods = findAllMods(project, base)

        repeat(repeats) { i ->
            println("Moving random item ($i)")
            getCargoCheckMessages(project, base)
            do {
                val moved = moveRandomItems(allMods)
            } while (!moved)
            PsiDocumentManager.getInstance(project).commitAllDocuments()

            println("Running cargo check")
            getCargoCheckMessages(project, base)
            gitHardReset(project, base)
        }

        println("Done")
    }

    private fun moveRandomItems(allMods: List<RsMod>, moveToSameCrate: Boolean = true): Boolean {
        val sourceMod = allMods.random()
        val targetMod = allMods.random()
        if (sourceMod === targetMod || moveToSameCrate && sourceMod.crateRoot != targetMod.crateRoot) return false
        return moveRandomItems(project, sourceMod, targetMod)
    }

    fun ensureCargoCheckSuccess(base: VirtualFile) {
        val messages = getCargoCheckMessages(project, base)
        assertEmpty(messages)
    }
}

fun findAllMods(project: Project, base: VirtualFile): List<RsMod> {
    return base
        .findDescendants { it.fileType == RsFileType }
        .map { it.toPsiFile(project) }
        .filterIsInstance<RsFile>()
        .filter { it.crateRoot != null && it.cargoWorkspace != null }
        .flatMap { (it.descendantsOfType<RsModItem>() + it) as List<RsMod> }
        .also { check(it.size > 1) }
}
