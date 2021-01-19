/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring.changeSignature

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.refactoring.changeSignature.ParameterInfo
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.usageView.UsageInfo
import org.rust.ide.refactoring.findBinding
import org.rust.ide.utils.import.RsImportHelper
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.types.ty.TyUnit
import org.rust.stdext.mapToMutableList

sealed class RsFunctionUsage(element: RsElement) : UsageInfo(element) {
    open val isCallUsage: Boolean = false

    class FunctionCall(val call: RsCallExpr) : RsFunctionUsage(call) {
        override val isCallUsage: Boolean = true
    }

    class MethodCall(val call: RsMethodCall) : RsFunctionUsage(call) {
        override val isCallUsage: Boolean = true
    }

    class Reference(val path: RsPath) : RsFunctionUsage(path)
    class MethodImplementation(val overriddenMethod: RsFunction) : RsFunctionUsage(overriddenMethod)
}

fun findFunctionUsages(function: RsFunction): List<RsFunctionUsage> {
    return function.findUsages().map {
        when (it) {
            is RsCallExpr -> RsFunctionUsage.FunctionCall(it)
            is RsMethodCall -> RsFunctionUsage.MethodCall(it)
            is RsPath -> RsFunctionUsage.Reference(it)
            else -> error("unreachable")
        }
    }.toList()
}

fun processFunctionUsage(config: RsChangeFunctionSignatureConfig, usage: RsFunctionUsage) {
    val function = config.function
    val factory = RsPsiFactory(function.project)
    if (config.nameChanged()) {
        renameFunctionUsage(factory, usage, config)
    }
    if (usage.isCallUsage) {
        changeArguments(factory, function, usage, buildParameterOperations(config))
    }
}

fun processFunction(
    project: Project,
    config: RsChangeFunctionSignatureConfig,
    function: RsFunction
) {
    val factory = RsPsiFactory(project)

    if (config.nameChanged()) {
        rename(factory, function, config)
    }
    changeVisibility(function, config)
    changeReturnType(factory, function, config)
    val allRenames = mutableMapOf<RsElement, String>()
    if (config.nameChanged()) {
        allRenames[function] = config.name
    }
    val parameterRenames = changeParameters(factory, function, config, buildParameterOperations(config))
    for ((parameter, newName) in parameterRenames) {
        allRenames[parameter] = newName
    }
    renameParameters(factory, parameterRenames, allRenames)
    changeAsync(factory, function, config)
    changeUnsafe(factory, function, config)
}

private fun rename(factory: RsPsiFactory, function: RsFunction, config: RsChangeFunctionSignatureConfig) {
    function.identifier.replace(factory.createIdentifier(config.name))
}

private fun renameFunctionUsage(
    factory: RsPsiFactory,
    usage: RsFunctionUsage,
    config: RsChangeFunctionSignatureConfig
) {
    val identifier = factory.createIdentifier(config.name)
    when (usage) {
        is RsFunctionUsage.Reference -> usage.path.referenceNameElement?.replace(identifier)
        is RsFunctionUsage.FunctionCall -> {
            val path = (usage.call.expr as? RsPathExpr)?.path ?: return
            path.referenceNameElement?.replace(identifier)
        }
        is RsFunctionUsage.MethodCall -> usage.call.identifier.replace(identifier)
    }
}

fun changeVisibility(function: RsFunction, config: RsChangeFunctionSignatureConfig) {
    if (function.vis?.text == config.visibility?.text) return

    function.vis?.delete()

    val vis = config.visibility
    if (vis != null) {
        function.addBefore(vis, function.firstChild)
    }
}

private fun changeReturnType(factory: RsPsiFactory, function: RsFunction, config: RsChangeFunctionSignatureConfig) {
    if (!areTypesEqual(function.retType?.typeReference, config.returnTypeReference)) {
        function.retType?.delete()
        if (config.returnType !is TyUnit) {
            val ret = factory.createRetType(config.returnTypeReference.text)
            function.addAfter(ret, function.valueParameterList) as RsRetType
            RsImportHelper.importTypeReferencesFromTy(function, config.returnType, useAliases = true)
        }
    }
}

