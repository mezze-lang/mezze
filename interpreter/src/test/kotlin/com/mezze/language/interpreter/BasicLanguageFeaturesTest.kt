package com.mezze.language.interpreter

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BasicLanguageFeaturesTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    private fun parseTypeCheckAndRun(source: String): Map<String, MezzeValue> {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (_, context) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
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

        assertEquals(42L, (variables["intValue"] as MezzeInt).value)
        assertEquals(3.14, (variables["floatValue"] as MezzeFloat).value, 0.001)
        assertEquals("Hello World", (variables["stringValue"] as MezzeString).value)
        assertEquals(true, (variables["boolTrue"] as MezzeBool).value)
        assertEquals(false, (variables["boolFalse"] as MezzeBool).value)
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

        assertEquals(10L, (variables["a"] as MezzeInt).value)
        assertEquals(3L, (variables["b"] as MezzeInt).value)
        assertEquals(13L, (variables["addition"] as MezzeInt).value)
        assertEquals(7L, (variables["subtraction"] as MezzeInt).value)
        assertEquals(30L, (variables["multiplication"] as MezzeInt).value)
        assertEquals(3L, (variables["division"] as MezzeInt).value) // Integer division
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

        assertEquals(10.5, (variables["x"] as MezzeFloat).value, 0.001)
        assertEquals(2.5, (variables["y"] as MezzeFloat).value, 0.001)
        assertEquals(13.0, (variables["floatAdd"] as MezzeFloat).value, 0.001)
        assertEquals(8.0, (variables["floatSub"] as MezzeFloat).value, 0.001)
        assertEquals(26.25, (variables["floatMul"] as MezzeFloat).value, 0.001)
        assertEquals(4.2, (variables["floatDiv"] as MezzeFloat).value, 0.001)
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

        assertEquals("John", (variables["firstName"] as MezzeString).value)
        assertEquals("Doe", (variables["lastName"] as MezzeString).value)
        assertEquals("John Doe", (variables["fullName"] as MezzeString).value)
        assertEquals("Hello, John Doe!", (variables["greeting"] as MezzeString).value)
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

        assertEquals(true, (variables["equal"] as MezzeBool).value)
        assertEquals(true, (variables["notEqual"] as MezzeBool).value)
        assertEquals(true, (variables["lessThan"] as MezzeBool).value)
        assertEquals(true, (variables["greaterThan"] as MezzeBool).value)
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

        assertEquals(true, (variables["t"] as MezzeBool).value)
        assertEquals(false, (variables["f"] as MezzeBool).value)
        assertEquals(true, (variables["condition1"] as MezzeBool).value)
        assertEquals(false, (variables["condition2"] as MezzeBool).value)
    }

    @Test
    fun `test basic records`() {
        val source = """
            let emptyRecord = {}
            let person = {name = "Alice", age = 30, active = True}
            let nested = {user = {id = 123, name = "Bob"}, department = "Engineering"}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val emptyRecord = variables["emptyRecord"] as MezzeRecord
        assertEquals(0, emptyRecord.fields.size)

        val person = variables["person"] as MezzeRecord
        assertEquals("Alice", (person.fields["name"] as MezzeString).value)
        assertEquals(30L, (person.fields["age"] as MezzeInt).value)
        assertEquals(true, (person.fields["active"] as MezzeBool).value)

        val nested = variables["nested"] as MezzeRecord
        val user = nested.fields["user"] as MezzeRecord
        assertEquals(123L, (user.fields["id"] as MezzeInt).value)
        assertEquals("Bob", (user.fields["name"] as MezzeString).value)
        assertEquals("Engineering", (nested.fields["department"] as MezzeString).value)
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

        assertEquals("Charlie", (variables["personName"] as MezzeString).value)
        assertEquals(25L, (variables["personAge"] as MezzeInt).value)
        assertEquals("NYC", (variables["personCity"] as MezzeString).value)
        assertEquals("USA", (variables["personCountry"] as MezzeString).value)
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

        assertEquals(10L, (variables["max"] as MezzeInt).value)
        assertEquals(5L, (variables["min"] as MezzeInt).value)
        assertEquals("not equal", (variables["message"] as MezzeString).value)
        assertEquals("both positive", (variables["nested"] as MezzeString).value)
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

        assertEquals(20L, (variables["complex1"] as MezzeInt).value) // (2 + 3) * 4 = 20
        assertEquals(14L, (variables["complex2"] as MezzeInt).value) // 2 + (3 * 4) = 14
        assertEquals(8L, (variables["complex3"] as MezzeInt).value) // (2 * 3) + (4 / 2) = 8
        assertEquals(true, (variables["boolComplex"] as MezzeBool).value) // nested if conditions
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

        assertEquals(10L, (variables["x"] as MezzeInt).value)
        assertEquals(15L, (variables["y"] as MezzeInt).value) // 10 + 5
        assertEquals(150L, (variables["z"] as MezzeInt).value) // 10 * 15
        assertEquals(140L, (variables["result"] as MezzeInt).value) // 150 - 10
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

        assertEquals(true, (variables["isAdult"] as MezzeBool).value)
        assertEquals("Hello David", (variables["greeting"] as MezzeString).value)
        assertEquals("David earns a lot", (variables["salaryInfo"] as MezzeString).value)
        assertEquals(36L, (variables["ageNextYear"] as MezzeInt).value)
    }
}
