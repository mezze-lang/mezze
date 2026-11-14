package com.mezze.language.typechecker

import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * T0210 may never assert a difference and then show none.
 *
 *     error[T0210]: Type annotation for 'peek' doesn't match inferred type:
 *                   annotation says {} -> Int, but inferred {} -> Int
 *
 * THE CAUSE IS A DIFFERENCE IN SCOPE between two correct pieces of code:
 *
 *   - `alphaEquivalent` (TypeValidator) compares a function's ABILITY
 *     CONSTRAINTS as well as its parameter and return types;
 *   - `prettyPrintType` deliberately hides a constraint whose type args are all
 *     concrete — "Only show constraints with unresolved type variables" — since
 *     a discharged obligation is not part of the signature a reader needs.
 *
 * So a mismatch in `with Into Frac Int` (fully concrete) failed the comparison
 * and left the printed forms identical, while `with Weigh 'a` shows up and reads
 * fine. Neither side is wrong on its own; the message has to cover the gap.
 *
 * Asserted as a PROPERTY — the message never prints one type twice as though it
 * were two, and names the ability responsible — rather than as snapshots, the
 * same way [ErrorMessageQualityGuardTest] and
 * [FieldAccessOnNonRecordMessageTest] do.
 */
class AnnotationMismatchMessageTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    /**
     * Only the T0210s: the cascade program reports a T0205 first, and that one is
     * the real error — this test is about the follow-on.
     *
     * Goes through `checkParsedModule`, the COLLECTING entry point the CLI uses,
     * not `typeCheck`: the latter surfaces the first error as a thrown
     * `TypeCheckError`, so the T0205 would hide the T0210 this test is about —
     * which is exactly what happened when it was written the other way.
     */
    private fun annotationErrorsFor(src: String): List<String> {
        val parsed = parser.parseParsedModule(src.trimIndent(), "test.mz")
        val r = typeChecker.checkParsedModule(parsed, Paths.get("."), enablePrelude = true)
        val failure = r as? TypeChecker.TypeCheckResult.Failure
            ?: fail("expected the typecheck to fail, got: $r")
        val all = failure.errors
            .filter { it.code == ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH }
            .map { it.message }
        assertTrue(
            all.isNotEmpty(),
            "expected a T0210 among ${failure.errors.map { it.code }} for:\n$src",
        )
        return all
    }

    /** "annotation says X, but inferred X" for one and the same X. */
    private fun assertNotSelfContradictory(msg: String) {
        val m = Regex("""annotation says (.+?), but inferred (.+?)(?:\s*\(|$)""").find(msg)
        if (m != null) {
            assertNotEquals(
                m.groupValues[1].trim(), m.groupValues[2].trim(),
                "T0210 printed the SAME type on both sides, which asserts a " +
                    "difference and shows none:\n$msg",
            )
        }
    }

    @Test
    fun `a concrete constraint the printer hides is still named`() {
        // `(5).into {}` cannot resolve its target through `.numerator`, so the
        // body's error leaves `with Into Frac Int` on `peek`'s type. Every type
        // arg is concrete, so the printer drops it and both sides read `{} -> Int`.
        val msgs = annotationErrorsFor(
            """
            use std::int
            use std::frac
            use std::abilities::convert::{ Into }
            pub let peek: {} -> Int = {} -> do
              let q: Frac = (5).into {}
              q.numerator
            pub let main = {} -> {}
            """,
        )
        val msg = msgs.first()
        assertNotSelfContradictory(msg)
        assertTrue(
            msg.contains("Into"),
            "the message must name the ability whose constraint differs — that is " +
                "the ONLY difference between the two sides, and the printer hides " +
                "it. Got:\n$msg",
        )
    }

    @Test
    fun `a constraint the printer does show reads as before, plus what to do`() {
        // `with Weigh 'a` carries a type VARIABLE, so the printer renders it and
        // the two sides already differ on screen. The wording must stay in its
        // original shape; the added clause says what to do about it.
        val msg = annotationErrorsFor(
            """
            use std::int
            ability Weigh for 'a where { weigh: { it: 'a } -> Int }
            pub let total: { a: 'x, b: 'x } -> Int = { a, b } -> a.weigh {} + b.weigh {}
            pub let main = {} -> {}
            """,
        ).first()
        assertNotSelfContradictory(msg)
        assertTrue(
            msg.contains("annotation says") && msg.contains("but inferred"),
            "a VISIBLE difference keeps the original wording — only the " +
                "print-the-same case replaces it. Got:\n$msg",
        )
        assertTrue(
            msg.contains("Weigh"),
            "and it names the ability. Got:\n$msg",
        )
    }

    @Test
    fun `declaring a with the body does not need stays legal`() {
        // THE CONTROL for the "annotation declares one the body does not need"
        // branch of the explainer: measured ACCEPTED, so that branch is defensive
        // and this test says so rather than leaving a reader to assume it fires.
        // Matches the impl matrix — a `with` constraint may be ADDED.
        val parsed = parser.parseParsedModule(
            """
            use std::int
            ability Weigh for 'a where { weigh: { it: 'a } -> Int }
            pub let ignore: { a: 'x } with Weigh 'x -> Int = { a } -> 7
            pub let main = {} -> {}
            """.trimIndent(),
            "test.mz",
        )
        val r = typeChecker.checkParsedModule(parsed, Paths.get("."), enablePrelude = true)
        assertTrue(
            r !is TypeChecker.TypeCheckResult.Failure,
            "an annotation may promise MORE than the body needs; got: " +
                ((r as? TypeChecker.TypeCheckResult.Failure)?.errors?.joinToString("\n") {
                    "${it.code}: ${it.message}"
                } ?: "$r"),
        )
    }
}
