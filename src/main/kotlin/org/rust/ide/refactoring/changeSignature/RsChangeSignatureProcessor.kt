/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring.changeSignature

import com.intellij.openapi.project.Project
import com.intellij.refactoring.changeSignature.ChangeInfo
import com.intellij.refactoring.changeSignature.ChangeSignatureProcessorBase
import com.intellij.usageView.BaseUsageViewDescriptor
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewDescriptor

/**
 * The main implementation resides in [RsChangeSignatureUsageProcessor].
 */
class RsChangeSignatureProcessor(project: Project, changeInfo: ChangeInfo)
    : ChangeSignatureProcessorBase(project, changeInfo) {
    override fun createUsageViewDescriptor(usages: Array<out UsageInfo>?): UsageViewDescriptor =
        BaseUsageViewDescriptor(changeInfo.method)
}
