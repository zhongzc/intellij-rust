package org.rust.ide.refactoring.move

import com.intellij.dvcs.DvcsUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.project.settings.rustSettings
import org.rust.cargo.project.settings.toolchain
import org.rust.cargo.project.workspace.CargoWorkspace.LibKind
import org.rust.cargo.project.workspace.CargoWorkspace.TargetKind
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.cargo.toolchain.CargoCommandLine
import org.rust.cargo.toolchain.CargoTopMessage
import org.rust.cargo.toolchain.ExternalLinter
import org.rust.cargo.toolchain.run
import org.rust.ide.annotator.RsExternalLinterUtils
import org.rust.ide.notifications.showBalloon
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.openapiext.*
import kotlin.math.max
import kotlin.random.Random

@ExperimentalStdlibApi
class RsMoveRandomItemsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project!!
        val base = project.guessProjectDir()!!
        val crateRoots = findRandomCrateRoot(project)
        doAction(1, project, base, crateRoots)
    }
}

const val REPEATS: Int = 10
const val MOVE_TO_SAME_CRATE: Boolean = true
val RANDOM: Random = Random.Default

@ExperimentalStdlibApi
private fun doAction(index: Int, project: Project, base: VirtualFile, crateRoots: List<RsFile>) {
    checkReadAccessAllowed()
    checkIsSmartMode(project)
    saveAllDocuments()
    project.computeWithCancelableProgress("git reset --hard ...") {
        gitHardReset(project, base)
    }

    do {
        val (sourceMod, targetMod) = findRandomSourceAndTargetMods(crateRoots)
        project.showBalloon(
            "$index: Moving from ${sourceMod.qualifiedName} to ${targetMod.qualifiedName}",
            NotificationType.INFORMATION
        )
        val success = moveRandomItems(project, sourceMod, targetMod)
    } while (!success)

    project.computeWithCancelableProgress("Cargo check...") {
        if (getCargoCheckMessages(project, base).isNotEmpty()) {
            project.showBalloon("Cargo check failed", NotificationType.WARNING)
            runInEdt {
                CargoCommandLine(
                    "check",
                    workingDirectory = base.pathAsPath,
                    additionalArguments = listOf("--all", "--all-targets")
                ).run(crateRoots.first().cargoProject!!)
            }
            return@computeWithCancelableProgress
        }

        if (index < REPEATS) {
            invokeLater {
                runReadActionInSmartMode(DumbService.getInstance(project)) {
                    doAction(index + 1, project, base, crateRoots)
                }
            }
        }
    }
}

private fun findRandomSourceAndTargetMods(crateRoots: List<RsFile>): Pair<RsMod, RsMod> {
    val sourceCrateRoot = crateRoots.random(RANDOM)
    val targetCrateRoot = if (MOVE_TO_SAME_CRATE) sourceCrateRoot else crateRoots.random(RANDOM)
    val sourceMod = getAllMods(sourceCrateRoot).random(RANDOM)
    val targetMod = getAllMods(targetCrateRoot).random(RANDOM)
    return Pair(sourceMod, targetMod)
}

private fun findRandomCrateRoot(project: Project): List<RsFile> {
    val cargoProject = project.cargoProjects.allProjects.single()
    return cargoProject.workspace!!.packages
        .filter { it.origin == PackageOrigin.WORKSPACE }
        .flatMap { it.targets }
        .filter {
            val kind = it.kind
            kind == TargetKind.Bin
                || kind is TargetKind.Lib && kind.kinds.all { it != LibKind.PROC_MACRO }
        }
        .map { it.crateRoot!!.toPsiFile(project)!!.rustFile!! }
}

private fun getAllMods(crateRoot: RsFile): List<RsMod> {
    val modsSelf = crateRoot.descendantsOfTypeOrSelf<RsMod>()
    val modsInner = crateRoot.descendantsOfType<RsModDeclItem>()
        .flatMap { getAllMods(it.reference.resolve() as RsFile) }
    return modsSelf + modsInner
}

fun movedItemDescription(item: RsItemElement): String {
    return item.name ?: RsMoveMemberInfo(item).description
}

@ExperimentalStdlibApi
fun moveRandomItems(project: Project, sourceMod: RsMod, targetMod: RsMod): Boolean {
    checkReadAccessAllowed()
    if (sourceMod == targetMod) return false
    if (sourceMod.isTest() || targetMod.isTest()) return false
    val itemsToMove = findRandomItemsToMove(sourceMod)?.toSet() ?: return false
    println("Moving from ${sourceMod.qualifiedName} to ${targetMod.qualifiedName}: " +
        itemsToMove.joinToString { movedItemDescription(it) })
    return try {
        RsMoveTopLevelItemsProcessor(project, itemsToMove, targetMod, searchForReferences = true, throwOnConflicts = true).run()
        true
    } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
        // todo move even if conflicts, and check that `cargo check` report errors after move ?
        false
    } catch (e: Exception) {
        val title = RefactoringBundle.message("error.title")
        val message = "${e.javaClass.simpleName}: ${e.message}"
        CommonRefactoringUtil.showErrorMessage(title, message, "refactoring.moveFile", project)
        throw e
    }
}

fun findRandomItemsToMove(sourceMod: RsMod): List<RsItemElement>? {
    val itemsAll = sourceMod.children
        .filterIsInstance<RsItemElement>()
        .filter { RsMoveTopLevelItemsHandler.canMoveElement(it) }
        .filter { !sourceMod.isCrateRoot || it !is RsFunction || it.name != "main" }
    if (itemsAll.isEmpty()) return null
    val numberItemsToMove = if (RANDOM.nextBoolean()) 1 else max(1, RANDOM.nextInt(itemsAll.size))
    val itemsToMove = itemsAll.shuffled().take(numberItemsToMove)
    return itemsToMove + collectRelatedImplItems(sourceMod, itemsToMove)
}

private fun RsMod.isTest(): Boolean {
    val outerAttrList = when (this) {
        is RsModItem -> outerAttrList
        is RsFile -> declaration?.outerAttrList ?: return false
        else -> error(false)
    }
    return outerAttrList.any { it.text.contains("test") }
}

fun getCargoCheckMessages(project: Project, base: VirtualFile): List<CargoTopMessage> {
    CodeInsightFixtureTestCase.assertEquals(ExternalLinter.CARGO_CHECK, project.rustSettings.externalLinter)
    val checkResult = RsExternalLinterUtils.checkWrapped(
        project.toolchain!!,
        project,
        Disposable {},
        base.pathAsPath,
        null
    )!!
    return checkResult.messages.filter { it.message.level == "error" }
}

fun gitHardReset(project: Project, base: VirtualFile) {
    DvcsUtil.workingTreeChangeStarted(project, null).use {
        println("Restoring repository state")
        val path = base.pathAsPath
        check(!path.toString().contains("intellij-rust"))
        GeneralCommandLine("git", "reset", "--hard")
            .withWorkDirectory(path)
            .createProcess()
            .waitFor()
        VfsUtil.markDirtyAndRefresh(false, true, false, base)
    }
}
