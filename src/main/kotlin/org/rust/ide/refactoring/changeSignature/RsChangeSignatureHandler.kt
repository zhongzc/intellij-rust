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
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.changeSignature.ChangeInfo
import com.intellij.refactoring.changeSignature.ChangeSignatureHandler
import com.intellij.refactoring.changeSignature.ChangeSignatureProcessorBase
import com.intellij.refactoring.changeSignature.ParameterInfo.NEW_PARAMETER
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.usageView.BaseUsageViewDescriptor
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewDescriptor
import org.rust.RsBundle
import org.rust.ide.refactoring.findBinding
import org.rust.ide.utils.import.RsImportHelper.importTypeReferencesFromTy
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.RsElementTypes.COMMA
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.types.ty.TyUnit
import org.rust.lang.core.types.type
import org.rust.openapiext.editor
import org.rust.openapiext.elementUnderCaretInEditor
import org.rust.stdext.mapToMutableList

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

class RsChangeSignatureProcessor(project: Project, changeInfo: ChangeInfo)
    : ChangeSignatureProcessorBase(project, changeInfo) {
    override fun performRefactoring(usages: Array<out UsageInfo>) {
        val rsChangeInfo = changeInfo as? RsSignatureChangeInfo ?: return
        val config = rsChangeInfo.config
        val function = config.function
        val project = function.project

        if (function.owner is RsAbstractableOwner.Trait) {
            function.searchForImplementations().forEach {
                val overridden = (it as? RsFunction) ?: return@forEach
                changeSignature(project, config, overridden, findFunctionUsages(overridden))
            }
        }

        changeSignature(project, config, function, usages.mapNotNull { it as? Usage })
    }

    override fun findUsages(): Array<UsageInfo> {
        val rsChangeInfo = changeInfo as? RsSignatureChangeInfo ?: return emptyArray()
        val function = rsChangeInfo.config.function
        return findFunctionUsages(function).toTypedArray()
    }

    override fun createUsageViewDescriptor(usages: Array<out UsageInfo>?): UsageViewDescriptor =
        BaseUsageViewDescriptor(changeInfo.method)
}

private sealed class Usage(element: RsElement) : UsageInfo(element) {
    open val isCallUsage: Boolean = false

    class FunctionCall(val call: RsCallExpr) : Usage(call) {
        override val isCallUsage: Boolean = true
    }

    class MethodCall(val call: RsMethodCall) : Usage(call) {
        override val isCallUsage: Boolean = true
    }

    class Reference(val path: RsPath) : Usage(path)
    class Parameter(val parameter: RsValueParameter, usage: RsElement) : Usage(usage)
}

private fun findFunctionUsages(function: RsFunction): List<Usage> {
    val functionUsages = function.findUsages().map {
        when (it) {
            is RsCallExpr -> Usage.FunctionCall(it)
            is RsMethodCall -> Usage.MethodCall(it)
            is RsPath -> Usage.Reference(it)
            else -> error("unreachable")
        }
    }.toList()
    val parameterUsages = function.valueParameters.flatMap { parameter ->
        val binding = (parameter.pat as? RsPatIdent)?.patBinding ?: return@flatMap emptyList()
        ReferencesSearch.search(binding, binding.useScope)
            .mapNotNull {
                Usage.Parameter(parameter, it.element as? RsElement ?: return@mapNotNull null)
            }
    }

    return functionUsages + parameterUsages
}

private fun changeSignature(
    project: Project,
    config: RsChangeFunctionSignatureConfig,
    function: RsFunction,
    usages: List<Usage>
) {
    // TODO: find all usages before calling this function
    val factory = RsPsiFactory(project)
    val parameterOps = buildParameterOperations(config)
    val nameChanged = config.name != function.name

    if (nameChanged) {
        rename(factory, function, config)
    }
    changeVisibility(function, config)
    changeReturnType(factory, function, config)
    changeParameters(factory, function, config, usages, parameterOps)
    changeAsync(factory, function, config)
    changeUnsafe(factory, function, config)

    usages.forEach {
        if (nameChanged) {
            renameFunctionUsage(factory, it, config)
        }
        if (it.isCallUsage) {
            changeArguments(factory, function, it, parameterOps)
        }
    }
}

private fun rename(factory: RsPsiFactory, function: RsFunction, config: RsChangeFunctionSignatureConfig) {
    function.identifier.replace(factory.createIdentifier(config.name))
}

private fun renameFunctionUsage(
    factory: RsPsiFactory,
    usage: Usage,
    config: RsChangeFunctionSignatureConfig
) {
    val identifier = factory.createIdentifier(config.name)
    when (usage) {
        is Usage.Reference -> usage.path.referenceNameElement?.replace(identifier)
        is Usage.FunctionCall -> {
            val path = (usage.call.expr as? RsPathExpr)?.path ?: return
            path.referenceNameElement?.replace(identifier)
        }
        is Usage.MethodCall -> usage.call.identifier.replace(identifier)
    }
}

private fun changeVisibility(function: RsFunction, config: RsChangeFunctionSignatureConfig) {
    if (function.vis?.text == config.visibility?.text) return

    function.vis?.delete()

    val vis = config.visibility
    if (vis != null) {
        function.addBefore(vis, function.firstChild)
    }
}

private fun changeReturnType(factory: RsPsiFactory, function: RsFunction, config: RsChangeFunctionSignatureConfig) {
    if (function.returnType != config.returnType) {
        function.retType?.delete()
        if (config.returnType !is TyUnit) {
            val ret = factory.createRetType(config.renderType(config.returnType))
            function.addAfter(ret, function.valueParameterList)
            importTypeReferencesFromTy(function, config.returnType, useAliases = true)
        }
    }
}

