package com.mezze.language.cli

import com.mezze.language.ast.MezzeType
import com.mezze.language.cli.commands.SampleBranches
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typedast.TypedLetDecl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * What `mez sample` learns about a function by READING it, before running it.
 *
 * Blind draws reach the boundary of `n > max_value` essentially never: for
 * `let max_value = 100`, the seeded sampler produced Int's extremes and `1`, and
 * not one row where `big` changes its answer. Reading the comparison gives the
 * two values that do — 100 and 101 — and resolving `max_value` is what makes
 * that possible at all.
 */
class SampleBranchesTest {

    /** Pins per parameter, as `value | why`, for the function named [target]. */
    private fun pins(source: String, target: String): Map<String, List<String>> {
        val parsed = MezzeParser().parseParsedModule(source.trimIndent(), "test.mz")
        val result = TypeChecker().checkParsedModule(parsed, Paths.get("."), currentModulePath = null)
        val success = result as? TypeChecker.TypeCheckResult.Success
            ?: error("typecheck failed: ${(result as TypeChecker.TypeCheckResult.Failure).errors.joinToString { it.message }}")
        val decls = success.typedModule!!.decls.filterIsInstance<TypedLetDecl>()
        val decl = decls.single { it.name == target }
        val fields = ((decl.scheme.type as MezzeType.FunctionType).paramType as MezzeType.RecordType).fields
        val out = linkedMapOf<String, MutableList<String>>()
        val imported = success.loadedModules.values.flatMap { it.typedModule?.decls.orEmpty() }.filterIsInstance<TypedLetDecl>()
        for (row in SampleBranches.inventory(decl, fields, decls, imported)) {
            for ((param, pin) in row.pins) out.getOrPut(param) { mutableListOf() }.add("${pin.source} | ${pin.why}")
        }
        return out
    }

    @Test
    fun `a top-level constant is resolved and both sides of the comparison pinned`() {
        assertEquals(
            mapOf("n" to listOf(
                "100 | if n > max_value (= 100) — else",
                "101 | if n > max_value (= 100) — then",
            )),
            pins("""
                let max_value = 100
                pub let big = { n } -> n > max_value
            """, "big"),
        )
    }

    /** `>=` flips between 39 and 40, not 40 and 41 — and a chain of constants is followed. */
    @Test
    fun `a chain of constants is followed and ge puts the boundary below`() {
        assertEquals(
            listOf("39 | if n >= limit (= 40) — else", "40 | if n >= limit (= 40) — then"),
            pins("""
                let base = 40
                let limit = base
                pub let f = { n } -> n >= limit
            """, "f")["n"],
        )
    }

    /** `10 < n` is `n > 10` seen from `n`: the operator is mirrored, not read as written. */
    @Test
    fun `a parameter on the right mirrors the operator`() {
        assertEquals(
            listOf("10 | if n > 10 — else", "11 | if n > 10 — then"),
            pins("pub let f = { n } -> 10 < n", "f")["n"],
        )
    }

    @Test
    fun `string equality pins the value and a value that differs`() {
        assertEquals(
            listOf("\"admin\" | if s == \"admin\" — then", "\"adminx\" | if s == \"admin\" — else"),
            pins("pub let f = { s } -> s == \"admin\"", "f")["s"],
        )
    }

    /**
     * Arithmetic between known values is folded. `(0 - 7) / 2` is -3, not -4:
     * `Int` division truncates on the JVM, and pinning against -4 would put a row
     * on the wrong side.
     */
    @Test
    fun `constant arithmetic is folded with the runtime's division`() {
        assertEquals(
            listOf("-3 | if n > neg (= -3) — else", "-2 | if n > neg (= -3) — then"),
            pins("""
                let neg = (0 - 7) / 2
                pub let f = { n } -> n > neg
            """, "f")["n"],
        )
    }

    /** A constant that needs a function call is NOT guessed: no pin, ordinary draws. */
    @Test
    fun `a constant computed by a call is left unresolved`() {
        assertEquals(
            emptyMap<String, List<String>>(),
            pins("""
                let compute = { x } -> x * 2
                let limit = compute { x = 21 }
                pub let f = { n } -> n > limit
            """, "f"),
        )
    }

    /** `n > Int.MAX` can never hold: one pin, no wrapped-around `then` value. */
    @Test
    fun `a boundary at Int's maximum does not wrap`() {
        val p = pins("pub let f = { n } -> n > 9223372036854775807", "f")["n"]!!
        assertEquals(1, p.size, "got $p")
        assertTrue(p.single().endsWith("— else"), "got $p")
    }

