package com.mezze.language.typechecker
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * #215: an effectful function must NOT be accepted where a PURE callback is
 * expected. Passing `leaky : {} -> {} <Log>` (performs Log) into a parameter
 * typed `f: {} -> {}` (pure) used to type-check — `Unification.unifyFunctions`
 * had a "pure vs concrete effects" branch that returned an empty substitution,
 * silently swallowing the argument's effect row. The enclosing function then
 * type-checked as pure while performing Log at runtime (verified: the program
 * ran and emitted the effect).
 *
 * The fix routes that branch through `unifyEffectRows`, whose directional
 * extras logic rejects effects on the ACTUAL side that a CLOSED formal row
 * cannot absorb. The mirror direction — a pure lambda into an effectful
 * (`<...>`) slot — is legitimate widening and must still be accepted.
 *
 * Self-contained (inline `effect Log`) so it does not depend on the stdlib.
 */
class EffectfulIntoPureSlotSoundnessTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker
    @BeforeEach fun s() { ModuleResolver.clearCache(); TypeChecker.clearCache(); parser = MezzeParser(); tc = TypeChecker() }
    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    private val effectDecl = """
        effect Log where {
            emit: { msg: Str } -> {} <Log>
        }
    """.trimIndent()

    @Test fun `effectful callback into a pure callback slot is rejected`() {
        val msg = err(
            """
            $effectDecl
            let leaky: {} -> {} <Log> = {} -> perform Log in emit { msg = "leak" }
            let apply_pure: { f: {} -> {} } -> {} = { f } -> f {}
            let go: {} -> {} = {} -> apply_pure { f = leaky }
            """
        )
        assertNotNull(msg, "passing an effectful `{} -> {} <Log>` into a pure `f: {} -> {}` must be rejected")
        assertTrue(msg!!.contains("Log"), "the rejection should name the laundered effect Log; got: $msg")
    }

    @Test fun `a pure lambda into a pure callback slot is accepted`() {
        assertNull(
            err(
                """
                let apply_pure: { f: {} -> Int } -> Int = { f } -> f {}
                let go: {} -> Int = {} -> apply_pure { f = {} -> 5 }
                """
            )
        )
    }

    @Test fun `a pure lambda into an effectful callback slot is accepted (widening)`() {
        assertNull(
            err(
                """
                $effectDecl
                let apply_eff: { f: {} -> Int <...> } -> Int <...> = { f } -> f {}
                let go: {} -> Int = {} -> apply_eff { f = {} -> 5 }
                """
            )
        )
    }

    @Test fun `an effectful callback into an effectful slot propagates the effect`() {
        // Sound baseline: apply_eff's callback IS effectful (<...>), so leaky is
        // accepted AND go must itself carry <Log> — declaring go pure is rejected.
        assertNotNull(
            err(
                """
                $effectDecl
                let leaky: {} -> {} <Log> = {} -> perform Log in emit { msg = "x" }
                let apply_eff: { f: {} -> {} <...> } -> {} <...> = { f } -> f {}
                let go: {} -> {} = {} -> apply_eff { f = leaky }
                """
            ),
            "go is declared pure but apply_eff propagates leaky's Log, so go must be rejected",
        )
    }
}
