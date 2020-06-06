/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring.move

import org.rust.MockEdition
import org.rust.ProjectDescriptor
import org.rust.WithStdlibRustProjectDescriptor
import org.rust.cargo.project.workspace.CargoWorkspace

@MockEdition(CargoWorkspace.Edition.EDITION_2018)
class RsMoveTopLevelItemsTest : RsMoveTopLevelItemsTestBase() {

    fun `test simple`() = doTest("""
    //- main.rs
        mod mod1 {
            fn foo/*caret*/() {}
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            fn foo() {}
        }
    """)

    fun `test item with same name exists in new mod`() = doTestConflictsError("""
    //- main.rs
        mod mod1 {
            fn foo/*caret*/() {}
        }
        mod mod2/*target*/ {
            fn foo() {}
        }
    """)

    fun `test outside reference to private item of old mod`() = doTestConflictsError("""
    //- main.rs
        mod mod1 {
            fn foo/*caret*/() { bar(); }
            fn bar() {}
        }
        mod mod2/*target*/ {}
    """)

    fun `test outside reference to private method of struct in old mod using UFCS`() = doTestConflictsError("""
    //- main.rs
        mod mod1 {
            fn foo/*caret*/() { Bar::bar(); }
            pub struct Bar {}
            impl Bar {
                fn bar() {}  // private
            }
        }
        mod mod2/*target*/ {}
    """)

    fun `test outside reference to public method of struct in old mod using UFCS`() = doTest("""
    //- main.rs
        mod mod1 {
            fn foo/*caret*/() { Bar::bar(); }
            pub struct Bar {}
            impl Bar {
                pub fn bar() {}  // public
            }
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {
            pub struct Bar {}
            impl Bar {
                pub fn bar() {}  // public
            }
        }
        mod mod2 {
            use crate::mod1::Bar;

            fn foo() { Bar::bar(); }
        }
    """)

    fun `test inside reference, when new mod is private`() = doTestConflictsError("""
    //- main.rs
        mod mod1 {
            fn bar() { foo(); }
            fn foo/*caret*/() {}
        }
        mod inner {
            // private
            mod mod2/*target*/ {}
        }
    """)

    fun `test move inherent impl without struct to different crate 1`() = doTestConflictsError("""
    //- lib.rs
        pub struct Foo {}
        impl Foo/*caret*/ {}
    //- main.rs
        pub mod mod2/*target*/ {}
    """)

    fun `test move inherent impl without struct to different crate 2`() = doTestConflictsError("""
    //- lib.rs
        pub mod foo1 {
            pub struct Foo {}
        }
        pub mod foo2/*caret*/ {
            use crate::foo1::Foo;
            impl Foo {}
        }
    //- main.rs
        pub mod mod2/*target*/ {}
    """)

    fun `test move inherent impl without struct to same crate`() = doTest("""
    //- main.rs
        mod mod1 {
            pub struct Foo {}
            impl Foo/*caret*/ {}
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {
            pub struct Foo {}
        }
        mod mod2 {
            use crate::mod1::Foo;

            impl Foo {}
        }
    """)

    fun `test move struct without inherent impl to different crate 1`() = doTestConflictsError("""
    //- main.rs
        pub struct Foo/*caret*/ {}
        impl Foo {}
    //- lib.rs
        pub mod mod2/*target*/ {}
    """)

    fun `test move struct without inherent impl to different crate 2`() = doTestConflictsError("""
    //- main.rs
        pub mod foo1/*caret*/ {
            pub struct Foo {}
        }
        pub mod foo2 {
            use crate::foo1::Foo;
            impl Foo {}
        }
    //- lib.rs
        pub mod mod2/*target*/ {}
    """)

    fun `test move struct without inherent impl to same crate`() = doTest("""
    //- main.rs
        mod mod1 {
            struct Foo/*caret*/ {}
            impl Foo {}
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {
            use crate::mod2::Foo;

            impl Foo {}
        }
        mod mod2 {
            pub struct Foo {}
        }
    """)

    fun `test move struct with inherent impl to different crate`() = doTest("""
    //- main.rs
        mod mod1 {
            struct Foo/*caret*/ {}
            impl Foo/*caret*/ {}
        }
    //- lib.rs
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {}
    //- lib.rs
        mod mod2 {
            struct Foo {}

            impl Foo {}
        }
    """)

    fun `test move trait impl without trait to different crate 1`() = doTestConflictsError("""
    //- lib.rs
        pub trait Foo {}
        impl Foo for ()/*caret*/ {}
    //- main.rs
        pub mod mod2/*target*/ {}
    """)

    fun `test move trait impl without trait to different crate 2`() = doTestConflictsError("""
    //- lib.rs
        pub mod mod1 {
            pub trait Foo {}
            impl Foo for Bar/*caret*/ {}
            pub struct Bar {}
        }
    //- main.rs
        pub mod mod2/*target*/ {}
    """)

    fun `test move trait impl without trait to different crate 3`() = doTestConflictsError("""
    //- lib.rs
        pub mod mod1 {
            pub trait Foo<T> {}
            impl Foo<Bar2> for Bar1/*caret*/ {}
            pub struct Bar1 {}
            pub struct Bar2 {}
        }
    //- main.rs
        pub mod mod2/*target*/ {}
    """)

