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
        // richer surface — nesting, effects, chains, spreads, guards, generics
        "let nested = f { a = g { b = h { c = 1 } } }",
        "let r = perform Log in do\n  emit { msg = \"x\" }\n  1",
        "let chain = xs.map { f = {x} -> x + 1 }.filter { p = @ }.fold { init = 0, f = {acc, x} -> acc + x }",
        "let guarded = {x} -> match x is\n  n if n > 0 -> n\n  _ -> 0",
        "newtype UserId = Int",
        "let typed = {a: Int, b: Str, c} -> a",
        "let interp = \"outer $(f { x = inner { y = 1 } })\"",
        "let spread = { ...base, a = 1, b = 2 }",
        "let seqspread = [...ys, 1, 2]",
        "type Pair 'a 'b = | P { fst: 'a, snd: 'b } |",
        "let deep = (((1 + 2) * 3))",
        "let nestedseq = [[1, 2], [3, 4], [5]]",
        "let multido = {} -> do\n  let a = 1\n  let b = a + 1\n  let c = b + 1\n  c",
        "effect State 'a where { get: {} -> 'a, put: { val: 'a } -> Unit }",
        "let withc = {x: 'a} -> x.show {} with Show 'a",
        "let opt = match maybe is\n  Some { val } -> val\n  None -> 0",
        "pub let exported = {x} -> x",
        "let lam = {f: {x: Int} -> Int} -> f { x = 1 }",
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

    @Test
    fun `regression - the exact inputs fuzzing caught crashing now fail gracefully`() {
        // Locked forever: each of these used to HANG (unconsumed operator loop) or
        // StackOverflow the parser. assertRobust runs under a per-input timeout, so
        // a re-introduced hang fails here by name, not by wedging the suite.
        val previouslyCrashing = listOf(
            "let p = a |>",                       // pipe with no RHS — HUNG
            "let x = a ==",                       // comparison, no RHS — HUNG
            "let x = a ||",                       // logical-or, no RHS — HUNG
            "let x = a &&",                       // logical-and, no RHS — HUNG
            "let x = a + ",                       // additive, no RHS — HUNG
            "let x = " + "(".repeat(1000) + "1" + ")".repeat(1000),  // deep parens — StackOverflow
            "let x = " + "(".repeat(1000),        // deep unbalanced — StackOverflow
            "type T = " + "[".repeat(1000) + "Int" + "]".repeat(1000) // deep type — StackOverflow
        )
        for (src in previouslyCrashing) assertRobust(src)
    }

    @Test
    fun `bracket chaos - flipping, dropping and adding brackets never crashes`() {
        val rng = Random(0xB4ACE7L)
        val brackets = "{}()[]".toList()
        repeat(5000) {
            val seed = seeds[rng.nextInt(seeds.size)]
            val chars = seed.toMutableList()
            var i = 0
            while (i < chars.size) {
                if (chars[i] in brackets && rng.nextInt(3) == 0) {
                    when (rng.nextInt(3)) {
                        0 -> chars[i] = brackets[rng.nextInt(brackets.size)]   // flip to another bracket
                        1 -> { chars.removeAt(i); continue }                   // drop it
                        2 -> chars.add(i, brackets[rng.nextInt(brackets.size)])// add a stray one
                    }
                }
                i++
            }
            assertRobust(chars.joinToString(""))
        }
    }

    @Test
    fun `keyword and operator mistypes never crash`() {
        val rng = Random(0x7A9E1L)
        // token -> plausible near-misses a user actually fat-fingers
        val swaps = mapOf(
            "->" to listOf("=>", "-", ">", "->>", "<-"),
            "=" to listOf("==", ":=", "=>", "="),
            "::" to listOf(":", ":::", ".", "::="),
            "|>" to listOf("|", ">", "|>>", "||>"),
            "let" to listOf("lett", "le", "lets", "let"),
            "match" to listOf("mathc", "mtch", "Match"),
            "type" to listOf("tpye", "typ", "Type"),
            "effect" to listOf("efect", "effct"),
            "ability" to listOf("abilty", "abilit"),
            "where" to listOf("were", "wher", "whre"),
            "do" to listOf("dp", "od"),
            "is" to listOf("si", "iss"),
            "with" to listOf("wth", "wit")
        )
        repeat(4000) {
            var src = seeds[rng.nextInt(seeds.size)]
            repeat(rng.nextInt(1, 4)) {
                val (from, tos) = swaps.entries.elementAt(rng.nextInt(swaps.size))
                if (src.contains(from)) src = src.replaceFirst(from, tos[rng.nextInt(tos.size)])
            }
            assertRobust(src)
        }
    }

    @Test
    fun `unbalanced bracket soup never crashes`() {
        val rng = Random(0xB507L)
        val bracketChars = "{}()[]<>".toList()
        repeat(4000) {
            val n = rng.nextInt(1, 60)
            val body = (0 until n).map { bracketChars[rng.nextInt(bracketChars.size)] }.joinToString("")
            // sometimes wrap in a decl so it enters expression/type parsing
            val src = when (rng.nextInt(3)) {
                0 -> body
                1 -> "let x = $body"
                else -> "type T = $body"
            }
            assertRobust(src)
        }
    }

    @Test
    fun `deep nesting does not crash the parser`() {
        // Probe recursion limits: nested parens/brackets/braces, balanced and not.
        // A StackOverflowError here is a real robustness finding (a user pasting
        // deeply-nested generated code must get an error, not a compiler crash).
        for (depth in listOf(25, 100, 250, 500, 1000)) {
            assertRobust("let x = " + "(".repeat(depth) + "1" + ")".repeat(depth))
            assertRobust("let x = " + "(".repeat(depth) + "1")           // unbalanced open
            assertRobust("let x = " + "[".repeat(depth) + "1" + "]".repeat(depth))
            assertRobust("let x = " + "{ a = ".repeat(depth) + "1" + " }".repeat(depth))
            assertRobust("type T = " + "[".repeat(depth) + "Int" + "]".repeat(depth))
        }
    }

    @Test
    fun `deep nesting of varied constructs does not crash`() {
        // Probe recursion paths that may NOT flow through the expression/type
        // depth guards: nested lambdas, function types, match, do-blocks, record
        // literals, and string interpolation. A StackOverflowError on any is a
        // guard gap to close.
        for (depth in listOf(50, 200, 600, 1200)) {
            assertRobust("let f = " + "{x} -> ".repeat(depth) + "x")                 // nested lambdas
            assertRobust("let t: " + "{} -> ".repeat(depth) + "Int = f")             // nested function types
            assertRobust("let d = {} -> " + "do\n  ".repeat(depth) + "1")            // nested do-blocks
            assertRobust("let r = " + "{ a = ".repeat(depth) + "1" + " }".repeat(depth)) // nested records
            assertRobust("let s = " + "\"$(".repeat(depth) + "x" + ")\"".repeat(depth))  // nested string interpolation
            assertRobust("let n = " + "-".repeat(depth) + "1")                       // unary chain
            assertRobust("let m = " + "[".repeat(depth) + "1")                       // deep seq, unbalanced
        }
    }

    @Test
    fun `string and number literal edge cases never crash`() {
        val literals = listOf(
            // strings / interpolation / escapes
            "\"", "\"\\", "\"\\n\"", "\"$(", "\"$()\"", "\"$(x", "\"$($(x))\"",
            "\"unterminated", "\"\\u\"", "\"\\u12\"", "\"tab\\there\"", "\"\\\"",
            "\"$(\"$(y)\")\"", "\"a $(b) c $(d",
            // numbers / fractions / naturals
            "1.2.3", "1e", "1e999", "0x", "0xGG", "1_", "_1", "1__2", "1.", ".5",
            "1n", "3/4", "1.2.3.4", "999999999999999999999999999999", "0b", "0o9",
            "1.0e", "1.e5", "0x1p3", "--1", "1..2"
        )
        for (lit in literals) {
            assertRobust(lit)
            assertRobust("let x = $lit")
            assertRobust("let x = $lit + 1")
        }
    }

    @Test
    fun `whitespace, newline and comment injection never crashes`() {
        val rng = Random(0x115L)
        val inject = listOf(" ", "\n", "\t", "\n\n", "  ", "\r\n", " # note\n", "\n## doc\n", "\n   \n")
        repeat(4000) {
            val seed = seeds[rng.nextInt(seeds.size)]
            val sb = StringBuilder()
            for (c in seed) {
                sb.append(c)
                if (rng.nextInt(6) == 0) sb.append(inject[rng.nextInt(inject.size)])
            }
            assertRobust(sb.toString())
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
