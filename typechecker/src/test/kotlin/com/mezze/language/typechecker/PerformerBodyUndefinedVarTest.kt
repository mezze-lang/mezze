package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * S-19: a performer op body is held to the same undefined-variable rule as
 * everything else.
 *
 * `impl Beep for Beeper where { beep = {} -> nonexistent_thing }` COMPILED. The
 * same typo in a plain function, or in an ability impl body, was reported — the
 * performer path was the one place it was dropped.
 *
 * The cause was a hook that specialised the shared check and then replaced it.
 * `onUndefinedVars` returns `true` to mean "I own this failure"; the performer's
 * handler exists only to give `scope` a tailored explanation, but it returned
 * `true` for ANY undefined variable, which stopped validation and reported
 * nothing. (`onSolveFailure` had the mirror-image defect: nothing followed its
 * `when`, so declining with `false` discarded the failure just as surely as
 * `true` did. Both hooks now fall through to the default report when they
 * decline.)
 *
 * The `scope` diagnostics are the reason the hook exists, so they are asserted
 * here too — a fix that reported the typo by dropping the hook would lose them.
 */
class PerformerBodyUndefinedVarTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun check(source: String) {
        val parsed = parser.parseParsedModule(source.trimIndent(), "test.mz")
        typeChecker.typeCheck(parsed, Paths.get("."))
    }

    private fun failureOf(source: String): TypeCheckError =
        assertThrows<TypeCheckError> { check(source) }

    /** THE case. Accepted in silence before the fix. */
    @Test
    fun `an undefined variable in a performer op body is reported`() {
        val e = failureOf(
            """
            effect Beep where { beep: {} -> Int <Beep> }
            impl Beep for Beeper where { beep = {} -> nonexistent_thing }
            """
        )
        assertTrue(
            (e.message ?: "").contains("nonexistent_thing"),
            "the undefined variable should be named: ${e.message}",
        )
    }

    /**
     * The same typo in an ability impl body, which always WAS reported. Here as the
     * control: it is what the performer path should have matched all along.
     */
    @Test
    fun `an undefined variable in an ability impl body is reported too`() {
        val e = failureOf(
            """
            ability Greet for 'a where { greet: { it: 'a } -> Int }
            type P = { x: Int }
            impl Greet for P where { greet = { it } -> nonexistent_thing }
            """
        )
        assertTrue(
            (e.message ?: "").contains("nonexistent_thing"),
            "the undefined variable should be named: ${e.message}",
        )
    }

    /** A typo ALONGSIDE a legitimate `scope` read — the hook must not mask it. */
    @Test
    fun `a typo next to a valid scope read is still reported`() {
        val e = failureOf(
            """
            effect Beep where { beep: {} -> Int <Beep> }
            impl Beep for Beeper where {
              initialize_scope = {} -> { n = 5 },
              finalize_scope = {} -> {},
              beep = {} -> scope.n + nonexistent_thing
            }
            """
        )
        assertTrue(
            (e.message ?: "").contains("nonexistent_thing"),
            "the typo was masked by the scope handler: ${e.message}",
        )
    }

    // ── the `scope` explanations the hook exists for ──────────────────────────

    @Test
    fun `reading scope without initialize_scope explains why it is absent`() {
        val e = failureOf(
            """
            effect Beep where { beep: {} -> Int <Beep> }
            impl Beep for Beeper where { beep = {} -> scope.n }
            """
        )
        assertTrue(
            (e.message ?: "").contains("does not define initialize_scope"),
            "expected the tailored scope explanation, got: ${e.message}",
        )
    }

    @Test
    fun `reading scope inside initialize_scope says the scope is being created`() {
        val e = failureOf(
            """
            effect Beep where { beep: {} -> Int <Beep> }
            impl Beep for Beeper where {
              initialize_scope = {} -> { n = scope.n },
              finalize_scope = {} -> {},
              beep = {} -> scope.n
            }
            """
        )
        assertTrue(
            (e.message ?: "").contains("not available in initialize_scope"),
            "expected the tailored initialize_scope explanation, got: ${e.message}",
        )
    }

    /**
     * The check now runs on every performer body, so a legitimate `scope` read must
     * not be mistaken for an undefined variable.
     */
    @Test
    fun `a valid scoped performer still compiles`() {
        val parsed = parser.parseParsedModule(
            """
            effect Beep where { beep: {} -> Int <Beep> }
            impl Beep for Beeper where {
              initialize_scope = {} -> { n = 5 },
              finalize_scope = {} -> {},
              beep = {} -> scope.n
            }
            """.trimIndent(),
            "test.mz",
        )
        assertNotNull(typeChecker.typeCheck(parsed, Paths.get(".")))
    }
}
