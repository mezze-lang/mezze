package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.parser.ParseException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import java.nio.file.Paths
import kotlin.random.Random

/**
 * S0.3 — meta-property harness for the typechecker re-layering.
 *
 * Complements TypeCheckerFuzzTest (which asserts "does not crash" with a random,
 * non-reproducible seed) by pinning the properties every later stage must
 * preserve, over a FIXED seed sweep so any failure is reproducible:
 *
 *   1. Determinism — the same source type-checks to the same inferred types
 *      across independent runs (no dependence on tyvar counter / iteration order /
 *      cache state). A re-layering that introduces order-dependence fails here.
 *   2. No JVM crash — the typechecker only ever throws TypeCheckError/ParseException,
 *      never a raw StackOverflow/OOM/other (reproducible-seed version of the fuzz).
 *
 * The fmt∘check == check property lives in the CLI test module (it needs both
 * :formatter and :typechecker on the classpath, which :typechecker alone lacks).
 */
class TypeCheckerMetaPropertyTest {

    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
    }

    /** Fixed seeds → reproducible corpus. Widen freely; failures print the seed. */
    private val seeds = (1L..60L).toList()

    @Test
    fun `inference is deterministic across independent runs`() {
        for (seed in seeds) {
            val source = generateRandomProgram(Random(seed))
            val a = inferOrNull(source)
            val b = inferOrNull(source)
            // Both runs must agree: either both error (same first-error class is
            // enough — we only compare the SUCCESS type maps) or both succeed with
            // identical inferred types for every top-level binding.
            if (a != null && b != null) {
                assertEquals(a, b,
                    "Non-deterministic inference (seed=$seed):\n$source\nrun1=$a\nrun2=$b")
            } else {
                // If one run produced a type map and the other didn't, that itself
                // is non-determinism.
                assertEquals(a == null, b == null,
                    "Non-deterministic success/failure (seed=$seed):\n$source")
            }
        }
    }

    @Test
    fun `typechecker never throws a non-TypeCheckError on the fixed corpus`() {
        for (seed in seeds) {
            val source = generateRandomProgram(Random(seed))
            try {
                val parsed = parser.parseParsedModule(source, "meta_$seed.mz")
                TypeChecker().typeCheck(parsed, Paths.get("."))
            } catch (_: TypeCheckError) {
                // fine — a random program may not type-check
            } catch (_: ParseException) {
                // fine — a random program may not parse
            } catch (e: StackOverflowError) {
                throw AssertionError("StackOverflow (seed=$seed):\n$source", e)
            } catch (e: OutOfMemoryError) {
                throw AssertionError("OutOfMemory (seed=$seed):\n$source", e)
            } catch (e: Throwable) {
                throw AssertionError(
                    "Typechecker threw ${e.javaClass.name} (not a TypeCheckError) " +
                    "(seed=$seed):\n$source", e)
            }
        }
    }

    /** Inferred top-level types as a comparable map, or null if the program errored. */
    private fun inferOrNull(source: String): Map<String, String>? {
        return try {
            val parsed = parser.parseParsedModule(source, "meta.mz")
            val result = TypeChecker().typeCheck(parsed, Paths.get("."))
            // Compare the printed forms of every top-level binding's inferred type.
            // Print form is the stable, human-meaningful identity here.
            val names = parsed.decls.mapNotNull { topLevelName(it) }
            names.associateWith { n -> result.getVariableType(n)?.toString() ?: "<none>" }
        } catch (_: TypeCheckError) {
            null
        } catch (_: ParseException) {
            null
        }
    }

    private fun topLevelName(decl: Any?): String? {
        // Best-effort: pull a simple `let name = ...` binder name. The generator
        // only emits `let xN = ...`, so a regex on the decl's rendered form is
        // adequate and avoids coupling to the ParsedDecl hierarchy.
        val s = decl?.toString() ?: return null
        return Regex("""x\d+""").find(s)?.value
    }

    // ---- generator: mirrors TypeCheckerFuzzTest's grammar (kept local/simple) ----

    private fun generateRandomProgram(rng: Random): String {
        val n = 2 + rng.nextInt(5)
        val sb = StringBuilder()
        for (i in 0 until n) {
            val vars = (0 until i).map { "x$it" }
            sb.append("let x$i = ").append(generateExpr(rng, 0, 3, vars)).append("\n")
        }
        return sb.toString()
    }

    private fun generateExpr(rng: Random, depth: Int, maxDepth: Int, vars: List<String>): String {
        if (depth >= maxDepth) return leaf(rng, vars)
        return when (rng.nextInt(6)) {
            0 -> leaf(rng, vars)
            1 -> "{ a = ${generateExpr(rng, depth + 1, maxDepth, vars)}, b = ${generateExpr(rng, depth + 1, maxDepth, vars)} }"
            2 -> "[${generateExpr(rng, depth + 1, maxDepth, vars)}, ${generateExpr(rng, depth + 1, maxDepth, vars)}]"
            3 -> "{ p, q } -> ${generateExpr(rng, depth + 1, maxDepth, vars + listOf("p", "q"))}"
            4 -> "${leafNum(rng, vars)} + ${leafNum(rng, vars)}"
            else -> "\"v = \$(${leafNum(rng, vars)}.to_str {})\""
        }
    }

    private fun leaf(rng: Random, vars: List<String>): String = when (rng.nextInt(4)) {
        0 -> rng.nextInt(100).toString()
        1 -> "\"str${rng.nextInt(9)}\""
        2 -> if (rng.nextBoolean()) "True" else "False"
        else -> vars.randomOrNull(rng) ?: rng.nextInt(100).toString()
    }

    private fun leafNum(rng: Random, vars: List<String>): String =
        vars.randomOrNull(rng) ?: rng.nextInt(100).toString()

    private fun <T> List<T>.randomOrNull(rng: Random): T? =
        if (isEmpty()) null else this[rng.nextInt(size)]
}
