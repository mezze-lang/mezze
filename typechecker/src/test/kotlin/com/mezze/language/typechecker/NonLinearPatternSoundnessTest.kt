package com.mezze.language.typechecker
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A pattern must not bind the same variable name twice (a "non-linear" pattern).
 * Found by the multi-agent soundness hunt: `A { x = n, y = n }` bound `n` to both
 * `x` and `y` with NO unification — the checker arbitrarily took the last field's
 * type (last-wins), so `n` could be used at a type the runtime never gives it
 * (silent type confusion). Now rejected (V0606); or-patterns, which legitimately
 * reuse a name across mutually-exclusive alternatives, are unaffected.
 */
class NonLinearPatternSoundnessTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker
    @BeforeEach fun s() { ModuleResolver.clearCache(); TypeChecker.clearCache(); parser = MezzeParser(); tc = TypeChecker() }
    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    @Test fun `duplicate binder in a record pattern is rejected`() {
        val e = err("""
            type T = | A { x: Str, y: Int } |
            let f = { t: T } ->
              match t is
                A { x = n, y = n } -> 1
        """)
        assertNotNull(e, "binding `n` twice must be rejected")
        assertTrue(e!!.contains("more than once"), "clear message: $e")
    }

    @Test fun `duplicate binder in a seq pattern is rejected`() {
        assertNotNull(err("""
            let f = { xs: [Int] } ->
              match xs is
                [n, n] -> n
                _ -> 0
        """), "`[n, n]` binds n twice")
    }

    @Test fun `duplicate binder across nested destructure is rejected`() {
        assertNotNull(err("""
            type Inner = | I { a: Int } |
            type Outer = | O { p: Inner, q: Int } |
            let f = { o: Outer } ->
              match o is
                O { p = I { a = n }, q = n } -> n
        """), "n bound in nested field and outer field")
    }

    @Test fun `distinct binders are accepted`() {
        assertNull(err("""
            type T = | A { x: Str, y: Int } |
            let f = { t: T } ->
              match t is
                A { x = p, y = q } -> q
        """))
    }

    @Test fun `underscore may repeat - it binds nothing`() {
        assertNull(err("""
            type T = | A { x: Str, y: Int } |
            let f = { t: T } ->
              match t is
                A { x = _, y = _ } -> 0
        """), "`_` is not a binding and may repeat")
    }

    @Test fun `or-pattern reusing a name across alternatives is still accepted`() {
        assertNull(err("""
            type T = | A { v: Int }, B { v: Int } |
            let f = { t: T } ->
              match t is
                A { v = n } | B { v = n } -> n
        """), "or-pattern alternatives legitimately bind the same name")
    }
}
