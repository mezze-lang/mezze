package com.mezze.language.typechecker
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * #212: nested-variant exhaustiveness must recurse to ARBITRARY depth, not just
 * one level. `Wrap { m = Mid { i = X } }` misses `Mid { i = Y }` three levels
 * deep; the earlier one-level check marked the whole `m` field covered because
 * `Mid { … }` had a payload. missingInVariantField now recurses into a
 * constructor-with-record-payload sub-pattern.
 */
class DeepNestedExhaustivenessTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker
    @BeforeEach fun s() { ModuleResolver.clearCache(); TypeChecker.clearCache(); parser = MezzeParser(); tc = TypeChecker() }
    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    private val threeLevel = """
        type Inner = | X, Y |
        type Mid = | Mid { i: Inner } |
        type Outer = | Wrap { m: Mid } |
    """.trimIndent()

    @Test fun `three levels missing the deep constructor is rejected`() {
        val e = err("""
            $threeLevel
            let f = { o: Outer } -> match o is
              Wrap { m = Mid { i = X } } -> 1
        """)
        assertNotNull(e, "3-level `Wrap { m = Mid { i = Y } }` must be reported")
        assertTrue(e!!.contains("Y"), "names the deep missing ctor: $e")
    }

    @Test fun `three levels covering both deep constructors is accepted`() {
        assertNull(err("""
            $threeLevel
            let f = { o: Outer } -> match o is
              Wrap { m = Mid { i = X } } -> 1
              Wrap { m = Mid { i = Y } } -> 2
        """))
    }

    @Test fun `a wildcard at the deep field is accepted`() {
        assertNull(err("""
            $threeLevel
            let f = { o: Outer } -> match o is
              Wrap { m = Mid { i = _ } } -> 1
        """))
    }

    @Test fun `binding the whole middle payload is accepted`() {
        assertNull(err("""
            $threeLevel
            let f = { o: Outer } -> match o is
              Wrap { m = mm } -> 1
        """))
    }

    @Test fun `four levels deep still reports the miss`() {
        assertNotNull(err("""
            type L0 = | A, B |
            type L1 = | W1 { x: L0 } |
            type L2 = | W2 { y: L1 } |
            type L3 = | W3 { z: L2 } |
            let f = { v: L3 } -> match v is
              W3 { z = W2 { y = W1 { x = A } } } -> 1
        """), "4-level miss must be reported")
    }

    @Test fun `the two-level case still works (regression)`() {
        assertNotNull(err("""
            type Inner = | X, Y |
            type Mid = | Mid { i: Inner } |
            let f = { m: Mid } -> match m is
              Mid { i = X } -> 1
        """))
    }
}
