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
 * bind the SAME variable to DIFFERENT values, the loser is discarded and the two
 * are never unified — so a solve can finish holding one of two answers with
 * nothing having checked that they agreed. The correct response to a collision is
 * to UNIFY the two values; picking one is a guess wearing a union's clothes.
 *
 * This is not hypothetical. It is the mechanism behind the last elaboration rows:
 * for `Option.flatten` the solved substitution ends up holding
 *
 *     'c  ->  Option([Some, None])              (the None arm)
 *     'd  ->  TypeApplication(Option 'a)        (the match result var)
 *
 * with an `Equality('c, 'd)` in the constraint set that the solve reports as
 * satisfied. Two mutually unifiable values, never compared.
 *
 * Most collisions look benign — `Bool([True, False])` replaced by `BoolType` is
 * one type in two representations — but nothing enforces that, which is the point
 * of counting them.
 *
 * The gate is an UPPER BOUND, like the other ratchets here. Fixing compose to
 * unify on collision is a change to the most central operation in the solver and
 * wants its own pass with both suites; until then this keeps the number from
 * growing while nobody is looking.
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
        SubstitutionClobbers.recording = true
    }

    @AfterEach
    fun tearDown() {
        SubstitutionClobbers.recording = false
        SubstitutionClobbers.seen.clear()
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
