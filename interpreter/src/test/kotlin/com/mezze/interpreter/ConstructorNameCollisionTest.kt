package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One tag, one group per module — and what that costs a name two modules share.
 *
 * Two stdlib modules declare a `Timeout`: `std::effects::http`'s
 * `HttpError.Timeout { url, ms }` and `std::effects::async`'s
 * `CancelReason.Timeout { deadline_ms }`.
 *
 * This class used to assert that a module importing BOTH names could write
 * either one, the solver choosing from the fields. It no longer can: a tag
 * belongs to one group per module however the name arrived, so importing both
 * names is refused at the import, before any `Timeout` is written. The clash
 * exists whether or not the tag is ever used, and a module holding both names
 * has no answer for a bare one.
 *
 * The practical case the old doc raised is real — a retrying HTTP call needs
 * `Http` and an async runtime — and it still works, by the route the refusal
 * names: import one group's name and reach the other through a function in its
 * signature. A group inside an imported function's signature travels as
 * STRUCTURE; a claim comes from importing the NAME. That asymmetry is the whole
 * reason two libraries sharing a tag still compose.
 *
 * What survives unchanged from the original bug: the arity check asks the
 * question the solver asks — is there a candidate these fields fit? — rather
 * than walking declarations and throwing on the first disagreement, which read
 * a set of alternatives as a conjunction.
 */
class ConstructorNameCollisionTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    @Test
    fun `importing two groups that share a tag is refused`() {
        // The rule itself, and the former premise of this whole class.
        val e = runCatching {
            MezzeTestHelper.run(
                """
                use std::effects::http::{ HttpError }
                use std::effects::async::{ CancelReason }
                let x = 1
                x
                """.trimIndent(),
            )
        }.exceptionOrNull()
        val msg = generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        assertTrue(
            msg.contains("claimed by both") &&
                msg.contains("HttpError") && msg.contains("CancelReason"),
            "Both names claim `Timeout`, so the module has two groups for one tag. Refused at " +
                "the import — note the program never writes `Timeout`.\n\ngot: $msg",
        )
    }

    @Test
    fun `the http Timeout is constructible with its own group in scope`() {
        assertEquals(
            "x",
            MezzeTestHelper.run(
                """
                use std::effects::http::{ HttpError }
                let e: HttpError = Timeout { url = "x", ms = 100 }
                match e is
                  Timeout { url, ms } -> url
                  _ -> "other"
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `the async Timeout is constructible with its own group in scope`() {
        // The same name, the other group. Each works in its own scope, which is
        // what still rules out "resolve the name once, globally" — the two
        // declarations are both live, just never in one module at once.
        assertEquals(
            100L,
            MezzeTestHelper.run(
                """
                use std::effects::async::{ CancelReason }
                let r: CancelReason = Timeout { deadline_ms = 100 }
                match r is
                  Timeout { deadline_ms } -> deadline_ms
                  _ -> 0
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `fields that fit no candidate are still an arity error`() {
        // The arity check is a disjunction, NOT removed: a literal no candidate
        // accepts is still rejected. `{ url }` alone is not `{ url, ms }`.
        val e = runCatching {
            MezzeTestHelper.run(
                """
                use std::effects::http::{ HttpError }
                let e: HttpError = Timeout { url = "x" }
                match e is
                  _ -> "unreachable"
                """.trimIndent(),
            )
        }.exceptionOrNull()
        assertTrue(
            e?.message?.contains("Variant constructor Timeout expects fields ms, url") == true,
            "expected the arity error named against the candidate in scope; got: ${e?.message}",
        )
    }

    @Test
    fun `an unambiguous constructor still reports its own arity error`() {
        val e = runCatching {
            MezzeTestHelper.run(
                """
                use std::effects::http::{ HttpError }
                let e: HttpError = InvalidUrl { url = "x", reason = "r", extra = 1 }
                match e is
                  _ -> "unreachable"
                """.trimIndent(),
            )
        }.exceptionOrNull()
        assertTrue(
            e?.message?.contains("got extra field(s): extra") == true,
            "expected the single-candidate arity error; got: ${e?.message}",
        )
    }
}