    /** The match-arm pins from S-21 still come out, now with their reasons. */
    @Test
    fun `match arms still pin one constructor each`() {
        assertEquals(
            listOf("Empty | match s — `Empty` arm", "Circle { r = Arbitrary.arbitrary {} } | match s — `Circle` arm"),
            pins("""
                type Shape = | Empty, Circle { r: Int } |
                pub let f = { s: Shape } -> match s is
                  Empty -> 0
                  Circle { r } -> r
            """, "f")["s"],
        )
    }

    // ── field paths ───────────────────────────────────────────────────────────

    /**
     * `p.x > limit` pins the FIELD the branch reads and draws the rest, so the row
     * reaches the branch without fixing anything it does not depend on.
     */
    @Test
    fun `a comparison on a field pins that field and draws the others`() {
        val p = pins("""
            let limit = 10
            pub let f = { p: { x: Int, y: Int } } -> p.x > limit
        """, "f")["p"]!!
        assertEquals(2, p.size, "got $p")
        assertTrue(p[0].startsWith("{ x = 10, y = Arbitrary.arbitrary {} } | if p.x > limit (= 10) — else"), "got ${p[0]}")
        assertTrue(p[1].startsWith("{ x = 11, y = Arbitrary.arbitrary {} } | if p.x > limit (= 10) — then"), "got ${p[1]}")
    }

    /** A path through two records rebuilds both levels around the pinned leaf. */
    @Test
    fun `a nested path rebuilds every record on the way down`() {
        val p = pins("""
            type Point = { x: Int, y: Int }
            type Scene = { name: Str, origin: Point }
            pub let f = { s: Scene } -> s.origin.x >= 5
        """, "f")["s"]!!
        assertTrue(p[1].startsWith("{ name = Arbitrary.arbitrary {}, origin = { x = 5, y = Arbitrary.arbitrary {} } }"), "got ${p[1]}")
        assertTrue(p[1].endsWith("if s.origin.x >= 5 — then"), "got ${p[1]}")
    }

    /** `match s.shape` pins the constructor at that field. */
    @Test
    fun `a match on a field pins the constructor there`() {
        val p = pins("""
            type Shape = | Empty, Circle { r: Int } |
            type Scene = { name: Str, shape: Shape }
            pub let f = { s: Scene } -> match s.shape is
              Empty -> 0
              Circle { r } -> r
        """, "f")["s"]!!
        assertEquals(
            listOf(
                "{ name = Arbitrary.arbitrary {}, shape = Empty } | match s.shape — `Empty` arm",
                "{ name = Arbitrary.arbitrary {}, shape = Circle { r = Arbitrary.arbitrary {} } } | match s.shape — `Circle` arm",
            ),
            p,
        )
    }

    // ── derived values ────────────────────────────────────────────────────────

    /** `let m = n * 2` then `m > limit`: solve back to the `n` either side of the flip. */
    @Test
    fun `a comparison on a let derived from a parameter is solved back to it`() {
        assertEquals(
            listOf("5 | if m > limit (= 10) — else (m = n * 2)", "6 | if m > limit (= 10) — then (m = n * 2)"),
            pins("""
                let limit = 10
                pub let f = { n: Int } -> do
                  let m = n * 2
                  m > limit
            """, "f")["n"],
        )
    }

    /** A DECREASING derivation flips the other way: 59 → 41 > 40, 60 → 40 is not. */
    @Test
    fun `a decreasing derivation pins the right sides`() {
        assertEquals(
            listOf("59 | if m > 40 — then (m = 100 - n)", "60 | if m > 40 — else (m = 100 - n)"),
            pins("""
                pub let f = { n: Int } -> do
                  let m = 100 - n
                  m > 40
            """, "f")["n"],
        )
    }

    /** `2 * n == 7` has no integer solution: only the reachable side is pinned. */
    @Test
    fun `an equality with no integer solution pins only the reachable side`() {
        val p = pins("""
            pub let f = { n: Int } -> do
              let m = n * 2
              m == 7
        """, "f")["n"]!!
        assertEquals(1, p.size, "got $p")
        assertTrue(p.single().contains("— else"), "got $p")
    }

    /** Through two lets and a field: `b = (p.x + 5) * 2`, and the text says so. */
    @Test
    fun `a chain of lets through a field is solved and shown with its grouping`() {
        assertEquals(
            listOf("{ x = -6 } | if b < 0 — then (b = (p.x + 5) * 2)", "{ x = -5 } | if b < 0 — else (b = (p.x + 5) * 2)"),
            pins("""
                pub let f = { p: { x: Int } } -> do
                  let a = p.x + 5
                  let b = a * 2
                  b < 0
            """, "f")["p"],
        )
    }

