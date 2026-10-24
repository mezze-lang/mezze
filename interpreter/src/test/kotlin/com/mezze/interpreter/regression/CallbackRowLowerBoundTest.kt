package com.mezze.interpreter.regression

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `...` is zero or more effects, so a NAMED effect before it is mandatory.
 *
 * `<Boom, ...>` means `<Boom>` or `<Boom, Noise>` — never `<>`. A bare `<...>`
 * names nothing, so it accepts anything including a pure callback, which is
 * what `spawn : { f: {} -> 'a <...> }` relies on.
 *
 * The lower bound was enforced only when the effect was CAUGHT — when the
 * enclosing function's return row did NOT name it, so
 * `stampCaughtOnCallbackRows` had something to stamp. With the enclosing row
 * naming the same effect, `callbackEffects − returnEffects` is empty, nothing
 * was stamped, and the only marker the unifier had for "this row is a
 * declaration" was that stamp. So a pure callback satisfied `<Boom, ...>`.
 *
 * A row is now marked as written-on-a-callback regardless of what is caught,
 * which is what makes the bound hold in both shapes.
 */
class CallbackRowLowerBoundTest {

    private val head = """
        effect Boom where { bang: { msg: Str } -> Str <Boom> }
        effect Noise where { shout: { msg: Str } -> Str <Noise> }

        impl Boom for Banger where { bang = { msg } -> msg }
        impl Noise for Shouter where { shout = { msg } -> msg }
    """.trimIndent()

    private fun run(body: String): Any = MezzeTestHelper.run("$head\n$body")

    /**
     * THE regression: the enclosing row names Boom too, so nothing is caught
     * and no stamp existed. A pure callback must still be refused.
     */
    @Test
    fun `a pure callback does not satisfy a named callback row`() {
        val outcome = runCatching {
            run(
                """
                let runs: { f: { x: Int } -> Str <Boom, ...> } -> Str <Boom> = { f } -> f { x = 1 }
                perform Banger in runs { f = { x } -> "pure" }
                """.trimIndent()
            )
        }
        val err = outcome.exceptionOrNull()
        assertTrue(
            err != null && (err.message ?: "").contains("Boom"),
            "a pure callback must not satisfy <Boom, ...>; got ${err?.message ?: outcome.getOrNull()}",
        )
    }

    /** A callback that performs the named effect is accepted, as before. */
    @Test
    fun `a callback performing the named effect is accepted`() {
        val r = run(
            """
            let runs: { f: { x: Int } -> Str <Boom, ...> } -> Str <Boom> = { f } -> f { x = 1 }
            perform Banger in runs { f = { x } -> Boom.bang { msg = "b" } }
            """.trimIndent()
        )
        assertEquals("b", r)
    }

    /**
     * A BARE tail names nothing, so it still accepts a pure callback. This is
     * the case `spawn` depends on, and the reason the bound is on NAMED effects
     * rather than on openness.
     */
    @Test
    fun `a bare tail still accepts a pure callback`() {
        val r = run(
            """
            let runs: { f: { x: Int } -> Str <...> } -> Str <...> = { f } -> f { x = 1 }
            runs { f = { x } -> "pure" }
            """.trimIndent()
        )
        assertEquals("pure", r)
    }

    /** And the tail may carry more than the named effect. */
    @Test
    fun `a callback may perform more than the row names`() {
        val r = run(
            """
            let runs: { f: { x: Int } -> Str <Boom, ...> } -> Str <Boom, ...> = { f } -> f { x = 1 }
            perform Shouter in perform Banger in runs { f = { x } -> do
              let a = Boom.bang { msg = "b" }
              let b = Noise.shout { msg = "n" }
              a }
            """.trimIndent()
        )
        assertEquals("b", r)
    }
}
