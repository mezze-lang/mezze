package com.mezze.language.typechecker

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

    /** null ⇒ accepted; else the error message. */
    private fun check(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src, "fuzz.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    /** Is this message a NON-EXHAUSTIVENESS rejection (the thing we test)? Other
     *  rejections (unreachable arm, shadowing, parse) are unrelated noise here. */
    private fun isNonExhaustivenessError(msg: String): Boolean {
        val m = msg.lowercase()
        val isExhaust = m.contains("non-exhaustive") || m.contains("not exhaustive") ||
            m.contains("no arm covers") || m.contains("missing") ||
            m.contains("cannot be exhaustive")
        // An "unreachable" report is a DIFFERENT decision; don't count it as a
        // non-exhaustiveness rejection.
        return isExhaust && !m.contains("unreachable") && !m.contains("never execute")
    }

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

            val msg = check(src)
            val rejectedAsNonExhaustive = msg != null && isNonExhaustivenessError(msg)

            if (expectedExhaustive && rejectedAsNonExhaustive) {
                // P1 violation: false-positive rejection.
                failures.add("FALSE-POSITIVE (exhaustive rejected) seed=$seed iter=$i\n$src\n-> $msg")
            } else if (!expectedExhaustive && !rejectedAsNonExhaustive) {
                // P2 violation: false-negative acceptance. (msg may be non-null
                // for an unrelated reason; only a MISSING non-exhaustiveness
                // verdict is the bug.)
                failures.add("FALSE-NEGATIVE (non-exhaustive accepted) seed=$seed iter=$i\n$src\n-> ${msg ?: "accepted"}")
            }
        }
        if (failures.isNotEmpty()) {
            fail<Unit>("Exhaustiveness fuzzer found ${failures.size} violation(s):\n\n" +
                failures.joinToString("\n\n"))
        }
    }

    // Fixed seeds so any failure reproduces exactly.
    @Test fun `fuzz seed 1`() = runSweep(1L, 60)
    @Test fun `fuzz seed 42`() = runSweep(42L, 60)
    @Test fun `fuzz seed 1337`() = runSweep(1337L, 60)
    @Test fun `fuzz seed 2718281828`() = runSweep(2718281828L, 60)
    @Test fun `fuzz seed 161803`() = runSweep(161803L, 60)
}