    /**
     * The same name bound in two different scopes is ambiguous to a by-name
     * lookup: no guess about which binding a use means. (Rebinding within ONE
     * block is already a compile error.)
     */
    @Test
    fun `a name bound in two scopes is not read`() {
        assertEquals(
            emptyMap<String, List<String>>(),
            pins("""
                pub let f = { n: Int } -> do
                  let g = {} -> do
                    let m = n * 3
                    m
                  let m = n * 2
                  m > 10
            """, "f"),
        )
    }

    // ── two parameters ────────────────────────────────────────────────────────

    /**
     * Raw rows, for branches that constrain two parameters TOGETHER — the
     * per-parameter view in [pins] cannot show that `a` and `b` were chosen as a
     * pair.
     */
    private fun rows(source: String, target: String): List<String> {
        val parsed = MezzeParser().parseParsedModule(source.trimIndent(), "test.mz")
        val success = TypeChecker().checkParsedModule(parsed, Paths.get("."), currentModulePath = null)
            as TypeChecker.TypeCheckResult.Success
        val decls = success.typedModule!!.decls.filterIsInstance<TypedLetDecl>()
        val decl = decls.single { it.name == target }
        val fields = ((decl.scheme.type as MezzeType.FunctionType).paramType as MezzeType.RecordType).fields
        val imported = success.loadedModules.values.flatMap { it.typedModule?.decls.orEmpty() }.filterIsInstance<TypedLetDecl>()
        return SampleBranches.inventory(decl, fields, decls, imported).map { row ->
            row.pins.entries.joinToString(", ") { "${it.key} = ${it.value.source}" } + " | " + row.why
        }
    }

    /**
     * Less, equal, greater — as PAIRS. Equal is the edge blind draws never reach:
     * two independent 64-bit draws are essentially never the same value.
     */
    @Test
    fun `a comparison between two parameters pins less equal and greater`() {
        assertEquals(
            listOf(
                "a = 0, b = 1 | if a < b — then (<)",
                "a = 1, b = 1 | if a < b — else (==)",
                "a = 1, b = 0 | if a < b — else (>)",
            ),
            rows("pub let f = { a: Int, b: Int } -> a < b", "f"),
        )
    }

    /** Two leaves in ONE parameter are placed into the same record. */
    @Test
    fun `two fields of one parameter are pinned in the same record`() {
        assertEquals(
            listOf(
                "p = { x = 0, y = 1 } | if p.x <= p.y — then (<)",
                "p = { x = 1, y = 1 } | if p.x <= p.y — then (==)",
                "p = { x = 1, y = 0 } | if p.x <= p.y — else (>)",
            ),
            rows("pub let f = { p: { x: Int, y: Int } } -> p.x <= p.y", "f"),
        )
    }

    /** Pairs and single-parameter pins share the table without displacing each other. */
    @Test
    fun `single-parameter pins and pairs pack without clashing`() {
        val r = rows("""
            pub let f = { a: Int, b: Int } -> do
              let x = a > 10
              let y = a < b
              y
        """, "f")
        assertEquals(5, r.size, "got $r")
        assertTrue(r.count { it.contains("a > 10") } == 2, "got $r")
        assertTrue(r.count { it.contains("a < b") } == 3, "got $r")
    }

    // ── sizes ─────────────────────────────────────────────────────────────────

    /** `seq::size { xs } > min_chunk` pins sequences of length 3 and 4. */
    @Test
    fun `a size comparison pins sequences either side of the length`() {
        val p = pins("""
            use std::collections::seq
            let min_chunk = 3
            pub let f = { xs: [Int] } -> seq::size { xs = xs } > min_chunk
        """, "f")["xs"]!!
        assertEquals(2, p.size, "got $p")
        assertEquals(3, p[0].substringBefore(" |").count { it == ',' } + 1, "got ${p[0]}")
        assertTrue(p[0].endsWith("if size(xs) > min_chunk (= 3) — else"), "got ${p[0]}")
        assertEquals(4, p[1].substringBefore(" |").count { it == ',' } + 1, "got ${p[1]}")
    }

    /** The inherent `xs.size {}` reaches the same native and gets the same treatment. */
    @Test
    fun `the dot spelling of size is read too`() {
        val p = pins("pub let f = { xs: [Int] } -> xs.size {} <= 2", "f")["xs"]!!
        assertEquals(listOf("then", "else"), p.map { it.substringAfterLast("— ") }, "got $p")
    }