    fun `test move trait impl with implementing type to different crate 1`() = doTestNoConflicts("""
    //- lib.rs
        pub mod mod1 {
            pub trait Foo {}
            impl Foo for Bar/*caret*/ {}
            pub struct Bar/*caret*/ {}
        }
    //- main.rs
        pub mod mod2/*target*/ {}
    """)

    fun `test move trait impl with implementing type to different crate 2`() = doTestNoConflicts("""
    //- lib.rs
        pub mod mod1 {
            pub trait Foo<T> {}
            impl Foo<Bar> for ()/*caret*/ {}
            pub struct Bar/*caret*/ {}
        }
    //- main.rs
        pub mod mod2/*target*/ {}
    """)

    fun `test move trait with trait impl to different crate`() = doTestNoConflicts("""
    //- lib.rs
        pub mod mod1 {
            pub trait Foo/*caret*/ {}
            impl Foo for ()/*caret*/ {}
        }
    //- main.rs
        pub mod mod2/*target*/ {}
    """)

    // todo add `pub` to fields too ?
    fun `test add pub to moved items if necessary`() = doTest("""
    //- main.rs
        mod mod1 {
            mod foo1/*caret*/ { pub fn foo1_func() {} }
            fn foo2/*caret*/() {}
            struct Foo3/*caret*/ {}
            struct Foo4/*caret*/ { pub field: i32 }
            struct Foo5/*caret*/ { pub field: i32 }
            struct Foo6/*caret*/(pub i32);
            struct Foo7/*caret*/(pub i32);
            fn bar() {
                foo1::foo1_func();
                foo2();
                let _ = Foo3 {};
                let _ = Foo4 { field: 0 };
                let Foo5 { field: _ } = None.unwrap();
                let _ = Foo6(0);
                let Foo7(_) = None.unwrap();
            }
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {
            use crate::mod2::{foo1, Foo3, Foo4, Foo5, Foo6, Foo7};
            use crate::mod2;

            fn bar() {
                foo1::foo1_func();
                mod2::foo2();
                let _ = Foo3 {};
                let _ = Foo4 { field: 0 };
                let Foo5 { field: _ } = None.unwrap();
                let _ = Foo6(0);
                let Foo7(_) = None.unwrap();
            }
        }
        mod mod2 {
            pub mod foo1 { pub fn foo1_func() {} }

            pub fn foo2() {}

            pub struct Foo3 {}

            pub struct Foo4 { pub field: i32 }

            pub struct Foo5 { pub field: i32 }

            pub struct Foo6(pub i32);

            pub struct Foo7(pub i32);
        }
    """)

    fun `test add pub to moved items if necessary when items has doc comments`() = doTest("""
    //- main.rs
        mod mod1 {
            // comment 1
            #[attr1]
            fn foo1/*caret*/() {}
            /// comment 2
            #[attr2]
            struct Foo2/*caret*/ {}
            fn bar() {
                foo1();
                let _ = Foo2 {};
            }
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {
            use crate::mod2;
            use crate::mod2::Foo2;

            fn bar() {
                mod2::foo1();
                let _ = Foo2 {};
            }
        }
        mod mod2 {
            // comment 1
            #[attr1]
            pub fn foo1() {}

            /// comment 2
            #[attr2]
            pub struct Foo2 {}
        }
    """)

    fun `test add pub to items in old module if necessary`() = doTestIgnore("""
    //- main.rs
        mod mod1 {
            fn foo/*caret*/() {
                bar1::bar1_func();
                bar2();
                let _ = Bar3 {};
                let _ = Bar4 { field: 0 };
                let Bar5 { field: _ } = None.unwrap();
                let _ = Bar6(0);
                let Bar7(_) = None.unwrap();
            }
            mod bar1 { pub fn bar1_func() {} }
            fn bar2() {}
            struct Bar3 {}
            struct Bar4 { pub field: i32 }
            struct Bar5 { pub field: i32 }
            struct Bar6(pub i32);
            struct Bar7(pub i32);
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {
            pub mod bar1 { pub fn bar1_func() {} }
            pub fn bar2() {}
            pub struct Bar3 {}
            pub struct Bar4 { pub field: i32 }
            pub struct Bar5 { pub field: i32 }
            pub struct Bar6(pub i32);
            pub struct Bar7(pub i32);
        }
        mod mod2 {
            fn foo() {
                bar1::bar1_func();
                bar2();
                let _ = Bar3 {};
                let _ = Bar4 { field: 0 };
                let Bar5 { field: _ } = None.unwrap();
                let _ = Bar6(0);
                let Bar7(_) = None.unwrap();
            }
        }
    """)

