package com.mezze.language.typechecker

import com.mezze.language.ast.solving.SubstitutionClobbers
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.fail

/**
 * `Substitution.compose` must not quietly choose between two answers.
 *
 * Composition is a union in which one side wins on a collision. When both sides
 * bind the SAME variable to DIFFERENT values, picking one is a guess wearing a
 * union's clothes: the correct response is to UNIFY the two, because both sides
 * claim to be that variable's value and so they are equal.
 *
 * Collisions now unify, and the two tests below say what that has to mean.
 *
 * The first is the INVARIANT and it is absolute: no collision may FAIL to unify.
 * A collision whose two values contradict each other is a type error the solve is
 * about to swallow — it keeps one value, the contradiction never surfaces, and
 * the program compiles on an answer nothing checked. Measured at zero, and zero
 * is the only acceptable number.
 *
 * Getting there took a second fix. Unification without a registry could not
 * expand `Option`/`Result`, so `Option([Some, None])` against
 * `TypeApplication(Option 'b)` — one type in two spellings — reported
 * "Option 'a is not compatible with Option 'b" and counted as a contradiction.
 * `Some`/`None` and `Ok`/`Err` are reserved constructors, so those shapes are now
 * builtin knowledge exactly as `Cons`/`Nil` already were. That took the count
 * from 11 to 0.
 *
 * The second is a RATCHET on how often collisions happen at all. Unifying makes
 * a collision harmless, not desirable: it is still two passes disagreeing about
 * one variable, 216 times in the smallest program that pulls in the stdlib —
 * 164 of them one type in two representations and 52 two different variables for
 * one variable, which is the "one written 'a becomes two TypeVars" shape. It may
 * only go DOWN.
 */
class SubstitutionClobberGateTest {

    /**
     * Distinct variables bound twice to different values while type-checking the
     * smallest program that still pulls in the stdlib.
     *
     * MEASURED at 194, not chosen — the gate was first run with this set to 0 and
     * the number read off the failure. Lower is better and it may only go DOWN.
     */
    private val baseline = 194

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        SubstitutionClobbers.seen.clear()
        SubstitutionClobbers.conflicts.clear()
        SubstitutionClobbers.recording = true
    }

    @AfterEach
    fun tearDown() {
        SubstitutionClobbers.recording = false
        SubstitutionClobbers.seen.clear()
        SubstitutionClobbers.conflicts.clear()
    }

    /**
     * The invariant. Zero, not a ratchet: a collision that does not unify is a
     * contradiction, and the solve is about to keep one side of it.
     */
    @Test
    fun `every collision unifies`() {
        TypeChecker().typeCheck(
            MezzeParser().parseParsedModule("let f = { x } -> x\nf { x = 1 }", "test.mz"),
            Paths.get("."),
        )
        val conflicts = SubstitutionClobbers.conflicts
        if (conflicts.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("${conflicts.size} collision(s) in compose did NOT unify:")
                    conflicts.take(10).forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("Both sides claim to be that variable's value, so they must be equal.")
                    appendLine("Two that do not unify are a contradiction, and compose is about to keep")
                    appendLine("one of them with nothing reporting the other. Either the two spellings")
                    appendLine("denote one type and unification cannot see it, or a pass upstream is")
                    appendLine("binding the same variable twice to genuinely different types.")
                },
            )
        }
    }

    @Test
    fun `composition discards no more conflicting bindings than it did`() {
        TypeChecker().typeCheck(
            MezzeParser().parseParsedModule("let f = { x } -> x\nf { x = 1 }", "test.mz"),
            Paths.get("."),
        )
        val found = SubstitutionClobbers.seen.size
        if (found > baseline) {
            fail(
                buildString {
                    appendLine("Substitution.compose discarded a conflicting binding for $found distinct")
                    appendLine("variable(s), was $baseline. Each one is a variable bound to two different")
                    appendLine("values where only one survived and nothing unified them — the solve kept")
                    appendLine("an answer without checking it against the one it threw away.")
                    appendLine()
                    appendLine("Either unify on collision, or establish why this particular one cannot")
                    appendLine("conflict. Do not raise the number to make this pass.")
                },
            )
        }
    }
}
