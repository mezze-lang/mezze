package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.ast.MezzeType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * BUG C (#331): an effectful callback must not be laundered into a pure-declared
 * function when it reaches an effectful `<...>` HOF slot through a POLYMORPHIC
 * intermediary.
 *
 * The `<...>` callback-effect harvest (`collectClosureArgEffects`) runs at
 * constraint-generation time and skips any argument field that is not yet a
 * concrete `FunctionType` (`fieldType as? FunctionType ?: continue`). Routing the
 * callback through `id: {g: 'a} -> 'a` makes the field a `VarType` at that read,
 * so its `<Log>` was dropped and a pure `go` was wrongly accepted. The fix
 * re-harvests the SOLVED argument at decl finalization and folds the recovered
 * effects into the under-promise check.
 *
 * Uses a real effectful stdlib op (`println` from `std::effects::log`) as the
 * effectful value: a hand-rolled `perform Log in emit` is rejected as "Unknown
 * performer" without a handler, which would make the test pass trivially.
 */
class EffectLaunderPolyIntermediaryTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() { ModuleResolver.clearCache(); TypeChecker.clearCache(); parser = MezzeParser(); tc = TypeChecker() }

    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    private val prelude = "use std::effects::log::{Console, println}"

    @Test @Timeout(60, unit = TimeUnit.SECONDS)
    fun `effectful callback direct into effectful slot forces caller to carry the effect`() {
        // Sound baseline: leaky's <Log> propagates, so declaring go pure is rejected.
        val msg = err("""
            $prelude
            let leaky: {} -> {} <Log> = {} -> println { msg = "x" }
            let apply_eff: { f: {} -> {} <...> } -> {} <...> = { f } -> f {}
            let go: {} -> {} = {} -> apply_eff { f = leaky }
        """)
        assertNotNull(msg, "go is pure but apply_eff propagates leaky's Log — must be rejected")
        assertTrue(msg!!.contains("Log"), "rejection should name the Log effect; got: $msg")
    }

    @Test @Timeout(60, unit = TimeUnit.SECONDS)
    fun `effectful callback through a polymorphic intermediary is not laundered`() {
        // BUG C: routing leaky through `id` erases its FunctionType to a VarType at
        // the gen-time harvest. Without the post-solve re-harvest, go was accepted
        // pure while performing Log.
        val msg = err("""
            $prelude
            let leaky: {} -> {} <Log> = {} -> println { msg = "x" }
            let apply_eff: { f: {} -> {} <...> } -> {} <...> = { f } -> f {}
            let id: { g: 'a } -> 'a = { g } -> g
            let go: {} -> {} = {} -> apply_eff { f = id { g = leaky } }
        """)
        assertNotNull(msg, "go is pure but leaky's Log flows through `id` into apply_eff — must be rejected")
        assertTrue(msg!!.contains("Log"), "rejection should name the laundered Log effect; got: $msg")
    }

    private fun emittedRow(name: String, src: String): MezzeType.EffectRow? {
        val result = tc.typeCheck(
            parser.parseParsedModule(src.trimIndent(), "t.mz"),
            Paths.get("."), enablePrelude = true
        )
        return (result.getVariableType(name) as MezzeType.FunctionType).effects
    }

    @Test @Timeout(60, unit = TimeUnit.SECONDS)
    fun `S9b poly-intermediary effect appears in the emitted row of an un-annotated fn`() {
        // #375: an UN-ANNOTATED `go` whose callback reaches an effectful `<...>` HOF
        // through the polymorphic intermediary `id` must carry `<Log>` in its
        // EMITTED/inferred row — not just have the validator know about it. Before
        // the S9b fix the gen-time harvest skipped the still-VarType callback field
        // and the emitted row came out EMPTY (type-dishonest, though still sound).
        val viaId = emittedRow("go", """
            $prelude
            let leaky: {} -> {} <Log> = {} -> println { msg = "x" }
            let apply_eff: { f: {} -> {} <...> } -> {} <...> = { f } -> f {}
            let id: { g: 'a } -> 'a = { g } -> g
            let go = {} -> apply_eff { f = id { g = leaky } }
        """)
        assertNotNull(viaId, "un-annotated go must carry an effect row")
        assertTrue(
            viaId!!.effects.contains("Log"),
            "emitted row must carry the poly-intermediary Log effect; got: $viaId"
        )
    }

    @Test @Timeout(60, unit = TimeUnit.SECONDS)
    fun `S9b a genuinely pure un-annotated fn through the intermediary stays pure`() {
        // Guard against over-attribution: a PURE callback through `id` must leave
        // go's emitted row empty (no spurious effect added).
        val row = emittedRow("go", """
            $prelude
            let pure_cb: {} -> {} = {} -> {}
            let apply_eff: { f: {} -> {} <...> } -> {} <...> = { f } -> f {}
            let id: { g: 'a } -> 'a = { g } -> g
            let go = {} -> apply_eff { f = id { g = pure_cb } }
        """)
        assertTrue(
            row == null || row.effects.isEmpty(),
            "pure callback must not add a spurious effect; got: $row"
        )
    }

    @Test @Timeout(60, unit = TimeUnit.SECONDS)
    fun `a genuinely pure callback through the intermediary stays accepted`() {
        // Guard against over-rejection: a PURE callback through `id` must still be
        // accepted for a pure-declared caller.
        assertNull(
            err("""
                $prelude
                let pure_cb: {} -> {} = {} -> {}
                let apply_eff: { f: {} -> {} <...> } -> {} <...> = { f } -> f {}
                let id: { g: 'a } -> 'a = { g } -> g
                let go: {} -> {} = {} -> apply_eff { f = id { g = pure_cb } }
            """)
        )
    }

    // ---- The same launder, one `perform` outwards.
    //
    // A pending harvest records the effects an op does NOT catch, to be re-folded
    // into the enclosing declaration once the substitution is known. Crossing a
    // `perform` used to DROP that list wholesale — so the poly-intermediary hole
    // above reopened as soon as the call sat inside any handler, whether or not
    // that handler covered the laundered effect. The boundary now widens each
    // harvest's `caughtByOp` by what the handler actually discharges, which is the
    // only answer that separates these two tests.

    private val twoEffects = """
        effect MyLog where {
          info : {msg: Str} -> {} <MyLog>
        }
        impl MyLog for TestLogger where {
          info : {msg: Str} -> {} = { msg } -> {}
        }
    """.trimIndent()

    @Test @Timeout(60, unit = TimeUnit.SECONDS)
    fun `an UNHANDLED laundered effect still surfaces through a perform boundary`() {
        // `perform TestLogger` discharges MyLog and says nothing about Log, so
        // leaky's Log must still reach the pure-declared `go` and be rejected.
        val msg = err("""
            $prelude
            $twoEffects
            let leaky: {} -> {} <Log> = {} -> println { msg = "x" }
            let apply_eff: { f: {} -> {} <...> } -> {} <...> = { f } -> f {}
            let id: { g: 'a } -> 'a = { g } -> g
            let go: {} -> {} = {} -> perform TestLogger in do
                MyLog.info {msg = "hi"}
                apply_eff { f = id { g = leaky } }
        """)
        assertNotNull(
            msg,
            "the handler covers MyLog, not Log — a perform must not discharge an effect " +
                "no handler names, and `go` is declared pure",
        )
        assertTrue(msg!!.contains("Log"), "rejection should name the laundered effect; got: $msg")
    }

    @Test @Timeout(60, unit = TimeUnit.SECONDS)
    fun `a HANDLED laundered effect is discharged by the perform and not re-added`() {
        // The mirror, and the reason this cannot be fixed by simply passing the
        // harvest through: once the handler covers the effect, re-folding it at
        // decl finalization puts back a row entry the perform just discharged.
        //
        // DIRECT, not through `id`: this test is about the harvest being
        // re-added after discharge, and the direct form isolates that. The
        // `id` form is the test below, which used to fail on a different
        // check entirely.
        assertNull(
            err("""
                $twoEffects
                let leaky: {} -> {} <MyLog> = {} -> MyLog.info { msg = "x" }
                let apply_eff: { f: {} -> {} <...> } -> {} <...> = { f } -> f {}
                let go: {} -> {} = {} -> perform TestLogger in do
                    apply_eff { f = leaky }
            """),
            "TestLogger handles MyLog, so `go` really is pure and must be accepted",
        )
    }

    @Test @Timeout(60, unit = TimeUnit.SECONDS)
    fun `a handler IS allowed to fire on an effect that arrives through the intermediary`() {
        // The false REJECTION. `leaky` performs MyLog, it reaches `apply_eff`
        // through `id`, and `perform TestLogger` handles MyLog — the program is
        // well-typed and the handler does fire.
        //
        // It was refused with "Handler 'TestLogger' handles 'MyLog', but the
        // `perform` body performs no effect — so the handler can never fire."
        // The dead-handler check runs at GENERATION time, while the recovery for
        // a callback laundered through a polymorphic intermediary (#331 BUG C)
        // happens at declaration finalization. The check was reading a row it
        // already knew to be incomplete: the body carries a pending closure
        // harvest, which is precisely the note that says so.
        assertNull(
            err("""
                $twoEffects
                let leaky: {} -> {} <MyLog> = {} -> MyLog.info { msg = "x" }
                let apply_eff: { f: {} -> {} <...> } -> {} <...> = { f } -> f {}
                let id: { g: 'a } -> 'a = { g } -> g
                let go: {} -> {} = {} -> perform TestLogger in do
                    apply_eff { f = id { g = leaky } }
            """),
            "the handler covers the effect the laundered callback performs, so the perform is valid",
        )
    }

    @Test @Timeout(60, unit = TimeUnit.SECONDS)
    fun `a genuinely dead handler is STILL rejected`() {
        // The boundary that keeps the fix from disabling the rule. Skipping the
        // check when a harvest is pending must not skip it when there is none:
        // `perform TestLogger in 1 + 2` performs nothing, by a row that is
        // complete, and is still a bug worth naming.
        val msg = err("""
            $twoEffects
            let go: {} -> Int = {} -> perform TestLogger in 1 + 2
        """)
        assertNotNull(msg, "a handler over a body that performs nothing must still be rejected")
        assertTrue(
            msg!!.contains("TestLogger") || msg.contains("MyLog"),
            "the rejection should name the dead handler or its effect; got: $msg",
        )
    }
}
