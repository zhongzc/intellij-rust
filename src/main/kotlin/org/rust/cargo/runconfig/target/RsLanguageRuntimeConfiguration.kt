/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.runconfig.target

import com.intellij.execution.target.LanguageRuntimeConfiguration
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.PersistentStateComponent

class RsLanguageRuntimeConfiguration : LanguageRuntimeConfiguration(RsLanguageRuntimeType.TYPE_ID),
                                       PersistentStateComponent<RsLanguageRuntimeConfiguration.MyState> {
    var cargoPath: String = ""

    override fun getState() = MyState().also {
        it.cargoPath = this.cargoPath
    }

    override fun loadState(state: MyState) {
        this.cargoPath = state.cargoPath ?: ""
    }

    class MyState : BaseState() {
        var cargoPath by string()
    }
}
