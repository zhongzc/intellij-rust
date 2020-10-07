/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS
import org.rust.cargo.project.settings.rustSettings
import org.rust.ide.utils.isEnabledByCfg
import org.rust.lang.core.crate.Crate
import org.rust.lang.core.crate.impl.CargoBasedCrate
import org.rust.lang.core.crate.impl.DoctestCrate
import org.rust.lang.core.macros.*
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.*
import org.rust.lang.core.resolve.ItemProcessingMode.WITHOUT_PRIVATE_IMPORTS
import org.rust.lang.core.resolve2.RsModInfoBase.*
import org.rust.openapiext.testAssert
import org.rust.openapiext.toPsiFile

@Suppress("SimplifyBooleanWithConstants")
val Project.isNewResolveEnabled: Boolean
    get() = rustSettings.newResolveEnabled || true

/** null return value means that new resolve can't be used */
fun processItemDeclarations2(
    scope: RsMod,
    ns: Set<Namespace>,
    processor: RsResolveProcessor,
    ipm: ItemProcessingMode
): Boolean? {
    val (project, defMap, modData) = when (val info = getModInfo(scope)) {
        CantUseNewResolve -> return null
        InfoNotFound -> return false
        is RsModInfo -> info
    }

    for ((name, perNs) in modData.visibleItems.entriesWithName(processor.name)) {
        val visItems = arrayOf(
            perNs.types to Namespace.Types,
            perNs.values to Namespace.Values,
            perNs.macros to Namespace.Macros,
        )
        // TODO: Profile & optimize
        // We need set here because item could belong to multiple namespaces (e.g. unit struct)
        // Also we need to distinguish unit struct and e.g. mod and function with same name in one module
        val elements = hashSetOf<RsNamedElement>()
        for ((visItem, namespace) in visItems) {
            if (visItem === null || namespace !in ns) continue
            if (ipm === WITHOUT_PRIVATE_IMPORTS && visItem.visibility === Visibility.Invisible) continue
            elements += visItem.toPsi(defMap, project, namespace)
        }
        elements.any { processor(name, it) } && return true
    }

    if (processor.name === null && Namespace.Types in ns) {
        for ((traitPath, traitVisibility) in modData.unnamedTraitImports) {
            val trait = VisItem(traitPath, traitVisibility)
            val traitPsi = trait.toPsi(defMap, project, Namespace.Types)
            traitPsi.any { processor("_", it) } && return true
        }
    }

    if (ipm.withExternCrates && Namespace.Types in ns) {
        for ((name, externCrateDefMap) in defMap.externPrelude.entriesWithName(processor.name)) {
            if (modData.visibleItems[name]?.types !== null) continue
            val externCrateRoot = externCrateDefMap.root.toRsMod(project) ?: continue
            processor(name, externCrateRoot) && return true
        }
    }

    return false
}

/**
 * null return value means that new resolve can't be used.
 * [runBeforeResolve] is passed to conform with [MacroResolver],
 * and it should be called only if we are going to use new resolve.
 * We need to get [ModData] to check if we can use new resolve, which is not fast,
 * so we unite check and actual resolve as an optimization.
 */
fun processMacros(scope: RsMod, processor: RsResolveProcessor, runBeforeResolve: () -> Boolean): Boolean? {
    val (project, defMap, modData) = when (val info = getModInfo(scope)) {
        CantUseNewResolve -> return null
        InfoNotFound -> return false
        is RsModInfo -> info
    }
    if (runBeforeResolve()) return true

    for ((name, macroInfo) in modData.legacyMacros.entriesWithName(processor.name)) {
        val visItem = VisItem(macroInfo.path, Visibility.Public)
        val macros = visItem.toPsi(defMap, project, Namespace.Macros).singleOrNull() ?: continue
        processor(name, macros) && return true
    }

    for ((name, perNs) in modData.visibleItems.entriesWithName(processor.name)) {
        val visItem = perNs.macros ?: continue
        val macros = visItem.toPsi(defMap, project, Namespace.Macros).singleOrNull() ?: continue
        processor(name, macros) && return true
    }

    return false
}

/**
 * Resolve without PSI is needed to prevent caching incomplete result in [expandedItemsCached].
 * Consider:
 * - Macro expansion task wants to expand some macro
 * - Firstly we resolve macro path
 * - It can trigger items resolve [processItemDeclarations2].
 *   E.g. if macro path is two segment - we need to resolve first segment as mod
 * - [processItemDeclarations2] uses [expandedItemsCached] which will try to expand all macros in scope,
 *   which results in recursion,
 *   which is prevented by returning null from macro expansion,
 *   therefore result of [expandedItemsCached] is incomplete (and cached)
 */
