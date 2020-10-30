/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring

import org.intellij.lang.annotations.Language
import org.rust.RsTestBase
import org.rust.ide.refactoring.changeSignature.Parameter
import org.rust.ide.refactoring.changeSignature.RsFunctionSignatureConfig
import org.rust.ide.refactoring.changeSignature.withMockChangeFunctionSignature
import org.rust.lang.core.psi.RsPat
import org.rust.lang.core.psi.RsPsiFactory
import org.rust.lang.core.types.ty.TyInteger
import org.rust.lang.core.types.ty.TyUnit

class RsChangeSignatureTest : RsTestBase() {
    fun `test rename function`() = doTest("""
        fn foo/*caret*/() {}
    """, """
        fn bar() {}
    """) {
        name = "bar"
    }

    fun `test rename function change usage`() = doTest("""
        fn foo/*caret*/() {}
        fn test() {
            foo()
        }
    """, """
        fn bar() {}
        fn test() {
            bar()
        }
    """) {
        name = "bar"
    }

    fun `test rename function change complex path usage`() = doTest("""
        mod inner {
            pub fn foo/*caret*/() {}
        }
        fn test() {
            inner::foo()
        }
    """, """
        mod inner {
            pub fn bar() {}
        }
        fn test() {
            inner::bar()
        }
    """) {
        name = "bar"
    }

    fun `test rename method change usage`() = doTest("""
        struct S;
        impl S {
            fn foo/*caret*/(&self) {}
        }

        fn test(s: S) {
            s.foo();
        }
    """, """
        struct S;
        impl S {
            fn bar(&self) {}
        }

        fn test(s: S) {
            s.bar();
        }
    """) {
        name = "bar"
    }

    fun `test change visibility`() = doTest("""
        pub fn foo/*caret*/() {}
    """, """
        pub(crate) fn foo() {}
    """) {
        setVisibility("pub(crate)")
    }

    fun `test remove visibility`() = doTest("""
        pub fn foo/*caret*/() {}
    """, """
        fn foo() {}
    """) {
        visibility = null
    }

    fun `test change return type`() = doTest("""
        fn foo/*caret*/() -> i32 { 0 }
    """, """
        fn foo() -> u32 { 0 }
    """) {
        returnType = TyInteger.U32
    }

    fun `test add return type`() = doTest("""
        fn foo/*caret*/() {}
    """, """
        fn foo() -> u32 {}
    """) {
        returnType = TyInteger.U32
    }

    fun `test remove return type`() = doTest("""
        fn foo/*caret*/() -> u32 { 0 }
    """, """
        fn foo() { 0 }
    """) {
        returnType = TyUnit
    }

    fun `test remove return type without block`() = doTest("""
        trait Trait {
            fn foo/*caret*/() -> i32;
        }
    """, """
        trait Trait {
            fn foo() -> u32;
        }
    """) {
        returnType = TyInteger.U32
    }

    fun `test remove only parameter`() = doTest("""
        fn foo/*caret*/(a: u32) {
            let c = a;
        }
        fn bar() {
            foo(0);
        }
    """, """
        fn foo() {
            let c = a;
        }
        fn bar() {
            foo();
        }
    """) {
        parameters.removeAt(0)
    }

    fun `test remove first parameter`() = doTest("""
        fn foo/*caret*/(a: u32, b: u32) {
            let c = a;
        }
        fn bar() {
            foo(0, 1);
        }
    """, """
        fn foo(b: u32) {
            let c = a;
        }
        fn bar() {
            foo(1);
        }
    """) {
        parameters.removeAt(0)
    }

    fun `test remove middle parameter`() = doTest("""
        fn foo/*caret*/(a: u32, b: u32, c: u32) {
            let c = a;
        }
        fn bar() {
            foo(0, 1, 2);
        }
    """, """
        fn foo(a: u32, c: u32) {
            let c = a;
        }
        fn bar() {
            foo(0, 2);
        }
    """) {
        parameters.removeAt(1)
    }

    fun `test remove last parameter`() = doTest("""
        fn foo/*caret*/(a: u32, b: u32) {}
        fn bar() {
            foo(0, 1);
        }
    """, """
        fn foo(a: u32) {}
        fn bar() {
            foo(0);
        }
    """) {
        parameters.removeAt(parameters.size - 1)
    }

    fun `test remove last parameter trailing comma`() = doTest("""
        fn foo/*caret*/(a: u32,) {}
    """, """
        fn foo() {}
    """) {
        parameters.clear()
    }

    fun `test add only parameter`() = doTest("""
        fn foo/*caret*/() {}
        fn bar() {
            foo();
        }
    """, """
        fn foo(a: u32) {}
        fn bar() {
            foo();
        }
    """) {
        parameters.add(Parameter(createPat("a"), TyInteger.U32))
    }

    fun `test add last parameter`() = doTest("""
        fn foo/*caret*/(a: u32) {}
        fn bar() {
            foo(0);
        }
    """, """
        fn foo(a: u32, b: u32) {}
        fn bar() {
            foo(0, );
        }
    """) {
        parameters.add(Parameter(createPat("b"), TyInteger.U32))
    }