    fun `test change scope for pub(in) visibility`() = doTest("""
    //- main.rs
        mod inner1 {
            mod inner2 {
                mod inner3 {
                    mod mod1 {
                        pub(crate) fn foo1/*caret*/() {}
                        pub(self) fn foo2/*caret*/() {}
                        pub(super) fn foo3/*caret*/() {}
                        pub(in super::super) fn foo4/*caret*/() {}
                        pub(in crate::inner1) fn foo5/*caret*/() {}
                    }
                }
                mod mod2/*target*/ {}
            }
        }
    """, """
    //- main.rs
        mod inner1 {
            mod inner2 {
                mod inner3 {
                    mod mod1 {}
                }
                mod mod2 {
                    pub(crate) fn foo1() {}

                    pub(in crate::inner1::inner2) fn foo2() {}

                    pub(in crate::inner1::inner2) fn foo3() {}

                    pub(in crate::inner1::inner2) fn foo4() {}

                    pub(in crate::inner1) fn foo5() {}
                }
            }
        }
    """)

    fun `test spaces 1`() = doTest("""
    //- main.rs
        mod mod1 {
            const C1/*caret*/: i32 = 0;
            const C2/*caret*/: i32 = 0;
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            const C1: i32 = 0;
            const C2: i32 = 0;
        }
    """)

    fun `test spaces 2`() = doTest("""
    //- main.rs
        mod mod1 {
            const C1/*caret*/: i32 = 0;
            const C2: i32 = 0;
            const C3/*caret*/: i32 = 0;
            const C4: i32 = 0;
        }
        mod mod2/*target*/ {
            const D1: i32 = 0;
            const D2: i32 = 0;
        }
    """, """
    //- main.rs
        mod mod1 {
            const C2: i32 = 0;
            const C4: i32 = 0;
        }
        mod mod2 {
            const D1: i32 = 0;
            const D2: i32 = 0;
            const C1: i32 = 0;
            const C3: i32 = 0;
        }
    """)

    // spaces after moved items are moved to new file
    fun `test spaces 3`() = doTest("""
    //- main.rs
        mod mod1 {
            const C1/*caret*/: i32 = 0;

            const C2: i32 = 0;
            const C3/*caret*/: i32 = 0;


            const C4: i32 = 0;
            const C5/*caret*/: i32 = 0;
        }
        mod mod2/*target*/ {
            const D1: i32 = 0;
        }
    """, """
    //- main.rs
        mod mod1 {
            const C2: i32 = 0;
            const C4: i32 = 0;
        }
        mod mod2 {
            const D1: i32 = 0;
            const C1: i32 = 0;

            const C3: i32 = 0;


            const C5: i32 = 0;
        }
    """)

    // spaces before moved items are kept in old file
    fun `test spaces 4`() = doTest("""
    //- main.rs
        mod mod1 {
            const C0: i32 = 0;

            const C1/*caret*/: i32 = 0;
            const C2: i32 = 0;


            const C3/*caret*/: i32 = 0;
            const C4: i32 = 0;
            const C5/*caret*/: i32 = 0;
        }
        mod mod2/*target*/ {
            const D1: i32 = 0;
        }
    """, """
    //- main.rs
        mod mod1 {
            const C0: i32 = 0;

            const C2: i32 = 0;


            const C4: i32 = 0;
        }
        mod mod2 {
            const D1: i32 = 0;
            const C1: i32 = 0;
            const C3: i32 = 0;
            const C5: i32 = 0;
        }
    """)

    fun `test move doc comments together with items`() = doTest("""
    //- main.rs
        mod mod1 {
            /// comment 1
            fn foo1/*caret*/() {}
            /// comment 2
            struct Foo2/*caret*/ {}
            /// comment 3
            impl Foo2/*caret*/ {}
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            /// comment 1
            fn foo1() {}

            /// comment 2
            struct Foo2 {}

            /// comment 3
            impl Foo2 {}
        }
    """)

    fun `test move usual comments together with items`() = doTest("""
    //- main.rs
        mod mod1 {
            // comment 1
            fn foo1/*caret*/() {}
            // comment 2
            struct Foo2/*caret*/ {}
            // comment 3
            impl Foo2/*caret*/ {}
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            // comment 1
            fn foo1() {}

            // comment 2
            struct Foo2 {}

            // comment 3
            impl Foo2 {}
        }
    """)

    fun `test move inherent impl together with struct`() = doTest("""
    //- main.rs
        mod mod1 {
            struct Foo/*caret*/ {}
            impl/*caret*/ Foo {}
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            struct Foo {}

            impl Foo {}
        }
    """)

    fun `test copy usual imports from old mod`() = doTest("""
    //- main.rs
        mod mod1 {
            use crate::bar;
            use crate::bar::BarStruct;
            fn foo/*caret*/() {
                bar::bar_func();
                let _ = BarStruct {};
            }
        }
        mod mod2/*target*/ {}
        mod bar {
            pub fn bar_func() {}
            pub struct BarStruct {}
        }
    """, """
    //- main.rs
        mod mod1 {
            use crate::bar;
            use crate::bar::BarStruct;
        }
        mod mod2 {
            use crate::bar;
            use crate::bar::BarStruct;

            fn foo() {
                bar::bar_func();
                let _ = BarStruct {};
            }
        }
        mod bar {
            pub fn bar_func() {}
            pub struct BarStruct {}
        }
    """)

