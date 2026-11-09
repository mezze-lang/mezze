package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * What a `newtype` can carry — the construct every other refusal points at.
 *
 * V0706 refuses an impl on a transparent alias and suggests `newtype`.
 * V0406 refuses an inherent impl on a container you do not own and suggests an
 * ability. Both suggestions are only worth making if the newtype route actually
 * works, and "works" has to mean the method RESOLVES AT A CALL SITE, not that
 * the declaration was accepted — accepted-and-unreachable is precisely the state
 * `type Id = Int; impl Id where` was in, and reading declaration acceptance as
 * reachability is how that went unnoticed. Every case here calls what it
 * declares.
 *
 * Why a newtype can carry what an alias cannot: it has an IDENTITY. The alias
 * cases fail because `IntType`/`SeqType`/a function type keep nowhere to record
 * "this was called Id", so the name is lost between the impl and the receiver. A
 * newtype is a declaration with a hash, so the name survives.
 *
 * NewtypeOpacitySpecTest pins construct/destructure/naming and
 * NewtypeDeriveSpecTest pins what is auto-derived; neither asks what impls a
 * newtype may carry, which is this file. Most of these guard behaviour that
 * already held — the one case that is new is the cross-module inherent orphan,
 * which now answers V0406 through the ownership rule.
 */
class NewtypeImplSpecTest : VariantSpecBase() {

    private val eqHash = "eq = { it, r } -> True, neq = { it, r } -> False"
    private val hash = "hash = { it } -> 1"

    // ================================= inherent impls, whatever is wrapped

    /**
     * The fix V0706 names, so it had better work: the method the alias could
     * not have, on the newtype that can.
     */
    @Test fun `a newtype of a primitive carries an inherent method`() {
        accepts(
            """
            use std::int
            newtype M = Int
            impl M where { un = { it: M } -> match it is M.(n) -> n }
            pub let u = { m: M } -> m.un {}
            $MAIN
            """,
            "`type Id = Int; impl Id where` is V0706 and this is the spelling it " +
                "points at. If this ever stops working the diagnostic is lying.",
        )
    }

    /**
     * A newtype of a SEQ is the case that matters most, because neither the raw
     * `[Int]` nor `type Ids = [Int]` can carry a method in user code — the first
     * is V0406 (we do not own `Seq`), the second is V0706 (no identity).
     */
    @Test fun `a newtype of a seq carries an inherent method`() {
        accepts(
            """
            use std::int
            newtype Ids = [Int]
            impl Ids where { howmany = { it: Ids } -> 1 }
            pub let u = { i: Ids } -> i.howmany {}
            $MAIN
            """,
            "The only route to a method on a sequence-shaped type you own. Both " +
                "alternatives are refused, so this one has to exist.",
        )
    }

    /** A function type keeps no identity either, so same story. */
    @Test fun `a newtype of a function type carries an inherent method`() {
        accepts(
            """
            use std::int
            newtype H = { n: Int } -> Int
            impl H where { one = { it: H } -> 1 }
            pub let u = { h: H } -> h.one {}
            $MAIN
            """,
            "`type H = { n: Int } -> Int; impl H where` is V0706.",
        )
    }

    /** A record payload needs matching to read, but the impl is ordinary. */
    @Test fun `a newtype of a record carries an inherent method`() {
        accepts(
            """
            use std::int
            newtype P = { x: Int }
            impl P where { getx = { it: P } -> match it is P.(r) -> r.x }
            pub let u = { p: P } -> p.getx {}
            $MAIN
            """,
            "A record alias could carry this too — it keeps an identity — but the " +
                "newtype version is opaque, which is the difference the author is " +
                "choosing between.",
        )
    }

    /** Applied to its own parameter, and to a concrete argument at the call. */
    @Test fun `a generic newtype carries an inherent method`() {
        accepts(
            """
            use std::int
            newtype Wrap 'a = 'a
            impl Wrap 'a where { peek = { it: Wrap 'a } -> 1 }
            pub let u = { w: Wrap Int } -> w.peek {}
            $MAIN
            """,
            "`Wrap` is the outermost constructor and it is locally declared, so " +
                "the ownership rule is satisfied by the newtype itself.",
        )
    }

