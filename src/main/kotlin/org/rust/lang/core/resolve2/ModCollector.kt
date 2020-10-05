/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS
import com.intellij.openapiext.isUnitTestMode
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.stubs.StubTreeLoader
import org.rust.lang.RsConstants
import org.rust.lang.RsFileType
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.macros.RangeMap
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.ext.RsItemElement
import org.rust.lang.core.psi.ext.RsMod
import org.rust.lang.core.psi.ext.isEnabledByCfgSelf
import org.rust.lang.core.psi.ext.variants
import org.rust.lang.core.resolve.namespaces
import org.rust.lang.core.resolve.processModDeclResolveVariants
import org.rust.lang.core.resolve2.util.RESOLVE_RANGE_MAP_KEY
import org.rust.lang.core.stubs.*
import org.rust.openapiext.*

class ModCollector(
    private val modData: ModData,
    private val defMap: CrateDefMap,
    private val crateRoot: ModData,
    private val context: CollectorContext,
    private val macroDepth: Int = 0,
    /**
     * called when new [RsItemElement] is found
     * default behaviour: just add it to [ModData.visibleItems]
     * behaviour when processing expanded items:
     * add it to [ModData.visibleItems] and propagate to modules which have glob import from [ModData]
     */
    private val onAddItem: (ModData, String, PerNs) -> Unit =
        { containingMod, name, perNs -> containingMod.addVisibleItem(name, perNs) },
) : ModVisitor {

    private var hashCalculator: HashCalculator? = null

    private val crate: Crate get() = context.crate
    private val project: Project get() = context.project

    fun collectFileAndCalculateHash(file: RsFile) {
        val hashCalculator = HashCalculator()
        collectMod(file.getStubOrBuild() ?: return, hashCalculator)
        val fileHash = hashCalculator.getFileHash()
        defMap.addVisitedFile(file, modData, fileHash)
    }

    fun collectExpandedElements(expandedFile: RsFileStub) = collectMod(expandedFile, null)

    private fun collectMod(mod: StubElement<out RsMod>, hashCalculator: HashCalculator?) {
        this.hashCalculator = hashCalculator
        val visitor = if (hashCalculator !== null) {
            val hashVisitor = hashCalculator.getVisitor(crate, modData.fileRelativePath)
            CompositeModVisitor(hashVisitor, this)
        } else {
            this
        }
        ModCollectorBase.collectMod(mod, modData.isDeeplyEnabledByCfg, visitor, crate)
        if (isUnitTestMode) {
            modData.checkChildModulesAndVisibleItemsConsistency()
        }
    }

    override fun collectImport(import: ImportLight) {
        context.imports += Import(
            containingMod = modData,
            usePath = import.usePath,
            nameInScope = import.nameInScope,
            visibility = convertVisibility(import.visibility, import.isDeeplyEnabledByCfg),
            isGlob = import.isGlob,
            isExternCrate = import.isExternCrate,
            isPrelude = import.isPrelude
        )

        if (import.isDeeplyEnabledByCfg && import.isExternCrate && import.isMacroUse) {
            importExternCrateMacros(import.usePath.single())
        }
    }

    // `#[macro_use] extern crate <name>;` - import macros
    fun importExternCrateMacros(externCrateName: String) {
        val externCrateDefMap = defMap.resolveExternCrateAsDefMap(externCrateName)
        if (externCrateDefMap !== null) {
            defMap.importAllMacrosExported(externCrateDefMap)
        }
    }

    override fun collectItem(item: ItemLight, stub: RsNamedStub) {
        val name = item.name

        // could be null if `.resolve()` on `RsModDeclItem` returns null
        val childModData = tryCollectChildModule(item, stub)

        val visItem = convertToVisItem(item, stub) ?: return
        if (visItem.isModOrEnum && childModData === null) return
        val perNs = PerNs(visItem, item.namespaces)
        onAddItem(modData, name, perNs)

        // we have to check `modData[name]` to be sure that `childModules` and `visibleItems` are consistent
        if (childModData !== null && perNs.types === modData[name].types) {
            modData.childModules[name] = childModData
        }
    }

    private fun convertToVisItem(item: ItemLight, stub: RsNamedStub): VisItem? {
        val visibility = convertVisibility(item.visibility, item.isDeeplyEnabledByCfg)
        val itemPath = modData.path.append(item.name)
        val isModOrEnum = stub is RsModItemStub || stub is RsModDeclItemStub || stub is RsEnumItemStub
        return VisItem(itemPath, visibility, isModOrEnum)
    }

    private fun tryCollectChildModule(item: ItemLight, stub: RsNamedStub): ModData? {
        if (stub is RsEnumItemStub) return collectEnumAsModData(item, stub)

        val childMod = when (stub) {
            is RsModItemStub -> ChildMod.Inline(stub, item.name, project)
            is RsModDeclItemStub -> {
                val childModPsi = resolveModDecl(item.name, item.pathAttribute) ?: return null
                ChildMod.File(childModPsi, item.name, project)
            }
            else -> return null
        }
        val isDeeplyEnabledByCfg = item.isDeeplyEnabledByCfg
        val childModData = collectChildModule(childMod, isDeeplyEnabledByCfg, item.pathAttribute)
        if (item.hasMacroUse && isDeeplyEnabledByCfg) modData.legacyMacros += childModData.legacyMacros
        return childModData
    }

    private fun collectChildModule(
        childMod: ChildMod,
        isDeeplyEnabledByCfg: Boolean,
        pathAttribute: String?
    ): ModData {
        ProgressManager.checkCanceled()
        val childModPath = modData.path.append(childMod.name)
        val (fileId, fileRelativePath) = when (childMod) {
            is ChildMod.File -> childMod.file.virtualFile.fileId to ""
            is ChildMod.Inline -> modData.fileId to "${modData.fileRelativePath}::${childMod.name}"
        }
        val childModData = ModData(
            parent = modData,
            crate = modData.crate,
            path = childModPath,
            isDeeplyEnabledByCfg = isDeeplyEnabledByCfg,
            fileId = fileId,
            fileRelativePath = fileRelativePath,
            ownedDirectoryId = childMod.getOwnedDirectory(modData, pathAttribute)?.virtualFile?.fileId
        )
        childModData.legacyMacros += modData.legacyMacros

        val collector = ModCollector(
            modData = childModData,
            defMap = defMap,
            crateRoot = crateRoot,
            context = context,
        )
        when (childMod) {
            is ChildMod.File -> collector.collectFileAndCalculateHash(childMod.file)
            is ChildMod.Inline -> collector.collectMod(childMod.mod, hashCalculator)
        }
        return childModData
    }

    private fun collectEnumAsModData(enum: ItemLight, enumStub: RsEnumItemStub): ModData {
        val enumName = enum.name
        val enumPath = modData.path.append(enumName)
        val enumData = ModData(
            parent = modData,
            crate = modData.crate,
            path = enumPath,
            isDeeplyEnabledByCfg = enum.isDeeplyEnabledByCfg,
            fileId = modData.fileId,
            fileRelativePath = "${modData.fileRelativePath}::$enumName",
            ownedDirectoryId = modData.ownedDirectoryId,  // actually can use any value here
            isEnum = true
        )
        for (variantPsi in enumStub.variants) {
            val variantName = variantPsi.name ?: continue
            val variantPath = enumPath.append(variantName)
            val isVariantDeeplyEnabledByCfg = enumData.isDeeplyEnabledByCfg && variantPsi.isEnabledByCfgSelf(crate)
            val variantVisibility = if (isVariantDeeplyEnabledByCfg) Visibility.Public else Visibility.CfgDisabled
            val variant = VisItem(variantPath, variantVisibility)
            enumData.visibleItems[variantName] = PerNs(variant, variantPsi.namespaces)
        }
        return enumData
    }

    override fun collectMacroCall(call: MacroCallLight, stub: RsMacroCallStub) {
        check(modData.isDeeplyEnabledByCfg) { "for performance reasons cfg-disabled macros should not be collected" }
        val bodyHash = call.bodyHash
        val pathOneSegment = call.path.singleOrNull()
        if (bodyHash === null && pathOneSegment != "include") return
        val macroDef = pathOneSegment?.let { modData.legacyMacros[it] }
        val dollarCrateMap = stub.getUserData(RESOLVE_RANGE_MAP_KEY) ?: RangeMap.EMPTY
        context.macroCalls += MacroCallInfo(modData, call.path, call.body, bodyHash, macroDepth, macroDef, dollarCrateMap)
    }

    override fun collectMacroDef(def: MacroDefLight) {
        val bodyHash = def.bodyHash ?: return
        val macroPath = modData.path.append(def.name)

        val defInfo = MacroDefInfo(
            modData.crate,
            macroPath,
            def.body,
            bodyHash,
            def.hasMacroExport,
            def.hasLocalInnerMacros,
            project
        )
        modData.legacyMacros[def.name] = defInfo

        if (def.hasMacroExport) {
            val visItem = VisItem(macroPath, Visibility.Public)
            val perNs = PerNs(macros = visItem)
            onAddItem(crateRoot, def.name, perNs)
        }
    }

    private fun convertVisibility(visibility: VisibilityLight, isDeeplyEnabledByCfg: Boolean): Visibility {
        if (!isDeeplyEnabledByCfg) return Visibility.CfgDisabled
        return when (visibility) {
            VisibilityLight.Public -> Visibility.Public
            // TODO: Optimization - create Visibility.Crate and Visibility.Private to reduce allocations
            VisibilityLight.RestrictedCrate -> Visibility.Restricted(crateRoot)
            VisibilityLight.Private -> Visibility.Restricted(modData)
            is VisibilityLight.Restricted -> resolveRestrictedVisibility(visibility.inPath, crateRoot, modData)
        }
    }

    /** See also [processModDeclResolveVariants] */
    private fun resolveModDecl(name: String, pathAttribute: String?): RsFile? {
        val (parentDirectory, fileNames) = if (pathAttribute === null) {
            val parentDirectory = modData.getOwnedDirectory(project) ?: return null
            val fileNames = arrayOf("$name.rs", "$name/mod.rs")
            parentDirectory to fileNames
        } else {
            // https://doc.rust-lang.org/reference/items/modules.html#the-path-attribute
            val parentDirectory = if (modData.isRsFile) {
                // For path attributes on modules not inside inline module blocks,
                // the file path is relative to the directory the source file is located.
                val containingMod = modData.asPsiFile(project) ?: return null
                containingMod.parent
            } else {
                // Paths for path attributes inside inline module blocks are relative to
                // the directory of file including the inline module components as directories.
                modData.getOwnedDirectory(project)
            } ?: return null
            val explicitPath = FileUtil.toSystemIndependentName(pathAttribute)
            parentDirectory to arrayOf(explicitPath)
        }

        val virtualFiles = fileNames.mapNotNull { parentDirectory.virtualFile.findFileByMaybeRelativePath(it) }
        // Note: It is possible that [virtualFiles] is not empty,
        // but result is null, when e.g. file is too big (thus will be [PsiFile] and not [RsFile])
        if (virtualFiles.isEmpty()) {
            for (fileName in fileNames) {
                val path = parentDirectory.virtualFile.pathAsPath.resolve(fileName)
                defMap.missedFiles.add(path)
            }
        }
        return virtualFiles.singleOrNull()?.toPsiFile(project) as? RsFile
    }
}