    // it is idiomatic to import parent mod for functions, and directly import structs/enums
    // https://doc.rust-lang.org/book/ch07-04-bringing-paths-into-scope-with-the-use-keyword.html#creating-idiomatic-use-paths
    fun `test add usual imports for items in old mod`() = doTest("""
    //- main.rs
        mod mod1 {
            use inner::Bar4;
            fn foo/*caret*/() {
                bar1();
                let _ = Bar2 {};
                inner::bar3();
                let _ = Bar4 {};
            }
            pub mod inner {
                pub fn bar3() {}
                pub struct Bar4 {}
            }
            pub fn bar1() {}
            pub struct Bar2 {}
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {
            use inner::Bar4;

            pub mod inner {
                pub fn bar3() {}
                pub struct Bar4 {}
            }
            pub fn bar1() {}
            pub struct Bar2 {}
        }
        mod mod2 {
            use crate::mod1;
            use crate::mod1::{Bar2, inner};
            use crate::mod1::inner::Bar4;

            fn foo() {
                mod1::bar1();
                let _ = Bar2 {};
                inner::bar3();
                let _ = Bar4 {};
            }
        }
    """)

    fun `test outside reference to function in old mod when move from crate root`() = doTest("""
    //- main.rs
        fn foo/*caret*/() { bar(); }
        fn bar() {}
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        fn bar() {}
        mod mod2 {
            fn foo() { crate::bar(); }
        }
    """)

    fun `test copy trait imports from old mod`() = doTest("""
    //- main.rs
        mod mod1 {
            use crate::bar::Bar;
            fn foo/*caret*/() { ().bar_func(); }
        }
        mod mod2/*target*/ {}
        mod bar {
            pub trait Bar { fn bar_func(&self) {} }
            impl Bar for () {}
        }
    """, """
    //- main.rs
        mod mod1 {
            use crate::bar::Bar;
        }
        mod mod2 {
            use crate::bar::Bar;

            fn foo() { ().bar_func(); }
        }
        mod bar {
            pub trait Bar { fn bar_func(&self) {} }
            impl Bar for () {}
        }
    """)

    fun `test outside reference to method of trait in old mod`() = doTest("""
    //- main.rs
        mod mod1 {
            fn foo/*caret*/() { ().bar(); }
            pub trait Bar { fn bar(&self) {} }
            impl Bar for () {}
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {
            pub trait Bar { fn bar(&self) {} }
            impl Bar for () {}
        }
        mod mod2 {
            use crate::mod1::Bar;

            fn foo() { ().bar(); }
        }
    """)

    fun `test self reference to trait method`() = doTest("""
    //- main.rs
        mod mod1 {
            fn foo1/*caret*/() { ().foo(); }
            pub trait Foo2/*caret*/ { fn foo(&self) {} }
            impl Foo2 for ()/*caret*/ {}
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            fn foo1() { ().foo(); }

            pub trait Foo2 { fn foo(&self) {} }

            impl Foo2 for () {}
        }
    """)

    fun `test inside reference to method of moved trait`() = doTest("""
    //- main.rs
        mod mod1 {
            pub trait Foo/*caret*/ { fn foo(&self) {} }
            impl Foo for ()/*caret*/ {}
            fn bar() { ().foo(); }
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {
            use crate::mod2::Foo;

            fn bar() { ().foo(); }
        }
        mod mod2 {
            pub trait Foo { fn foo(&self) {} }

            impl Foo for () {}
        }
    """)

    fun `test outside references which starts with super 1`() = doTest("""
    //- main.rs
        mod inner1 {
            pub fn inner1_func() {}
            pub mod inner2 {
                pub fn inner2_func() {}
                mod mod1 {
                    fn foo/*caret*/() {
                        super::super::inner1_func();
                        super::inner2_func();
                    }
                }
            }
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod inner1 {
            pub fn inner1_func() {}
            pub mod inner2 {
                pub fn inner2_func() {}
                mod mod1 {}
            }
        }
        mod mod2 {
            use crate::inner1;
            use crate::inner1::inner2;

            fn foo() {
                inner1::inner1_func();
                inner2::inner2_func();
            }
        }
    """)

    fun `test outside references which starts with super 2`() = doTest("""
    //- main.rs
        mod inner1 {
            pub fn inner1_func() {}
            pub mod inner2 {
                pub fn inner2_func() {}
                mod mod1 {
                    fn foo1/*caret*/() {
                        use super::super::inner1_func;
                        inner1_func();

                        use super::inner2_func;
                        inner2_func();
                    }

                    fn foo2/*caret*/() {
                        use super::super::*;
                        inner1_func();

                        use super::*;
                        inner2_func();
                    }
                }
            }
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod inner1 {
            pub fn inner1_func() {}
            pub mod inner2 {
                pub fn inner2_func() {}
                mod mod1 {}
            }
        }
        mod mod2 {
            fn foo1() {
                use crate::inner1::inner1_func;
                inner1_func();

                use crate::inner1::inner2::inner2_func;
                inner2_func();
            }

            fn foo2() {
                use crate::inner1::*;
                inner1_func();

                use crate::inner1::inner2::*;
                inner2_func();
            }
        }
    """)

