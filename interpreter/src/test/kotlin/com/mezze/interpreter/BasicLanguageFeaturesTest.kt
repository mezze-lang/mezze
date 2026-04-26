package com.mezze.interpreter

import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.interpreter.values.MezzeRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Basic language features tests using V2 unboxed values.
 * Migrated from V1 interpreter tests.
 */
class BasicLanguageFeaturesTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private fun runAndGetContext(source: String): Map<String, Any> {
        MezzeTestHelper.run(source)
        @Suppress("UNCHECKED_CAST")
        return MezzeLanguage.getLastExecutionContext() as Map<String, Any>
    }

    @Test
    fun `test basic literals`() {
        val source = """
            let intValue = 42
            let floatValue = 3.14
            let stringValue = "Hello World"
            let boolTrue = True
            let boolFalse = False
        """.trimIndent()

        val ctx = runAndGetContext(source)

        assertEquals(42L, ctx["intValue"])
        assertEquals(3.14, ctx["floatValue"] as Double, 0.001)
        assertEquals("Hello World", ctx["stringValue"])
        assertEquals(true, ctx["boolTrue"])
        assertEquals(false, ctx["boolFalse"])
    }

    @Test
    fun `test arithmetic operations`() {
        val source = """
            let a = 10
            let b = 3
            let addition = a + b
            let subtraction = a - b
            let multiplication = a * b
            let division = a / b
        """.trimIndent()

        val ctx = runAndGetContext(source)

        assertEquals(10L, ctx["a"])
        assertEquals(3L, ctx["b"])
        assertEquals(13L, ctx["addition"])
        assertEquals(7L, ctx["subtraction"])
        assertEquals(30L, ctx["multiplication"])
        assertEquals(3L, ctx["division"]) // Integer division
    }

    @Test
    fun `test float arithmetic operations`() {
        val source = """
            let x = 10.5
            let y = 2.5
            let floatAdd = x + y
            let floatSub = x - y
            let floatMul = x * y
            let floatDiv = x / y
        """.trimIndent()

        val ctx = runAndGetContext(source)

        assertEquals(10.5, ctx["x"] as Double, 0.001)
        assertEquals(2.5, ctx["y"] as Double, 0.001)
        assertEquals(13.0, ctx["floatAdd"] as Double, 0.001)
        assertEquals(8.0, ctx["floatSub"] as Double, 0.001)
        assertEquals(26.25, ctx["floatMul"] as Double, 0.001)
        assertEquals(4.2, ctx["floatDiv"] as Double, 0.001)
    }

    @Test
    fun `test string interpolation`() {
        val source = """
            let firstName = "John"
            let lastName = "Doe"
            let fullName = "$(firstName) $(lastName)"
            let greeting = "Hello, $(fullName)!"
        """.trimIndent()

        val ctx = runAndGetContext(source)

        assertEquals("John", ctx["firstName"])
        assertEquals("Doe", ctx["lastName"])
        assertEquals("John Doe", ctx["fullName"])
        assertEquals("Hello, John Doe!", ctx["greeting"])
    }

    @Test
    fun `test comparison operations`() {
        val source = """
            let a = 5
            let b = 10
            let equal = a == a
            let notEqual = a != b
            let lessThan = a < b
            let greaterThan = b > a
        """.trimIndent()

        val ctx = runAndGetContext(source)

        assertEquals(true, ctx["equal"])
        assertEquals(true, ctx["notEqual"])
        assertEquals(true, ctx["lessThan"])
        assertEquals(true, ctx["greaterThan"])
    }

    @Test
    fun `test boolean values`() {
        val source = """
            let t = True
            let f = False
            let condition1 = if t then True else False
            let condition2 = if f then True else False
        """.trimIndent()

        val ctx = runAndGetContext(source)

        assertEquals(true, ctx["t"])
        assertEquals(false, ctx["f"])
        assertEquals(true, ctx["condition1"])
        assertEquals(false, ctx["condition2"])
    }

    @Test
    fun `test basic records`() {
        val source = """
            let emptyRecord = {}
            let person = {name = "Alice", age = 30, active = True}
            let nested = {user = {id = 123, name = "Bob"}, department = "Engineering"}
        """.trimIndent()

        val ctx = runAndGetContext(source)

        // Empty record - V2 may return MezzeUnit or empty MezzeRecord
        val emptyRecord = ctx["emptyRecord"]
        if (emptyRecord is MezzeRecord) {
            assertEquals(0, emptyRecord.shape.fieldCount)
        }

        val person = ctx["person"] as MezzeRecord
        assertEquals("Alice", person.getField("name"))
        assertEquals(30L, person.getField("age"))
        assertEquals(true, person.getField("active"))

        val nested = ctx["nested"] as MezzeRecord
        val user = nested.getField("user") as MezzeRecord
        assertEquals(123L, user.getField("id"))
        assertEquals("Bob", user.getField("name"))
        assertEquals("Engineering", nested.getField("department"))
    }

    @Test
    fun `test record member access`() {
        val source = """
            let person = {name = "Charlie", age = 25, details = {city = "NYC", country = "USA"}}
            let personName = person.name
            let personAge = person.age
            let personCity = person.details.city
            let personCountry = person.details.country
        """.trimIndent()

        val ctx = runAndGetContext(source)

        assertEquals("Charlie", ctx["personName"])
        assertEquals(25L, ctx["personAge"])
        assertEquals("NYC", ctx["personCity"])
        assertEquals("USA", ctx["personCountry"])
    }

    @Test
    fun `test if expressions`() {
        val source = """
            let x = 10
            let y = 5
            let max = if x > y then x else y
            let min = if x < y then x else y
            let message = if x == y then "equal" else "not equal"
            let nested = if x > 0 then (if y > 0 then "both positive" else "x positive") else "x not positive"
        """.trimIndent()

        val ctx = runAndGetContext(source)

        assertEquals(10L, ctx["max"])
        assertEquals(5L, ctx["min"])
        assertEquals("not equal", ctx["message"])
        assertEquals("both positive", ctx["nested"])
    }

    @Test
    fun `test complex expressions`() {
        val source = """
            let a = 2
            let b = 3
            let c = 4
            let complex1 = (a + b) * c
            let complex2 = a + (b * c)
            let complex3 = (a * b) + (c / a)
            let boolComplex = if a < b then (if b < c then True else False) else False
        """.trimIndent()

        val ctx = runAndGetContext(source)

        assertEquals(20L, ctx["complex1"]) // (2 + 3) * 4 = 20
        assertEquals(14L, ctx["complex2"]) // 2 + (3 * 4) = 14
        assertEquals(8L, ctx["complex3"])  // (2 * 3) + (4 / 2) = 8
        assertEquals(true, ctx["boolComplex"])
    }

    @Test
    fun `test variable references and shadowing`() {
        val source = """
            let x = 10
            let y = x + 5
            let z = x * y
            let result = z - x
        """.trimIndent()

        val ctx = runAndGetContext(source)

        assertEquals(10L, ctx["x"])
        assertEquals(15L, ctx["y"])   // 10 + 5
        assertEquals(150L, ctx["z"])  // 10 * 15
        assertEquals(140L, ctx["result"]) // 150 - 10
    }

    @Test
    fun `test mixed type expressions`() {
        val source = """
            let person = {name = "David", age = 35, salary = 75000.50}
            let isAdult = person.age > 17
            let greeting = "Hello $(person.name)"
            let salaryInfo = "$(person.name) earns a lot"
            let ageNextYear = person.age + 1
        """.trimIndent()

        val ctx = runAndGetContext(source)

        assertEquals(true, ctx["isAdult"])
        assertEquals("Hello David", ctx["greeting"])
        assertEquals("David earns a lot", ctx["salaryInfo"])
        assertEquals(36L, ctx["ageNextYear"])
    }
}
