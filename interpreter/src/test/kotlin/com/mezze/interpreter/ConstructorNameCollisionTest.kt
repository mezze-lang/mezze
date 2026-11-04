package com.mezze.interpreter

import com.mezze.language.typechecker.ModuleResolver
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One tag, one group per module — and the arity check that asks which candidate
 * a literal fits.
 *
 * This class used to be written against a collision the stdlib HAPPENED to have:
 * `std::effects::http`'s `HttpError.Timeout { url, ms }` beside
 * `std::effects::async`'s `CancelReason.Timeout { deadline_ms }`. It asserted that
 * a module importing both names could write either one, the solver choosing from
 * the fields.
 *
 * Both premises are gone, in opposite directions:
 *
 *  - Holding two groups that claim one tag is REFUSED, whether the names were
 *    declared or imported, and it is refused before any `Timeout` is written.
 *  - There is no longer a stdlib pair to borrow. Error groups carry their domain
 *    (`HttpTimeout`, `DbTimeout`, `CancelTimeout`), so no two stdlib groups share
 *    a tag — which is the point of the convention, and why this class cannot
 *    depend on one. A test that needs two library types to collide breaks the
 *    next time either is renamed, which is what happened here.
 *
 * So the rule is exercised with types declared in the program itself. The IMPORT
 * route — the same rule arriving through `use` — is covered where a suite can
 * write modules: `VariantSpecCrossModuleTest`'s `importing two claimants of one
 * tag` and `one claimant declared, one imported`.
 */
class ConstructorNameCollisionTest {

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
    }

    private fun failure(source: String): String {
        val thrown = runCatching { MezzeTestHelper.run(source.trimIndent()) }
            .exceptionOrNull() ?: error("expected this program to be refused, but it ran")
        return generateSequence(thrown) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
    }

    @Test
    fun `two groups declared here that share a tag is refused`() {
        val msg = failure(
            """
            type ErrA = | Shared { msg: Str }, OnlyA |
            type ErrB = | Shared { code: Int }, OnlyB |
            let x = 1
            x
            """,
        )
        assertTrue(
            msg.contains("Shared"),
            "Both groups claim `Shared`, so the module holds two groups for one tag and has no " +
                "answer for a bare `Shared`. Note the program never writes it — the clash exists " +
                "whether or not the tag is used.\n\ngot: $msg",
        )
    }

    @Test
    fun `one group alone is fine, and its tag is writable`() {
        // The mirror. Without the second declaration the same tag is ordinary.
        assertEquals(
            "hi",
            MezzeTestHelper.run(
                """
                type ErrA = | Shared { msg: Str }, OnlyA |
                let e: ErrA = Shared { msg = "hi" }
                match e is
                  Shared { msg } -> msg
                  OnlyA -> "other"
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `fields that fit no candidate are an arity error`() {
        // The arity check asks the question the solver asks — is there a candidate
        // these fields fit? — rather than walking declarations and throwing on the
        // first disagreement, which read a set of alternatives as a conjunction.
        val e = runCatching {
            MezzeTestHelper.run(
                """
                use std::effects::http::{ HttpError }
                let e: HttpError = HttpTimeout { url = "x" }
                match e is
                  _ -> "unreachable"
                """.trimIndent(),
            )
        }.exceptionOrNull()
        assertTrue(
            e?.message?.contains("HttpTimeout") == true,
            "expected the arity error to name the constructor; got: ${e?.message}",
        )
    }

    @Test
    fun `an unambiguous constructor still reports its own arity error`() {
        val e = runCatching {
            MezzeTestHelper.run(
                """
                use std::effects::http::{ HttpError }
                let e: HttpError = HttpInvalidUrl { url = "x", reason = "r", extra = 1 }
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