    fun `test absolute outside reference which should be changed because of reexports`() = doTest("""
    //- main.rs
        mod inner1 {
            mod mod1 {
                fn foo/*caret*/() { crate::inner1::bar::bar_func(); }
            }
            pub use bar::*;
            // private
            mod bar { pub fn bar_func() {} }
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod inner1 {
            mod mod1 {}
            pub use bar::*;
            // private
            mod bar { pub fn bar_func() {} }
        }
        mod mod2 {
            fn foo() { crate::inner1::bar_func(); }
        }
    """)

    fun `test outside references to items in new mod`() = doTest("""
    //- main.rs
        mod mod1 {
            fn foo1/*caret*/() { crate::mod2::bar1(); }
            fn foo2/*caret*/() {
                use crate::mod2;
                mod2::bar1();
            }
            mod inner/*caret*/ {
                fn foo3() { crate::mod2::bar1(); }
                fn foo4() {
                    use crate::mod2;
                    mod2::bar1();
                }
            }
        }
        mod mod2/*target*/ {
            pub fn bar1() {}
        }
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            pub fn bar1() {}

            fn foo1() { bar1(); }

            fn foo2() {
                use crate::mod2;
                bar1();
            }

            mod inner {
                fn foo3() { crate::mod2::bar1(); }
                fn foo4() {
                    use crate::mod2;
                    mod2::bar1();
                }
            }
        }
    """)

    fun `test outside references to items in new mod in use group`() = doTestIgnore("""
    //- main.rs
        mod mod1 {
            fn foo/*caret*/() {
                use crate::mod2::{bar1, bar2};
                bar1();
                bar2();
            }
        }
        mod mod2/*target*/ {
            pub fn bar1() {}
            pub fn bar2() {}
        }
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            pub fn bar1() {}
            pub fn bar2() {}

            fn foo() {
                bar1();
                bar2();
            }
        }
    """)

    fun `test outside references to items in submodule of new mod`() = doTest("""
    //- main.rs
        mod mod1 {
            fn foo1/*caret*/() {
                crate::mod2::inner1::bar1();
                crate::mod2::inner1::bar2();
            }
            fn foo2/*caret*/() {
                use crate::mod2::inner1;
                inner1::bar1();
                inner1::bar2();
            }
            mod inner/*caret*/ {
                fn foo3() {
                    crate::mod2::inner1::bar1();
                    crate::mod2::inner1::bar2();
                }
                fn foo4() {
                    use crate::mod2::inner1;
                    inner1::bar1();
                    inner1::bar2();
                }
            }
        }
        mod mod2/*target*/ {
            pub mod inner1 {
                pub use inner2::*;
                mod inner2 { pub fn bar2() {} }
                pub fn bar1() {}
            }
        }
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            pub mod inner1 {
                pub use inner2::*;
                mod inner2 { pub fn bar2() {} }
                pub fn bar1() {}
            }

            fn foo1() {
                crate::mod2::inner1::bar1();
                crate::mod2::inner1::bar2();
            }

            fn foo2() {
                inner1::bar1();
                inner1::bar2();
            }

            mod inner {
                fn foo3() {
                    crate::mod2::inner1::bar1();
                    crate::mod2::inner1::bar2();
                }
                fn foo4() {
                    use crate::mod2::inner1;
                    inner1::bar1();
                    inner1::bar2();
                }
            }
        }
    """)

    fun `test outside reference to static method`() = doTest("""
    //- main.rs
        mod mod1 {
            fn foo/*caret*/() {
                Bar::func();
            }
            pub struct Bar {}
            impl Bar { pub fn func() {} }
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {
            pub struct Bar {}
            impl Bar { pub fn func() {} }
        }
        mod mod2 {
            use crate::mod1::Bar;

            fn foo() {
                Bar::func();
            }
        }
    """)

    @ProjectDescriptor(WithStdlibRustProjectDescriptor::class)
    fun `test outside reference to items from prelude`() = doTest("""
    //- main.rs
        mod mod1 {
            fn foo/*caret*/() {
                let _ = String::new();
                let _ = Some(1);
                let _ = Vec::<i32>::new();
                let _: Vec<i32> = Vec::new();
            }
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            fn foo() {
                let _ = String::new();
                let _ = Some(1);
                let _ = Vec::<i32>::new();
                let _: Vec<i32> = Vec::new();
            }
        }
    """)

    @ProjectDescriptor(WithStdlibRustProjectDescriptor::class)
    fun `test outside references to macros from prelude`() = doTest("""
    //- main.rs
        mod mod1 {
            fn foo/*caret*/() {
                println!("foo");
                let _ = format!("{}", 1);
            }
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            fn foo() {
                println!("foo");
                let _ = format!("{}", 1);
            }
        }
    """)

    @ProjectDescriptor(WithStdlibRustProjectDescriptor::class)
    fun `test outside reference to items from stdlib`() = doTest("""
    //- main.rs
        mod mod1 {
            use std::fs;
            fn foo/*caret*/() {
                let _ = fs::read_dir(".");
            }
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {
            use std::fs;
        }
        mod mod2 {
            use std::fs;

            fn foo() {
                let _ = fs::read_dir(".");
            }
        }
    """)

