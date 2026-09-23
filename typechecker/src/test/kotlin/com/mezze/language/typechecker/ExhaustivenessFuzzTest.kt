package com.mezze.language.typechecker

import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.random.Random

/**
 * S6.3 (#368): a RANDOM-PATTERN fuzzer for the ONE coverage algorithm in
 * [MatchValidator.validateParsedExhaustiveness]. It checks the two properties
 * that matter and that the fuzzer can decide by CONSTRUCTION — the oracle is the
 * generator, never the checker under test:
 *
 *   P1 (no false-positive rejection): a match the generator built to BE
 *      exhaustive (covers every constructor, or includes a `_` catch-all, or
 *      covers both True/False for Bool) must NOT be rejected as non-exhaustive.
 *
 *   P2 (no false-negative acceptance): a match the generator built to be NOT
 *      exhaustive (omits ≥1 constructor with no catch-all, or omits a Bool case)
 *      must BE rejected.
 *
 * The generator emits only patterns whose exhaustiveness answer is known by
 * construction — bare constructor patterns, Bool literals, and a `_` catch-all,
 * over freshly generated closed variants / Bool. It does NOT emit guards, nested
 * payloads, or or-patterns in the oracle-critical position, because those make
 * the "is this exhaustive?" answer harder to know a-priori; those dimensions are
 * pinned by the per-hole regression tests instead. Fixed seeds ⇒ any failure
 * reproduces exactly.
 *
 * IMPORTANT: only NON-exhaustiveness errors count against a property. A random
 * program may be rejected for an UNRELATED reason (an unreachable-arm warning,
 * a shadowing error, …); those are filtered out via [isNonExhaustivenessError]
 * so the fuzzer tests exactly the coverage decision and nothing else.
 */
class ExhaustivenessFuzzTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    /** null ⇒ accepted; else the rejection. */
    private data class Rejection(val code: ErrorCode, val message: String)

    private fun check(src: String): Rejection? =
        try { tc.typeCheck(parser.parseParsedModule(src, "fuzz.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { Rejection(e.code, e.message ?: "") }

    /**
     * Is this rejection a NON-EXHAUSTIVENESS verdict — the one decision under
     * test? Other rejections (unreachable arm, shadowing, parse) are unrelated
     * noise and must not count either way.
     *
     * Keyed on the CODE. This used to ask the message: `contains("non-exhaustive")
     * || contains("not exhaustive") || contains("no arm covers") ||
     * contains("missing") || contains("cannot be exhaustive")`, minus
     * `contains("unreachable")`. An oracle made of diagnostic prose degrades
     * silently — reword the message and P1 (no false-positive rejection) simply
     * stops noticing the rejections it is supposed to catch, while the fuzzer
     * keeps reporting 0 violations. `contains("missing")` was the loudest problem:
     * it also matches "missing required fields" and "missing field", so unrelated
     * record errors were being counted as exhaustiveness verdicts.
     */
    private fun isNonExhaustivenessError(r: Rejection): Boolean =
        r.code == ErrorCode.V0601_NON_EXHAUSTIVE_MATCH ||
            r.code == ErrorCode.V0602_NON_EXHAUSTIVE_RECORD_PATTERN

    // Generated closed variant: name + constructor names (all nullary/unit).
    private data class Variant(val name: String, val ctors: List<String>)

    private fun genVariant(rng: Random, idx: Int): Variant {
        val n = rng.nextInt(2, 5) // 2..4 constructors
        val ctors = (0 until n).map { "C${idx}_$it" }
        return Variant("V$idx", ctors)
    }

    /**
     * Build a program matching a value of the given variant. If [exhaustive],
     * the arms cover every constructor (optionally plus/instead a `_`); if not,
     * we omit at least one constructor and never add a catch-all. Returns
     * (source, expectedExhaustive).
     */
    private fun genVariantMatch(rng: Random, v: Variant): Pair<String, Boolean> {
        val exhaustive = rng.nextBoolean()
        val useCatchAll = exhaustive && rng.nextBoolean()

        val arms = StringBuilder()
        var body = 0
        val covered: List<String>
        if (useCatchAll) {
            // Cover a random subset, then a catch-all ⇒ definitely exhaustive.
            covered = v.ctors.shuffled(rng).take(rng.nextInt(0, v.ctors.size))
            for (c in covered) arms.appendLine("  $c -> ${body++}")
            arms.appendLine("  _ -> ${body++}")
        } else if (exhaustive) {
            // Cover ALL constructors, no catch-all ⇒ exhaustive.
            covered = v.ctors.shuffled(rng)
            for (c in covered) arms.appendLine("  $c -> ${body++}")
        } else {
            // Omit at least one constructor, no catch-all ⇒ NON-exhaustive.
            val keep = rng.nextInt(1, v.ctors.size) // 1..n-1 constructors
            covered = v.ctors.shuffled(rng).take(keep)
            for (c in covered) arms.appendLine("  $c -> ${body++}")
        }

        val ctorDecls = v.ctors.joinToString(", ")
        val src = """
type ${v.name} = | $ctorDecls |
let f: { x: ${v.name} } -> Int = { x } -> match x is
${arms.toString().trimEnd()}
""".trimIndent() + "\n"
        return src to exhaustive
    }

    /** A Bool-field record match; oracle known by whether both True/False (or a
     *  catch-all) are present. */
    private fun genBoolMatch(rng: Random): Pair<String, Boolean> {
        val exhaustive = rng.nextBoolean()
        val useCatchAll = exhaustive && rng.nextBoolean()
        val arms = StringBuilder()
        var body = 0
        when {
            useCatchAll -> {
                if (rng.nextBoolean()) arms.appendLine("  { b = True } -> ${body++}")
                arms.appendLine("  _ -> ${body++}")
            }
            exhaustive -> {
                // both True and False, random order
                val order = listOf("True", "False").shuffled(rng)
                for (t in order) arms.appendLine("  { b = $t } -> ${body++}")
            }
            else -> {
                // exactly one of True/False, no catch-all ⇒ non-exhaustive
                val t = if (rng.nextBoolean()) "True" else "False"
                arms.appendLine("  { b = $t } -> ${body++}")
            }
        }
        val src = """
let f: { r: { b: Bool } } -> Int = { r } -> match r is
${arms.toString().trimEnd()}
""".trimIndent() + "\n"
        return src to exhaustive
    }

    private fun runSweep(seed: Long, iterations: Int) {
        val rng = Random(seed)
        val failures = mutableListOf<String>()
        for (i in 0 until iterations) {
            // Fresh caches each program so nothing leaks between iterations.
            ModuleResolver.clearCache(); TypeChecker.clearCache()

            val (src, expectedExhaustive) = if (rng.nextInt(4) == 0) {
                genBoolMatch(rng)
            } else {
                genVariantMatch(rng, genVariant(rng, i))
            }

            val rejection = check(src)
            val rejectedAsNonExhaustive = rejection != null && isNonExhaustivenessError(rejection)

            if (expectedExhaustive && rejectedAsNonExhaustive) {
                // P1 violation: false-positive rejection.
                failures.add("FALSE-POSITIVE (exhaustive rejected) seed=$seed iter=$i\n$src\n-> ${rejection?.code} ${rejection?.message}")
            } else if (!expectedExhaustive && !rejectedAsNonExhaustive) {
                // P2 violation: false-negative acceptance. (msg may be non-null
                // for an unrelated reason; only a MISSING non-exhaustiveness
                // verdict is the bug.)
                failures.add("FALSE-NEGATIVE (non-exhaustive accepted) seed=$seed iter=$i\n$src\n-> ${rejection?.let { it.code.toString() + " " + it.message } ?: "accepted"}")
            }
        }
        if (failures.isNotEmpty()) {
            fail<Unit>("Exhaustiveness fuzzer found ${failures.size} violation(s):\n\n" +
                failures.joinToString("\n\n"))
        }
    }

    // Fixed seeds so any failure reproduces exactly. Iteration count bumped to 300
    // per seed for the S7.3 capstone soundness sweep (larger corpus, 0 violations).
    @Test fun `fuzz seed 1`() = runSweep(1L, 300)
    @Test fun `fuzz seed 42`() = runSweep(42L, 300)
    @Test fun `fuzz seed 1337`() = runSweep(1337L, 300)
    @Test fun `fuzz seed 2718281828`() = runSweep(2718281828L, 300)
    @Test fun `fuzz seed 161803`() = runSweep(161803L, 300)
}
