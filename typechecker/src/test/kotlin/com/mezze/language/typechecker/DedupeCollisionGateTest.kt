package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.compiler.TypedASTCompiler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.fail

/**
 * Content addressing must actually hold where the compiler relies on it.
 *
 * Every compiled function is collapsed by hash, first occurrence wins, and the
 * code doing it states the premise plainly: "content-addressed lowering means
 * structurally identical bodies share a hash, so the duplicate rows are
 * semantically the same function, just compiled more than once."
 *
 * The premise HOLDS. This gate previously recorded 8 collisions and described them
 * as real — "the dedupe then discards a body some call site needed". That was
 * wrong, and the error was in the gate, not the compiler: the check compared
 * `kept.body != fn.body`, and `NodeIR` equality includes `SourceInfo`. All eight
 * were the same body emitted at different lines. Measured, on each of them:
 * identical length, identical captures, identical structure, differing only in
 * `startLine` / `fileIndex` — e.g. the `sorted_set.mz` comparators at lines 55, 57,
 * 59 and 61. It also explains the loose end left by BUG 7, which found that
 * separating the eight "changed no behaviour": there was no behaviour to change.
 *
 * The check now compares `shapeKey()` — the whole function with source positions
 * removed, body AND the capture and parameter lists it is compiled against — and
 * the count is ZERO. So this is no longer a ratchet holding a number down; it is
 * the invariant itself, and any hash carrying two genuinely different functions
 * fails it.
 *
 * Comparing the capture list too is what let `saltedLambdaHash` stop folding in the
 * parent FRAME SLOT its dict captures are read from. That slot is where the PARENT
 * keeps the dict; it never appears in the lambda's body or its own capture names,
 * so salting with it gave one lambda several identities for a difference it does
 * not contain. Measured on removal: no change across the interpreter suite (6242
 * tests), the typechecker suite, and SeqKeyOrdTest — the test it was added for.
 * The capture NAMES are still folded in, and are load-bearing: two identical
 * comparators capturing different dicts are different functions.
 *
 * Still true, and still the reason not to salt by the enclosing function: doing so
 * ties a nested lambda's identity to its declaration's hash, so adding a `newtype`
 * changes that hash and two programs identical after newtype erasure emit
 * different IR, which NewtypeErasureIRTest rightly rejects.
 */
class DedupeCollisionGateTest {

    /**
     * Hashes whose dedupe discards a body that differs in SHAPE. Zero, and it has
     * to stay zero — this is not a number to raise.
     *
     * Unlike the old count, this one does not move with which modules happen to be
     * resolved when the test runs: source positions were what varied, and they are
     * no longer compared.
     */
    private val baseline = 0

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
        TypedASTCompiler.dedupeCollisions.clear()
        TypedASTCompiler.recordDedupeCollisions = true
    }

    @AfterEach
    fun teardown() {
        TypedASTCompiler.recordDedupeCollisions = false
        TypedASTCompiler.dedupeCollisions.clear()
    }

    @Test
    fun `the per-hash dedupe discards no more different bodies than it did`() {
        typeChecker.typeCheck(
            parser.parseParsedModule("let f = { x } -> x\nf { x = 1 }", "test.mz"),
            Paths.get("."),
        )
        val found = TypedASTCompiler.dedupeCollisions.size
        // A floor is deliberately absent, and now for a different reason than
        // before: the correct count is zero, so there is nothing to find. The old
        // comment here explained that a floor made the gate pass alone and fail in
        // the suite; that flakiness was the source-position noise this no longer
        // compares.
        if (found > baseline) {
            fail(
                "the dedupe discards a body of a DIFFERENT SHAPE for $found distinct hash(es). " +
                    "A function some call site needed has been replaced by another that merely " +
                    "hashes the same — source positions are not compared, so this is a real " +
                    "difference in what the body computes. Either the identity is missing " +
                    "something the two bodies differ in (the dict-capture layout is the " +
                    "precedent), or the dedupe should not be collapsing them. Hashes: " +
                    TypedASTCompiler.dedupeCollisions.map { it.take(12) }.sorted(),
            )
        }
    }
}