    fun `test outside references to generic struct`() = doTest("""
    //- main.rs
        mod mod1 {
            fn foo/*caret*/() {
                let _ = Bar::<i32> { field: 0 };
            }
            pub struct Bar<T> { pub field: T }
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {
            pub struct Bar<T> { pub field: T }
        }
        mod mod2 {
            use crate::mod1::Bar;

            fn foo() {
                let _ = Bar::<i32> { field: 0 };
            }
        }
    """)

    fun `test outside references to generic function`() = doTest("""
    //- main.rs
        mod mod1 {
            fn foo/*caret*/() {
                bar::<i32>();
            }
            pub fn bar<T>() {}
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {
            pub fn bar<T>() {}
        }
        mod mod2 {
            use crate::mod1;

            fn foo() {
                mod1::bar::<i32>();
            }
        }
    """)

    @ProjectDescriptor(WithStdlibRustProjectDescriptor::class)
    fun `test outside reference to Debug trait in derive`() = doTest("""
    //- main.rs
        mod mod1 {
            #[derive(Debug)]
            struct Foo/*caret*/ {}
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            #[derive(Debug)]
            struct Foo {}
        }
    """)

    fun `test outside reference to custom trait in derive`() = doTest("""
    //- main.rs
        mod mod1 {
            use crate::bar::Bar;
            #[derive(Bar)]
            struct Foo/*caret*/ {}
        }
        mod mod2/*target*/ {}
        mod bar {
            pub trait Bar {}
        }
    """, """
    //- main.rs
        mod mod1 {
            use crate::bar::Bar;
        }
        mod mod2 {
            use crate::bar::Bar;

            #[derive(Bar)]
            struct Foo {}
        }
        mod bar {
            pub trait Bar {}
        }
    """)

    fun `test outside reference inside macro`() = doTest("""
    //- main.rs
        mod mod1 {
            fn foo/*caret*/() {
                println!("{}", BAR);
            }
            pub const BAR: i32 = 0;
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {
            pub const BAR: i32 = 0;
        }
        mod mod2 {
            use crate::mod1::BAR;

            fn foo() {
                println!("{}", BAR);
            }
        }
    """)

    // todo ? рассматривать только `RsPath` которые абсолютные или `this.path.reference().resolve == sourceMod`
    // todo ? для перемещаемых модулей: если был glob import `use mod1::*`, то добавлять `use mod2::*`
    fun `test self references 1`() = doTest("""
    //- main.rs
        mod mod1 {
            fn foo1/*caret*/() { bar1(); }
            fn foo2/*caret*/() { crate::mod1::bar2(); }
            fn bar1/*caret*/() {}
            fn bar2/*caret*/() {}
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            fn foo1() { bar1(); }

            fn foo2() { bar2(); }

            fn bar1() {}

            fn bar2() {}
        }
    """)

    fun `test self references from moved submodule 1`() = doTest("""
    //- main.rs
        mod mod1 {
            mod foo1/*caret*/ {
                use crate::mod1;
                fn test() { mod1::bar1(); }
            }
            mod foo2/*caret*/ {
                use crate::mod1::*;
                fn test() { bar2(); }
            }
            fn bar1/*caret*/() {}
            fn bar2/*caret*/() {}
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            mod foo1 {
                use crate::{mod1, mod2};
                fn test() { mod2::bar1(); }
            }

            mod foo2 {
                use crate::mod1::*;
                use crate::mod2::bar2;

                fn test() { bar2(); }
            }

            fn bar1() {}

            fn bar2() {}
        }
    """)

    fun `test self references from moved submodule 2`() = doTest("""
    //- main.rs
        mod mod1 {
            mod foo1/*caret*/ {
                use crate::mod1::Bar1;
                fn test() { let _ = Bar1 {}; }
            }
            mod foo2/*caret*/ {
                use crate::mod1::*;
                fn test() { let _ = Bar2 {}; }
            }
            struct Bar1/*caret*/ {}
            struct Bar2/*caret*/ {}
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            mod foo1 {
                use crate::mod2::Bar1;
                fn test() { let _ = Bar1 {}; }
            }

            mod foo2 {
                use crate::mod1::*;
                use crate::mod2::Bar2;

                fn test() { let _ = Bar2 {}; }
            }

            struct Bar1 {}

            struct Bar2 {}
        }
    """)

    fun `test self references to moved submodule`() = doTest("""
    //- main.rs
        mod mod1 {
            mod foo1/*caret*/ {
                pub fn func() {}
            }
            fn foo2/*caret*/() { foo1::func(); }
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            mod foo1 {
                pub fn func() {}
            }

            fn foo2() { foo1::func(); }
        }
    """)

    fun `test self references to generic item`() = doTest("""
    //- main.rs
        mod mod1 {
            fn foo1<T>/*caret*/() {}
            fn foo2/*caret*/() {
                foo1::<i32>();
            }
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            fn foo1<T>() {}

            fn foo2() {
                foo1::<i32>();
            }
        }
    """)

