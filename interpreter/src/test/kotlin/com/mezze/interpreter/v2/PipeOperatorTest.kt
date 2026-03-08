package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.mezze.interpreter.v2.values.MezzeRecord

/**
 * Tests for pipe operator functionality.
 * Migrated from V1 to V2 with unboxed values.
 */
class PipeOperatorTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `test basic pipe operator with placeholder`() {
        val code = """
            let square = {x} -> x * x
            5 |> square {x = @}
        """
        assertEquals(25L, eval(code))
    }

    @Test
    fun `test pipe operator with multiple parameters - first`() {
        val code = """
            let add = {x, y} -> x + y
            5 |> add {x = 10, y = @}
        """
        assertEquals(15L, eval(code)) // 10 + 5
    }

    @Test
    fun `test pipe operator with multiple parameters - second`() {
        val code = """
            let add = {x, y} -> x + y
            3 |> add {x = @, y = 7}
        """
        assertEquals(10L, eval(code)) // 3 + 7
    }

    @Test
    fun `test chained pipe operations`() {
        val code = """
            let square = {x} -> x * x
            let double = {x} -> x * 2
            3 |> square {x = @} |> double {x = @}
        """
        assertEquals(18L, eval(code)) // (3² * 2) = 18
    }

    @Test
    fun `test pipe with string operations`() {
        val code = """
            let greet = {name} -> "Hello, $(name)!"
            let shout = {text} -> "$(text)!!!"
            "World" |> greet {name = @} |> shout {text = @}
        """
        assertEquals("Hello, World!!!!", eval(code))
    }

    @Test
    fun `test pipe with complex expressions - first`() {
        val code = """
            let square = {x} -> x * x
            (5 + 3) |> square {x = @}
        """
        assertEquals(64L, eval(code)) // (5+3)² = 64
    }

    @Test
    fun `test pipe with complex expressions - second`() {
        val code = """
            let square = {x} -> x * x
            (10 - 2) |> square {x = @}
        """
        assertEquals(64L, eval(code)) // (10-2)² = 64
    }

    @Test
    fun `test pipe with record operations`() {
        val code = """
            let person = {name = "Alice", age = 30}
            let getName: {p: {name: String, ...}} -> String = {p} -> p.name
            let greet = {name} -> "Hello, $(name)!"
            person |> getName {p = @} |> greet {name = @}
        """
        assertEquals("Hello, Alice!", eval(code))
    }

    @Test
    fun `test pipe with arithmetic chains`() {
        val code = """
            let add = {x, y} -> x + y
            let multiply = {x, y} -> x * y
            2 |> add {x = 3, y = @} |> multiply {x = 4, y = @}
        """
        assertEquals(20L, eval(code)) // (3 + 2) * 4 = 20
    }

    @Test
    fun `test pipe with record updates`() {
        val code = """
            let updateAge: {person: {name: String, age: Int}, newAge: Int} -> {name: String, age: Int} = {person, newAge} -> {...person, age = newAge}
            let person = {name = "Bob", age = 25}
            let result = person |> updateAge {person = @, newAge = 30}
            result.age
        """
        assertEquals(30L, eval(code))
    }

    @Test
    fun `test pipe with conditional functions - first`() {
        val code = """
            let max = {x, y} -> if x > y then x else y
            5 |> max {x = 3, y = @}
        """
        assertEquals(5L, eval(code))
    }

    @Test
    fun `test pipe with conditional functions - second`() {
        val code = """
            let max = {x, y} -> if x > y then x else y
            2 |> max {x = 8, y = @}
        """
        assertEquals(8L, eval(code))
    }

    @Test
    fun `test pipe with do expressions`() {
        val code = """
            let processNumber = {n} -> do
                let doubled = n * 2
                let squared = doubled * doubled
                squared + 1
            3 |> processNumber {n = @}
        """
        assertEquals(37L, eval(code)) // ((3*2)² + 1) = 37
    }

    @Test
    fun `test multiple placeholder positions - first`() {
        val code = """
            let combine = {x, y, z} -> x + y * z
            5 |> combine {x = @, y = 2, z = 3}
        """
        assertEquals(11L, eval(code)) // 5 + 2*3 = 11
    }

    @Test
    fun `test multiple placeholder positions - second`() {
        val code = """
            let combine = {x, y, z} -> x + y * z
            4 |> combine {x = 1, y = @, z = 2}
        """
        assertEquals(9L, eval(code)) // 1 + 4*2 = 9
    }

    @Test
    fun `test multiple placeholder positions - third`() {
        val code = """
            let combine = {x, y, z} -> x + y * z
            3 |> combine {x = 2, y = 4, z = @}
        """
        assertEquals(14L, eval(code)) // 2 + 4*3 = 14
    }

    @Test
    fun `test pipe with higher-order functions`() {
        val code = """
            let applyTwice = {f, x} -> f {x = f {x = x}}
            let increment = {x} -> x + 1
            5 |> applyTwice {f = increment, x = @}
        """
        assertEquals(7L, eval(code)) // increment(increment(5)) = 7
    }

    @Test
    fun `test complex pipe chain with mixed operations`() {
        val code = """
            let add = {x, y} -> x + y
            let square = {x} -> x * x
            let toString = {x} -> "Result: calculated"
            2 |> add {x = 3, y = @} |> square {x = @} |> toString {x = @}
        """
        assertEquals("Result: calculated", eval(code))
    }

    @Test
    fun `test pipe with simple record operations`() {
        val code = """
            let person = {name = "Charlie", age = 25}
            let getAge: {p: {age: Int, ...}} -> Int = {p} -> p.age
            let addOne = {x} -> x + 1
            person |> getAge {p = @} |> addOne {x = @}
        """
        assertEquals(26L, eval(code)) // 25 + 1 = 26
    }
}
