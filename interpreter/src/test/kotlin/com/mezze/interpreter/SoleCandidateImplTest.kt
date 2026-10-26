package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One candidate impl must not be handed to a receiver of another type.
 *
 * `tryResolveAbilityOp` guesses when an ability has a SOLE candidate, and that
 * guess was recorded as a latent silent-wrong-answer: "one candidate plus a
 * variant receiver returns it regardless of type — that is how a dictionary
 * slot got `C` for `Bool` at `Str`."
 *
 * It does NOT reproduce, in any of the four shapes below: each is refused with
 * "<T> does not implement 'C'". Most likely closed by the dictionary-crossing
 * fix (92f7a3a85), which made each slot resolve against the type its own
 * constraint is about. Kept as regression coverage rather than deleted, because
 * the guess itself is still there — it is observable in a different and benign
 * way, where a single candidate makes an otherwise undetermined class argument
 * come out determined (see UndeterminedClassArgTest's note on why its ability
 * needs TWO impls).
 *
 * If one of these starts returning "a bool" or "a V" instead of refusing, the
 * guess has become unsound again.
 */
class SoleCandidateImplTest {
    private val ability = """
        ability C for 'a where { c: { it: 'a } -> Str }
        let describe: { v: 'a } with C 'a -> Str = { v } -> v.c {}
    """.trimIndent()

    private fun refusal(src: String): String? =
        try { MezzeTestHelper.run(src.trimIndent()); null }
        catch (e: Throwable) { e.message?.lineSequence()?.first() }

    private fun assertRefused(what: String, src: String) {
        val message = refusal(src)
        assertTrue(
            message != null && message.contains("does not implement"),
            "$what must be refused, not guessed; got: ${message ?: "accepted"}",
        )
    }

    @Test fun `a primitive receiver gets no other type's sole impl`() {
        assertRefused(
            "a Str receiver against the only impl, which is for Bool",
            """
            $ability
            impl C for Bool where { c = { it } -> "a bool" }
            describe { v = "a string" }
            """,
        )
    }

    @Test fun `the same through a dictionary`() {
        assertRefused(
            "the same, reached through a forwarded dictionary",
            """
            $ability
            impl C for Bool where { c = { it } -> "a bool" }
            let apply: { f: { v: 'a } -> Str, v: 'a } -> Str = { f, v } -> f { v = v }
            apply { f = describe, v = "a string" }
            """,
        )
    }

    @Test fun `a variant receiver gets no other type's sole impl`() {
        assertRefused(
            "a variant receiver against a Bool impl — the shape the note named",
            """
            type V = | A, B |
            $ability
            impl C for Bool where { c = { it } -> "a bool" }
            describe { v = A }
            """,
        )
    }

    @Test fun `one variant's impl is not used for another variant`() {
        assertRefused(
            "a W receiver against the only impl, which is for V",
            """
            type V = | A, B |
            type W = | X, Y |
            $ability
            impl C for V where { c = { it } -> "a V" }
            describe { v = X }
            """,
        )
    }

    /** The control: at its OWN type the sole candidate must still be found. */
    @Test fun `the sole candidate is used at its own type`() {
        assertEquals(
            "a bool",
            MezzeTestHelper.run(
                """
                $ability
                impl C for Bool where { c = { it } -> "a bool" }
                describe { v = True }
                """.trimIndent()
            ).toString(),
        )
    }
}
