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
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.RefactoringFactory
import com.intellij.refactoring.changeSignature.ChangeInfo
import com.intellij.refactoring.changeSignature.ChangeSignatureHandler
import com.intellij.refactoring.changeSignature.ChangeSignatureProcessorBase
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.usageView.BaseUsageViewDescriptor
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewDescriptor
import org.rust.RsBundle
import org.rust.ide.presentation.renderInsertionSafe
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
            "refactoring.renameRefactorings"
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
                changeSignature(project, config, overridden, null)
            }
        }

        changeSignature(project, config, function, usages.mapNotNull { it.element as? RsElement })
    }

    override fun findUsages(): Array<UsageInfo> {
        val rsChangeInfo = changeInfo as? RsSignatureChangeInfo ?: return emptyArray()
        return rsChangeInfo.config.function.findUsages().map {
            UsageInfo(it)
        }.toList().toTypedArray()
    }

    override fun createUsageViewDescriptor(usages: Array<out UsageInfo>?): UsageViewDescriptor =
        BaseUsageViewDescriptor(changeInfo.method)
}

private fun changeSignature(
    project: Project,
    config: RsChangeFunctionSignatureConfig,
    function: RsFunction,
    usages: List<RsElement>?
) {
    // TODO: find all usages before calling this function
    val actualUsages = usages ?: function.findUsages().toList()

    val factory = RsPsiFactory(project)
    val parameterOps = buildParameterOperations(config)
    val nameChanged = config.name != function.name

    if (nameChanged) {
        rename(factory, function, config)
    }
    changeVisibility(function, config)
    changeReturnType(factory, function, config)
    changeParameters(factory, function, config, parameterOps)
    changeAsync(factory, function, config)
    changeUnsafe(factory, function, config)

    actualUsages.forEach {
        if (nameChanged) {
            renameUsage(factory, it, config)
        }
        if (it.isCallUsage) {
            changeArguments(factory, it, parameterOps)
        }
    }
}

private val RsElement.isCallUsage: Boolean
    get () = this is RsCallExpr || this is RsMethodCall

private fun rename(factory: RsPsiFactory, function: RsFunction, config: RsChangeFunctionSignatureConfig) {
    function.identifier.replace(factory.createIdentifier(config.name))
}

private fun renameUsage(
    factory: RsPsiFactory,
    usage: RsElement,
    config: RsChangeFunctionSignatureConfig
) {
    val identifier = factory.createIdentifier(config.name)
    when (usage) {
        is RsPath -> usage.referenceNameElement?.replace(identifier)
        is RsCallExpr -> {
            val path = (usage.expr as? RsPathExpr)?.path ?: return
            path.referenceNameElement?.replace(identifier)
        }
        is RsMethodCall -> usage.identifier.replace(identifier)
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
    parameterOps: List<ParameterOperation>
) {
    val parameters = function.valueParameterList ?: return
    val parameterList = parameters.valueParameterList.toList()
    val originalParameters = parameters.copy() as RsValueParameterList

    fun createType(parameter: Parameter): RsTypeReference =
        factory.tryCreateType(config.renderType(parameter.type)) ?: factory.createType("()")

    cycle@ for ((index, data) in config.parameters.zip(parameterOps).withIndex()) {
        val (parameter, op) = data
        when (op) {
            is ParameterOperation.Add -> {
                deleteItem(parameterList, index)

                val typeReference = createType(parameter)
                val newParameter = factory.createValueParameter(parameter.patText, typeReference, reference = false)
                val anchor = findAnchorToInsertItem(parameters, index)
                insertItemWithComma(factory, newParameter, parameters, anchor)
                importTypeReferencesFromTy(function, parameter.type, useAliases = true)
            }
            is ParameterOperation.Move -> {
                deleteItem(parameterList, index)

                val originalParameter = originalParameters.valueParameterList.getOrNull(op.originalIndex)
                    ?: continue@cycle
                val anchor = findAnchorToInsertItem(parameters, index)
                insertItemWithComma(factory, originalParameter, parameters, anchor)
            }
        }

        when (op) {
            is ParameterOperation.Move, is ParameterOperation.Keep -> {
                val currentParameter = parameters.valueParameterList[index]
                if (parameter.pat.text != currentParameter.pat?.text) {
                    if (parameter.pat is RsPatIdent && currentParameter.pat is RsPatIdent) {
                        renameParameter(function.project, currentParameter, parameter.patText)
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

private fun changeArguments(factory: RsPsiFactory, usage: RsElement, parameterOps: List<ParameterOperation>) {
    val arguments = when (usage) {
        is RsCallExpr -> usage.valueArgumentList
        is RsMethodCall -> usage.valueArgumentList
        else -> error("unreachable")
    }

    val argumentList = arguments.exprList.toList()
    val originalArguments = arguments.copy() as RsValueArgumentList

    cycle@ for ((index, op) in parameterOps.withIndex()) {
        when (op) {
            is ParameterOperation.Add -> {
                // We had an argument on this position, reset it
                // TODO: remove also surrounding whitespace and comments
                if (index < arguments.exprList.size) {
                    arguments.exprList[index].delete()
                } else if (arguments.exprList.isNotEmpty()) {
                    // This argument is new, add comma
                    arguments.addBefore(factory.createComma(), arguments.rparen ?: continue@cycle)
                }
            }
            is ParameterOperation.Move -> {
                deleteItem(argumentList, index)

                val originalArgument = originalArguments.exprList.getOrNull(op.originalIndex) ?: continue@cycle
                val anchor = findAnchorToInsertItem(arguments, index)
                insertItemWithComma(factory, originalArgument, arguments, anchor)
            }
        }
    }

    // Remove remaining arguments
    for (index in parameterOps.size until argumentList.size) {
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

private fun renameParameter(project: Project, parameter: RsValueParameter, newName: String) {
    val binding = parameter.pat?.findBinding() ?: return
    // TODO: how to run a refactoring inside another refactoring?
    RefactoringFactory.getInstance(project).createRename(binding, newName).run()
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
 */
private fun findAnchorToInsertItem(items: PsiElement, index: Int): PsiElement? {
    val commas = items.childrenWithLeaves.filter { it.elementType == COMMA }.toList()
    return when {
        index == 0 -> (items as? RsValueParameterList)?.selfParameter ?: items.firstChild
        commas.size < index -> items.lastChild.prevSibling
        else -> commas[index - 1]
    }
}

private fun deleteItem(originalList: List<RsElement>, index: Int) =
    originalList.getOrNull(index)?.deleteWithSurroundingCommaAndWhitespace()

/**
 * Builds a plan of operations that need to be performed for each original parameter/argument.
 */
private fun buildParameterOperations(config: RsChangeFunctionSignatureConfig): List<ParameterOperation> =
    config.parameters.withIndex().mapToMutableList { (index, parameter) ->
        val oldIndex = config.originalParameters.indexOf(parameter)

        when {
            oldIndex == -1 -> ParameterOperation.Add
            oldIndex != index -> ParameterOperation.Move(oldIndex)
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