    // ============================================ abilities, local and imported

    @Test fun `a local ability may be implemented for a newtype`() {
        accepts(
            """
            use std::int
            newtype M = Int
            ability Norm for 'a where { norm: { it: 'a } -> Int }
            impl Norm for M where { norm = { it } -> 1 }
            pub let u = { m: M } -> m.norm {}
            $MAIN
            """,
            "Owning the ability is already enough for any target; the newtype adds " +
                "nothing here. Pinned so the two routes are known to be independent.",
        )
    }

    /**
     * An IMPORTED ability for a newtype — the case the orphan rule exists to
     * limit, allowed here because the TYPE is local. This is what makes a
     * newtype the answer to `impl Eq for [Int]` being refused.
     */
    @Test fun `an imported ability may be implemented for a newtype of a primitive`() {
        accepts(
            """
            use std::int
            use std::abilities::compare::{ Eq, Hash }
            newtype M = Int
            impl Eq for M where { $eqHash }
            impl Hash for M where { $hash }
            pub let u = { a: M, b: M } -> a == b
            $MAIN
            """,
            "`==` resolving is the assertion: the impl is reached, not merely " +
                "accepted. Eq and Hash are one decision, so both are written.",
        )
    }

    /**
     * Rust's newtype escape hatch from the orphan rule, in our terms:
     * `impl Eq for [Int]` is refused because neither `Eq` nor `Seq` is ours,
     * and wrapping makes the target ours.
     */
    @Test fun `an imported ability may be implemented for a newtype of a seq`() {
        accepts(
            """
            use std::int
            use std::abilities::compare::{ Eq, Hash }
            newtype Ids = [Int]
            impl Eq for Ids where { $eqHash }
            impl Hash for Ids where { $hash }
            pub let u = { a: Ids, b: Ids } -> a == b
            $MAIN
            """,
            "The whole reason the newtype pattern is the sanctioned answer to a " +
                "coherence refusal rather than a workaround.",
        )
    }

    @Test fun `an imported ability may be implemented for a generic newtype`() {
        accepts(
            """
            use std::int
            use std::abilities::compare::{ Eq, Hash }
            newtype Wrap 'a = 'a
            impl Eq for Wrap Int where { $eqHash }
            impl Hash for Wrap Int where { $hash }
            pub let u = { a: Wrap Int, b: Wrap Int } -> a == b
            $MAIN
            """,
            "An applied newtype target, where the constructor is still ours.",
        )
    }

    // ============== nothing a newtype carries leaks to what it wraps

    /**
     * The property the whole design rests on, and the reason a newtype is safe
     * to be the answer to a coherence refusal.
     *
     * `impl Eq for [Int]` is refused because it would give EVERY `[Int]` in the
     * program an `Eq` that this module does not own. If wrapping were a way
     * AROUND that — if the impl on `Ids` reached `[Int]` — the refusal would be
     * cosmetic: two packages could each wrap `[Int]`, each implement `Eq`, and a
     * plain `[Int]` would have two. The orphan rule would leak through the very
     * construct offered as its answer.
     *
     * It does not. A newtype is not an escape from the rule, it is a type of
     * your own, and the methods are its alone.
     */
    @Test fun `an inherent method on a newtype does not reach the wrapped type`() {
        rejects(
            """
            use std::int
            newtype Ids = [Int]
            impl Ids where { howmany = { it: Ids } -> 1 }
            pub let u = { xs: [Int] } -> xs.howmany {}
            $MAIN
            """,
            "T0201",
            "A plain `[Int]` is a `Seq`, not an `Ids`. Were this accepted the " +
                "orphan rule would be unenforceable, since wrapping is always " +
                "available.",
        )
    }

    /** Same for an ability impl, which is the case the orphan rule governs. */
    @Test fun `an ability impl on a newtype does not reach the wrapped type`() {
        rejects(
            """
            use std::int
            newtype Ids = [Int]
            ability Howmany for 'a where { howmany: { it: 'a } -> Int }
            impl Howmany for Ids where { howmany = { it } -> 1 }
            pub let u = { xs: [Int] } -> xs.howmany {}
            $MAIN
            """,
            "V0402",
            "`[Int] does not implement 'Howmany'` — the impl went on `Ids` and " +
                "stayed there.",
        )
    }