private fun changeParameters(
    factory: RsPsiFactory,
    function: RsFunction,
    config: RsChangeFunctionSignatureConfig,
    parameterOps: List<ParameterOperation>
): Map<RsValueParameter, String> {
    val parameters = function.valueParameterList ?: return emptyMap()
    val parameterList = parameters.valueParameterList.toList()
    val originalParameters = parameters.copy() as RsValueParameterList
    val parameterRenames = mutableMapOf<RsValueParameter, String>()

    cycle@ for ((index, data) in config.parameters.zip(parameterOps).withIndex()) {
        val (parameter, op) = data
        when (op) {
            is ParameterOperation.Add -> {
                deleteItem(parameterList, index)

                val typeReference = parameter.typeReference
                val newParameter = factory.createValueParameter(parameter.patText, typeReference, reference = false)
                val anchor = findAnchorToInsertItem(parameters, function, index)
                insertItemWithComma(factory, newParameter, parameters, anchor)
                RsImportHelper.importTypeReferencesFromElements(function, setOf(parameter.typeReference), useAliases = true)
            }
            is ParameterOperation.Move -> {
                deleteItem(parameterList, index)

                val originalParameter = originalParameters.valueParameterList.getOrNull(op.originalIndex)
                    ?: continue@cycle
                val anchor = findAnchorToInsertItem(parameters, function, index)
                insertItemWithComma(factory, originalParameter, parameters, anchor)
            }
        }

        when (op) {
            is ParameterOperation.Move, is ParameterOperation.Keep -> {
                val currentParameter = parameters.valueParameterList[index]
                if (parameter.patText != currentParameter.pat?.text) {
                    if (parameter.pat is RsPatIdent && currentParameter.pat is RsPatIdent) {
                        parameterRenames[currentParameter] = parameter.patText
                    } else {
                        currentParameter.pat?.replace(parameter.pat)
                    }
                }
                if (!areTypesEqual(parameter.typeReference, currentParameter.typeReference)) {
                    currentParameter.typeReference?.replace(parameter.typeReference)
                    // TODO: import + test
                }
            }
        }
    }

    // remove remaining parameters
    for (index in parameterOps.size until parameterList.size) {
        deleteItem(parameterList, index)
    }
    return parameterRenames
}

private fun renameParameters(
    factory: RsPsiFactory,
    parameterRenames: Map<RsValueParameter, String>,
    allRenames: Map<RsElement, String>) {
    for ((parameter, newName) in parameterRenames) {
        renameParameter(factory, parameter, newName, allRenames)
    }
}

private fun renameParameter(
    factory: RsPsiFactory,
    parameter: RsValueParameter,
    newName: String,
    allRenames: Map<RsElement, String>
) {
    val identifier = factory.createIdentifier(newName)

    val binding = parameter.pat?.findBinding()
    if (binding != null) {
        val usages = RenameUtil.findUsages(binding, newName, false, false, allRenames)
        for (info in usages) {
            RenameUtil.rename(info, newName)
        }
    }

    binding?.identifier?.replace(identifier)
}

private fun changeArguments(
    factory: RsPsiFactory,
    function: RsFunction,
    usage: RsFunctionUsage,
    parameterOps: List<ParameterOperation>
) {
    val arguments = when (usage) {
        is RsFunctionUsage.FunctionCall -> usage.call.valueArgumentList
        is RsFunctionUsage.MethodCall -> usage.call.valueArgumentList
        else -> error("unreachable")
    }

    // Skip over explicit self parameter in UFCS
    val offset = if (usage is RsFunctionUsage.FunctionCall && function.isMethod) {
        1
    } else {
        0
    }
    val argumentList = arguments.exprList.drop(offset).toList()
    val originalArgumentList = (arguments.copy() as RsValueArgumentList).exprList.drop(offset).toList()
    val argumentCount = argumentList.size

    cycle@ for ((index, op) in parameterOps.withIndex()) {
        when (op) {
            is ParameterOperation.Add -> {
                // We had an argument on this position, reset it
                // TODO: remove also surrounding whitespace and comments
                if (index < argumentList.size) {
                    argumentList[index].delete()
                } else if (arguments.exprList.isNotEmpty()) {
                    // This argument is new, add comma
                    arguments.addBefore(factory.createComma(), arguments.rparen ?: continue@cycle)
                }
            }
            is ParameterOperation.Move -> {
                deleteItem(argumentList, index)

                val originalArgument = originalArgumentList.getOrNull(op.originalIndex) ?: continue@cycle
                val anchor = findAnchorToInsertItem(arguments, function, index)
                insertItemWithComma(factory, originalArgument, arguments, anchor)
            }
        }
    }

    // Remove remaining arguments
    for (index in parameterOps.size until argumentCount) {
        deleteItem(argumentList, index)
    }
}

private fun changeAsync(factory: RsPsiFactory, function: RsFunction, config: RsChangeFunctionSignatureConfig) {
    val async = function.node.findChildByType(RsElementTypes.ASYNC)?.psi
    if (config.isAsync) {
        if (async == null) {
            val asyncKw = factory.createAsyncKeyword()
            function.addBefore(asyncKw, function.unsafe ?: function.fn)
        }
    } else {
        async?.delete()
    }
}

private fun changeUnsafe(factory: RsPsiFactory, function: RsFunction, config: RsChangeFunctionSignatureConfig) {
    if (config.isUnsafe) {
        if (function.unsafe == null) {
            val unsafe = factory.createUnsafeKeyword()
            function.addBefore(unsafe, function.fn)
        }
    } else {
        function.unsafe?.delete()
    }
}

