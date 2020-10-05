/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapiext.isUnitTestMode
import gnu.trove.THashMap
import gnu.trove.TObjectHash
import org.rust.lang.core.crate.Crate

fun afterDefMapBuiltDebug(defMap: CrateDefMap, context: CollectorContext) {
    if (!isUnitTestMode) return
    checkNoUnresolvedImportsAndMacros2(context.imports, context.macroCalls)
    // printStatistics(defMap)
}

private fun checkNoUnresolvedImportsAndMacros2(imports: MutableList<Import>, macros: MutableList<MacroCallInfo>) {
    val unresolvedImports = imports.filter { it.containingMod.isDeeplyEnabledByCfg }
    val unresolvedMacros = macros.filter { it.containingMod.isDeeplyEnabledByCfg }

    if (unresolvedImports.isNotEmpty() || unresolvedMacros.isNotEmpty()) {
        check(true)
    }
    // check(unresolvedImports.isEmpty()) { "Found ${unresolvedImports.size} unresolved imports: $unresolvedImports" }
    // check(unresolvedMacros.isEmpty()) { "Found ${unresolvedMacros.size} unresolved macroCalls: $unresolvedMacros" }
}

private fun printStatistics(defMap: CrateDefMap) {
    val modules = defMap.root.descendantModules
    val numberVisItems = modules.sumBy { mod ->
        mod.visibleItems.values.sumBy { listOfNotNull(it.types, it.values, it.macros).size }
    }
    println("$defMap stats: ${modules.size} modules, $numberVisItems vis items")
}

private val ModData.descendantModules: List<ModData>
    get() = childModules.values.flatMap { it.descendantModules } + this

fun printStatistics(defMaps: Map<Crate, CrateDefMap>) {
    // printDistinctItemsStatistics(defMaps)
    // printVisibilityStatistics(defMaps)
}

private fun printDistinctItemsStatistics(defMaps: Map<Crate, CrateDefMap>) {
    val items = defMaps.values
        .flatMap { it.root.descendantModules }
        .flatMap { it.visibleItems.values }
        .flatMap { listOfNotNull(it.types, it.values, it.macros) }
    println("Items:                ${items.size}")
    println("Items distinct id:    ${items.distinctBy { IdWrapper(it) }.size}")
    println("Items distinct equal: ${items.distinct().size}")
}

class IdWrapper(val value: Any) {
    override fun hashCode(): Int = value.hashCode()
    override fun equals(other: Any?): Boolean = value === (other as IdWrapper).value
}

fun printMapStatistics(defMaps: Map<Crate, CrateDefMap>) {
    val modules = defMaps.values.flatMap { it.root.descendantModules }
    printMapStatistics("visibleItems", modules) { it.visibleItems }
    printMapStatistics("childModules", modules) { it.childModules }
    printMapStatistics("legacyMacros", modules) { it.legacyMacros }
    printMapStatistics("unnamedTrait", modules) { it.unnamedTraitImports }
}

private fun printMapStatistics(description: String, modules: List<ModData>, getter: (ModData) -> Map<*, *>) {
    var sizeSum = 0
    var capacitySum = 0
    for (module in modules) {
        val capacityField = TObjectHash::class.java.getDeclaredMethod("capacity")
        capacityField.isAccessible = true
        val map = getter(module) as THashMap
        val capacity = capacityField.invoke(map) as Int
        capacitySum += capacity
        sizeSum += map.size
    }
    println("$description:  $sizeSum / $capacitySum")
}

fun printVisibilityStatistics(defMaps: Map<Crate, CrateDefMap>) {
    val items = defMaps.values
        .flatMap { it.root.descendantModules }
        .flatMap { it.visibleItems.values }
        .flatMap { listOfNotNull(it.types, it.values, it.macros) }
        .toHashSet()
    println("Total visItems: ${items.size}")
    println("\tPublic:      ${items.count { it.visibility === Visibility.Public }}")
    println("\tRestricted:  ${items.count { it.visibility is Visibility.Restricted }}")
    println("\tInvisible:   ${items.count { it.visibility === Visibility.Invisible }}")
    println("\tCfgDisabled: ${items.count { it.visibility === Visibility.CfgDisabled }}")
}
