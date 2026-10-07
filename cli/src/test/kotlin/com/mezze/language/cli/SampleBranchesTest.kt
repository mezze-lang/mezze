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
        val fields = ((decl.scheme.type as MezzeType.FunctionType).paramType as MezzeType.RecordType).fields.keys
        return SampleBranches.inventory(decl, fields, decls)
            .mapValues { (_, ps) -> ps.map { "${it.source} | ${it.why}" } }
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
}
