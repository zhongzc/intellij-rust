/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring

import org.intellij.lang.annotations.Language
import org.rust.MockAdditionalCfgOptions
import org.rust.MockEdition
import org.rust.RsTestBase
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.ide.refactoring.changeSignature.Parameter
import org.rust.ide.refactoring.changeSignature.RsChangeFunctionSignatureConfig
import org.rust.ide.refactoring.changeSignature.withMockChangeFunctionSignature
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.RsElement

class RsChangeSignatureTest : RsTestBase() {
    @MockAdditionalCfgOptions("intellij_rust")
    fun `test unavailable if a parameter is cfg-disabled`() = checkError("""
        fn foo/*caret*/(#[cfg(not(intellij_rust))] a: u32) {}
    """, """Cannot perform refactoring.
Cannot change signature of function with cfg-disabled parameters""")

    @MockAdditionalCfgOptions("intellij_rust")
    fun `test available if a parameter is cfg-enabled`() = doTest("""
        fn foo/*caret*/(#[cfg(intellij_rust)] a: u32) {}
    """, """
        fn bar(#[cfg(intellij_rust)] a: u32) {}
    """) {
        name = "bar"
    }

    fun `test do not change anything`() = doTest("""
        async unsafe fn foo/*caret*/(a: u32, b: bool) -> u32 { 0 }
        fn bar() {
            unsafe { foo(1, true); }
        }
    """, """
        async unsafe fn foo(a: u32, b: bool) -> u32 { 0 }
        fn bar() {
            unsafe { foo(1, true); }
        }
    """) {}

    fun `test rename function reference`() = doTest("""
        fn foo/*caret*/() {}
        fn id<T>(t: T) {}

        fn baz() {
            id(foo)
        }
    """, """
        fn bar/*caret*/() {}
        fn id<T>(t: T) {}

        fn baz() {
            id(bar)
        }
    """) {
        name = "bar"
    }

