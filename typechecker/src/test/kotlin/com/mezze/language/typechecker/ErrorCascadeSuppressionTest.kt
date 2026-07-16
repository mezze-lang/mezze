package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * #61: one root type error should not spawn a pile of follow-on errors. When a
 * constraint fails it leaves its type var(s) unresolved; every later constraint
 * that touches those same vars would fail too. The solver now marks a failed
 * constraint's vars as "poisoned" and suppresses any later failure that depends
 * only on poisoned vars — so the user sees the root cause, not its cascade.
 *
 * These tests use checkParsedModule directly (sealed result exposes the full
 * error list); typeCheck() throws only the first error and hides the count.
 */
class ErrorCascadeSuppressionTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        System.setProperty("mezze.typecheck.strict", "true")
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun errorsFor(src: String): List<TypeChecker.TypeCheckError> {
        val parsed = parser.parseParsedModule(src, "cascade.mz")
        val r = typeChecker.checkParsedModule(parsed, Paths.get("."), enablePrelude = false)
        return when (r) {
            is TypeChecker.TypeCheckResult.Failure -> r.errors
            is TypeChecker.TypeCheckResult.Success -> emptyList()
        }
    }

    @Test
    fun `single bad var reused many times does not produce one error per use`() {
        // `n` is forced to Int by `n + 1`, then read as a record via `.field`
        // five times in the same expression. Each field read on the (Int, not a
        // record) param used to re-report the same HasField failure. With
        // suppression, the follow-on reads collapse once the param's var is
        // poisoned by the first failure.
        val src = """
            let g = { n } -> n + 1 + n.foo + n.bar + n.baz + n.qux + n.quux
            g { n = 3 }
        """.trimIndent()

        val errors = errorsFor(src)
        // Don't pin an exact count (solver internals may vary), but a single-root
        // conflict must not fan out into one error per downstream use. Five reads
        // must collapse well below five errors.
        assertTrue(
            errors.size <= 3,
            "expected cascade suppressed well below the 5 reuses, got ${errors.size}: ${errors.map { it.message }}"
        )
    }

    @Test
    fun `two independent errors are both still reported`() {
        // Two unrelated conflicts on different vars — neither is a cascade of the
        // other, so suppression must NOT collapse them into one.
        val src = """
            let bad1: Int = "not a number"
            let bad2: Str = 999
        """.trimIndent()

        val errors = errorsFor(src)
        assertTrue(
            errors.size >= 2,
            "expected both independent errors reported, got ${errors.size}: ${errors.map { it.message }}"
        )
    }

    @Test
    fun `a valid program still type-checks after suppression logic`() {
        val src = """
            let add = { a, b } -> a + b
            let r = add { a = 1, b = 2 }
            r
        """.trimIndent()

        val errors = errorsFor(src)
        assertTrue(errors.isEmpty(), "valid program should have no errors, got: ${errors.map { it.message }}")
    }
}
