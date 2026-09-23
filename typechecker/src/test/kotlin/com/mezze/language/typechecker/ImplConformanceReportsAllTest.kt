package com.mezze.language.typechecker

import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * An impl with several conformance mistakes reports all of them at once.
 *
 * Conformance is several independent questions about one impl — does it
 * implement every declared method, does it add one that is not declared, does it
 * bind every associated type. Answering the first and throwing meant a user with
 * three mistakes fixed them one compile at a time, and the effect-side twin of
 * this check already accumulated, so the two halves of one rule behaved
 * differently.
 */
class ImplConformanceReportsAllTest {

    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
    }

    private fun errorsFrom(src: String): List<TypeCheckError> {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        return try {
            TypeChecker().typeCheck(parser.parseParsedModule(src.trimIndent(), "conf.mz"), Paths.get("."))
            fail("expected the impl to be rejected")
        } catch (e: TypeCheckError) {
            e.all()
        }
    }

    @Test
    fun `a missing method and an extra method are reported together`() {
        val errors = errorsFrom(
            """
            ability Shape for 'a where {
              area: { it: 'a } -> Int,
              name: { it: 'a } -> Str
            }
            type Sq = { s: Int }
            impl Shape for Sq where {
              area = { it } -> it.s * it.s,
              perimeter = { it } -> it.s * 4
            }
            let main = {} -> 1
        """
        )
        val codes = errors.map { it.code }.toSet()
        assertTrue(
            ErrorCode.V0404_IMPL_MISSING_METHOD in codes,
            "the missing `name` was not reported: ${errors.map { it.message }}"
        )
        assertTrue(
            ErrorCode.V0405_IMPL_EXTRA_METHOD in codes,
            "the extra `perimeter` was not reported: ${errors.map { it.message }}"
        )
    }

    @Test
    fun `a missing associated-type binding is reported alongside a missing method`() {
        val errors = errorsFrom(
            """
            ability Convert for 'a having 'out where {
              to: { it: 'a } -> 'out,
              back: { it: 'out } -> 'a
            }
            type Id = { n: Int }
            impl Convert for Id where {
              to = { it } -> it.n
            }
            let main = {} -> 1
        """
        )
        val codes = errors.map { it.code }.toSet()
        assertTrue(
            ErrorCode.V0413_MISSING_ASSOCIATED_TYPE_BINDING in codes,
            "the missing `having 'out` binding was not reported: ${errors.map { it.message }}"
        )
        assertTrue(
            ErrorCode.V0404_IMPL_MISSING_METHOD in codes,
            "the missing `back` was not reported: ${errors.map { it.message }}"
        )
    }

    @Test
    fun `one mistake is reported once, not twice`() {
        // The "no bindings at all" message is a better-worded special case of
        // "missing bindings", not a second finding. Reporting both would describe
        // one mistake twice.
        val errors = errorsFrom(
            """
            ability Convert for 'a having 'out where {
              to: { it: 'a } -> 'out
            }
            type Id = { n: Int }
            impl Convert for Id where {
              to = { it } -> it.n
            }
            let main = {} -> 1
        """
        )
        assertEquals(
            1, errors.count { it.code == ErrorCode.V0413_MISSING_ASSOCIATED_TYPE_BINDING },
            "the single missing-binding mistake was reported more than once: ${errors.map { it.message }}"
        )
    }

    @Test
    fun `the first error still reads exactly as before`() {
        // Every existing `catch (e: TypeCheckError)` sees the first problem and
        // nothing else changes for it; the rest are opt-in via `additional`.
        val errors = errorsFrom(
            """
            ability Shape for 'a where {
              area: { it: 'a } -> Int,
              name: { it: 'a } -> Str
            }
            type Sq = { s: Int }
            impl Shape for Sq where {
              area = { it } -> it.s * it.s,
              perimeter = { it } -> it.s * 4
            }
            let main = {} -> 1
        """
        )
        assertTrue(errors.size >= 2, "expected several problems, got ${errors.size}")
        assertTrue(
            errors.first().message.isNotBlank() && errors.first().code != ErrorCode.UNCATEGORIZED,
            "the primary error must still carry its own message and code"
        )
    }
}
