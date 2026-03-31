package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.mezze.interpreter.v2.values.MezzeRecord

/**
 * Tests for let destructuring in the interpreter.
 * Migrated from V1 to V2 with unboxed values.
 */
class LetDestructuringInterpreterTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========== TOP-LEVEL RECORD DESTRUCTURING ==========

    @Test
    fun `test simple record destructuring at top level`() {
        val source = """
            let point = { x = 10, y = 20 }
            let { x, y } = point
            x + y
        """.trimIndent()

        assertEquals(30L, eval(source))
    }

    @Test
    fun `test record destructuring extracts correct values`() {
        val source = """
            let record = { a = 1, b = 2, c = 3 }
            let { a, b, c } = record
            a + b + c
        """.trimIndent()

        assertEquals(6L, eval(source))
    }

    @Test
    fun `test record destructuring with renamed bindings`() {
        val source = """
            let point = { x = 100, y = 200 }
            let { x = myX, y = myY } = point
            myX + myY
        """.trimIndent()

        assertEquals(300L, eval(source))
    }

    @Test
    fun `test nested record destructuring`() {
        val source = """
            let outer = { inner = { value = 42 } }
            let { inner = { value } } = outer
            value * 2
        """.trimIndent()

        assertEquals(84L, eval(source))
    }

    @Test
    fun `test deeply nested destructuring`() {
        val source = """
            let deep = { a = { b = { c = 100 } } }
            let { a = { b = { c } } } = deep
            c + 1
        """.trimIndent()

        assertEquals(101L, eval(source))
    }

    // ========== DESTRUCTURING WITH DIFFERENT TYPES ==========

    @Test
    fun `test destructuring with string fields`() {
        val source = """
            let person = { name = "Alice", city = "NYC" }
            let { name, city } = person
            { name = name, city = city }
        """.trimIndent()

        val result = eval(source) as MezzeRecord
        assertEquals("Alice", result.getField("name"))
        assertEquals("NYC", result.getField("city"))
    }

    @Test
    fun `test destructuring with boolean fields`() {
        val source = """
            let flags = { active = True, verified = False }
            let { active, verified } = flags
            if active then (if verified then True else False) else False
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test destructuring with mixed types`() {
        val source = """
            let data = { name = "Bob", age = 30, active = True }
            let { name, age, active } = data
            age > 18
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    // ========== DESTRUCTURING IN DO BLOCKS ==========

    @Test
    fun `test destructuring in do block`() {
        val source = """
            let getPoint = {} -> { x = 5, y = 10 }

            let compute = {} -> do
                let point = getPoint {}
                let { x, y } = point
                x * y

            compute {}
        """.trimIndent()

        assertEquals(50L, eval(source))
    }

    @Test
    fun `test multiple destructurings in do block`() {
        val source = """
            let p1 = { x = 1, y = 2 }
            let p2 = { x = 10, y = 20 }

            let compute = {} -> do
                let { x = a, y = b } = p1
                let { x = c, y = d } = p2
                a + b + c + d

            compute {}
        """.trimIndent()

        assertEquals(33L, eval(source))
    }

    // ========== DESTRUCTURING IN FUNCTION ARGUMENTS ==========

    @Test
    fun `test using destructured values in function calls`() {
        val source = """
            let add = {a, b} -> a + b
            let point = { x = 3, y = 4 }
            let { x, y } = point
            add { a = x, b = y }
        """.trimIndent()

        assertEquals(7L, eval(source))
    }

    @Test
    fun `test destructuring in lambda body`() {
        val source = """
            let processPoint = {point} -> do
                let { x, y } = point
                x * y

            processPoint { point = { x = 6, y = 7 } }
        """.trimIndent()

        assertEquals(42L, eval(source))
    }

    // ========== PARTIAL DESTRUCTURING WITH ELLIPSIS ==========

    @Test
    fun `test partial destructuring with ellipsis`() {
        val source = """
            let record = { a = 1, b = 2, c = 3 }
            let { a, ... } = record
            a * 10
        """.trimIndent()

        assertEquals(10L, eval(source))
    }

    @Test
    fun `test partial destructuring extracts only named fields`() {
        val source = """
            let data = { name = "Test", value = 42, extra = 100 }
            let { name, value, ... } = data
            value + 1
        """.trimIndent()

        assertEquals(43L, eval(source))
    }

    // ========== COMPLEX SCENARIOS ==========

    @Test
    fun `test destructuring result used in conditionals`() {
        val source = """
            let config = { threshold = 10, multiplier = 2 }
            let { threshold, multiplier } = config
            let value = 15
            if value > threshold then value * multiplier else value
        """.trimIndent()

        assertEquals(30L, eval(source))
    }

    @Test
    fun `test chained destructuring operations`() {
        val source = """
            let data1 = { x = 5 }
            let { x } = data1
            let data2 = { y = x * 2 }
            let { y } = data2
            y + x
        """.trimIndent()

        assertEquals(15L, eval(source))
    }
}