    @Test
    fun `is_empty pins an empty and a non-empty sequence`() {
        val p = pins("""
            use std::collections::seq
            pub let f = { xs: [Str] } -> if seq::is_empty { xs = xs } then 0 else 1
        """, "f")["xs"]!!
        assertEquals("[] | is_empty(xs) — empty", p[0])
        assertTrue(p[1].endsWith("is_empty(xs) — not empty"), "got $p")
    }

    /**
     * Identity, not name: a user function that happens to be called `size` is a
     * different hash with a different body, and must not be read as a length.
     */
    @Test
    fun `a user function named size is not mistaken for the sequence size`() {
        assertEquals(
            emptyMap<String, List<String>>(),
            pins("""
                let size = { xs: [Int] } -> 42
                pub let f = { xs: [Int] } -> size { xs = xs } > 3
            """, "f"),
        )
    }

    // ── guards ────────────────────────────────────────────────────────────────

    private val classify = """
        let big_r = 10
        type Shape = | Empty, Circle { r: Int }, Rect { w: Int, h: Int } |
        pub let f = { s: Shape } -> match s is
          Circle { r } if r > big_r -> "big circle"
          Circle { r } -> "circle"
          Rect { w, h } if w == h -> "square"
          Rect { w, h } -> "rect"
          Empty -> "empty"
    """

    /** A guard on a payload field pins the constructor AND that field's boundary. */
    @Test
    fun `a guard on a payload field pins both sides of the guard`() {
        val p = pins(classify, "f")["s"]!!
        assertTrue("Circle { r = 10 } | match s — `Circle` guard: if r > big_r (= 10) — else" in p, "got $p")
        assertTrue("Circle { r = 11 } | match s — `Circle` guard: if r > big_r (= 10) — then" in p, "got $p")
    }

    /** `w == h` between two payload fields: the equal pair is what reaches "square". */
    @Test
    fun `a guard relating two payload fields pins the equal pair`() {
        val p = pins(classify, "f")["s"]!!
        assertTrue("Rect { w = 1, h = 1 } | match s — `Rect` guard: if w == h — then (==)" in p, "got $p")
    }

    /** `x if x < 0` — the bound name is the scrutinee itself. */
    @Test
    fun `a guard on a bare variable pattern pins the scrutinee`() {
        assertEquals(
            listOf("-1 | match n guard — if x < 0 — then", "0 | match n guard — if x < 0 — else"),
            pins("""
                pub let f = { n: Int } -> match n is
                  x if x < 0 -> "neg"
                  x -> "non-neg"
            """, "f")["n"],
        )
    }

    // ── constants that need evaluating ────────────────────────────────────────

    private val viaCall = """
        let compute = { x: Int } -> x * 2
        let limit = compute { x = 21 }
        pub let f = { n: Int } -> n > limit
    """

    private fun typed(source: String): Pair<TypedLetDecl, List<TypedLetDecl>> {
        val parsed = MezzeParser().parseParsedModule(source.trimIndent(), "test.mz")
        val success = TypeChecker().checkParsedModule(parsed, Paths.get("."), currentModulePath = null)
            as TypeChecker.TypeCheckResult.Success
        val decls = success.typedModule!!.decls.filterIsInstance<TypedLetDecl>()
        return decls.single { it.name == "f" } to decls
    }

    /** Folding cannot see through a call, so the constant is reported for evaluation. */
    @Test
    fun `a constant defined by a call is reported as needing evaluation`() {
        val (f, decls) = typed(viaCall)
        assertEquals(listOf("limit"), SampleBranches.unresolvedConstants(f, decls).map { it.name })
    }

    /** A foldable constant is NOT reported — no round-trip for what folding already knows. */
    @Test
    fun `a foldable constant is not reported`() {
        val (f, decls) = typed("""
            let limit = 6 * 7
            pub let f = { n: Int } -> n > limit
        """)
        assertEquals(emptyList<String>(), SampleBranches.unresolvedConstants(f, decls).map { it.name })
    }

    /** Given the evaluated value, the branch is pinned exactly as a literal would be. */
    @Test
    fun `an evaluated constant is used like a literal`() {
        val (f, decls) = typed(viaCall)
        val limit = decls.single { it.name == "limit" }
        val fields = ((f.scheme.type as MezzeType.FunctionType).paramType as MezzeType.RecordType).fields
        val rows = SampleBranches.inventory(f, fields, decls, evaluated = mapOf(limit.hash to 42L))
        assertEquals(
            listOf("42 | if n > limit (= 42) — else", "43 | if n > limit (= 42) — then"),
            rows.map { r -> r.pins.getValue("n").let { "${it.source} | ${it.why}" } },
        )
    }
}
