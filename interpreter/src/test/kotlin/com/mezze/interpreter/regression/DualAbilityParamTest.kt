package com.mezze.interpreter.regression

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Bug: two ability-constrained params share one impl.
 *
 * Given `let both = { a, b } -> a.area {} + b.area {}` where `a: Square`
 * and `b: Rect`, both calls resolved to the SAME impl because the resolver
 * used only the arg-record (which is empty for both) and never consulted
 * the receiver.
 *
 * Root cause: TypedASTCompiler.tryResolveAbilityOpByArgs ignored the receiver
 * type entirely. Substitution/arg-record are identical between the two calls,
 * so the scan returned the same opHash.
 *
 * Fix: monomorphize per (typearg-tuple) so receivers are concrete at emit time,
 * or fall back to receiver-specific resolution.
 */
class DualAbilityParamTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `both areas — Square and Rect`() {
        val src = """
            ability Shape for 'a where { area: { it: 'a } -> Int }
            type Square = { side: Int }
            type Rect = { w: Int, h: Int }
            impl Shape for Square where { area = { it } -> it.side * it.side }
            impl Shape for Rect where { area = { it } -> it.w * it.h }

            let both = { a, b } -> a.area {} + b.area {}
            both { a = { side = 3 }, b = { w = 2, h = 5 } }
        """.trimIndent()
        // 3*3 + 2*5 = 9 + 10 = 19
        assertEquals(19L, eval(src))
    }

    @Test
    fun `both areas swapped order — b first then a`() {
        val src = """
            ability Shape for 'a where { area: { it: 'a } -> Int }
            type Square = { side: Int }
            type Rect = { w: Int, h: Int }
            impl Shape for Square where { area = { it } -> it.side * it.side }
            impl Shape for Rect where { area = { it } -> it.w * it.h }

            let both = { a, b } -> b.area {} + a.area {}
            both { a = { side = 3 }, b = { w = 2, h = 5 } }
        """.trimIndent()
        assertEquals(19L, eval(src))
    }

    @Test
    fun `sequential calls no operator`() {
        // Verifies each receiver dispatches independently even without `+`.
        val src = """
            ability Shape for 'a where { area: { it: 'a } -> Int }
            type Square = { side: Int }
            type Rect = { w: Int, h: Int }
            impl Shape for Square where { area = { it } -> it.side * it.side }
            impl Shape for Rect where { area = { it } -> it.w * it.h }

            let both = { a, b } -> do
                let x = a.area {}
                let y = b.area {}
                x * 100 + y
            both { a = { side = 3 }, b = { w = 2, h = 5 } }
        """.trimIndent()
        // 9 * 100 + 10 = 910
        assertEquals(910L, eval(src))
    }

    @Test
    fun `Dog and Cat Str ability`() {
        val src = """
            ability Speak for 'a where { speak: { it: 'a } -> Str }
            type Dog = { name: Str, breed: Str }
            type Cat = { name: Str, indoor: Bool }
            impl Speak for Dog where { speak = { it } -> "woof: ${'$'}(it.name)" }
            impl Speak for Cat where { speak = { it } -> "meow: ${'$'}(it.name)" }

            let both = { a, b } -> "${'$'}(a.speak {}) | ${'$'}(b.speak {})"
            both { a = { name = "rex", breed = "lab" }, b = { name = "kit", indoor = True } }
        """.trimIndent()
        assertEquals("woof: rex | meow: kit", eval(src))
    }

    @Test
    fun `three-parameter version at three distinct types`() {
        val src = """
            ability Shape for 'a where { area: { it: 'a } -> Int }
            type Square = { side: Int }
            type Rect = { w: Int, h: Int }
            type Tri = { b: Int, h: Int }
            impl Shape for Square where { area = { it } -> it.side * it.side }
            impl Shape for Rect where { area = { it } -> it.w * it.h }
            impl Shape for Tri where { area = { it } -> it.b * it.h / 2 }

            let all3 = { a, b, c } -> a.area {} + b.area {} + c.area {}
            all3 { a = { side = 3 }, b = { w = 2, h = 5 }, c = { b = 4, h = 6 } }
        """.trimIndent()
        // 9 + 10 + 12 = 31
        assertEquals(31L, eval(src))
    }

    @Test
    fun `regression — same-type both params`() {
        // Sanity: both params share a type, both calls hit that impl.
        val src = """
            ability Shape for 'a where { area: { it: 'a } -> Int }
            type Square = { side: Int }
            impl Shape for Square where { area = { it } -> it.side * it.side }

            let both = { a, b } -> a.area {} + b.area {}
            both { a = { side = 3 }, b = { side = 4 } }
        """.trimIndent()
        // 9 + 16 = 25
        assertEquals(25L, eval(src))
    }

    @Test
    fun `regression — one param used`() {
        val src = """
            ability Shape for 'a where { area: { it: 'a } -> Int }
            type Square = { side: Int }
            type Rect = { w: Int, h: Int }
            impl Shape for Square where { area = { it } -> it.side * it.side }
            impl Shape for Rect where { area = { it } -> it.w * it.h }

            let useA = { a, b } -> a.area {}
            useA { a = { side = 3 }, b = { w = 2, h = 5 } }
        """.trimIndent()
        assertEquals(9L, eval(src))
    }

    @Test
    fun `regression — single-param generic still works`() {
        val src = """
            ability Shape for 'a where { area: { it: 'a } -> Int }
            type Square = { side: Int }
            impl Shape for Square where { area = { it } -> it.side * it.side }

            let one = { a } -> a.area {}
            one { a = { side = 7 } }
        """.trimIndent()
        assertEquals(49L, eval(src))
    }
}
