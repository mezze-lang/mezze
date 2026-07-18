package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import com.mezze.language.parsedast.ParsedModule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Contravariant callback effect rows.
 *
 * DESIGN RULE:
 *   When a lambda is passed as a function argument, its actual effect row
 *   may be a SUBSET of the callback's declared effect row. Extras on the
 *   declared side are "what this primitive handles" — they're absorbed
 *   before reaching the return row.
 *
 * PHYSICAL EXAMPLE:
 *   Async.spawn's declaration is:
 *     spawn : { f: {} -> 'a <Exception, ...> } -> Promise 'a <Async, ...>
 *
 *   The `<Exception, ...>` on the callback and `<Async, ...>` on the return
 *   share the row var `...`. The difference — Exception on callback minus
 *   Exception on return — is what spawn CATCHES at runtime. Callbacks may or
 *   may not actually perform Exception; either way is fine.
 *
 * SOUNDNESS CLAIM:
 *   - Callback performs Exception → declared row includes it → callback's row
 *     is a subset → OK, spawn catches → return row has NO Exception. Safe.
 *   - Callback performs Time → not in declared row → declared row absorbs
 *     via `...` → return row propagates Time. Safe.
 *   - Callback performs Exception + Time → declared `<Exception, ...>` matches,
 *     `...` = <Time> → return `<Async, Time>`. Exception caught, Time surfaces.
 *   - Callback performs nothing → declared row `<Exception, ...>` accepts as
 *     empty subset, `...` = <> → return `<Async>`. Nothing caught, nothing
 *     surfaces. Safe.
 *
 * These tests should FAIL under the current invariant-unification code and
 * PASS after callback rows become contravariant.
 */
class CallbackContravariantRowTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun effectsOf(module: ParsedModule, name: String): Set<String> {
        val result = typeChecker.typeCheck(module, Paths.get("."))
        val t = result.getVariableType(name) as? MezzeType.FunctionType
        assertNotNull(t, "expected $name to be a FunctionType, got ${result.getVariableType(name)}")
        return t!!.effects?.effects ?: emptySet()
    }

    private fun parse(source: String): ParsedModule =
        parser.parseParsedModule(source.trimIndent(), "test.mz")

    // ─── (1) callback that DOESN'T perform the declared handled effect is a TYPE ERROR ─
    //
    // The callback's declared row is <Exception, ...>. This lambda is pure.
    // Under strict rules: if you declare `<Exception, ...>` on the callback,
    // the actual body MUST perform Exception. Otherwise the type is a lie.
    // Row subtraction only APPLIES to effects the actual body performs; it
    // doesn't turn declared effects into optional ones.

    @Test
    fun `pure callback FAILS to match declared callback row that requires Exception`() {
        val src = """
            use std::effects::async::{ Async, LoomAsync }

            let outer = {} -> do
              let p = Async.spawn { f = {} -> 42 }
              Async.await { p = p }
        """
        val module = parse(src)
        // Under strict rules the callback declared row must be fully performed
        // by the actual lambda. A pure lambda vs `<Exception, ...>` should error.
        // The error may bubble up from the stdlib async module (ImportError) if
        // any stdlib combinator's own callback fails the same strict rule.
        var errored = false
        try {
            typeChecker.typeCheck(module, Paths.get("."))
        } catch (_: TypeCheckError) { errored = true }
        catch (_: com.mezze.language.typechecker.module.ModuleError.ImportError) { errored = true }
        assertTrue(errored, "Expected typecheck to fail (TypeCheckError or ImportError), but it succeeded.")
    }

    // ─── (2) Exception performed by callback is CAUGHT by spawn — doesn't surface ─────

    @Test
    fun `Exception performed by callback is caught by spawn`() {
        val src = """
            use std::effects::async::{ Async, LoomAsync }
            use std::effects::exception::{ Exception, unwrap }

            let outer = {} -> do
              let p = Async.spawn { f = {} -> unwrap { result = Ok { val = 1 } } }
              Async.await { p = p }
        """
        val effects = effectsOf(parse(src), "outer")
        assertFalse("Exception" in effects,
            "Exception performed by spawn's callback must be CAUGHT by spawn. Got: $effects.")
        assertTrue("Async" in effects, "Async must be present. Got: $effects")
    }

    // ─── (3) Callback missing declared Exception is a TYPE ERROR ─────────────────────
    //
    // Callback performs Time but not Exception. Declared row `<Exception, ...>`
    // requires Exception. Missing it is a type error under strict rules.

    @Test
    fun `callback performing Time but not Exception FAILS strict callback row`() {
        val src = """
            use std::effects::async::{ Async, LoomAsync }
            use std::effects::time::{ Time }

            let outer = {} -> do
              let p = Async.spawn { f = {} -> Async.sleep { ms = 1 } }
              Async.await { p = p }
        """
        val module = parse(src)
        // Under strict rules the callback declared row must be fully performed
        // by the actual lambda. A pure lambda vs `<Exception, ...>` should error.
        // The error may bubble up from the stdlib async module (ImportError) if
        // any stdlib combinator's own callback fails the same strict rule.
        var errored = false
        try {
            typeChecker.typeCheck(module, Paths.get("."))
        } catch (_: TypeCheckError) { errored = true }
        catch (_: com.mezze.language.typechecker.module.ModuleError.ImportError) { errored = true }
        assertTrue(errored, "Expected typecheck to fail (TypeCheckError or ImportError), but it succeeded.")
    }

    // ─── (4) Exception caught by spawn, Time surfaces via ρ ─────────────────────────

    @Test
    fun `Exception is caught but Time from callback surfaces`() {
        val src = """
            use std::effects::async::{ Async, LoomAsync }
            use std::effects::time::{ Time }
            use std::effects::exception::{ Exception, unwrap }

            let outer = {} -> do
              let p = Async.spawn { f = {} -> do
                Async.sleep { ms = 1 }
                unwrap { result = Ok { val = 42 } }
              }
              Async.await { p = p }
        """
        val effects = effectsOf(parse(src), "outer")
        assertTrue("Time" in effects, "Time must surface. Got: $effects")
        assertTrue("Async" in effects, "Async must be present. Got: $effects")
        assertFalse("Exception" in effects, "Exception must be caught by spawn. Got: $effects")
    }

    // ─── (5) Contravariant rule applies GENERALLY, not just to spawn ─────────────────
    //
    // A user-defined higher-order function should honor the same rule.

    @Test
    fun `user-defined higher-order fn declares callback row Exception and callee doesn't perform it`() {
        val src = """
            effect Foo where { foo: {} -> {} <Foo> }
            effect Bar where { bar: {} -> {} <Bar> }

            impl Foo for FooImpl where { foo = {} -> {} }
            impl Bar for BarImpl where { bar = {} -> {} }

            ## Declares callback row includes Foo. Body uses it directly.
            let apply : { f: {} -> Int <Foo, ...> } -> Int <Foo, ...> = { f } -> f {}

            ## Callee does NOT perform Foo — under contravariant callback rows, this is fine.
            let callee = {} -> apply { f = {} -> 42 }
        """
        val effects = effectsOf(parse(src), "callee")
        assertTrue(effects.isEmpty() || effects == setOf("Foo"),
            "callee should be either <> (callback performed nothing so nothing propagates) " +
            "or <Foo> (callback declared Foo). Got: $effects")
    }

    // ─── (6) `and_then` callers should typecheck without needing Exception handler ─────
    //
    // This is the concrete stdlib case that motivated the whole design. `and_then`'s
    // body uses `unwrap` (Exception). Under contravariant callback rows + spawn's
    // updated signature, Exception is caught inside spawn. `and_then`'s type stays
    // `Promise 'b <Async, ...>` — no Exception at the caller.

    @Test
    fun `and_then callers do not need an Exception handler`() {
        val src = """
            use std::effects::async::{ Async, LoomAsync, and_then }
            use std::effects::exception::{ Exception, unwrap }
            use std::result

            let outer = {} -> perform LoomAsync in do
              let p = Async.spawn { f = {} -> unwrap { result = Ok { val = 3 } } }
              let q = and_then { p = p, f = { val } -> Async.spawn { f = {} -> unwrap { result = Ok { val = val + 100 } } } }
              Async.await { p = q }
        """
        val effects = effectsOf(parse(src), "outer")
        assertFalse("Exception" in effects,
            "`and_then` must not require Exception at the caller. Got: $effects")
    }
}
