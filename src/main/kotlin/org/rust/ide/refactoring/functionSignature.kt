/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring

import org.rust.ide.refactoring.extractFunction.dependTypes
import org.rust.ide.refactoring.extractFunction.types
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.RsTypeParameter
import org.rust.lang.core.psi.ext.bounds
import org.rust.lang.core.psi.ext.typeArguments
import org.rust.lang.core.psi.ext.typeParameters
import org.rust.lang.core.types.ty.Ty
import org.rust.lang.core.types.type

/**
 * Helper class for storing and formatting information about the signature of a function.
 */
abstract class RsFunctionSignatureConfig(val function: RsFunction) {
    protected abstract val parameterTypes: List<Ty>
    abstract val returnType: Ty

    protected val typeParametersText: String
        get() {
            val typeParams = typeParameters()
            if (typeParams.isEmpty()) return ""
            return typeParams.joinToString(separator = ",", prefix = "<", postfix = ">") { it.text }
        }

    protected val whereClausesText: String
        get() {
            val wherePredList = function.whereClause?.wherePredList ?: return ""
            if (wherePredList.isEmpty()) return ""
            val typeParams = typeParameters().map { it.declaredType }
            if (typeParams.isEmpty()) return ""
            val filtered = wherePredList.filter { it.typeReference?.type in typeParams }
            if (filtered.isEmpty()) return ""
            return filtered.joinToString(separator = ",", prefix = " where ") { it.text }
        }

    private fun typeParameterBounds(): Map<Ty, Set<Ty>> =
        function.typeParameters.associate { typeParameter ->
            val type = typeParameter.declaredType
            val bounds = mutableSetOf<Ty>()
            typeParameter.bounds.flatMapTo(bounds) {
                it.bound.traitRef?.path?.typeArguments?.flatMap { it.type.types() }.orEmpty()
            }
            type to bounds
        }

    protected fun typeParameters(): List<RsTypeParameter> {
        val bounds = typeParameterBounds()
        val paramAndReturnTypes = mutableSetOf<Ty>()
        (parameterTypes + returnType).forEach {
            paramAndReturnTypes.addAll(it.types())
            paramAndReturnTypes.addAll(it.dependTypes(bounds))
        }
        return function.typeParameters.filter { it.declaredType in paramAndReturnTypes }
    }
}