    fun `test rename function import`() = doTest("""
        mod bar {
            pub fn foo/*caret*/() {}
        }
        use bar::{foo};
    """, """
        mod bar {
            pub fn baz/*caret*/() {}
        }
        use bar::{baz};
    """) {
        name = "baz"
    }

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
        returnTypeDisplay = createType("u32")
    }

    fun `test change return type lifetime`() = doTest("""
        fn foo<'a, 'b>/*caret*/(a: &'a u32, b: &'b u32) -> &'a i32 { 0 }
    """, """
        fn foo<'a, 'b>(a: &'a u32, b: &'b u32) -> &'b i32 { 0 }
    """) {
        returnTypeDisplay = createType("&'b i32")
    }

    fun `test add return type`() = doTest("""
        fn foo/*caret*/() {}
    """, """
        fn foo() -> u32 {}
    """) {
        returnTypeDisplay = createType("u32")
    }

    fun `test add return type with lifetime`() = doTest("""
        fn foo/*caret*/<'a>(a: &'a u32) { a }
                          //^
    """, """
        fn foo/*caret*/<'a>(a: &'a u32) -> &'a u32 { a }
                          //^
    """) {
        returnTypeDisplay = createType("&'a u32")
    }

    fun `test add return type with default type arguments`() = doTest("""
        struct S<T, R=u32>(T, R);
        fn foo/*caret*/(s: S<bool>) { unimplemented!() }
                      //^
    """, """
        struct S<T, R=u32>(T, R);
        fn foo/*caret*/(s: S<bool>) -> S<bool> { unimplemented!() }
                      //^
    """) {
        val parameter = findElementInEditor<RsValueParameter>()
        returnTypeDisplay = parameter.typeReference!!
    }

    fun `test remove return type`() = doTest("""
        fn foo/*caret*/() -> u32 { 0 }
    """, """
        fn foo() { 0 }
    """) {
        returnTypeDisplay = createType("()")
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
        returnTypeDisplay = createType("u32")
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
        parameters.add(Parameter(createPat("a"), createType("u32")))
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
        parameters.add(Parameter(createPat("b"), createType("u32")))
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
        parameters.add(Parameter(createPat("b"), createType("u32")))
        parameters.add(Parameter(createPat("c"), createType("u32")))
    }

    fun `test add parameter with lifetime`() = doTest("""
        fn foo/*caret*/<'a>(a: &'a u32) {}
                          //^
    """, """
        fn foo/*caret*/<'a>(a: &'a u32, b: &'a u32) {}
                          //^
    """) {
        val parameter = findElementInEditor<RsValueParameter>()
        parameters.add(Parameter(createPat("b"), parameter.typeReference!!))
    }

    fun `test add parameter with default type arguments`() = doTest("""
        struct S<T, R=u32>(T, R);
        fn foo/*caret*/(a: S<bool>) { unimplemented!() }
                      //^
    """, """
        struct S<T, R=u32>(T, R);
        fn foo/*caret*/(a: S<bool>, b: S<bool>) { unimplemented!() }
                      //^
    """) {
        val parameter = findElementInEditor<RsValueParameter>()
        parameters.add(Parameter(createPat("b"), parameter.typeReference!!))
    }

    fun `test add parameter to method`() = doTest("""
        struct S;
        impl S {
            fn foo/*caret*/(&self) {}
        }
        fn bar(s: S) {
            s.foo();
        }
    """, """
        struct S;
        impl S {
            fn foo/*caret*/(&self, a: u32) {}
        }
        fn bar(s: S) {
            s.foo();
        }
    """) {
        parameters.add(Parameter(createPat("a"), createType("u32")))
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

    fun `test swap method parameters`() = doTest("""
        struct S;
        impl S {
            fn foo/*caret*/(&self, a: u32, b: u32) {}
        }
        fn bar(s: S) {
            s.foo(0, 1);
        }
    """, """
        struct S;
        impl S {
            fn foo/*caret*/(&self, b: u32, a: u32) {}
        }
        fn bar(s: S) {
            s.foo(1, 0);
        }
    """) {
        swapParameters(0, 1)
    }

    fun `test swap method parameters UFCS`() = doTest("""
        struct S;
        impl S {
            fn foo/*caret*/(&self, a: u32, b: u32) {}
        }
        fn bar(s: S) {
            S::foo(&s, 0, 1);
        }
    """, """
        struct S;
        impl S {
            fn foo/*caret*/(&self, b: u32, a: u32) {}
        }
        fn bar(s: S) {
            S::foo(&s, 1, 0);
        }
    """) {
        swapParameters(0, 1)
    }

    fun `test add method parameter UFCS`() = doTest("""
        struct S;
        impl S {
            fn foo/*caret*/(&self) {}
        }
        fn bar(s: S) {
            S::foo(&s);
        }
    """, """
        struct S;
        impl S {
            fn foo/*caret*/(&self, a: u32) {}
        }
        fn bar(s: S) {
            S::foo(&s, );
        }
    """) {
        parameters.add(Parameter(createPat("a"), createType("u32")))
    }

    fun `test delete method parameter UFCS`() = doTest("""
        struct S;
        impl S {
            fn foo/*caret*/(&self, a: u32, b: u32) {}
        }
        fn bar(s: S) {
            S::foo(&s, 0, 1);
        }
    """, """
        struct S;
        impl S {
            fn foo/*caret*/(&self, b: u32) {}
        }
        fn bar(s: S) {
            S::foo(&s, 1);
        }
    """) {
        parameters.removeAt(0)
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
        parameters[1] = Parameter(createPat("a"), createType("u32"))
    }

    fun `test rename parameter ident with ident`() = doTest("""
        fn foo/*caret*/(a: u32) {
            let _ = a;
            let _ = a + 1;
        }
    """, """
        fn foo(b: u32) {
            let _ = b;
            let _ = b + 1;
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
        parameters[0].changeType(createType("i32"))
    }

    /*fun `test change trait impls`() = doTest("""
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
    }*/

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

    /*@MockEdition(CargoWorkspace.Edition.EDITION_2018)
    fun `test import return type in different module`() = doTest("""
        mod foo {
            pub struct S;
                     //^
            pub trait Trait {
                fn f1/*caret*/(&self);
            }
        }
        mod bar {
            use super::foo::Trait;

            struct T;
            impl Trait for T {
                fn f1(&self) { unimplemented!() }
            }
        }
    """, """
        mod foo {
            pub struct S;
                     //^
            pub trait Trait {
                fn f1(&self) -> S;
            }
        }
        mod bar {
            use super::foo::Trait;
            use crate::foo::S;

            struct T;
            impl Trait for T {
                fn f1(&self) -> S { unimplemented!() }
            }
        }
    """) {
        returnTypeDisplay = referToType("S", findElementInEditor<RsStructItem>())
    }

    @MockEdition(CargoWorkspace.Edition.EDITION_2018)
    fun `test import parameter type in different module`() = doTest("""
        mod foo {
            pub struct S;
                     //^
            pub trait Trait {
                fn f1/*caret*/(&self);
            }
        }
        mod bar {
            use super::foo::Trait;

            struct T;
            impl Trait for T {
                fn f1(&self) { unimplemented!() }
            }
        }
    """, """
        mod foo {
            pub struct S;
                     //^
            pub trait Trait {
                fn f1(&self, a: S);
            }
        }
        mod bar {
            use super::foo::Trait;
            use crate::foo::S;

            struct T;
            impl Trait for T {
                fn f1(&self, a: S) { unimplemented!() }
            }
        }
    """) {
        parameters.add(Parameter(createPat("a"), referToType("S", findElementInEditor<RsStructItem>())))
    }*/

    private fun RsChangeFunctionSignatureConfig.swapParameters(a: Int, b: Int) {
        val param = parameters[a]
        parameters[a] = parameters[b]
        parameters[b] = param
    }

    private fun RsChangeFunctionSignatureConfig.setVisibility(vis: String) {
        visibility = RsPsiFactory(project).createVis(vis)
    }

    private fun createPat(text: String): RsPat = RsPsiFactory(project).createPat(text)
    private fun createType(text: String): RsTypeReference = RsPsiFactory(project).createType(text)

    /**
     * Refer to existing type in the test code snippet.
     */
    private fun referToType(text: String, context: RsElement): RsTypeReference
        = RsTypeReferenceCodeFragment(myFixture.project, text, context).typeReference!!

    private fun doTest(
        @Language("Rust") code: String,
        @Language("Rust") excepted: String,
        modifyConfig: RsChangeFunctionSignatureConfig.() -> Unit
    ) {
        withMockChangeFunctionSignature({ config ->
            modifyConfig.invoke(config)
        }) {
            checkEditorAction(code, excepted, "ChangeSignature", trimIndent = false)
        }
    }

    private fun checkError(@Language("Rust") code: String, errorMessage: String) {
        try {
            checkEditorAction(code, code, "ChangeSignature")
            error("no error found, expected $errorMessage")
        } catch (e: Exception) {
            assertEquals(errorMessage, e.message)
        }
    }
}