fun resolveToMacroWithoutPsi(call: RsMacroCall): RsMacroDataWithHash? {
    val scope = call.containingMod
    testAssert({ call.isTopLevelExpansion }, { "resolveToMacroWithoutPsi works only for top level macro calls" })
    val (_, defMap, modData) = when (val info = getModInfo(scope)) {
        CantUseNewResolve -> {
            val def = call.resolveToMacro() ?: return null
            return RsMacroDataWithHash(def)
        }
        InfoNotFound -> return null
        is RsModInfo -> info
    }

    val callPath = call.pathSegmentsAdjusted.toTypedArray()
    val def = defMap.resolveMacroCallToMacroDefInfo(modData, callPath) ?: return null
    return RsMacroDataWithHash(RsMacroData(def.body), def.bodyHash)
}

private val RsMacroCall.pathSegments: List<String>
    get() = generateSequence(path) { it.path }
        .map { it.referenceName }
        .toMutableList()
        .also { it.reverse() }

/**
 * Adjustment is performed according to [getPathKind]:
 * - If macro call is expanded from macro def with `#[macro_export(local_inner_macros)]` attribute:
 *   before: `foo!();`
 *   after:  `IntellijRustDollarCrate::12345::foo!();`
 *                                     ~~~~~ crateId
 * - If macro call starts with [MACRO_DOLLAR_CRATE_IDENTIFIER]:
 *   before: `IntellijRustDollarCrate::foo!();`
 *   after:  `IntellijRustDollarCrate::12345::foo!();`
 *                                     ~~~~~ crateId
 *
 * See also [processMacroCallPathResolveVariants] and [findDependencyCrateByNamePath]
 */
private val RsMacroCall.pathSegmentsAdjusted: List<String>
    get() {
        val segments = pathSegments

        val expandedFrom = findMacroCallExpandedFromNonRecursive()?.resolveToMacro() ?: return segments
        val cameFromMacroDef = !path.cameFromMacroCall()
        return when {
            segments.size == 1 && cameFromMacroDef && expandedFrom.hasMacroExportLocalInnerMacros -> {
                val crate = expandedFrom.containingCrate?.id ?: return segments
                listOf(MACRO_DOLLAR_CRATE_IDENTIFIER, crate.toString()) + segments
            }
            segments.first() == MACRO_DOLLAR_CRATE_IDENTIFIER -> {
                val crate = path.resolveDollarCrateIdentifier()?.id ?: return segments
                listOf(MACRO_DOLLAR_CRATE_IDENTIFIER, crate.toString()) + segments.subList(1, segments.size)
            }
            else -> segments
        }
    }

private sealed class RsModInfoBase {
    object CantUseNewResolve : RsModInfoBase()
    object InfoNotFound : RsModInfoBase()
    data class RsModInfo(val project: Project, val defMap: CrateDefMap, val modData: ModData) : RsModInfoBase()
}

private fun getModInfo(scope: RsMod): RsModInfoBase {
    val project = scope.project
    if (!project.isNewResolveEnabled || scope.modName == TMP_MOD_NAME || scope.isLocal) return CantUseNewResolve
    val crate = scope.containingCrate as? CargoBasedCrate ?: return CantUseNewResolve

    val defMap = project.getDefMap(crate) ?: return InfoNotFound
    val modData = defMap.getModData(scope) ?: return InfoNotFound

    if (isModShadowedByOtherMod(scope, modData)) return CantUseNewResolve

    return RsModInfo(project, defMap, modData)
}

private fun Project.getDefMap(crate: Crate): CrateDefMap? {
    check(crate !is DoctestCrate) { "doc test crates are not supported by CrateDefMap" }
    if (crate.id === null) return null
    val defMap = defMapService.getOrUpdateIfNeeded(crate)
    if (defMap === null) RESOLVE_LOG.error("DefMap is null for $crate during resolve")
    return defMap
}

/** E.g. `fn func() { mod foo { ... } }` */
private val RsMod.isLocal: Boolean
    get() = ancestorStrict<RsBlock>() != null