private fun changeParameters(
    factory: RsPsiFactory,
    function: RsFunction,
    config: RsChangeFunctionSignatureConfig,
    usages: List<Usage>,
    parameterOps: List<ParameterOperation>
) {
    val parameters = function.valueParameterList ?: return
    val parameterList = parameters.valueParameterList.toList()
    val originalParameters = parameters.copy() as RsValueParameterList
    val parameterUsageMap = mutableMapOf<RsValueParameter, MutableList<Usage.Parameter>>()
    for (usage in usages) {
        if (usage is Usage.Parameter) {
            parameterUsageMap.getOrPut(usage.parameter) { mutableListOf() }.add(usage)
        }
    }

    fun createType(parameter: Parameter): RsTypeReference =
        factory.tryCreateType(config.renderType(parameter.type)) ?: factory.createType("()")

    cycle@ for ((index, data) in config.parameters.zip(parameterOps).withIndex()) {
        val (parameter, op) = data
        when (op) {
            is ParameterOperation.Add -> {
                deleteItem(parameterList, index)

                val typeReference = createType(parameter)
                val newParameter = factory.createValueParameter(parameter.patText, typeReference, reference = false)
                val anchor = findAnchorToInsertItem(parameters, function, index)
                insertItemWithComma(factory, newParameter, parameters, anchor)
                importTypeReferencesFromTy(function, parameter.type, useAliases = true)
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
                if (parameter.pat.text != currentParameter.pat?.text) {
                    if (parameter.pat is RsPatIdent && currentParameter.pat is RsPatIdent) {
                        renameParameter(
                            function.project,
                            currentParameter,
                            parameterUsageMap[currentParameter].orEmpty(),
                            parameter.patText
                        )
                    } else {
                        currentParameter.pat?.replace(parameter.pat)
                    }
                }
                if (parameter.type != currentParameter.typeReference?.type) {
                    currentParameter.typeReference?.replace(createType(parameter))
                }
            }
        }
    }

    // remove remaining parameters
    for (index in parameterOps.size until parameterList.size) {
        deleteItem(parameterList, index)
    }
}

private fun changeArguments(
    factory: RsPsiFactory,
    function: RsFunction,
    usage: Usage,
    parameterOps: List<ParameterOperation>
) {
    val arguments = when (usage) {
        is Usage.FunctionCall -> usage.call.valueArgumentList
        is Usage.MethodCall -> usage.call.valueArgumentList
        else -> error("unreachable")
    }

    // Skip over explicit self parameter in UFCS
    val offset = if (usage is Usage.FunctionCall && function.isMethod) {
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

private fun renameParameter(
    project: Project,
    parameter: RsValueParameter,
    usages: List<Usage.Parameter>,
    newName: String
) {
    val factory = RsPsiFactory(project)
    val identifier = factory.createIdentifier(newName)

    val binding = parameter.pat?.findBinding()
    binding?.identifier?.replace(identifier)

    usages.forEach {
        val path = it.element as? RsPath
        path?.referenceNameElement?.replace(identifier)
    }
}

/**
 * Inserts a parameter/argument into a parameter/argument list along with comments and whitespace, fixing commas before and after.
 */
private fun insertItemWithComma(
    factory: RsPsiFactory,
    item: RsElement,
    items: RsElement,
    anchor: PsiElement?
) {
    /* TODO: Fix insertion of whitespace
    val start = parameter.getPrevNonCommentSibling()?.nextSibling ?: parameter
    val end = parameter.getNextNonCommentSibling()?.prevSibling ?: parameter

    var insertionAnchor = anchor
    val inserted = mutableListOf<PsiElement>()
    var toInsert = start
    while (true) {
        val newlyInserted = parameters.addAfter(toInsert, insertionAnchor)
        inserted.add(newlyInserted)
        if (toInsert == end) break

        insertionAnchor = newlyInserted
        toInsert = toInsert.nextSibling
    }*/

    val inserted = listOf(items.addAfter(item, anchor))

    val firstInserted = inserted.getOrNull(0) ?: return
    val previous = firstInserted.getPrevNonCommentSibling()
    if (previous != items.firstChild && previous?.elementType != COMMA) {
        items.addBefore(factory.createComma(), firstInserted)
    }

    val lastInserted = inserted.getOrNull(inserted.lastIndex) ?: return
    val next = lastInserted.getNextNonCommentSibling()
    if (next != items.lastChild && next?.elementType != COMMA) {
        items.addAfter(factory.createComma(), lastInserted)
    }
}

/**
 * Finds an element after which we should insert a parameter/argument so that it ends up at `index` position.
 * Skips over self parameter in methods.
 */
private fun findAnchorToInsertItem(items: PsiElement, function: RsFunction, index: Int): PsiElement? {
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

    val commas = firstChild.rightSiblings.filter { it.elementType == COMMA }.toList()
    return when {
        index == 0 -> firstChild
        commas.size < index -> items.lastChild.prevSibling
        else -> commas[index - 1]
    }
}
private fun skipFirstItem(itemHolder: RsElement, potentialFirstItem: PsiElement?): PsiElement = when {
    potentialFirstItem == null -> itemHolder.firstChild
    potentialFirstItem.nextSibling.elementType == COMMA -> potentialFirstItem.nextSibling
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
            parameter.index == NEW_PARAMETER -> ParameterOperation.Add
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
