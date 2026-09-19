package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * #397 — GENUINE cross-module newtype coverage.
 *
 * The pre-existing "two module" probe (NewtypeProbeTest.Q5) is fake — it puts
 * two decls in ONE module and even says so in its own comment. This suite builds
 * REAL separate module files under `src/` (module A + module B, B `use`-ing A),
 * mirroring the multi-file harness used by VariantStructuralAliasExtTest /
 * CrossModuleImplFieldItMismatchTest, and exercises the newtype barrier across
 * a true module boundary.
 *
 * Key findings this suite pins (verdicts as of #397):
 *   - PASS: A newtype value produced by A's smart constructor keeps its `Length`
 *     identity when returned to B, and B's calls to A's `unwrap` typecheck (case1).
 *   - PASS: CONSTRUCTOR VISIBILITY. `Name.(x)` is PRIVATE to the defining module.
 *     B canNOT forge `Length.(4.0)` directly — it is rejected with
 *     M0803_NOT_EXPORTED (case3). The defining module MAY use its own raw ctor
 *     (case3b). This is the intended encapsulation rule (the smart ctor `make` is
 *     the only public door), so there is NO ctor leak.
 *   - PASS: cross-module ability impl on a newtype resolves; B must `use` the
 *     ability (`Describe`) itself for dot-method resolution (case2).
 *   - PASS: a cross-module do-block binding `let x = A.make {..}` keeps the
 *     `Length` wrapper (case5, ties #395/#396).
 *   - WAS A BUG, FIXED (#401) and enabled (case4b): a newtype-over-record is
 *     OPAQUE. `.field` used to punch straight through the wrapper because
 *     `solveHasField` unwrapped the newtype with no opacity guard; it now
 *     rejects `.field` on any newtype, with no shape or module special-casing.
 *   - WAS A BUG, FIXED (#400) and enabled (case6): same-named newtypes in
 *     different modules are DISTINCT. They used to collide — a newtype was never
 *     stamped with a `Declared` identity, so unification fell to bare-name
 *     matching — and now carry a module-salted one that unification keys on.
 *
 * Both carried their intended assertion UNWEAKENED while they were failing, and
 * both now pass with that same assertion. Nothing here is papered over: every
 * case in this file is enabled.
 */
class CrossModuleNewtypeTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearCache()
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun writeModule(name: String, body: String) {
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("$name.mz"), body.trimIndent())
    }

    private fun checkMain(mainSrc: String): Result<TypeCheckResult> {
        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)
        Files.writeString(srcDir.resolve("main.mz"), mainSrc.trimIndent())
        return runCatching {
            val parsed = parser.parseParsedModule(mainSrc.trimIndent(), "main.mz")
            typeChecker.typeCheck(parsed, tempDir)
        }
    }

    /** Module A: a Length newtype over Float with smart ctor + unwrap accessor. */
    private fun writeLengthModule() = writeModule("length_lib", """
        pub newtype Length = Float
        pub let make : { v: Float } -> Length = { v } -> Length.(v)
        pub let unwrap : { l: Length } -> Float = { l } -> match l is
          Length.(f) -> f
    """)

    // =====================================================================
    // Case 1 — fn-return / construct / unwrap end to end across the boundary.
    // B calls A.make, stores the Length, then calls A.unwrap on it.
    // =====================================================================
    @Test
    fun `case1 — B constructs via A make, unwraps via A unwrap, types plumb through`() {
        writeLengthModule()
        val main = """
            use length_lib::{ Length, make, unwrap }

            let len : Length = make { v = 4.0 }
            let back : Float = unwrap { l = len }
        """
        val r = checkMain(main)
        assertTrue(r.isSuccess) {
            "cross-module make/unwrap should typecheck; got: ${r.exceptionOrNull()?.message}"
        }
        val res = r.getOrThrow()
        assertEquals("Length", res.getVariableType("len")?.let { prettyPrintType(it) },
            "len should carry the Length wrapper across the boundary")
        assertEquals("Float", res.getVariableType("back")?.let { prettyPrintType(it) },
            "unwrap should recover a plain Float")
    }

    // =====================================================================
    // Case 2 — cross-module ability IMPL on the newtype. A defines an ability
    // and `impl Describe for Length`; B imports and calls `.describe {}` on a
    // Length it received. RULE (confirmed PASS): cross-module impl resolution
    // fires, but B must `use` the ability itself (`Describe`) alongside the type
    // — the import list here brings in `{ Length, make, Describe }`. The dot-
    // method `len.describe {}` then resolves to A's impl and types as Str.
    // =====================================================================
    @Test
    fun `case2 — B uses ability impl defined on Length in A`() {
        writeModule("length_ability", """
            pub newtype Length = Float
            pub let make : { v: Float } -> Length = { v } -> Length.(v)

            ability Describe for 'a where {
              describe : { it: 'a } -> Str
            }

            impl Describe for Length where {
              describe = { it } -> match it is
                Length.(f) -> "len",
            }
        """)
        val main = """
            use length_ability::{ Length, make, Describe }

            let len : Length = make { v = 4.0 }
            let d : Str = len.describe {}
        """
        val r = checkMain(main)
        assertTrue(r.isSuccess) {
            "cross-module ability impl on newtype should resolve; got: ${r.exceptionOrNull()?.message}"
        }
        assertEquals("Str", r.getOrThrow().getVariableType("d")?.let { prettyPrintType(it) },
            "describe on an imported Length should type as Str")
    }

    // =====================================================================
    // Case 3 — CONSTRUCTOR VISIBILITY (the key question). A exports the TYPE
    // and the smart ctor `make`, but NOT the raw constructor. Can B write
    // `Length.(4.0)` directly? Intended rule: NO — the ctor is private to the
    // defining module. B must go through `make`. This locks the encapsulation.
    // =====================================================================
    @Test
    fun `case3 — B cannot forge Length directly, ctor is private to defining module`() {
        writeLengthModule()
        val main = """
            use length_lib::{ Length, make }

            ## Attempt to bypass the smart constructor with raw `.()` — the
            ## newtype constructor is NOT exported, so this must be rejected.
            let forged : Length = Length.(4.0)
        """
        val r = checkMain(main)
        assertTrue(r.isFailure) {
            "Forging a newtype via raw `.()` from a non-defining module must be rejected " +
            "(constructor is private). If this SUCCEEDS it is an encapsulation leak."
        }
        val msg = r.exceptionOrNull()?.message ?: ""
        assertTrue(
            msg.contains("private to the defining module") || msg.contains("not exported")
                || msg.contains("M0803"),
            "Rejection should be the constructor-privacy error; got: $msg"
        )
    }

    // Companion to case 3: the same `Length.(4.0)` IS allowed INSIDE the
    // defining module — confirming the barrier is module-scoped, not a blanket
    // ban. (A defines and uses its own ctor freely.)
    @Test
    fun `case3b — defining module may construct with raw ctor`() {
        val main = """
            pub newtype Length = Float
            let w : Length = Length.(4.0)
        """
        val r = checkMain(main)
        assertTrue(r.isSuccess) {
            "The defining module may use its own raw newtype ctor; got: ${r.exceptionOrNull()?.message}"
        }
        assertEquals("Length", r.getOrThrow().getVariableType("w")?.let { prettyPrintType(it) })
    }

    // =====================================================================
    // Case 4 — newtype-over-RECORD across modules. A exports Money (a newtype
    // over a record) + smart ctor; B consumes it and A exposes a field
    // accessor. (B cannot field-access through the opaque wrapper directly —
    // that's the same barrier — so A provides `amount_of`.)
    // =====================================================================
    @Test
    fun `case4 — newtype-over-record constructed in A, consumed in B`() {
        writeModule("money_lib", """
            pub newtype Money = { currency: Str, amount: Int }
            pub let usd : { amount: Int } -> Money = { amount } ->
              Money.({ currency = "USD", amount = amount })
            pub let amount_of : { m: Money } -> Int = { m } -> match m is
              Money.(r) -> r.amount
        """)
        val main = """
            use money_lib::{ Money, usd, amount_of }

            let price : Money = usd { amount = 500 }
            let cents : Int = amount_of { m = price }
        """
        val r = checkMain(main)
        assertTrue(r.isSuccess) {
            "cross-module newtype-over-record should typecheck; got: ${r.exceptionOrNull()?.message}"
        }
        val res = r.getOrThrow()
        assertEquals("Money", res.getVariableType("price")?.let { prettyPrintType(it) },
            "price should carry the Money wrapper across the boundary")
        assertEquals("Int", res.getVariableType("cents")?.let { prettyPrintType(it) })
    }

    // Companion to case 4: B cannot reach into the record shape hidden behind
    // the Money wrapper (the newtype is opaque across the boundary).
    //
    // WAS a real bug, FIXED by #401 and enabled — see the resolution at the end of
    // this comment. The assertion is the intended rule (a newtype-over-
    // record is OPAQUE; a consumer must use the defining module's accessors or
    // `match m is Money.(r) -> r.field`, never `.field` on the wrapper — the same
    // barrier as the private constructor in case3, and the record analog of the
    // "inner ops not available" opacity rule in NewtypeRulesTest). Design intent
    // is confirmed by ImportContextMerger.kt:39 ("Newtypes and records stay
    // nominal (intentional barriers)").
    //
    // FINDING: `.field` currently punches straight THROUGH the wrapper. A field
    // access lowers to a `Constraint.HasField` (ExprRecordConstraints.generateMember-
    // Access), and `ConstraintSolver.solveHasField` (ConstraintSolver.kt:1039-1092)
    // unwraps the newtype TRANSPARENTLY with no opacity/visibility check: a
    // `TypeApplication` expanding to `TypeExpansionResult.Newtype` recurses into
    // the newtype's single-ctor variant, whose payload record then satisfies the
    // field lookup. The mechanism is module-agnostic — this is a general newtype
    // opacity hole, not merely cross-module. Contrast the constructor path, which
    // DOES enforce M0803 privacy (case3). The assertion is left UNWEAKENED.
    // #401 FIXED: solveHasField now REJECTS `.field` on any newtype (absolute
    // opacity, no shape/module special-casing). Enabled.
    @Test
    fun `case4b — B cannot field-access through the opaque Money wrapper`() {
        writeModule("money_lib2", """
            pub newtype Money = { currency: Str, amount: Int }
            pub let usd : { amount: Int } -> Money = { amount } ->
              Money.({ currency = "USD", amount = amount })
        """)
        val main = """
            use money_lib2::{ Money, usd }

            let price : Money = usd { amount = 500 }
            ## Reaching `.amount` through the wrapper must fail — Money hides
            ## its inner record shape behind its (private) constructor.
            let leaked : Int = price.amount
        """
        val r = checkMain(main)
        assertTrue(r.isFailure) {
            "Field access through an opaque newtype wrapper from another module must be rejected " +
            "(no reaching past the barrier). If this SUCCEEDS it is an encapsulation leak."
        }
    }

    // =====================================================================
    // Case 5 — do-block + cross-module (ties #395/#396). A do-block binding of
    // a cross-module smart-ctor result must keep the Length wrapper. We assert
    // via the enclosing fn's return type (the do-block's last binding flows out
    // as the fn result), which renders `{} -> Length`.
    // =====================================================================
    @Test
    fun `case5 — cross-module value in a do-block binding keeps the Length wrapper`() {
        writeLengthModule()
        val main = """
            use length_lib::{ Length, make }

            let f : {} -> Length = {} -> do
              let x = make { v = 4.0 }
              x
        """
        val r = checkMain(main)
        assertTrue(r.isSuccess) {
            "cross-module do-block newtype binding should typecheck; got: ${r.exceptionOrNull()?.message}"
        }
        val fType = r.getOrThrow().getVariableType("f")?.let { prettyPrintType(it) }
        assertNotNull(fType)
        assertEquals("{} -> Length", fType,
            "do-block binding of a cross-module Length must keep its wrapper (the fn returns Length)")
    }

    // =====================================================================
    // Case 6 — same-named newtypes in DIFFERENT modules are DISTINCT. A.Id and
    // B.Id both `newtype Id = Str`. A REAL cross-module version of the fake Q5.
    // A function typed for A's Id must reject a B-produced Id (and vice versa).
    // =====================================================================
    // WAS a real bug, FIXED by #400 and enabled — see the resolution at the end of
    // this comment. Two modules each declaring `newtype Id = Str` are
    // intended to be DISTINCT nominal types; passing a mod_b Id where a mod_a Id
    // is expected must be rejected. The unit-level NominalHashIdentityTest already
    // asserts this at the `unify()` primitive (same-named newtypes with different
    // hashes must NOT unify) — but that test hand-builds `TypeIdentity.Declared`
    // identities the real pipeline never produces for newtypes.
    //
    // FINDING: same-named cross-module newtypes COLLIDE end-to-end. A newtype is
    // ALWAYS constructed with `identity = TypeIdentity.Anonymous` — every
    // construction site (TypedModuleEmitter.kt:266, ExprFunctionConstraints.kt:116,
    // PatternConstraints.kt:99/103) uses the 3-arg NewtypeType ctor and never
    // stamps `Declared`; TypeAliasBuilder.addParsedTypeDecl has no ParsedNewtypeDef
    // case. So in Unification.kt the NewtypeType/NewtypeType branch (~lines 380-405)
    // takes the `else` arm and decides identity by bare `type1.name == type2.name`
    // → "Id" == "Id" → unify(Str, Str) succeeds. Compounding it, the newtype
    // content hash (ParsedDeclHasher) folds in only name + wrapped body, no module/
    // decl-site salt, so even a stamped `Declared` hash would collide. The
    // assertion is left UNWEAKENED. (case6b confirms each module's own Id round-
    // trips, so the collision is genuinely the cross-module mismatch.)
    // #400 FIXED: newtypes now carry a module-salted TypeIdentity.Declared, and the
    // Unification NewtypeType branch keys on that (name-fallback only when a side is
    // hashless). Two modules' `Id` get different decl-site-salted hashes ⇒ DISTINCT.
    @Test
    fun `case6 — same-named newtypes in different modules stay distinct`() {
        writeModule("mod_a", """
            pub newtype Id = Str
            pub let a_id : { s: Str } -> Id = { s } -> Id.(s)
            pub let take_a : { x: Id } -> Int = { x } -> 1
        """)
        writeModule("mod_b", """
            pub newtype Id = Str
            pub let b_id : { s: Str } -> Id = { s } -> Id.(s)
        """)
        // Cross feed: pass mod_b's Id into mod_a's take_a. Distinct nominal
        // types ⇒ this must be rejected. If it SUCCEEDS, same-named newtypes
        // are colliding across modules (a real cross-module identity bug).
        val main = """
            use mod_a::{ take_a }
            use mod_b::{ b_id }

            let bad : Int = take_a { x = b_id { s = "hi" } }
        """
        val r = checkMain(main)
        assertTrue(r.isFailure) {
            "mod_a::Id and mod_b::Id must be DISTINCT — passing a mod_b Id where a mod_a Id " +
            "is expected must be rejected. If it typechecks, same-named newtypes collide."
        }
    }

    // Companion to case 6: each module's own Id round-trips fine — the failure
    // above is genuinely the cross-module mismatch, not a broken import.
    @Test
    fun `case6b — each module's own Id round-trips`() {
        writeModule("mod_a2", """
            pub newtype Id = Str
            pub let a_id : { s: Str } -> Id = { s } -> Id.(s)
            pub let take_a : { x: Id } -> Int = { x } -> 1
        """)
        val main = """
            use mod_a2::{ a_id, take_a }
            let ok : Int = take_a { x = a_id { s = "hi" } }
        """
        val r = checkMain(main)
        assertTrue(r.isSuccess) {
            "A module's own Id must round-trip; got: ${r.exceptionOrNull()?.message}"
        }
        assertEquals("Int", r.getOrThrow().getVariableType("ok")?.let { prettyPrintType(it) })
    }

    // =====================================================================
    // #400 — SAME-MODULE nominal distinctness. Two newtypes over the SAME
    // underlying type in ONE module are DISTINCT nominal types: same underlying
    // ≠ same type. Assigning one where the other is expected must be rejected.
    // =====================================================================
    @Test
    fun `same-module newtypes over the same underlying are distinct`() {
        val main = """
            newtype Length = Float
            newtype Age = Float

            let take_len : { l: Length } -> Int = { l } -> 1

            let a : Age = Age.(3.0)
            ## Passing an Age where a Length is expected must be rejected —
            ## Length and Age are distinct nominal types despite both wrapping Float.
            let bad : Int = take_len { l = a }
        """
        val r = checkMain(main)
        assertTrue(r.isFailure) {
            "Length and Age both wrap Float but are DISTINCT newtypes — an Age must not " +
            "be accepted where a Length is required. If it typechecks, same-underlying newtypes collide."
        }
    }

    // Companion: a Length round-trips as a Length within the same module (a
    // legitimate same-declaration unification must still succeed).
    @Test
    fun `same-module newtype round-trips as itself`() {
        val main = """
            newtype Length = Float
            let take_len : { l: Length } -> Int = { l } -> 1
            let x : Length = Length.(3.0)
            let ok : Int = take_len { l = x }
        """
        val r = checkMain(main)
        assertTrue(r.isSuccess) {
            "A Length must unify with a Length from the same declaration; got: ${r.exceptionOrNull()?.message}"
        }
        assertEquals("Int", r.getOrThrow().getVariableType("ok")?.let { prettyPrintType(it) })
    }

    // =====================================================================
    // #400 — GENERIC newtypes. `newtype Box 'a = 'a` and `newtype Other 'a = 'a`
    // are distinct nominal type constructors (Box Int ≠ Other Int), and a single
    // constructor is distinct across its own arg (Box Int ≠ Box Str). The generic
    // construction path must ALSO stamp a proper Declared identity, not Anonymous.
    // =====================================================================
    @Test
    fun `generic newtypes with the same shape are distinct constructors`() {
        val main = """
            newtype Box 'a = 'a
            newtype Other 'a = 'a

            let take_box : { b: Box Int } -> Int = { b } -> 1

            let o : Other Int = Other.(5)
            ## Other Int must not satisfy a Box Int parameter — distinct constructors.
            let bad : Int = take_box { b = o }
        """
        val r = checkMain(main)
        assertTrue(r.isFailure) {
            "Box 'a and Other 'a are distinct newtype constructors — Other Int must not be accepted " +
            "where Box Int is required. If it typechecks, generic newtypes collide (Anonymous identity leak)."
        }
    }

    @Test
    fun `generic newtype rejects a different type argument`() {
        val main = """
            newtype Box 'a = 'a
            let take_box_int : { b: Box Int } -> Int = { b } -> 1
            let s : Box Str = Box.("hi")
            ## Box Str must not satisfy Box Int — the wrapped arg differs.
            let bad : Int = take_box_int { b = s }
        """
        val r = checkMain(main)
        assertTrue(r.isFailure) {
            "Box Str must not be accepted where Box Int is required — the wrapped type argument differs."
        }
    }

    @Test
    fun `generic newtype round-trips at matching argument`() {
        val main = """
            newtype Box 'a = 'a
            let take_box_int : { b: Box Int } -> Int = { b } -> 1
            let b : Box Int = Box.(5)
            let ok : Int = take_box_int { b = b }
        """
        val r = checkMain(main)
        assertTrue(r.isSuccess) {
            "Box Int must unify with Box Int; got: ${r.exceptionOrNull()?.message}"
        }
        assertEquals("Int", r.getOrThrow().getVariableType("ok")?.let { prettyPrintType(it) })
    }

    // =====================================================================
    // #401 — ABSOLUTE OPACITY. A newtype wraps ANYTHING opaquely: the ONLY
    // door to the underlying is `match Name.(v)` — `.field` / raw-underlying
    // access NEVER punches through, in ANY module (even the defining one),
    // regardless of inner shape (record / variant / primitive / type-var).
    // These tests pin every shape SAME-MODULE and CROSS-MODULE, plus the
    // CONTRAST that plain records and transparent aliases stay transparent.
    // =====================================================================

    // ---- newtype-over-record ----

    @Test
    fun `opacity — same-module newtype-over-record field rejected`() {
        val main = """
            newtype Money = { currency: Str, amount: Int }
            let m : Money = Money.({ currency = "USD", amount = 500 })
            ## .amount must NOT punch through the opaque wrapper, even here.
            let leaked : Int = m.amount
        """
        val r = checkMain(main)
        assertTrue(r.isFailure) {
            "Field access through a newtype-over-record must be rejected in the defining module too."
        }
    }

    @Test
    fun `opacity — same-module newtype-over-record unwrap via match works`() {
        val main = """
            newtype Money = { currency: Str, amount: Int }
            let m : Money = Money.({ currency = "USD", amount = 500 })
            let amount : Int = match m is Money.({ amount = a, currency = _ }) -> a
        """
        val r = checkMain(main)
        assertTrue(r.isSuccess) {
            "Unwrapping a newtype-over-record via match must work; got: ${r.exceptionOrNull()?.message}"
        }
        assertEquals("Int", r.getOrThrow().getVariableType("amount")?.let { prettyPrintType(it) })
    }

    // ---- newtype-over-primitive (the Bug-1 case) ----

    @Test
    fun `opacity — same-module newtype-over-primitive dot-val rejected`() {
        val main = """
            newtype Money = Int
            ## `.val` is meaningless on a newtype over Int — must be a type error,
            ## not a runtime crash (Bug 1).
            let leaked : Int = Money.(350).val
        """
        val r = checkMain(main)
        assertTrue(r.isFailure) {
            "`.val` on a newtype-over-primitive must be a TYPE error (Bug 1), got success."
        }
    }

    @Test
    fun `opacity — same-module newtype-over-primitive unwrap via match works`() {
        val main = """
            newtype Money = Int
            let n : Int = match Money.(350) is Money.(v) -> v + 1
        """
        val r = checkMain(main)
        assertTrue(r.isSuccess) {
            "Unwrapping a newtype-over-primitive via match must work; got: ${r.exceptionOrNull()?.message}"
        }
        assertEquals("Int", r.getOrThrow().getVariableType("n")?.let { prettyPrintType(it) })
    }

    // ---- newtype-over-variant ----

    @Test
    fun `opacity — same-module newtype-over-variant field rejected`() {
        val main = """
            type Inner = A { x: Int } | B { y: Int }
            newtype Wrapped = Inner
            let w : Wrapped = Wrapped.(A { x = 1 })
            ## Cannot reach the inner variant's field without unwrapping first.
            let leaked : Int = w.x
        """
        val r = checkMain(main)
        assertTrue(r.isFailure) {
            "Field access through a newtype-over-variant must be rejected."
        }
    }

    // ---- newtype-over-typevar ----

    @Test
    fun `opacity — same-module newtype-over-typevar underlying access rejected`() {
        val main = """
            newtype Box 'a = 'a
            newtype Rec = { a: Int }
            let b : Box Rec = Box.(Rec.({ a = 1 }))
            ## Cannot reach into the wrapped 'a through the Box wrapper.
            let leaked : Int = b.a
        """
        val r = checkMain(main)
        assertTrue(r.isFailure) {
            "Underlying access through a newtype-over-typevar must be rejected."
        }
    }

    // ---- CONTRAST: plain record + transparent alias STAY transparent ----

    @Test
    fun `contrast — plain record field still works`() {
        val main = """
            let r : { a: Int, b: Str } = { a = 1, b = "x" }
            let got : Int = r.a
        """
        val r = checkMain(main)
        assertTrue(r.isSuccess) {
            "Plain record .field must stay transparent; got: ${r.exceptionOrNull()?.message}"
        }
        assertEquals("Int", r.getOrThrow().getVariableType("got")?.let { prettyPrintType(it) })
    }

    @Test
    fun `contrast — transparent alias field still works`() {
        val main = """
            type Point = { x: Int, y: Int }
            let p : Point = { x = 3, y = 4 }
            let got : Int = p.x
        """
        val r = checkMain(main)
        assertTrue(r.isSuccess) {
            "Transparent alias .field must stay transparent; got: ${r.exceptionOrNull()?.message}"
        }
        assertEquals("Int", r.getOrThrow().getVariableType("got")?.let { prettyPrintType(it) })
    }

    // ---- CROSS-MODULE opacity for primitive + variant + typevar shapes ----

    @Test
    fun `opacity — cross-module newtype-over-primitive dot-val rejected`() {
        writeModule("money_prim", """
            pub newtype Money = Int
            pub let of : { n: Int } -> Money = { n } -> Money.(n)
        """)
        val main = """
            use money_prim::{ Money, of }
            let m : Money = of { n = 350 }
            let leaked : Int = m.val
        """
        val r = checkMain(main)
        assertTrue(r.isFailure) {
            "`.val` on a cross-module newtype-over-primitive must be a type error."
        }
    }

    @Test
    fun `opacity — cross-module newtype-over-typevar underlying access rejected`() {
        writeModule("box_lib", """
            pub newtype Box 'a = 'a
            pub newtype Rec = { a: Int }
            pub let boxed : {} -> Box Rec = {} -> Box.(Rec.({ a = 1 }))
        """)
        val main = """
            use box_lib::{ Box, Rec, boxed }
            let b : Box Rec = boxed {}
            let leaked : Int = b.a
        """
        val r = checkMain(main)
        assertTrue(r.isFailure) {
            "Underlying access through a cross-module newtype-over-typevar must be rejected."
        }
    }
}
