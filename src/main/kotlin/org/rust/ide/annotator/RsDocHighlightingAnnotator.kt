/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.annotator

import com.intellij.ide.annotator.AnnotatorBase
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapiext.isUnitTestMode
import com.intellij.psi.PsiElement
import org.rust.cargo.project.settings.rustSettings
import org.rust.ide.colors.RsColor
import org.rust.ide.injected.findDoctestInjectableRanges
import org.rust.lang.core.psi.ext.containingCrate
import org.rust.lang.core.psi.ext.descendantOfTypeStrict
import org.rust.lang.core.psi.ext.elementType
import org.rust.lang.doc.psi.*

class RsDocHighlightingAnnotator : AnnotatorBase() {
    override fun annotateInternal(element: PsiElement, holder: AnnotationHolder) {
        if (holder.isBatchMode) return
        val color = when {
            element.elementType == RsDocElementTypes.DOC_DATA -> when (val parent = element.parent) {
                is RsDocCodeFence -> when {
                    parent.isDoctestInjected -> null
                    else -> RsColor.DOC_CODE
                }
                is RsDocCodeFenceStartEnd -> RsColor.DOC_CODE
                is RsDocCodeSpan -> when (parent.parent) {
                    is RsDocLinkText, is RsDocLinkLabel -> null
                    else -> RsColor.DOC_CODE
                }
                is RsDocCodeBlock, is RsDocCodeFenceLang -> RsColor.DOC_CODE
                is RsDocAtxHeading -> RsColor.DOC_HEADING
                else -> null
            }
            element is RsDocLink && element.descendantOfTypeStrict<RsDocGap>() == null -> RsColor.DOC_LINK
            else -> null
        } ?: return

        val severity = if (isUnitTestMode) color.testSeverity else HighlightSeverity.INFORMATION

        holder.newSilentAnnotation(severity).textAttributes(color.textAttributesKey).create()
    }

    private val RsDocCodeFence.isDoctestInjected: Boolean
        get() {
            if (!project.rustSettings.doctestInjectionEnabled) return false
            if (containingDoc.owner?.containingCrate?.areDoctestsEnabled != true) return false
            return findDoctestInjectableRanges(this).isNotEmpty()
        }
}
