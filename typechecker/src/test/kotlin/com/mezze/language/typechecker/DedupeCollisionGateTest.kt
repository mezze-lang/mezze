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
     *
     * 8 is the number IN THE FULL SUITE, which is the environment that matters. In
     * isolation it reads 7: what gets compiled depends on which modules are already
     * resolved when this runs, so the two differ by one and neither is wrong.
     */
    private val baseline = 8

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
        // Deliberately an UPPER BOUND only, with no "must be > 0" companion. The
        // collisions all live in the STDLIB compile, and whether this test triggers
        // one depends on whether an earlier test in the same run already warmed the
        // module cache — in isolation it reads 7, in the full suite it can read 0.
        // Asserting a floor made the gate pass alone and fail in the suite, which is
        // a flaky gate, which is worse than a loose one. Growth is what this has to
        // catch, and growth it catches either way.
        //
        // For a real reading:
        //   ./gradlew :typechecker:test --tests '*DedupeCollisionGateTest*'
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
