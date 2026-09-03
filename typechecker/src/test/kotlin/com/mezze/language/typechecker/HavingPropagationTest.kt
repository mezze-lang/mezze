package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * `having` / ability-constraint propagation beyond a direct `x.produce {}` call:
 *   - through a function alias (`let g = f`),
 *   - through a wrapper that calls the constrained function,
 * and a method that USES BOTH of a multi-associated-type ability's types.
 *
 * (The direct-call cases are in [AbilityCombinationMatrixTest]; this covers the
 *  indirection the matrix didn't.)
 */
class HavingPropagationTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun typeOf(name: String, b: String): String? {
        val r = tc.typeCheck(parser.parseParsedModule(b, "t.mz"), Paths.get("."), enablePrelude = true)
        return r.getVariableType(name)?.let { prettyPrintType(it) }
    }
    private fun err(b: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(b, "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    private val assoc = """
        ability Producer for 'a having 'out where { produce: { it: 'a } -> 'out }
        impl Producer for Int having 'out = Str where { produce = { it } -> "x" }
    """.trimIndent()

    // ── constraint survives indirection ──────────────────────────────────

    @Test
    fun `constraint survives a function alias`() {
        // `let g = f` must carry f's `with Producer 'a` (and its having) forward.
        val t = typeOf("g", "$assoc\npub let f = { x } -> x.produce {}\npub let g = f")
        assertNotNull(t, "g should typecheck")
        assertTrue(t!!.contains("Producer"),
            "the aliased fn must keep the Producer constraint, got: $t")
    }

    @Test
    fun `constraint composes through a wrapper that calls the constrained fn`() {
        val t = typeOf("wrap",
            "$assoc\npub let f = { x } -> x.produce {}\npub let wrap = { y } -> f { x = y }")
        assertNotNull(t, "wrap should typecheck")
        assertTrue(t!!.contains("Producer"),
            "the wrapper must inherit the Producer constraint, got: $t")
    }

    @Test
    fun `calling the constrained fn on a concrete arg typechecks`() {
        assertNull(err("$assoc\npub let f = { x } -> x.produce {}\npub let h = {} -> f { x = 5 }"),
            "calling f on a concrete Int must typecheck")
    }

    // ── a method that uses BOTH associated types ─────────────────────────

    @Test
    fun `generic method using both associated types renders both having bindings`() {
        val t = typeOf("go", """
            ability Unbundle for 'c having 'k, 'v where { unbundle: { it: 'c } -> { k: 'k, v: 'v } }
            impl Unbundle for [Int] having 'k = Int, 'v = Str where { unbundle = { it } -> { k = 0, v = "x" } }
            pub let go = { xs } -> xs.unbundle {}
        """.trimIndent())
        assertNotNull(t, "go should typecheck")
        assertTrue(Regex("""having 'k = '\w+, 'v = '\w+""").containsMatchIn(t!!),
            "both `having 'k` and `having 'v` should render as vars, got: $t")
    }

    @Test
    fun `concrete receiver resolves both associated types`() {
        assertNull(err("""
            ability Unbundle for 'c having 'k, 'v where { unbundle: { it: 'c } -> { k: 'k, v: 'v } }
            impl Unbundle for [Int] having 'k = Int, 'v = Str where { unbundle = { it } -> { k = 0, v = "x" } }
            pub let go: {} -> { k: Int, v: Str } = {} -> ([1]).unbundle {}
        """.trimIndent()), "both 'k=Int and 'v=Str must resolve on a concrete receiver")
    }

    @Test
    fun `wrong having on one of two associated types is rejected`() {
        val e = err("""
            ability Unbundle for 'c having 'k, 'v where { unbundle: { it: 'c } -> { k: 'k, v: 'v } }
            impl Unbundle for [Int] having 'k = Int, 'v = Str where { unbundle = { it } -> { k = 0, v = "x" } }
            pub let go: {} -> { k: Int, v: Int } = {} -> ([1]).unbundle {}
        """.trimIndent())
        assertNotNull(e, "annotating 'v as Int (impl says Str) must be rejected")
    }
}
