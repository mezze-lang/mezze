package com.mezze.language.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Assertions.fail
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.random.Random

/**
 * Fuzz / property tests for the parser's ROBUSTNESS. The one invariant every
 * possible input must satisfy:
 *
 *   parseParsedModule(anything) either returns a ParsedModule, or throws a
 *   ParseException that is LOCATED (line/col ≥ 1) with a NON-BLANK message —
 *   and NOTHING else: no NullPointerException / IndexOutOfBounds /
 *   StackOverflowError, and no HANG.
 *
 * A crash or a hang or a blank/locationless error IS the worst possible "error
 * message", so this underwrites the whole error-quality effort: malformed input
 * is the common case for a compiler and must always fail gracefully.
 *
 * Each candidate parses on a worker thread under a per-input timeout, so a
 * hanging input is ISOLATED and reported by value (fail-fast) instead of wedging
 * the whole suite. Three seeded (reproducible) generators: truncate valid
 * programs at every prefix (exercises EOF handling — the richest crash source),
 * randomly mutate valid programs, and splat random keyword/symbol soup.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ParserFuzzTest {

    private val parser = MezzeParser()
    // Daemon single-thread executor so a leaked (genuinely-hung) parse never
    // keeps the test JVM alive. JUnit makes a fresh instance per test method;
    // the executor is cheap and the JVM reaps daemons on exit.
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "parser-fuzz").apply { isDaemon = true }
    }

    private val seeds = listOf(
        "let x = 42",
        "let add = {a, b} -> a + b",
        "let g = {a: Int, b: Str} -> a",
        "type Color = | Red, Green, Blue |",
        "type Tree 'a = | Leaf, Node { value: 'a, left: Tree 'a } |",
        "let r = { name = \"ann\", age = 3 }",
        "let m = {x} -> match x is\n  Some { val } -> val\n  None -> 0",
        "let f = {} -> do\n  let y = 1 + 2\n  y",
        "use std::collections::seq",
        "effect Log where { emit: { msg: Str } -> Unit }",
        "ability Show for 'a where { show: { it: 'a } -> Str }",
        "let xs = [1, 2, 3]",
        "let p = a |> seq::map { xs = @, f = {x} -> x }",
        "let s = \"hi $(name), you are $(age)\"",
        "impl Show for Int where { show = { it } -> it.to_str {} }",
    )

    private fun esc(s: String) = s.replace("\n", "\\n")

    /** Returns a failure description for [src], or null if the parser handled it
     *  robustly (returned, or threw a located non-blank ParseException). */
    private fun problemWith(src: String): String? = try {
        parser.parseParsedModule(src, "fuzz.mz"); null
    } catch (e: ParseException) {
        when {
            e.errorMessage.isBlank() -> "BLANK message"
            e.line < 1 || e.column < 1 -> "BAD location L${e.line}:${e.column}"
            else -> null
        }
    } catch (t: Throwable) {
        "threw ${t.javaClass.name}: ${t.message}"
    }

    /** Run one input under a per-input timeout; fail-fast on any violation. */
    private fun assertRobust(src: String) {
        val future = exec.submit<String?> { problemWith(src) }
        val problem = try {
            future.get(4, TimeUnit.SECONDS)
        } catch (te: TimeoutException) {
            future.cancel(true)
            fail<String?>("Parser HUNG (>4s) on input <<${esc(src)}>>")
        }
        if (problem != null) fail<Unit>("$problem for input <<${esc(src)}>>")
    }

    @Test
    fun `truncation fuzz - every prefix of a valid program fails gracefully`() {
        for (seed in seeds) for (i in 0..seed.length) assertRobust(seed.substring(0, i))
    }

    @Test
    fun `mutation fuzz - random edits to valid programs never crash the parser`() {
        val rng = Random(0xBADC0DEL)
        val insertable = "{}()[]<>|=:,.@ \"'_-+".toList()
        repeat(4000) { assertRobust(mutate(seeds[rng.nextInt(seeds.size)], rng, insertable)) }
    }

    @Test
    fun `token-soup fuzz - random keyword and symbol sequences never crash`() {
        val rng = Random(0x5EEDL)
        val atoms = listOf(
            "let", "type", "match", "is", "do", "use", "effect", "ability", "impl",
            "for", "with", "where", "perform", "in", "pub", "newtype", "having",
            "{", "}", "(", ")", "[", "]", "->", "|>", "=", "::", ":", ",", "@",
            "...", "|", "x", "Foo", "'a", "1", "\"s\"", "+", ".", "_"
        )
        repeat(3000) {
            val n = rng.nextInt(1, 30)
            assertRobust((0 until n).joinToString(" ") { atoms[rng.nextInt(atoms.size)] })
        }
    }

    private fun mutate(s: String, rng: Random, insertable: List<Char>): String {
        val chars = s.toMutableList()
        repeat(rng.nextInt(1, 6)) {
            if (chars.isEmpty()) return ""
            when (rng.nextInt(4)) {
                0 -> chars.removeAt(rng.nextInt(chars.size))
                1 -> chars.add(rng.nextInt(chars.size + 1), insertable[rng.nextInt(insertable.size)])
                2 -> { val i = rng.nextInt(chars.size); chars.add(i, chars[i]) }
                3 -> if (chars.size > 1) {
                    val i = rng.nextInt(chars.size - 1)
                    val t = chars[i]; chars[i] = chars[i + 1]; chars[i + 1] = t
                }
            }
        }
        return chars.joinToString("")
    }
}
