package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A constructor NAME does not identify a constructor.
 *
 * Two stdlib modules declare one called `Timeout` — `std::effects::http`'s
 * `HttpError.Timeout { url, ms }` and `std::effects::async`'s
 * `CancelReason.Timeout { deadline_ms }` — and any module that reaches for both
 * (every retrying HTTP call does: it needs `Http` and an async runtime) has two
 * candidates under one name. Which one a construction MEANS is decided by the
 * solver, from the fields.
 *
 * The eager arity check used to walk the declarations and throw on the first one
 * whose fields disagreed, which reads a set of ALTERNATIVES as a conjunction: a
 * correct `Timeout { url = "x", ms = 100 }` was rejected for not being
 * `Timeout { deadline_ms }`, and which of the two judged you came down to
 * declaration-map order. The check now asks the question the solver asks — is
 * there a candidate these fields fit? — and only reports when none is.
 */
class ConstructorNameCollisionTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val bothInScope = """
        use std::effects::http::{ HttpError }
        use std::effects::async::{ CancelReason }
    """.trimIndent()

    @Test
    fun `the http Timeout is constructible with both modules in scope`() {
        assertEquals(
            "x",
            MezzeTestHelper.run(
                """
                $bothInScope
                let e: HttpError = Timeout { url = "x", ms = 100 }
                match e is
                  Timeout { url, ms } -> url
                  _ -> "other"
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `the async Timeout is constructible with both modules in scope`() {
        // The same name, the other member of the candidate set. Both have to work
        // in ONE scope, which is what rules out "resolve the name once, globally".
        assertEquals(
            100L,
            MezzeTestHelper.run(
                """
                $bothInScope
                let r: CancelReason = Timeout { deadline_ms = 100 }
                match r is
                  Timeout { deadline_ms } -> deadline_ms
                  _ -> 0
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `fields that fit neither candidate are still an arity error`() {
        // The check is relaxed to a disjunction, NOT removed: a literal no candidate
        // accepts is still rejected. `{ url }` alone is neither `{ url, ms }` nor
        // `{ deadline_ms }`.
        val e = runCatching {
            MezzeTestHelper.run(
                """
                $bothInScope
                let e: HttpError = Timeout { url = "x" }
                match e is
                  _ -> "unreachable"
                """.trimIndent(),
            )
        }.exceptionOrNull()
        assertTrue(
            e?.message?.contains("Variant constructor Timeout expects fields ms, url") == true,
            "expected the arity error named against the NEAREST candidate; got: ${e?.message}",
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
