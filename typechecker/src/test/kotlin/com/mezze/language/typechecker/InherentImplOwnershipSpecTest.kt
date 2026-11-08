package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * Who may add an inherent method — Rust's rule, at our granularity.
 *
 * Rust has two gates and they answer differently, which is the whole of this
 * file's subject:
 *
 *  - INHERENT (`impl Type { … }`): only the crate that DEFINES the type.
 *    `impl Vec<i32> {}` is E0116, and so is `impl Vec<MyType> {}` — owning
 *    something inside the container does not make the container yours.
 *    `impl i32 {}` is E0390. An alias is resolved first, so
 *    `type V = Vec<i32>; impl V {}` is still E0116.
 *  - TRAIT (the orphan rule, E0117): own the trait, OR own a type at the
 *    SURFACE of the header — self type, a trait argument, or under a
 *    fundamental type (`&`, `&mut`, `Box`). `Vec` is not fundamental, which is
 *    why `impl Display for Vec<MyType>` is still E0117.
 *
 * Our ability path was already this rule; `validateOrphanRules` returns early
 * when the ability is local, and otherwise scans `abilityTypeArgs + targetTypes`
 * at the surface. The inherent path was NOT: it asked whether ANY name mentioned
 * anywhere in the receiver tree was local, so a module could attach methods to a
 * container it does not own as long as it owned something inside it.
 *
 * Granularity is the one place we are deliberately finer than Rust. Rust's unit
 * of coherence is the crate, so inside one crate it does not care which module
 * an inherent impl sits in. We have a per-type home table, so:
 *
 *   - a NULLARY target is owned by its home MODULE (`impl Int` belongs in
 *     `std::int` and nowhere else — Rust has no way to say this)
 *   - an APPLIED target is owned by its home PACKAGE (Rust's rule), which is
 *     what keeps `impl [Promise 'a]` legal in `std::effects::async` and
 *     `impl Result Response HttpError` legal in `std::effects::http`
 *
 * What a refused module should do instead is declare an ability and implement
 * it: an ability you own may be implemented for any type. That is Rust's
 * extension-trait answer, and it is already how the ability path behaves.
 */
class InherentImplOwnershipSpecTest : VariantSpecBase() {

    // ============================================ a type argument confers nothing

    /**
     * The case that changed. `MyT` is local, `Seq` is not, and the receiver is a
     * `Seq`. Rust: `impl Vec<MyType> { }` → E0116.
     */
    @Test fun `an inherent impl on a seq of a local type is refused`() {
        rejects(
            """
            use std::int
            type MyT = { x: Int }
            impl [MyT] where { firstx = { it: [MyT] } -> 1 }
            $MAIN
            """,
            "V0406",
            "A type argument does not make the receiver local. The method is being " +
                "hung on `Seq`, which this module does not own, so two unrelated " +
                "packages could each add `firstx` to a seq and a `[MyT]` value would " +
                "have two method sets. Owning `MyT` says nothing about `Seq`.",
        )
    }

    /** Same for any foreign container, not just the seq sugar. */
    @Test fun `an inherent impl on a foreign container of a local type is refused`() {
        for (ctor in listOf("Option", "Result")) {
            rejects(
                """
                use std::int
                use std::option
                use std::result
                type MyT = { x: Int }
                impl $ctor MyT where { mine = { it: $ctor MyT } -> 1 }
                $MAIN
                """,
                "V0406",
                "`$ctor` is owned by its own stdlib module. Rust refuses " +
                    "`impl $ctor<MyType> { }` for the same reason it refuses " +
                    "`impl Vec<MyType> { }` — the impl is on the container.",
            )
        }
    }

    /** Nothing local anywhere: the case that was already refused, still is. */
    @Test fun `an inherent impl on a raw seq of a primitive is refused`() {
        rejects(
            """
            use std::int
            impl [Int] where { firstish = { it: [Int] } -> 1 }
            $MAIN
            """,
            "V0406",
            "Neither `Seq` nor `Int` is this module's. This was already refused " +
                "and is pinned so the ownership rewrite cannot loosen it.",
        )
    }

    /** A primitive is owned by its home module, so user code may not extend it. */
    @Test fun `an inherent impl on a primitive is refused`() {
        for (prim in listOf("Int", "Str", "Bool", "Float")) {
            rejects(
                """
                impl $prim where { twice$prim = { it: $prim } -> it }
                $MAIN
                """,
                "V0406",
                "Rust gives E0390 here with `consider using an extension trait " +
                    "instead`. Ours is the same answer: declare an ability and " +
                    "implement it for `$prim`.",
            )
        }
    }

    // ================================================== an alias is resolved first

    /**
     * The hole this closes. The impl used to be ACCEPTED and then unreachable:
     * the method table is keyed on the spelling `Id`, but `i: Id` has already
     * become `Int` by lookup time, so every call site — including one in this
     * very module — was told `No inherent method 'twice' for type 'Int'. It
     * exists for: Id.` A diagnostic that names a type which IS `Int` as the one
     * that has the method.
     */
    @Test fun `an inherent impl on a transparent alias of a primitive is refused`() {
        rejects(
            """
            use std::int
            type Id = Int
            impl Id where { twice = { it: Id } -> it + it }
            $MAIN
            """,
            "V0706",
            "Refused at the DECLARATION, where the fix is (`newtype Id = Int`), " +
                "rather than at every use site. Rust resolves aliases before " +
                "coherence too, which is why `type V = Vec<i32>; impl V {}` is " +
                "still E0116 and not a new type to hang methods on.",
        )
    }

    /** Transitively, because `type A = Int; type B = A` hides it one link out. */
    @Test fun `an inherent impl on an alias of an alias of a primitive is refused`() {
        rejects(
            """
            use std::int
            type A = Int
            type B = A
            impl B where { twice = { it: B } -> it + it }
            $MAIN
            """,
            "V0706",
            "The reason is about the underlying representation, which another " +
                "alias link does not change.",
        )
    }

    /** A seq alias is the same story: `SeqType` keeps no identity either. */
    @Test fun `an inherent impl on a seq alias is refused`() {
        rejects(
            """
            use std::int
            type Ids = [Int]
            impl Ids where { firstish = { it: Ids } -> 1 }
            $MAIN
            """,
            "V0706",
            "Two modules could each alias `[Int]` and each add methods to it. " +
                "Named by the spelling, both impls target one type.",
        )
    }

    /** And a function-type alias. */
    @Test fun `an inherent impl on a function type alias is refused`() {
        rejects(
            """
            use std::int
            type H = { n: Int } -> Int
            impl H where { call1 = { it: H } -> it { n = 1 } }
            $MAIN
            """,
            "V0706",
            "A function type keeps no identity, so the alias name never reaches " +
                "dispatch.",
        )
    }

    // ======================================================= what stays legal

    /**
     * A record alias is the case `type` exists for. `RecordType` KEEPS an
     * identity, so the name survives to dispatch and a bare record literal
     * adopts it — this must not be caught by the alias rule above.
     */
    @Test fun `an inherent impl on a record alias is accepted`() {
        accepts(
            """
            use std::int
            type P = { x: Int }
            impl P where { getx = { it: P } -> it.x }
            pub let u = { p: P } -> p.getx {}
            $MAIN
            """,
            "For a record the `type` name IS its only name, so the impl belongs " +
                "there. This is the line between the two alias cases: an identity " +
                "to record the name in, or nowhere to put it.",
        )
    }

    /** A variant alias, for the same reason. */
    @Test fun `an inherent impl on a variant alias is accepted`() {
        accepts(
            """
            use std::int
            type C = | R, G |
            impl C where { num = { it: C } -> 1 }
            pub let u = { c: C } -> c.num {}
            $MAIN
            """,
            "`VariantType` keeps an identity too.",
        )
    }

    /** A newtype is a NEW type and this module declared it. */
    @Test fun `an inherent impl on a newtype of a primitive is accepted`() {
        accepts(
            """
            use std::int
            newtype M = Int
            impl M where { un = { it: M } -> match it is M.(n) -> n }
            $MAIN
            """,
            "This is the fix V0706 points at, so it had better work. A newtype is " +
                "nominal and opaque, so the name reaches dispatch and only this " +
                "module can write the impl.",
        )
    }

    /** The ordinary case: a type this module declares. */
    @Test fun `an inherent impl on a locally declared type is accepted`() {
        accepts(
            """
            use std::int
            type MyT = { x: Int }
            impl MyT where { getx = { it: MyT } -> it.x }
            pub let u = { t: MyT } -> t.getx {}
            $MAIN
            """,
            "Unremarkable, and pinned because the ownership rewrite touches the " +
                "path every inherent impl takes.",
        )
    }

    /** Applied to its own parameters, still this module's type. */
    @Test fun `an inherent impl on a local generic type is accepted`() {
        accepts(
            """
            use std::int
            type Box 'a = { v: 'a }
            impl Box 'a where { unbox = { it: Box 'a } -> it.v }
            $MAIN
            """,
            "`Box` is the outermost constructor and it is local. Rust allows " +
                "`impl<T> MyBox<T> { }` in the defining crate for the same reason.",
        )
    }

    // ================================= the extension route Rust points you at

    /**
     * The answer a refused module is given. An ability you declare may be
     * implemented for ANY type — `validateOrphanRules` returns early on a local
     * ability — which is exactly Rust's extension trait, and exactly what
     * rustc's own E0390 hint suggests.
     */
    @Test fun `a local ability may be implemented for a foreign container`() {
        accepts(
            """
            use std::int
            type MyT = { x: Int }
            ability FirstX for 'a where { firstx: { it: 'a } -> Int }
            impl FirstX for [MyT] where { firstx = { it } -> 1 }
            pub let u = { xs: [MyT] } -> xs.firstx {}
            $MAIN
            """,
            "The method the inherent impl could not have. Owning the ability is " +
                "what makes it safe: the ability names the method, so two packages " +
                "cannot collide on `firstx` without one of them importing the other.",
        )
    }

    /** Including for a bare primitive, which is the `impl Int` replacement. */
    @Test fun `a local ability may be implemented for a primitive`() {
        accepts(
            """
            use std::int
            ability Twice for 'a where { twice: { it: 'a } -> Int }
            impl Twice for Int where { twice = { it } -> it + it }
            pub let u = { n: Int } -> n.twice {}
            $MAIN
            """,
            "`impl Int where { twice }` is refused and this is the sanctioned " +
                "spelling of the same intent — with the method reachable, which " +
                "the inherent impl on an alias never was.",
        )
    }

    /**
     * And the limit of that route, which is Rust's E0117: an IMPORTED ability
     * for a container you do not own is still refused, however local the
     * argument is. `impl Display for Vec<MyType>` is E0117 in Rust because
     * `Vec` is not a fundamental type.
     */
    @Test fun `an imported ability for a seq of a local type is still refused`() {
        rejects(
            """
            use std::int
            use std::abilities::compare::{ Eq }
            type MyT = { x: Int }
            impl Eq for [MyT] where { eq = { it, r } -> True, neq = { it, r } -> False }
            $MAIN
            """,
            "V0406",
            "Neither the ability nor the receiver's constructor is ours. This is " +
                "the asymmetry that used to exist for no reason either rule could " +
                "state: `impl [MyT] where` was accepted while this was refused, in " +
                "the same module. Now both are refused, and Rust refuses both too.",
        )
    }

    /** But a local type at the SURFACE does confer it — Rust scans `T0..Tn`. */
    @Test fun `an imported ability with a local ability argument is accepted`() {
        accepts(
            """
            use std::int
            use std::abilities::convert::{ Into }
            type MyT = { x: Int }
            impl Into MyT for Int where { into = { it } -> { x = it } }
            $MAIN
            """,
            "`impl From<MyType> for Vec<u8>` is legal in Rust: the orphan rule " +
                "scans every type in the header, not only the self type. Ours " +
                "scans `abilityTypeArgs + targetTypes` and agrees.",
        )
    }
}
