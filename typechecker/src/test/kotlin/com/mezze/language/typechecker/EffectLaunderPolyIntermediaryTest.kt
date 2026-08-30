package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
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
}
