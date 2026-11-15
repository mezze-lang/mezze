package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The three routes `buildInterpolatedString` now takes.
 *
 * Interpolation needs `Str`, so by the time a value reaches the builder it is
 * usually already a String — `"$(n.to_str {})"` for anything else. That is
 * worth an EXACT size and, for one literal and one value, no builder at all.
 * Previously every slot was budgeted 16 characters, so a two-character result
 * asked for seventeen: `bench/cases/allocsurface` at n=2e7 measured 15 287
 * ps/iter with 38 young collections against `str::concat`'s 9 784 and 17, for
 * a desugaring whose target is that concat. Now 11 945 and 25.
 *
 * THREE ROUTES, so three ways to be wrong, and the ways are quiet — a dropped
 * part, a part in the wrong order, or a mis-sized buffer that truncates:
 *
 *  - ONE literal + ONE value, which skips the builder and calls
 *    `String.concat`; the literal can come FIRST or SECOND and the two
 *    orders must not be confused.
 *  - ALL values strings with more parts, which sizes the builder exactly —
 *    an under-estimate would have truncated, an over-estimate only wastes.
 *  - A NON-STRING value, which keeps the `String.valueOf` path.
 */
class InterpolationBuildPathsTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun run(src: String): Any = MezzeTestHelper.run(src.trimIndent())

    /** Literal FIRST, one value: the `concat` path. */
    @Test
    fun `one literal then one value`() {
        assertEquals("ab", run(
            """
            let x = "b"
            "a$(x)"
            """
        ).toString())
    }

    /** Value FIRST, one literal: the same path with the operands the other way. */
    @Test
    fun `one value then one literal`() {
        assertEquals("ba", run(
            """
            let x = "b"
            "$(x)a"
            """
        ).toString())
    }

    /** Many parts, every value a string: the exactly-sized builder. */
    @Test
    fun `several string values keep their order`() {
        assertEquals("<a|b|c>", run(
            """
            let a = "a"
            let b = "b"
            let c = "c"
            "<$(a)|$(b)|$(c)>"
            """
        ).toString())
    }

    /** Two values adjacent, with no literal between them. */
    @Test
    fun `adjacent values with no literal between`() {
        assertEquals("xy", run(
            """
            let a = "x"
            let b = "y"
            "$(a)$(b)"
            """
        ).toString())
    }

    /** An EMPTY string value must not be dropped or mis-size the buffer. */
    @Test
    fun `an empty value is still a part`() {
        assertEquals("[]", run(
            """
            let e = ""
            "[$(e)]"
            """
        ).toString())
    }

    /** A long value, so an under-sized buffer would truncate rather than pad. */
    @Test
    fun `a long value is not truncated`() {
        assertEquals(103L, run(
            """
            use std::str
            let long = str::repeat { s = "z", n = 100 }
            str::size { s = "ab$(long)c" }
            """
        ))
    }

    /** A NON-string value keeps the `String.valueOf` route. */
    @Test
    fun `a non-string value still renders`() {
        assertEquals("n=7", run(
            """
            use std::int
            let n = 7
            "n=$(int::to_str { n = n })"
            """
        ).toString())
    }

    /** Interpolation with no expression at all is just the literal. */
    @Test
    fun `a literal with no interpolation is unchanged`() {
        assertEquals("plain", run(
            """
            let s = "plain"
            "$(s)"
            """
        ).toString())
    }
}