fun RsFile.getStubOrBuild(): RsFileStub? {
    val stubTree = greenStubTree ?: StubTreeLoader.getInstance().readOrBuild(project, virtualFile, this)
    val stub = stubTree?.root as? RsFileStub
    if (stub === null) RESOLVE_LOG.error("No stub for file ${virtualFile.path}")
    return stub
}

// https://doc.rust-lang.org/reference/visibility-and-privacy.html#pubin-path-pubcrate-pubsuper-and-pubself
private fun resolveRestrictedVisibility(
    path: Array<String>,
    crateRoot: ModData,
    containingMod: ModData
): Visibility.Restricted {
    val initialModData = when (path.first()) {
        "super", "self" -> containingMod
        else -> crateRoot
    }
    val pathTarget = path
        .fold(initialModData) { modData, segment ->
            val nextModData = when (segment) {
                "self" -> modData
                "super" -> modData.parent
                else -> modData.childModules[segment]
            }
            nextModData ?: return Visibility.Restricted(crateRoot)
        }
    return Visibility.Restricted(pathTarget)
}

private fun ModData.checkChildModulesAndVisibleItemsConsistency() {
    for ((name, childMod) in childModules) {
        check(name == childMod.name) { "Inconsistent name of $childMod" }
        check(visibleItems[name]?.types?.isModOrEnum == true)
        { "Inconsistent `visibleItems` and `childModules` in $this for name $name" }
    }
}

