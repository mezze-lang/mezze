package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Issue #231: regressions in inherent-method dispatch surfaced after
 * adding `Sized` to the prelude (#224) and the receiver-driven
 * dispatch preference (#229).
 *
 * Each test is a known-broken call that ALSO worked before any of
 * these landed. Locks in the expected behaviour so a future fix
 * surfaces immediately.
 */
class InherentMethodDispatchRegressionTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun check(code: String): TypeCheckResult {
        val parsed = parser.parseParsedModule(code, "test.mz")
        return typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = true)
    }

    @Test
    fun `Str inherent to_upper resolves via method syntax`() {
        // No Sized-overlap here — pure inherent dispatch on Str.
        // Should NOT throw "Cannot access field 'to_upper' on non-record type".
        val r = check("""let n = "hi".to_upper {}""")
        assertEquals(MezzeType.StringType, r.getVariableType("n"))
    }

    @Test
    fun `Int inherent abs resolves via method syntax`() {
        val r = check("let n = 5.abs {}")
        assertEquals(MezzeType.IntType, r.getVariableType("n"))
    }

    @Test
    fun `Int inherent is_even resolves via method syntax`() {
        val r = check("let b = 4.is_even {}")
        assertEquals(MezzeType.BoolType, r.getVariableType("b"))
    }

    @Test
    fun `Str size via method syntax routes to inherent or Sized impl`() {
        // Either Str.size (inherent) or Sized.size (ability) — both
        // exist now; either should resolve to Int.
        val r = check("""let n = "hi".size {}""")
        assertEquals(MezzeType.IntType, r.getVariableType("n"))
    }

    @Test
    fun `Seq size via method syntax`() {
        val r = check("let n = [1, 2, 3].size {}")
        assertEquals(MezzeType.IntType, r.getVariableType("n"))
    }

    @Test
    fun `Seq is_empty via method syntax`() {
        val r = check("let b = [1, 2, 3].is_empty {}")
        assertEquals(MezzeType.BoolType, r.getVariableType("b"))
    }

    /**
     * Probe: if all the above fail with "Cannot access field 'X' on
     * non-record type", `findAnonymousMethodsByName` is returning empty
     * for those names — i.e. the inherent impls from `impl Str where {…}`
     * never made it into the typing context.
     *
     * Useful when chasing the regression: this test PASSES if the impls
     * were registered (regardless of dispatch outcome). Failing here
     * means look at ContextBuilder.registerImportedInherentImpl wiring.
     */
    @Test
    fun `Str inherent impl operations are registered in the typing context`() {
        // Try a NON-dispatched probe: explicit qualified call works even
        // when method-syntax doesn't, because the qualified call resolves
        // to the free function, not via inherent registration.
        val r = check("""use std::str
                       |let n = str::to_upper { s = "hi" }""".trimMargin())
        // Pure smoke — if this fails, the str module didn't load.
        assertEquals(MezzeType.StringType, r.getVariableType("n"))
    }

    /**
     * User-reported regression: `let has_head = { xs } -> xs.first {}.is_some {}`
     * Two chained inherent calls on a closed-record-inferred receiver.
     */
    @Test
    fun `chained inherent method call on closed-record receiver`() {
        val r = check("let has_head = { xs } -> xs.first {}.is_some {}")
        // The outer .is_some {} should produce Bool — exact lambda type
        // depends on row inference, but typechecking must not fail.
        assertNotNull(r.getVariableType("has_head"))
    }

    /**
     * Issue #160: an inherent-method CHAIN on a FORWARD-REFERENCED
     * polymorphic call, inside STRING INTERPOLATION. All three conditions
     * were required to trigger T0206:
     *   - interpolation `"$( … )"` — its holes were invisible to
     *     ParsedExpr.children(), so `collectReferencedNames` couldn't see
     *     `help` and dependencyOrderedDecls never ordered help before g.
     *   - forward reference — help declared AFTER its caller; ordering would
     *     otherwise have inferred help's scheme first (help-before worked).
     *   - a multi-method chain — `.abs {}` is the deferred inherent method
     *     whose receiver (help's return) stayed a free tyvar.
     * Fix: ParsedStringLit.children() now yields its interpolation exprs.
     */
    @Test
    fun `interpolated inherent chain on forward-ref poly call typechecks`() {
        // Before the fix this threw T0206 (`abs` receiver undetermined).
        // A resolved `g` type proves the whole module typechecked.
        val r = check(
            """let g = {} -> "${'$'}(help { a = 5, b = 6 }.abs {}.to_str {})"
              |let help = { a, b } -> a + b""".trimMargin()
        )
        assertNotNull(r.getVariableType("g"))
    }

    @Test
    fun `interpolated single inherent method on forward-ref poly call typechecks`() {
        val r = check(
            """let g = {} -> "${'$'}(help { a = 5, b = 6 }.to_str {})"
              |let help = { a, b } -> a + b""".trimMargin()
        )
        assertNotNull(r.getVariableType("g"))
    }

    /**
     * User-reported regression: a multi-candidate inherent method
     * (`get_or` lives on Result + Option + Map) chained directly onto an
     * inherent method whose result is a named `Result`, where the inner
     * method's RECEIVER is bound by a `match … is Ok { val }` pattern:
     *
     *     match r is
     *       Ok { val } -> val.decode {}.get_or { def = "?" }   // decode : … -> Result Str Str
     *       Err { error = _ } -> "e"
     *
     * `val`'s payload TyVar isn't solved at generation time, so the
     * syntactic `getReceiverConcreteType` walk reported the whole
     * `val.decode {}` chain as "unresolved" — routing the outer `.get_or`
     * into the multi-candidate deferral, which bailed to a STRUCTURAL
     * field access and failed with
     *   T0205: Cannot access field 'get_or' on variant type: | Ok {…}, Err {…} |
     * even though `.decode {}`'s generated result type already carried the
     * concrete `Result` identity. Fix: dispatch multi-candidate inherent
     * calls by the GENERATED receiver type when it is concrete + resolves
     * to a unique target. `let`-binding the intermediary always worked; the
     * direct chain must too. See project_result_option_two_representations.
     */
    @Test
    fun `multi-candidate get_or chained on inherent Result result with match-bound receiver`() {
        val r = check(
            """use std::result::{ get_or }
              |type Wrap = { inner: Str }
              |impl Wrap where {
              |  decode = { it } -> Ok { val = it.inner }
              |}
              |let f = { r: Result Wrap Str } -> match r is
              |  Ok { val } -> val.decode {}.get_or { def = "?" }
              |  Err { error = _ } -> "e"""".trimMargin()
        )
        // A resolved `f : … -> Str` proves the chain dispatched by identity
        // instead of collapsing to a structural field access (T0205).
        assertEquals(MezzeType.StringType, (r.getVariableType("f") as? MezzeType.FunctionType)?.returnType)
    }

    /**
     * Companion to the above: the `let`-bound intermediary form ALWAYS
     * worked (the binding's type is solved before `.get_or` generates).
     * Kept so a future change that "fixes" the chain by breaking the
     * binding path is caught.
     */
    @Test
    fun `multi-candidate get_or on let-bound inherent Result result`() {
        val r = check(
            """use std::result::{ get_or }
              |type Wrap = { inner: Str }
              |impl Wrap where {
              |  decode = { it } -> Ok { val = it.inner }
              |}
              |let f = { r: Result Wrap Str } -> match r is
              |  Ok { val } -> do
              |    let d = val.decode {}
              |    d.get_or { def = "?" }
              |  Err { error = _ } -> "e"""".trimMargin()
        )
        assertEquals(MezzeType.StringType, (r.getVariableType("f") as? MezzeType.FunctionType)?.returnType)
    }
}
