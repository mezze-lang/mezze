package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BasicLanguageFeaturesTest {

    private val parser = FunkParser()
    private val typeChecker = TypeChecker()

    private fun parseTypeCheckAndRun(source: String): Map<String, FunkValue> {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (_, context) = FunkTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return context
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

        val variables = parseTypeCheckAndRun(source)

        assertEquals(42L, (variables["intValue"] as FunkInt).value)
        assertEquals(3.14, (variables["floatValue"] as FunkFloat).value, 0.001)
        assertEquals("Hello World", (variables["stringValue"] as FunkString).value)
        assertEquals(true, (variables["boolTrue"] as FunkBool).value)
        assertEquals(false, (variables["boolFalse"] as FunkBool).value)
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

        val variables = parseTypeCheckAndRun(source)

        assertEquals(10L, (variables["a"] as FunkInt).value)
        assertEquals(3L, (variables["b"] as FunkInt).value)
        assertEquals(13L, (variables["addition"] as FunkInt).value)
        assertEquals(7L, (variables["subtraction"] as FunkInt).value)
        assertEquals(30L, (variables["multiplication"] as FunkInt).value)
        assertEquals(3L, (variables["division"] as FunkInt).value) // Integer division
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

        val variables = parseTypeCheckAndRun(source)

        assertEquals(10.5, (variables["x"] as FunkFloat).value, 0.001)
        assertEquals(2.5, (variables["y"] as FunkFloat).value, 0.001)
        assertEquals(13.0, (variables["floatAdd"] as FunkFloat).value, 0.001)
        assertEquals(8.0, (variables["floatSub"] as FunkFloat).value, 0.001)
        assertEquals(26.25, (variables["floatMul"] as FunkFloat).value, 0.001)
        assertEquals(4.2, (variables["floatDiv"] as FunkFloat).value, 0.001)
    }

    @Test
    fun `test string interpolation`() {
        val source = """
            let firstName = "John"
            let lastName = "Doe"
            let fullName = "$(firstName) $(lastName)"
            let greeting = "Hello, $(fullName)!"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("John", (variables["firstName"] as FunkString).value)
        assertEquals("Doe", (variables["lastName"] as FunkString).value)
        assertEquals("John Doe", (variables["fullName"] as FunkString).value)
        assertEquals("Hello, John Doe!", (variables["greeting"] as FunkString).value)
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

        val variables = parseTypeCheckAndRun(source)

        assertEquals(true, (variables["equal"] as FunkBool).value)
        assertEquals(true, (variables["notEqual"] as FunkBool).value)
        assertEquals(true, (variables["lessThan"] as FunkBool).value)
        assertEquals(true, (variables["greaterThan"] as FunkBool).value)
    }

    @Test
    fun `test boolean values`() {
        val source = """
            let t = True
            let f = False
            let condition1 = if t then True else False
            let condition2 = if f then True else False
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(true, (variables["t"] as FunkBool).value)
        assertEquals(false, (variables["f"] as FunkBool).value)
        assertEquals(true, (variables["condition1"] as FunkBool).value)
        assertEquals(false, (variables["condition2"] as FunkBool).value)
    }

    @Test
    fun `test basic records`() {
        val source = """
            let emptyRecord = {}
            let person = {name = "Alice", age = 30, active = True}
            let nested = {user = {id = 123, name = "Bob"}, department = "Engineering"}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val emptyRecord = variables["emptyRecord"] as FunkRecord
        assertEquals(0, emptyRecord.fields.size)

        val person = variables["person"] as FunkRecord
        assertEquals("Alice", (person.fields["name"] as FunkString).value)
        assertEquals(30L, (person.fields["age"] as FunkInt).value)
        assertEquals(true, (person.fields["active"] as FunkBool).value)

        val nested = variables["nested"] as FunkRecord
        val user = nested.fields["user"] as FunkRecord
        assertEquals(123L, (user.fields["id"] as FunkInt).value)
        assertEquals("Bob", (user.fields["name"] as FunkString).value)
        assertEquals("Engineering", (nested.fields["department"] as FunkString).value)
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

        val variables = parseTypeCheckAndRun(source)

        assertEquals("Charlie", (variables["personName"] as FunkString).value)
        assertEquals(25L, (variables["personAge"] as FunkInt).value)
        assertEquals("NYC", (variables["personCity"] as FunkString).value)
        assertEquals("USA", (variables["personCountry"] as FunkString).value)
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

        val variables = parseTypeCheckAndRun(source)

        assertEquals(10L, (variables["max"] as FunkInt).value)
        assertEquals(5L, (variables["min"] as FunkInt).value)
        assertEquals("not equal", (variables["message"] as FunkString).value)
        assertEquals("both positive", (variables["nested"] as FunkString).value)
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

        val variables = parseTypeCheckAndRun(source)

        assertEquals(20L, (variables["complex1"] as FunkInt).value) // (2 + 3) * 4 = 20
        assertEquals(14L, (variables["complex2"] as FunkInt).value) // 2 + (3 * 4) = 14
        assertEquals(8L, (variables["complex3"] as FunkInt).value) // (2 * 3) + (4 / 2) = 8
        assertEquals(true, (variables["boolComplex"] as FunkBool).value) // nested if conditions
    }

    @Test
    fun `test variable references and shadowing`() {
        val source = """
            let x = 10
            let y = x + 5
            let z = x * y
            let result = z - x
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(10L, (variables["x"] as FunkInt).value)
        assertEquals(15L, (variables["y"] as FunkInt).value) // 10 + 5
        assertEquals(150L, (variables["z"] as FunkInt).value) // 10 * 15
        assertEquals(140L, (variables["result"] as FunkInt).value) // 150 - 10
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

        val variables = parseTypeCheckAndRun(source)

        assertEquals(true, (variables["isAdult"] as FunkBool).value)
        assertEquals("Hello David", (variables["greeting"] as FunkString).value)
        assertEquals("David earns a lot", (variables["salaryInfo"] as FunkString).value)
        assertEquals(36L, (variables["ageNextYear"] as FunkInt).value)
    }
}
