mod mod1;
mod mod2;

// replace with absolute path if there is one usage and path is not idiomatic
mod usages1 {
    use crate::mod1;

    fn test() {
        mod1::foo::func();
    }
}

// otherwise add new import
mod usages2 {
    use crate::mod1::*;

    fn test() {
        foo::func();
    }
}

mod usages3 {
    use crate::mod1::*;

    fn test() {
        foo::func();
        foo::func();
        foo::func();
    }
}

mod usages4 {
    use crate::mod1;

    fn test() {
        mod1::foo::func();
        mod1::foo::func();
        mod1::foo::func();
    }
}

// add new import to local use group if such exists
mod usages5 {
    fn test() {
        use crate::mod1::*;

        foo::func();
        foo::func();
        foo::func();
    }
}
