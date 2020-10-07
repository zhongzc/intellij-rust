/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.crate.CratePersistentId
import org.rust.lang.core.macros.macroExpansionManager
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.RustPsiChangeListener
import org.rust.lang.core.psi.rustPsiManager
import org.rust.lang.core.psi.rustStructureModificationTracker
import org.rust.openapiext.checkWriteAccessAllowed
import org.rust.openapiext.fileId
import org.rust.openapiext.pathAsPath
import org.rust.stdext.mapToSet
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class DefMapHolder(private val structureModificationTracker: ModificationTracker) {

    @Volatile
    var defMap: CrateDefMap? = null

    /** Value of [rustStructureModificationTracker] at the time when [defMap] started to built */
    @Volatile
    private var defMapStamp: Long = -1

    fun hasLatestStamp(): Boolean = !shouldRebuild && defMapStamp == structureModificationTracker.modificationCount

    fun setLatestStamp() {
        defMapStamp = structureModificationTracker.modificationCount
    }

    fun checkHasLatestStamp() {
        check(hasLatestStamp()) {
            "DefMapHolder must have latest stamp right after DefMap($defMap) was updated. " +
                "$defMapStamp vs ${structureModificationTracker.modificationCount}"
        }
    }

    @Volatile
    var shouldRebuild: Boolean = true
        set(value) {
            field = value
            if (value) {
                shouldRecheck = false
                changedFiles.clear()
            }
        }

    @Volatile
    var shouldRecheck: Boolean = false
    val changedFiles: MutableSet<RsFile> = hashSetOf()

    override fun toString(): String = "DefMapHolder($defMap, stamp=$defMapStamp)"
}

@Service
class DefMapService(val project: Project) : Disposable {

    private val defMaps: ConcurrentHashMap<CratePersistentId, DefMapHolder> = ConcurrentHashMap()
    val defMapsBuildLock: Any = Any()

    private val fileIdToCrateId: ConcurrentHashMap<FileId, CratePersistentId> = ConcurrentHashMap()

    /** Merged map of [CrateDefMap.missedFiles] for all crates */
    private val missedFiles: ConcurrentHashMap<Path, CratePersistentId> = ConcurrentHashMap()

    private val structureModificationTracker: ModificationTracker =
        project.rustPsiManager.rustStructureWithMacroCallsModificationTracker

    init {
        val connection = project.messageBus.connect(this)
        project.rustPsiManager.subscribeRustPsiChange(connection, object : RustPsiChangeListener {
            override fun rustPsiChanged(file: PsiFile, element: PsiElement, isStructureModification: Boolean) {
                /** Needed when macro expansion is disabled */
                if (!project.macroExpansionManager.isMacroExpansionEnabled) {
                    scheduleRebuildAllDefMaps()
                }
            }
        })
    }

    fun getDefMapHolder(crate: CratePersistentId): DefMapHolder {
        return defMaps.computeIfAbsent(crate) { DefMapHolder(structureModificationTracker) }
    }

    fun afterDefMapBuilt(defMap: CrateDefMap) {
        val crate = defMap.crate

        fileIdToCrateId.values.remove(crate)
        for (fileId in defMap.fileInfos.keys) {
            fileIdToCrateId[fileId] = crate
        }

        missedFiles.values.remove(crate)
        for (missedFile in defMap.missedFiles) {
            missedFiles[missedFile] = crate
        }
    }

    fun onCargoWorkspaceChanged() {
        scheduleRecheckAllDefMaps()
    }

    fun onFileAdded(file: RsFile) {
        checkWriteAccessAllowed()
        val path = file.virtualFile.pathAsPath
        val crate = missedFiles[path] ?: return
        getDefMapHolder(crate).shouldRebuild = true
    }

    fun onFileRemoved(file: RsFile) {
        checkWriteAccessAllowed()
        val crate = findCrate(file) ?: return
        getDefMapHolder(crate).shouldRebuild = true
    }

    fun onFileChanged(file: RsFile) {
        checkWriteAccessAllowed()
        val crate = findCrate(file) ?: return
        getDefMapHolder(crate).changedFiles += file
    }

    /** Note: we can't use [RsFile.crate], because it can trigger resolve */
    private fun findCrate(file: RsFile): CratePersistentId? {
        val fileId = file.virtualFile.fileId
        return fileIdToCrateId[fileId]
    }

    fun scheduleRebuildAllDefMaps() {
        for (defMapHolder in defMaps.values) {
            defMapHolder.shouldRebuild = true
        }
    }

    fun scheduleRecheckAllDefMaps() {
        for (defMapHolder in defMaps.values) {
            defMapHolder.shouldRecheck = true
        }
    }

    /** Removes DefMaps for crates not in crate graph */
    fun removeStaleDefMaps(allCrates: List<Crate>) {
        val allCrateIds = allCrates.mapToSet { it.id }
        defMaps.keys.removeIf { it !in allCrateIds }
    }

    override fun dispose() {}
}

val Project.defMapService: DefMapService
    get() = service()