/**
 * Inserts a parameter/argument into a parameter/argument list along with comments and whitespace.
 * Also fixes commas before and after the inserted item.
 */
private fun insertItemWithComma(
    factory: RsPsiFactory,
    item: RsElement,
    items: RsElement,
    anchor: PsiElement
) {
    // Insert all whitespace and comments around the item
    var insertionAnchor = anchor
    var index = items.childrenWithLeaves.indexOf(insertionAnchor)

    var start = item.getPrevNonCommentSibling()?.nextSibling ?: item
    val end = item.getNextNonCommentSibling()?.prevSibling ?: item

    // +1 because of zero indexing and +1 for start itself
    // If start == end, indexOf returns -1 and count becomes 1
    val count = start.rightSiblings.indexOf(end) + 2
    val inserted = mutableListOf<Int>()
    for (c in 0 until count) {
        items.addAfter(start, insertionAnchor)
        val children = items.childrenWithLeaves.toList()

        index = when {
            // Whitespaces were merge together, stay on the same index
            start is PsiWhiteSpace && insertionAnchor is PsiWhiteSpace -> index
            // Whitespace was auto-inserted before the inserted element, skip one index
            children[index + 1] is PsiWhiteSpace && start !is PsiWhiteSpace -> {
                assert(children[index + 2].elementType == start.elementType)
                index + 2
            }
            // Element was inserted normally
            else -> index + 1
        }
        inserted.add(index)

        // We need to use an index, because inserting e.g. comments can change PSI instance of whitespace around them
        insertionAnchor = children[index]
        start = start.nextSibling
    }

    val firstInsertedIndex = inserted.first()
    val lastInsertedIndex = inserted.last()
    val children = items.childrenWithLeaves.toList()

    // Add comma before first inserted element
    val firstInserted = children[firstInsertedIndex]
    val previous = firstInserted.getPrevNonCommentSibling()
    if (previous != items.firstChild && previous?.elementType != RsElementTypes.COMMA) {
        items.addBefore(factory.createComma(), firstInserted)
    }

    // Add comma after last inserted element
    val lastInserted = children[lastInsertedIndex]
    val next = lastInserted.getNextNonCommentSibling()
    if (next != items.lastChild && next?.elementType != RsElementTypes.COMMA) {
        items.addAfter(factory.createComma(), lastInserted)
    }
}

/**
 * Finds an element after which we should insert a parameter/argument so that it ends up at `index` position.
 * Skips over self parameter in methods.
 */
private fun findAnchorToInsertItem(items: PsiElement, function: RsFunction, index: Int): PsiElement {
    // Skip over self and potentially its following comma
    val firstChild = when (items) {
        is RsValueParameterList -> skipFirstItem(items, items.selfParameter)
        is RsValueArgumentList -> {
            // UFCS
            if (items.parent is RsCallExpr && function.isMethod) {
                skipFirstItem(items, items.exprList.getOrNull(0))
            } else {
                items.firstChild
            }
        }
        else -> error("unreachable")
    }

    val commas = firstChild.rightSiblings.filter { it.elementType == RsElementTypes.COMMA }.toList()
    return when {
        index == 0 -> firstChild
        commas.size < index -> items.lastChild.prevSibling
        else -> commas[index - 1]
    }
}

private fun skipFirstItem(itemHolder: RsElement, potentialFirstItem: PsiElement?): PsiElement = when {
    potentialFirstItem == null -> itemHolder.firstChild
    potentialFirstItem.nextSibling.elementType == RsElementTypes.COMMA -> potentialFirstItem.nextSibling
    else -> potentialFirstItem
}

private fun deleteItem(originalList: List<RsElement>, index: Int) =
    originalList.getOrNull(index)?.deleteWithSurroundingCommaAndWhitespace()

/**
 * Builds a plan of operations that need to be performed for each original parameter/argument.
 */
private fun buildParameterOperations(config: RsChangeFunctionSignatureConfig): List<ParameterOperation> =
    config.parameters.withIndex().mapToMutableList { (index, parameter) ->
        when {
            parameter.index == ParameterInfo.NEW_PARAMETER -> ParameterOperation.Add
            parameter.index != index -> ParameterOperation.Move(parameter.index)
            else -> ParameterOperation.Keep
        }
    }

private sealed class ParameterOperation {
    // Insert a new parameter at the current index
    object Add : ParameterOperation()

    // Keep the parameter at the same place
    object Keep : ParameterOperation()

    // Move a parameter from `originalIndex` to the current index
    class Move(val originalIndex: Int) : ParameterOperation()
}

private fun areTypesEqual(t1: RsTypeReference?, t2: RsTypeReference?): Boolean = (t1?.text ?: "()") == (t2?.text
    ?: "()")