    fun `test add multiple parameters`() = doTest("""
        fn foo/*caret*/(a: u32) {}
        fn bar() {
            foo(0);
        }
    """, """
        fn foo(a: u32, b: u32, c: u32) {}
        fn bar() {
            foo(0, , );
        }
    """) {
        parameters.add(Parameter(createPat("b"), TyInteger.U32))
        parameters.add(Parameter(createPat("c"), TyInteger.U32))
    }

    fun `test swap parameters`() = doTest("""
        fn foo/*caret*/(a: u32, b: u32) {}
        fn bar() {
            foo(0, 1);
        }
    """, """
        fn foo(b: u32, a: u32) {}
        fn bar() {
            foo(1, 0);
        }
    """) {
        swapParameters(0, 1)
    }

    /*fun `test swap parameters with comments`() = doTest("""
        fn foo/*caret*/( /*a0*/ a /*a1*/ : u32, /*b0*/ b: u32 /*b1*/) {}
        fn bar() {
            foo(0, 1);
        }
    """, """
        fn foo(/*b0*/ b: u32 /*b1*/,  /*a0*/ a /*a1*/ : u32) {}
        fn bar() {
            foo(1, 0);
        }
    """) {
        swapParameters(0, 1)
    }*/

    fun `test multiple move`() = doTest("""
        fn foo/*caret*/(a: u32, b: u32, c: u32) {}
        fn bar() {
            foo(0, 1, 2);
        }
    """, """
        fn foo(b: u32, c: u32, a: u32) {}
        fn bar() {
            foo(1, 2, 0);
        }
    """) {
        swapParameters(0, 1)
        swapParameters(1, 2)
    }

    fun `test move and add parameter`() = doTest("""
        fn foo/*caret*/(a: u32, b: u32) {}
        fn bar() {
            foo(0, 1);
        }
    """, """
        fn foo(b: u32, a: u32) {}
        fn bar() {
            foo(1, );
        }
    """) {
        parameters[0] = parameters[1]
        parameters[1] = Parameter(createPat("a"), TyInteger.U32)
    }

    fun `test rename parameter ident with ident`() = doTest("""
        fn foo/*caret*/(a: u32) {
            let _ = a;
        }
    """, """
        fn foo(b: u32) {
            let _ = b;
        }
    """) {
        parameters[0].pat = createPat("b")
    }

    fun `test rename parameter complex pat with ident`() = doTest("""
        fn foo/*caret*/((a, b): (u32, u32)) {
            let _ = a;
        }
    """, """
        fn foo(x: (u32, u32)) {
            let _ = a;
        }
    """) {
        parameters[0].pat = createPat("x")
    }

    fun `test rename parameter ident with complex pat`() = doTest("""
        fn foo/*caret*/(a: (u32, u32)) {
            let _ = a;
        }
    """, """
        fn foo((x, y): (u32, u32)) {
            let _ = a;
        }
    """) {
        parameters[0].pat = createPat("(x, y)")
    }

    fun `test change parameter type`() = doTest("""
        fn foo/*caret*/(a: u32) {}
    """, """
        fn foo(a: i32) {}
    """) {
        parameters[0].type = TyInteger.I32
    }

    fun `test change trait impls`() = doTest("""
        trait Trait {
            fn foo/*caret*/(&self);
        }
        struct S;
        impl Trait for S {
            fn foo(&self) {}
        }
        fn test(s: S) {
            s.foo();
        }
    """, """
        trait Trait {
            fn bar/*caret*/(&self);
        }
        struct S;
        impl Trait for S {
            fn bar(&self) {}
        }
        fn test(s: S) {
            s.bar();
        }
    """) {
        name = "bar"
    }

    fun `test add async`() = doTest("""
        fn foo/*caret*/(a: u32) {}
    """, """
        async fn foo(a: u32) {}
    """) {
        isAsync = true
    }

    fun `test remove async`() = doTest("""
        async fn foo/*caret*/(a: u32) {}
    """, """
        fn foo(a: u32) {}
    """) {
        isAsync = false
    }

    fun `test add unsafe`() = doTest("""
        fn foo/*caret*/(a: u32) {}
    """, """
        unsafe fn foo(a: u32) {}
    """) {
        isUnsafe = true
    }

    fun `test remove unsafe`() = doTest("""
        unsafe fn foo/*caret*/(a: u32) {}
    """, """
        fn foo(a: u32) {}
    """) {
        isUnsafe = false
    }

    fun `test add async unsafe and visibility`() = doTest("""
        fn foo/*caret*/(a: u32) {}
    """, """
        pub async unsafe fn foo(a: u32) {}
    """) {
        isAsync = true
        isUnsafe = true
        setVisibility("pub")
    }

    private fun RsFunctionSignatureConfig.swapParameters(a: Int, b: Int) {
        val param = parameters[a]
        parameters[a] = parameters[b]
        parameters[b] = param
    }

    private fun RsFunctionSignatureConfig.setVisibility(vis: String) {
        visibility = RsPsiFactory(project).createVis(vis)
    }

    private fun createPat(text: String): RsPat {
        return RsPsiFactory(project).createPat(text)
    }

    private fun doTest(@Language("Rust") code: String,
                       @Language("Rust") excepted: String,
                       modifyConfig: RsFunctionSignatureConfig.() -> Unit) {
        withMockChangeFunctionSignature({ config ->
            modifyConfig.invoke(config)
        }) {
            checkEditorAction(code, excepted, "ChangeSignature", trimIndent = false)
        }
    }
}