/** "shadowed by other mod" means that [ModData] is not accessible from [CrateDefMap.root] through [ModData.childModules] */
private fun isModShadowedByOtherMod(mod: RsMod, modData: ModData): Boolean {
    val isDeeplyEnabledByCfg = (mod.containingFile as RsFile).isDeeplyEnabledByCfg && mod.isEnabledByCfg
    val isShadowedByOtherInlineMod = isDeeplyEnabledByCfg != modData.isDeeplyEnabledByCfg

    return modData.isShadowedByOtherFile || isShadowedByOtherInlineMod
}

private fun <T> Map<String, T>.entriesWithName(name: String?): Map<String, T> {
    if (name === null) {
        return this
    } else {
        val value = this[name] ?: return emptyMap()
        return mapOf(name to value)
    }
}

private fun VisItem.toPsi(defMap: CrateDefMap, project: Project, ns: Namespace): List<RsNamedElement> {
    if (isModOrEnum) return path.toRsModOrEnum(defMap, project)
    val containingModOrEnum = containingMod.toRsModOrEnum(defMap, project).singleOrNull() ?: return emptyList()
    val isEnabledByCfg = isEnabledByCfg
    return when (containingModOrEnum) {
        is RsMod -> {
            if (ns === Namespace.Macros) {
                // TODO: expandedItemsIncludingMacros
                val macros = containingModOrEnum.itemsAndMacros
                    .filterIsInstance<RsNamedElement>()
                    .filter { (it is RsMacro || it is RsMacro2) && it.name == name && it.isEnabledByCfg == isEnabledByCfg }
                // TODO: Multiresolve for macro 2.0
                val macro = macros.lastOrNull()
                listOfNotNull(macro)
            } else {
                containingModOrEnum.expandedItemsAll
                    .filterIsInstance<RsNamedElement>()
                    .filter { it.name == name && ns in it.namespaces && it.isEnabledByCfg == isEnabledByCfg }
            }
        }
        is RsEnumItem -> {
            containingModOrEnum.variants
                .filter { it.name == name && ns in it.namespaces && it.isEnabledByCfg == isEnabledByCfg }
        }
        else -> error("Expected mod or enum, got: $containingModOrEnum")
    }
}

private val VisItem.isEnabledByCfg: Boolean get() = visibility !== Visibility.CfgDisabled

private fun ModPath.toRsModOrEnum(defMap: CrateDefMap, project: Project): List<RsNamedElement /* RsMod or RsEnumItem */> {
    val modData = defMap.getModData(this) ?: return emptyList()
    return if (modData.isEnum) {
        modData.toRsEnum(project)
    } else {
        val mod = modData.toRsMod(project)
        listOfNotNull(mod)
    }
}

private fun ModData.toRsEnum(project: Project): List<RsEnumItem> {
    if (!isEnum) return emptyList()
    val containingMod = parent?.toRsMod(project) ?: return emptyList()
    val isEnabledByCfg = asVisItem().isEnabledByCfg
    return containingMod.expandedItemsAll
        .filterIsInstance<RsEnumItem>()
        .filter { it.name == name && it.isEnabledByCfg == isEnabledByCfg }
}

private fun ModData.toRsMod(project: Project): RsMod? = toRsModNullable(project)
    ?: run {
        RESOLVE_LOG.warn("Can't find RsMod for $this")
        null
    }

private fun ModData.toRsModNullable(project: Project): RsMod? {
    if (isEnum) return null
    val file = PersistentFS.getInstance().findFileById(fileId)
        ?.toPsiFile(project) as? RsFile
        ?: return null
    val fileRelativeSegments = fileRelativePath.split("::")
    return fileRelativeSegments
        .subList(1, fileRelativeSegments.size)
        .fold(file as RsMod) { mod, segment ->
            mod.expandedItemsAll
                .filterIsInstance<RsModItem>()
                .filter { it.modName == segment }
                .singleOrCfgEnabled()
                ?: return null
        }
}

// TODO: Multiresolve
private inline fun <reified T : RsElement> List<T>.singleOrCfgEnabled(): T? =
    singleOrNull() ?: singleOrNull { it.isEnabledByCfg }

/** [expandedItemsExceptImplsAndUses] with addition of items from [RsForeignModItem]s */
private val RsItemsOwner.expandedItemsAll: List<RsItemElement>
    get() {
        val items = expandedItemsExceptImplsAndUses
        if (items.none { it is RsForeignModItem }) return items

        val (foreignItems, usualItems) = items.partition { it is RsForeignModItem }
        return usualItems + foreignItems.flatMap { it.stubChildrenOfType() }
    }
