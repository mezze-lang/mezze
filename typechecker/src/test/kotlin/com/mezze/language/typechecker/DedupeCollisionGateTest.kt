package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.compiler.TypedASTCompiler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Content addressing must actually hold where the compiler relies on it.
 *
 * Every compiled function is collapsed by hash, first occurrence wins, and the
 * code doing it states the premise plainly: "content-addressed lowering means
 * structurally identical bodies share a hash, so the duplicate rows are
 * semantically the same function, just compiled more than once."
 *
 * Measured, the premise does not hold. `saltedLambdaHash` is, by its own comment,
 * "type- and environment-blind": textually identical closures in different parents
 * compile to DIFFERENT bodies and share one hash. The dedupe then discards a body
 * some call site needed, and whichever occurrence came first wins — the `#106`
 * collision family, which has already produced wrong answers twice
 * (`SeqKeyOrdTest`, BUG 7).
 *
 * This gate does not claim the current collisions are harmful; it claims they are
 * REAL and bounds them, so the number cannot grow while nobody is looking. The
 * eight textually identical `{ a, b } -> if a < b …` comparators in
 * `sorted_set.mz` are the bulk of it.
 *
 * Why not just fix it: folding the enclosing function's hash into the salt
 * separates them, and was tried — it ties a nested lambda's identity to its
 * declaration's hash, so adding a `newtype` changes that hash and two programs
 * identical after newtype erasure emit different IR, which `NewtypeErasureIRTest`
 * rightly rejects. The identity has to come from something the lambda IS, not
 * from where it sits.
 */
class DedupeCollisionGateTest {

    /**
     * Distinct hashes whose dedupe discards a DIFFERENT body, compiling the
     * smallest possible program — so every one of them is in the stdlib, not in
     * the test source.
     */
    private val baseline = 7

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
        assertTrue(
            found > 0,
            "the gate measured NO collisions — either they are fixed (drop the baseline to 0 " +
                "and say so) or the recording switch stopped working and this gate is vacuous",
        )
        if (found > baseline) {
            fail(
                "the dedupe now discards a different body for $found distinct hashes, was " +
                    "$baseline. A function some call site needed has been replaced by another " +
                    "that merely hashes the same. Hashes: " +
                    TypedASTCompiler.dedupeCollisions.map { it.take(12) }.sorted(),
            )
        }
    }
}