    fun `test inside references from new mod`() = doTest("""
    //- main.rs
        mod mod1 {
            pub mod inner1/*caret*/ {
                pub use inner2::*;
                mod inner2 { pub fn foo3() {} }
                pub fn foo2() {}
            }
            pub fn foo1/*caret*/() {}
            pub fn foo4/*caret*/() {}
        }
        mod mod2/*target*/ {
            fn bar1() {
                crate::mod1::foo1();
                crate::mod1::inner1::foo2();
                crate::mod1::inner1::foo3();
            }
            fn bar2() {
                use crate::mod1::foo4;
                foo4();
            }
        }
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            fn bar1() {
                foo1();
                inner1::foo2();
                inner1::foo3();
            }
            fn bar2() {
                foo4();
            }

            pub mod inner1 {
                pub use inner2::*;
                mod inner2 { pub fn foo3() {} }
                pub fn foo2() {}
            }

            pub fn foo1() {}

            pub fn foo4() {}
        }
    """)

    fun `test inside references from new mod in use group`() = doTest("""
    //- main.rs
        mod mod1 {
            pub fn foo1/*caret*/() {}
            pub fn foo2/*caret*/() {}
        }
        mod mod2/*target*/ {
            use crate::mod1::{foo1, foo2};
            fn bar() {
                foo1();
                foo2();
            }
        }
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            fn bar() {
                foo1();
                foo2();
            }

            pub fn foo1() {}

            pub fn foo2() {}
        }
    """)

    fun `test inside references from child mod of new mod`() = doTest("""
    //- main.rs
        mod mod1 {
            pub fn foo/*caret*/() {}
        }
        mod mod2/*target*/ {
            mod inner1 {
                fn test() { crate::mod1::foo(); }
            }
            mod inner2 {
                fn test() {
                    use crate::mod1;
                    mod1::foo();
                }
            }
            mod inner3 {
                fn test() {
                    use crate::mod1::*;
                    foo();
                }
            }
        }
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            mod inner1 {
                fn test() { crate::mod2::foo(); }
            }
            mod inner2 {
                fn test() {
                    use crate::{mod1, mod2};
                    mod2::foo();
                }
            }
            mod inner3 {
                fn test() {
                    use crate::mod1::*;
                    use crate::mod2::foo;
                    foo();
                }
            }

            pub fn foo() {}
        }
    """)

    // it is idiomatic to import parent mod for functions, and directly import structs/enums
    // https://doc.rust-lang.org/book/ch07-04-bringing-paths-into-scope-with-the-use-keyword.html#creating-idiomatic-use-paths
    fun `test inside references from old mod`() = doTest("""
    //- main.rs
        mod mod1 {
            pub fn foo1/*caret*/() {}
            pub struct Foo2/*caret*/ {}
            pub enum Foo3/*caret*/ { V1 }
            fn bar() {
                foo1();
                let _ = Foo2 {};
                let _ = Foo3::V1;
            }
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {
            use crate::mod2;
            use crate::mod2::{Foo2, Foo3};

            fn bar() {
                mod2::foo1();
                let _ = Foo2 {};
                let _ = Foo3::V1;
            }
        }
        mod mod2 {
            pub fn foo1() {}

            pub struct Foo2 {}

            pub enum Foo3 { V1 }
        }
    """)

    fun `test inside references absolute`() = doTest("""
    //- main.rs
        mod mod1 {
            pub fn foo1/*caret*/() {}
            pub struct Foo2/*caret*/ {}
        }
        mod mod2/*target*/ {}

        mod usage {
            fn test() {
                crate::mod1::foo1();
                let _ = crate::mod1::Foo2 {};
            }
        }
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            pub fn foo1() {}

            pub struct Foo2 {}
        }

        mod usage {
            fn test() {
                crate::mod2::foo1();
                let _ = crate::mod2::Foo2 {};
            }
        }
    """)

    fun `test inside references starting with super`() = doTest("""
    //- main.rs
        mod mod1 {
            pub fn foo1/*caret*/() {}
            pub struct Foo2/*caret*/ {}

            mod usage {
                fn test() {
                    super::foo1();
                    let _ = super::Foo2 {};
                }
            }
        }
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {
            mod usage {
                use crate::mod2;
                use crate::mod2::Foo2;

                fn test() {
                    mod2::foo1();
                    let _ = Foo2 {};
                }
            }
        }
        mod mod2 {
            pub fn foo1() {}

            pub struct Foo2 {}
        }
    """)

    fun `test inside references with fully qualified import`() = doTest("""
    //- main.rs
        mod mod1 {
            pub fn foo1/*caret*/() {}
            pub struct Foo2/*caret*/ {}
        }
        mod mod2/*target*/ {}

        mod usage {
            use crate::mod1::foo1;
            use crate::mod1::Foo2;
            fn test() {
                foo1();
                let _ = Foo2 {};
            }
        }
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            pub fn foo1() {}

            pub struct Foo2 {}
        }

        mod usage {
            use crate::mod2::foo1;
            use crate::mod2::Foo2;
            fn test() {
                foo1();
                let _ = Foo2 {};
            }
        }
    """)

