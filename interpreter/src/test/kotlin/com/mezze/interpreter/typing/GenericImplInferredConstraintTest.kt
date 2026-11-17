package com.mezze.interpreter.typing

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * BUG 57: a generic impl whose op body needs the inner type's ability —
 * `impl Desc for Wrap 'a where { desc = { it } -> …it.inner.desc {}… }` — had
 * that `with Desc 'a` INFERRED, which the use-site solve enforced but emit never
 * turned into the op's dictionary parameter. One level of nesting worked by
 * accident; `Wrap (Wrap Int)` ran Wrap's `desc` on the inner Int and crashed with
 * "Expected record for field 'inner', got: Long". Written out, it always worked.
 *
 * Asserted on VALUES: the bug was a wrong dispatch, which a type check cannot see.
 */
class GenericImplInferredConstraintTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private val wrap = """
        type Wrap 'a = { inner: 'a }
        ability Desc for 'a where { desc: { it: 'a } -> Str }
        impl Desc for Int where { desc = { it } -> "int" }
        impl Desc for Wrap 'a where { desc = { it } -> "w(${'$'}(it.inner.desc {}))" }
    """.trimIndent()

    @Test
    fun `a nested generic impl dispatches each level to its own impl`() {
        val code = """
            $wrap
            let render: {} -> Str = {} -> do
              let w: Wrap (Wrap Int) = { inner = { inner = 3 } }
              w.desc {}
            render {}
        """.trimIndent()
        assertEquals("w(w(int))", MezzeTestHelper.run(code))
    }

    @Test
    fun `three levels`() {
        val code = """
            $wrap
            let render: {} -> Str = {} -> do
              let w: Wrap (Wrap (Wrap Int)) = { inner = { inner = { inner = 3 } } }
              w.desc {}
            render {}
        """.trimIndent()
        assertEquals("w(w(w(int)))", MezzeTestHelper.run(code))
    }

    @Test
    fun `only the op whose body needs the dictionary gets one`() {
        val code = """
            type Wrap 'a = { inner: 'a }
            ability Desc2 for 'a where { desc: { it: 'a } -> Str, tag: { it: 'a } -> Str }
            impl Desc2 for Int where { desc = { it } -> "int", tag = { it } -> "I" }
            impl Desc2 for Wrap 'a where { desc = { it } -> "w(${'$'}(it.inner.desc {}))", tag = { it } -> "W" }
            let render: {} -> Str = {} -> do
              let w: Wrap (Wrap Int) = { inner = { inner = 3 } }
              "${'$'}(w.desc {}) ${'$'}(w.tag {})"
            render {}
        """.trimIndent()
        assertEquals("w(w(int)) W", MezzeTestHelper.run(code))
    }

    /**
     * BUG 58: here `inner` is bound by a PATTERN, and it took the separately-solved
     * impl body's own variable rather than the signature's `'a`, so a
     * specialization had no dictionary for it. Failed with the constraint WRITTEN.
     */
    @Test
    fun `a nested generic impl over a VARIANT dispatches each level too`() {
        val code = """
            type Box 'a = | Bx { inner: 'a } |
            ability Desc for 'a where { desc: { it: 'a } -> Str }
            impl Desc for Int where { desc = { it } -> "int" }
            impl Desc for Box 'a where { desc = { it } -> match it is
              Bx { inner } -> "b(${'$'}(inner.desc {}))" }
            let render: {} -> Str = {} -> do
              let w: Box (Box Int) = Bx { inner = Bx { inner = 3 } }
              w.desc {}
            render {}
        """.trimIndent()
        assertEquals("b(b(int))", MezzeTestHelper.run(code))
    }
    @Test
    fun `a two-constructor generic variant dispatches its payload per level`() {
        val code = """
            type Opt 'a = | Som { v: 'a }, Non {}, |
            ability Desc for 'a where { desc: { it: 'a } -> Str }
            impl Desc for Int where { desc = { it } -> "int" }
            impl Desc for Opt 'a where { desc = { it } -> match it is
              Som { v } -> "s(${'$'}(v.desc {}))"
              Non {} -> "n" }
            let render: {} -> Str = {} -> do
              let a: Opt (Opt Int) = Som { v = Som { v = 1 } }
              let b: Opt (Opt Int) = Som { v = Non {} }
              "${'$'}(a.desc {}) ${'$'}(b.desc {})"
            render {}
        """.trimIndent()
        assertEquals("s(s(int)) s(n)", MezzeTestHelper.run(code))
    }
}
