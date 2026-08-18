package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Broad effect-soundness sweep — pins that a function performing an effect must
 * declare it, across every callee position, through associated types, and
 * through higher-order forwarding. Companion to [EffectThroughFieldCallTest]
 * (#195) and [EffectThroughBranchCallTest] (#197), which cover the two positions
 * that were once holes; this covers the positions that were always sound, so a
 * regression in effect tracking can't slip through unnoticed.
 *
 * Sources are inline (do-blocks are indentation-sensitive; splicing a `$const`
 * corrupts them). The reusable effect + effectful fn is repeated per source.
 */
class EffectSoundnessSweepTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    // ── the core rule: perform ⇒ must declare ────────────────────────────

    @Test fun `performing an effect in a pure function is rejected`() {
        val e = err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            let f: {} -> Str = {} -> do
              Log.log { msg = "x" }
              "done"
        """)
        assertNotNull(e); assertTrue(e!!.contains("Log"))
    }

    @Test fun `performing an effect declared in the row is accepted`() {
        assertNull(err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            let f: {} -> Str <Log> = {} -> do
              Log.log { msg = "x" }
              "done"
        """))
    }

    @Test fun `declaring the wrong effect is rejected`() {
        val e = err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            effect Boom where { boom : {} -> {} <Boom> }
            let f: {} -> Str <Boom> = {} -> do
              Log.log { msg = "x" }
              "done"
        """)
        assertNotNull(e)
    }

    @Test fun `calling an effectful helper from a pure function is rejected`() {
        val e = err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            let helper: {} -> {} <Log> = {} -> Log.log { msg = "y" }
            let caller: {} -> Str = {} -> do
              helper {}
              "done"
        """)
        assertNotNull(e); assertTrue(e!!.contains("Log"))
    }

    // ── effect hidden in a subexpression must still surface ──────────────

    @Test fun `effect in a record field value surfaces`() {
        assertNotNull(err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            let noisy: {} -> Str <Log> = {} -> do
              Log.log { msg = "z" }
              "v"
            let f: {} -> { a: Str } = {} -> { a = noisy {} }
        """))
    }

    @Test fun `effect in a seq element surfaces`() {
        assertNotNull(err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            let noisy: {} -> Str <Log> = {} -> do
              Log.log { msg = "z" }
              "v"
            let f: {} -> [Str] = {} -> [noisy {}]
        """))
    }

    @Test fun `effect in a match scrutinee surfaces`() {
        assertNotNull(err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            let noisy: {} -> Str <Log> = {} -> do
              Log.log { msg = "z" }
              "v"
            let f: {} -> Str = {} ->
              match noisy {} is
                _ -> "ok"
        """))
    }

    @Test fun `effect in an if condition surfaces`() {
        assertNotNull(err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            let noisyB: {} -> Bool <Log> = {} -> do
              Log.log { msg = "z" }
              True
            let f: {} -> Str = {} ->
              if noisyB {} then "t" else "e"
        """))
    }

    @Test fun `effect nested in a record inside a seq surfaces`() {
        assertNotNull(err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            let noisy: {} -> Str <Log> = {} -> do
              Log.log { msg = "z" }
              "v"
            let f: {} -> [{ a: Str }] = {} -> [{ a = noisy {} }]
        """))
    }

    // ── effect through every callee position (all sound) ─────────────────

    @Test fun `calling a do-block result that is an effectful fn is rejected when pure`() {
        assertNotNull(err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            let noisy: {} -> Str <Log> = {} -> do
              Log.log { msg = "z" }
              "v"
            let f: {} -> Str = {} ->
              (do
                let x = 1
                noisy) {}
        """))
    }

    @Test fun `calling a curried effectful result is rejected when pure`() {
        assertNotNull(err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            let noisy: {} -> Str <Log> = {} -> do
              Log.log { msg = "z" }
              "v"
            let mk: { a: Int } -> ({} -> Str <Log>) = { a } -> noisy
            let f: {} -> Str = {} -> (mk { a = 1 }) {}
        """))
    }

    @Test fun `calling a fn returned from another call is rejected when pure`() {
        assertNotNull(err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            let noisy: {} -> Str <Log> = {} -> do
              Log.log { msg = "z" }
              "v"
            let mk: {} -> ({} -> Str <Log>) = {} -> noisy
            let f: {} -> Str = {} -> (mk {}) {}
        """))
    }

    @Test fun `calling an effectful fn from a spread record field is rejected when pure`() {
        assertNotNull(err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            let noisy: {} -> Str <Log> = {} -> do
              Log.log { msg = "z" }
              "v"
            let f: {} -> Str = {} -> do
              let base = { g = noisy }
              let more = { ...base }
              (more.g) {}
        """))
    }

    @Test fun `calling the result of a match nested in a match arm is rejected when pure`() {
        assertNotNull(err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            let noisy: {} -> Str <Log> = {} -> do
              Log.log { msg = "z" }
              "v"
            let p: {} -> Str = {} -> "p"
            let f: {} -> Str = {} ->
              (match True is
                True ->
                  match False is
                    True -> p
                    False -> noisy
                False -> p) {}
        """))
    }

    // ── higher-order forwarding via a polymorphic effect row ─────────────

    @Test fun `a HOF forwarding an effectful callback surfaces the effect to a pure caller`() {
        assertNotNull(err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            let apply: { f: {} -> Str <...> } -> Str <...> = { f } -> f {}
            let noisy: {} -> Str <Log> = {} -> do
              Log.log { msg = "z" }
              "v"
            let g: {} -> Str = {} -> apply { f = noisy }
        """))
    }

    @Test fun `the same HOF with an effect-declaring caller is accepted`() {
        assertNull(err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            let apply: { f: {} -> Str <...> } -> Str <...> = { f } -> f {}
            let noisy: {} -> Str <Log> = {} -> do
              Log.log { msg = "z" }
              "v"
            let g: {} -> Str <Log> = {} -> apply { f = noisy }
        """))
    }

    // ── effect associated types (State having 'state) ────────────────────

    private val STATE = """
        effect State having 'state where {
          get: {} -> 'state <State>,
          put: {value: 'state} -> {} <State>
        }
    """.trimIndent()

    @Test fun `putting a wrong-typed value against a declared associated type is rejected`() {
        assertNotNull(err("""
            $STATE
            let f: {} -> {} <State having 'state = Int> = {} -> do
              State.put { value = "not an int" }
        """))
    }

    @Test fun `putting a matching value against a declared associated type is accepted`() {
        assertNull(err("""
            $STATE
            let f: {} -> {} <State having 'state = Int> = {} -> do
              State.put { value = 5 }
        """))
    }

    @Test fun `inconsistent associated types across two performs are rejected`() {
        assertNotNull(err("""
            $STATE
            let f = {} -> do
              State.put { value = 5 }
              State.put { value = "str" }
              State.get {}
        """))
    }

    @Test fun `an impl returning the wrong associated type is rejected`() {
        assertNotNull(err("""
            $STATE
            type Counter = { n: Int }
            impl State for Counter having 'state = Int where {
              get = {} -> "not int",
              put = {value} -> {}
            }
        """))
    }

    @Test fun `a correct associated-type impl is accepted`() {
        assertNull(err("""
            $STATE
            type Counter = { n: Int }
            impl State for Counter having 'state = Int where {
              get = {} -> 42,
              put = {value} -> {}
            }
        """))
    }

    // ── control: pure functions and stored (uncalled) effectful fns ──────

    @Test fun `a pure function through any of these positions stays pure`() {
        assertNull(err("""
            let pureFn: {} -> Str = {} -> "v"
            let f: {} -> Str = {} -> do
              let r = { g = pureFn }
              (r.g) {}
        """))
    }

    @Test fun `storing an effectful fn without calling it adds no effect`() {
        assertNull(err("""
            effect Log where { log : {msg: Str} -> {} <Log> }
            let noisy: {} -> Str <Log> = {} -> do
              Log.log { msg = "z" }
              "v"
            let f: {} -> Str = {} -> do
              let r = { g = noisy }
              "not called"
        """))
    }
}