    /** And the wrapped type's value cannot stand in for the newtype either. */
    @Test fun `the wrapped type does not pass where the newtype is asked`() {
        rejects(
            """
            use std::int
            newtype Ids = [Int]
            pub let takes = { i: Ids } -> 1
            pub let u = { xs: [Int] } -> takes { i = xs }
            $MAIN
            """,
            "T0201",
            "The wall stands in both directions; NewtypeDeriveSpecTest pins that " +
                "`Ids` does not get `Seq`'s methods, and this is the other side.",
        )
    }

    // =================================================== across module lines

    private fun writeNewtypeModule() = writeModule(
        "nt",
        """
        use std::int
        pub newtype M = Int
        pub let mk = { n: Int } -> M.(n)
        impl M where { un = { it: M } -> match it is M.(n) -> n }
        ability Norm for 'a where { norm: { it: 'a } -> Int }
        impl Norm for M where { norm = { it } -> 2 }
        """,
    )

    /** Opaque to CONSTRUCTION, not to use: the method travels with the type. */
    @Test fun `an importing module can call a newtype's inherent method`() {
        writeNewtypeModule()
        acceptsMain(
            """
            use std::int
            use nt::{ M, mk }
            pub let u = {} -> mk { n = 1 }.un {}
            $MAIN
            """,
            "Opacity is about the CONSTRUCTOR — NewtypeOpacitySpecTest pins that " +
                "the importer may not build or destructure one. The methods are " +
                "public API and must cross, or an opaque type would be useless.",
        )
    }

    @Test fun `an importing module can call a newtype's ability op`() {
        writeNewtypeModule()
        acceptsMain(
            """
            use std::int
            use nt::{ M, mk, Norm }
            pub let u = {} -> mk { n = 1 }.norm {}
            $MAIN
            """,
            "The ability is imported by name, exactly as Rust needs the trait in " +
                "scope for an extension method to be visible.",
        )
    }

    /** The orphan limit still applies to a newtype someone else declared. */
    @Test fun `an importing module may not implement an imported ability for an imported newtype`() {
        writeNewtypeModule()
        rejectsMain(
            """
            use std::int
            use std::abilities::compare::{ Eq, Hash }
            use nt::{ M }
            impl Eq for M where { $eqHash }
            impl Hash for M where { $hash }
            $MAIN
            """,
            "V0406",
            "Neither `Eq` nor `M` is this module's, so two packages could each " +
                "give `M` an `Eq`. Being a newtype makes it ownable, not ownerless.",
        )
    }

    /** And it may not add a method to it — the ownership rule, unchanged by opacity. */
    @Test fun `an importing module may not add an inherent method to an imported newtype`() {
        writeNewtypeModule()
        rejectsMain(
            """
            use std::int
            use nt::{ M }
            impl M where { extra = { it: M } -> 9 }
            $MAIN
            """,
            "V0406",
            "`M` is owned by `nt`. This is the same answer Rust gives to an " +
                "inherent impl on a foreign type, E0116, and the reason a newtype " +
                "is worth declaring: its method set is closed to other modules.",
        )
    }

    /**
     * But the extension route is open, as it is for any type: declare the
     * ability here and the imported newtype is a fine target.
     */
    @Test fun `an importing module may implement its OWN ability for an imported newtype`() {
        writeNewtypeModule()
        acceptsMain(
            """
            use std::int
            use nt::{ M }
            ability Mine for 'a where { mine: { it: 'a } -> Int }
            impl Mine for M where { mine = { it } -> 7 }
            pub let u = { m: M } -> m.mine {}
            $MAIN
            """,
            "Owning the ability is what makes it safe — the ability names the " +
                "method, so a second package cannot collide without importing it. " +
                "Opacity does not block this: the impl needs the TYPE, not its " +
                "constructor.",
        )
    }
}
