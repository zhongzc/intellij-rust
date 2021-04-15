/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.toml.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionType.BASIC
import org.rust.toml.CargoTomlPsiPattern.inDependencyKeyValue
import org.rust.toml.CargoTomlPsiPattern.inDependencyPackageFeatureArray
import org.rust.toml.CargoTomlPsiPattern.inFeatureDependencyArray
import org.rust.toml.CargoTomlPsiPattern.inKey
import org.rust.toml.CargoTomlPsiPattern.inSpecificDependencyHeaderKey
import org.rust.toml.CargoTomlPsiPattern.inSpecificDependencyKeyValue
import org.rust.toml.CargoTomlPsiPattern.inValueWithKey
import org.rust.toml.tomlPluginIsAbiCompatible

class CargoTomlCompletionContributor : CompletionContributor() {
    init {
        if (tomlPluginIsAbiCompatible()) {
            extend(BASIC, inKey, CargoTomlKeysCompletionProvider())
            extend(BASIC, inValueWithKey("edition"), CargoTomlKnownValuesCompletionProvider(listOf("2015", "2018")))
            extend(BASIC, inFeatureDependencyArray, CargoTomlFeatureDependencyCompletionProvider())
            extend(BASIC, inDependencyPackageFeatureArray, CargoTomlDependencyFeaturesCompletionProvider())

            // Available using crates local index
            extend(BASIC, inDependencyKeyValue, CargoTomlDependencyCompletionProvider())
            extend(BASIC, inSpecificDependencyHeaderKey, CargoTomlSpecificDependencyHeaderCompletionProvider())
            extend(BASIC, inSpecificDependencyKeyValue, CargoTomlSpecificDependencyVersionCompletionProvider())
        }
    }
}