private fun ModData.getOwnedDirectory(project: Project): PsiDirectory? {
    val ownedDirectoryId = ownedDirectoryId ?: return null
    return PersistentFS.getInstance()
        .findFileById(ownedDirectoryId)
        ?.toPsiDirectory(project)
}

private fun ModData.asPsiFile(project: Project): PsiFile? =
    PersistentFS.getInstance()
        .findFileById(fileId)
        ?.toPsiFile(project)
        ?: run {
            RESOLVE_LOG.error("Can't find PsiFile for $this")
            return null
        }

private sealed class ChildMod(val name: String, val project: Project) {
    class Inline(val mod: RsModItemStub, name: String, project: Project) : ChildMod(name, project)
    class File(val file: RsFile, name: String, project: Project) : ChildMod(name, project)
}

/**
 * Have to pass [pathAttribute], because [RsFile.pathAttribute] triggers resolve.
 * See also: [RsMod.getOwnedDirectory]
 */
private fun ChildMod.getOwnedDirectory(parentMod: ModData, pathAttribute: String?): PsiDirectory? {
    if (this is ChildMod.File && name == RsConstants.MOD_RS_FILE) return file.parent

    val (parentDirectory, path) = if (pathAttribute !== null) {
        when {
            this is ChildMod.File -> return file.parent
            parentMod.isRsFile -> parentMod.asPsiFile(project)?.parent to pathAttribute
            else -> parentMod.getOwnedDirectory(project) to pathAttribute
        }
    } else {
        parentMod.getOwnedDirectory(project) to name
    }
    if (parentDirectory === null) return null

    // Don't use `FileUtil#getNameWithoutExtension` to correctly process relative paths like `./foo`
    val directoryPath = FileUtil.toSystemIndependentName(path).removeSuffix(".${RsFileType.defaultExtension}")
    return parentDirectory.virtualFile
        .findFileByMaybeRelativePath(directoryPath)
        ?.let(parentDirectory.manager::findDirectory)
}
