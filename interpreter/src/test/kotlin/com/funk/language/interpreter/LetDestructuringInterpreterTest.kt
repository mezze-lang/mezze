package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class LetDestructuringInterpreterTest {

    private val parser = FunkParser()
    private val typeChecker = TypeChecker()

    private fun parseTypeCheckAndRun(source: String): Map<String, FunkValue> {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (_, context) = FunkTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return context
    }

    // ========== TOP-LEVEL RECORD DESTRUCTURING ==========

    @Test
    fun `test simple record destructuring at top level`() {
        val source = """
            let point = { x = 10, y = 20 }
            let { x, y } = point
            let sum = x + y
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(10L, (variables["x"] as FunkInt).value)
        assertEquals(20L, (variables["y"] as FunkInt).value)
        assertEquals(30L, (variables["sum"] as FunkInt).value)
    }

    @Test
    fun `test record destructuring extracts correct values`() {
        val source = """
            let record = { a = 1, b = 2, c = 3 }
            let { a, b, c } = record
            let result = a + b + c
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(1L, (variables["a"] as FunkInt).value)
        assertEquals(2L, (variables["b"] as FunkInt).value)
        assertEquals(3L, (variables["c"] as FunkInt).value)
        assertEquals(6L, (variables["result"] as FunkInt).value)
    }

    @Test
    fun `test record destructuring with renamed bindings`() {
        val source = """
            let point = { x = 100, y = 200 }
            let { x = myX, y = myY } = point
            let sum = myX + myY
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(100L, (variables["myX"] as FunkInt).value)
        assertEquals(200L, (variables["myY"] as FunkInt).value)
        assertEquals(300L, (variables["sum"] as FunkInt).value)
    }

    @Test
    fun `test nested record destructuring`() {
        val source = """
            let outer = { inner = { value = 42 } }
            let { inner = { value } } = outer
            let result = value * 2
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(42L, (variables["value"] as FunkInt).value)
        assertEquals(84L, (variables["result"] as FunkInt).value)
    }

    @Test
    fun `test deeply nested destructuring`() {
        val source = """
            let deep = { a = { b = { c = 100 } } }
            let { a = { b = { c } } } = deep
            let result = c + 1
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(100L, (variables["c"] as FunkInt).value)
        assertEquals(101L, (variables["result"] as FunkInt).value)
    }

    // ========== DESTRUCTURING WITH DIFFERENT TYPES ==========

    @Test
    fun `test destructuring with string fields`() {
        val source = """
            let person = { name = "Alice", city = "NYC" }
            let { name, city } = person
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("Alice", (variables["name"] as FunkString).value)
        assertEquals("NYC", (variables["city"] as FunkString).value)
    }

    @Test
    fun `test destructuring with boolean fields`() {
        val source = """
            let flags = { active = True, verified = False }
            let { active, verified } = flags
            let bothTrue = if active then (if verified then True else False) else False
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(true, (variables["active"] as FunkBool).value)
        assertEquals(false, (variables["verified"] as FunkBool).value)
        assertEquals(false, (variables["bothTrue"] as FunkBool).value)
    }

    @Test
    fun `test destructuring with mixed types`() {
        val source = """
            let data = { name = "Bob", age = 30, active = True }
            let { name, age, active } = data
            let isAdult = age > 18
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("Bob", (variables["name"] as FunkString).value)
        assertEquals(30L, (variables["age"] as FunkInt).value)
        assertEquals(true, (variables["active"] as FunkBool).value)
        assertEquals(true, (variables["isAdult"] as FunkBool).value)
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

            let result = compute {}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(50L, (variables["result"] as FunkInt).value)
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

            let result = compute {}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(33L, (variables["result"] as FunkInt).value)
    }

    // ========== DESTRUCTURING IN FUNCTION ARGUMENTS ==========

    @Test
    fun `test using destructured values in function calls`() {
        val source = """
            let add = {a, b} -> a + b
            let point = { x = 3, y = 4 }
            let { x, y } = point
            let result = add { a = x, b = y }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(7L, (variables["result"] as FunkInt).value)
    }

    @Test
    fun `test destructuring in lambda body`() {
        val source = """
            let processPoint = {point} -> do
                let { x, y } = point
                x * y

            let result = processPoint { point = { x = 6, y = 7 } }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(42L, (variables["result"] as FunkInt).value)
    }

    // ========== PARTIAL DESTRUCTURING WITH ELLIPSIS ==========

    @Test
    fun `test partial destructuring with ellipsis`() {
        val source = """
            let record = { a = 1, b = 2, c = 3 }
            let { a, ... } = record
            let result = a * 10
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(1L, (variables["a"] as FunkInt).value)
        assertEquals(10L, (variables["result"] as FunkInt).value)
    }

    @Test
    fun `test partial destructuring extracts only named fields`() {
        val source = """
            let data = { name = "Test", value = 42, extra = 100 }
            let { name, value, ... } = data
            let result = value + 1
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("Test", (variables["name"] as FunkString).value)
        assertEquals(42L, (variables["value"] as FunkInt).value)
        assertEquals(43L, (variables["result"] as FunkInt).value)
        assertFalse(variables.containsKey("extra"))
    }

    // ========== COMPLEX SCENARIOS ==========

    @Test
    fun `test destructuring result used in conditionals`() {
        val source = """
            let config = { threshold = 10, multiplier = 2 }
            let { threshold, multiplier } = config
            let value = 15
            let result = if value > threshold then value * multiplier else value
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(30L, (variables["result"] as FunkInt).value)
    }

    @Test
    fun `test chained destructuring operations`() {
        val source = """
            let data1 = { x = 5 }
            let { x } = data1
            let data2 = { y = x * 2 }
            let { y } = data2
            let result = y + x
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(5L, (variables["x"] as FunkInt).value)
        assertEquals(10L, (variables["y"] as FunkInt).value)
        assertEquals(15L, (variables["result"] as FunkInt).value)
    }
}