    fun `test inside references with import for parent mod`() = doTest("""
    //- main.rs
        mod mod1 {
            pub fn foo1/*caret*/() {}
            pub struct Foo2/*caret*/ {}
        }
        mod mod2/*target*/ {}

        mod usage {
            use crate::mod1;
            fn test() {
                mod1::foo1();
                let _ = mod1::Foo2 {};
            }
        }
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            pub fn foo1() {}

            pub struct Foo2 {}
        }

        mod usage {
            use crate::{mod1, mod2};
            fn test() {
                mod2::foo1();
                let _ = mod2::Foo2 {};
            }
        }
    """)

    fun `test inside references with import for grandparent mod`() = doTest("""
    //- main.rs
        mod inner1 {
            pub mod mod1 {
                pub fn foo1/*caret*/() {}
                pub struct Foo2/*caret*/ {}
            }
        }
        mod inner2 {
            pub mod mod2/*target*/ {}
        }

        mod usage {
            use crate::inner1;
            fn test() {
                inner1::mod1::foo1();
                let _ = inner1::mod1::Foo2 {};
            }
        }
    """, """
    //- main.rs
        mod inner1 {
            pub mod mod1 {}
        }
        mod inner2 {
            pub mod mod2 {
                pub fn foo1() {}

                pub struct Foo2 {}
            }
        }

        mod usage {
            use crate::{inner1, inner2};
            fn test() {
                inner2::mod2::foo1();
                let _ = inner2::mod2::Foo2 {};
            }
        }
    """)

    fun `test outside references from use group`() = doTestIgnore("""
    //- main.rs
        mod mod1 {
            fn foo/*caret*/() {
                use crate::mod2::{bar1, bar2, inner::{bar3, bar4}};
                bar1();
                bar2();
                bar3();
                bar4();
            }
        }
        mod mod2/*target*/ {
            pub fn bar1() {}
            pub fn bar2() {}
            pub mod inner {
                pub fn bar3() {}
                pub fn bar4() {}
            }
        }
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            pub fn bar1() {}
            pub fn bar2() {}
            pub mod inner {
                pub fn bar3() {}
                pub fn bar4() {}
            }

            fn foo() {
                use crate::mod2::inner::{bar3, bar4};
                bar1();
                bar2();
                bar3();
                bar4();
            }
        }
    """)

    fun `test inside references from use group 1`() = doTest("""
    //- main.rs
        mod mod1 {
            pub fn foo1/*caret*/() {}
            pub fn foo2/*caret*/() {}
            pub fn bar() {}
        }
        mod mod2/*target*/ {}
        mod usage {
            use crate::mod1::{foo1, foo2, bar};
        }
    """, """
    //- main.rs
        mod mod1 {
            pub fn bar() {}
        }
        mod mod2 {
            pub fn foo1() {}

            pub fn foo2() {}
        }
        mod usage {
            use crate::mod1::bar;
            use crate::mod2::{foo1, foo2};
        }
    """)

    fun `test inside references from use group 2`() = doTest("""
    //- main.rs
        mod mod1 {
            pub mod foo1/*caret*/ {
                pub fn foo1_func() {}
                pub mod foo1_inner {
                    pub fn foo1_inner_func() {}
                }
            }
            pub mod foo2/*caret*/ {
                pub fn foo2_func() {}
            }
        }
        mod mod2/*target*/ {}
        mod usage1 {
            use crate::mod1::{
                foo1::{foo1_func, foo1_inner::foo1_inner_func},
                foo2::foo2_func,
            };
        }
    """, """
    //- main.rs
        mod mod1 {}
        mod mod2 {
            pub mod foo1 {
                pub fn foo1_func() {}
                pub mod foo1_inner {
                    pub fn foo1_inner_func() {}
                }
            }

            pub mod foo2 {
                pub fn foo2_func() {}
            }
        }
        mod usage1 {
            use crate::mod2::foo1::{foo1_func, foo1_inner::foo1_inner_func};
            use crate::mod2::foo2::foo2_func;
        }
    """)

    fun `test move to other crate simple`() = doTest("""
    //- main.rs
        mod mod1 {
            fn foo/*caret*/() {}
        }
    //- lib.rs
        mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {}
    //- lib.rs
        mod mod2 {
            fn foo() {}
        }
    """)

    fun `test move to other crate 1`() = doTest("""
    //- main.rs
        mod mod1 {
            pub fn foo/*caret*/() {}
            fn bar() {
                foo();
            }
        }
    //- lib.rs
        pub mod mod2/*target*/ {}
    """, """
    //- main.rs
        mod mod1 {
            use test_package::mod2;

            fn bar() {
                mod2::foo();
            }
        }
    //- lib.rs
        pub mod mod2 {
            pub fn foo() {}
        }
    """)

    fun `test move to other crate 2`() = doTest("""
    //- lib.rs
        pub mod mod1 {
            fn foo/*caret*/() {
                bar();
            }
            pub fn bar() {}
        }
    //- main.rs
        mod mod2/*target*/ {}
    """, """
    //- lib.rs
        pub mod mod1 {
            pub fn bar() {}
        }
    //- main.rs
        mod mod2 {
            use test_package::mod1;

            fn foo() {
                mod1::bar();
            }
        }
    """)
}
