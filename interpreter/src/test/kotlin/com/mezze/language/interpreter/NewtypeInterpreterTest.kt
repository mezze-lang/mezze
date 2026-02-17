package com.mezze.language.interpreter

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class NewtypeInterpreterTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    private fun parseTypeCheckAndRun(source: String): Map<String, MezzeValue> {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (_, context) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return context
    }

    // ========== Basic Construction Tests ==========

    @Test
    fun `test simple newtype with String`() {
        val source = """
            newtype Email = Email String

            let email = Email {value = "test@example.com"}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val email = variables["email"] as MezzeVariant
        assertEquals("Email", email.constructor)
        val emailData = email.value as MezzeRecord
        assertEquals("test@example.com", (emailData.fields["value"] as MezzeString).value)

        println("✅ Simple newtype with String works!")
    }

    @Test
    fun `test simple newtype with Int`() {
        val source = """
            newtype UserId = UserId Int

            let userId = UserId {value = 42}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val userId = variables["userId"] as MezzeVariant
        assertEquals("UserId", userId.constructor)
        val userIdData = userId.value as MezzeRecord
        assertEquals(42L, (userIdData.fields["value"] as MezzeInt).value)

        println("✅ Simple newtype with Int works!")
    }

    @Test
    fun `test simple newtype with Float`() {
        val source = """
            newtype Temperature = Temperature Float

            let temp = Temperature {value = 98.6}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val temp = variables["temp"] as MezzeVariant
        assertEquals("Temperature", temp.constructor)
        val tempData = temp.value as MezzeRecord
        assertEquals(98.6, (tempData.fields["value"] as MezzeFloat).value, 0.001)

        println("✅ Simple newtype with Float works!")
    }

    // ========== Newtype with Records ==========

    @Test
    fun `test newtype with record type`() {
        val source = """
            newtype Point = Point { x: Float, y: Float }

            let origin = Point {x = 0.0, y = 0.0}
            let point = Point {x = 3.0, y = 4.0}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val origin = variables["origin"] as MezzeVariant
        assertEquals("Point", origin.constructor)
        val originData = origin.value as MezzeRecord
        assertEquals(0.0, (originData.fields["x"] as MezzeFloat).value, 0.001)
        assertEquals(0.0, (originData.fields["y"] as MezzeFloat).value, 0.001)

        val point = variables["point"] as MezzeVariant
        assertEquals("Point", point.constructor)
        val pointData = point.value as MezzeRecord
        assertEquals(3.0, (pointData.fields["x"] as MezzeFloat).value, 0.001)
        assertEquals(4.0, (pointData.fields["y"] as MezzeFloat).value, 0.001)

        println("✅ Newtype with record type works!")
    }

    // ========== Pattern Matching Tests ==========

    @Test
    fun `test newtype pattern matching`() {
        val source = """
            newtype Email = Email String

            let extractEmail = {e} -> match e is
                Email {value} -> value

            let email = Email {value = "alice@example.com"}
            let result = extractEmail {e = email}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("alice@example.com", (variables["result"] as MezzeString).value)

        println("✅ Newtype pattern matching works!")
    }

    @Test
    fun `test newtype pattern matching with record`() {
        val source = """
            newtype Point = Point { x: Float, y: Float }

            let getX = {p} -> match p is
                Point {x, y} -> x

            let getY = {p} -> match p is
                Point {x, y} -> y

            let point = Point {x = 5.0, y = 12.0}
            let xCoord = getX {p = point}
            let yCoord = getY {p = point}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(5.0, (variables["xCoord"] as MezzeFloat).value, 0.001)
        assertEquals(12.0, (variables["yCoord"] as MezzeFloat).value, 0.001)

        println("✅ Newtype pattern matching with record works!")
    }

    // ========== Helper Function Tests ==========

    @Test
    fun `test newtype with constructor helper function`() {
        val source = """
            newtype Email = Email String

            # Helper function to create Email
            let mkEmail = {s} -> Email {value = s}

            # Helper function to unwrap Email
            let unwrapEmail = {e} -> match e is
                Email {value} -> value

            let email = mkEmail {s = "bob@test.com"}
            let address = unwrapEmail {e = email}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val email = variables["email"] as MezzeVariant
        assertEquals("Email", email.constructor)

        assertEquals("bob@test.com", (variables["address"] as MezzeString).value)

        println("✅ Newtype with helper functions works!")
    }

    @Test
    fun `test newtype with validation function`() {
        val source = """
            newtype Age = Age Int

            # Helper with validation
            let mkAge = {n} ->
                if n >= 0
                then Age {value = n}
                else Age {value = 0}

            let validAge = mkAge {n = 25}
            let invalidAge = mkAge {n = -5}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val validAge = variables["validAge"] as MezzeVariant
        assertEquals("Age", validAge.constructor)
        val validAgeData = validAge.value as MezzeRecord
        assertEquals(25L, (validAgeData.fields["value"] as MezzeInt).value)

        val invalidAge = variables["invalidAge"] as MezzeVariant
        assertEquals("Age", invalidAge.constructor)
        val invalidAgeData = invalidAge.value as MezzeRecord
        assertEquals(0L, (invalidAgeData.fields["value"] as MezzeInt).value)

        println("✅ Newtype with validation function works!")
    }

    // ========== Multiple Newtypes Tests ==========

    @Test
    fun `test multiple distinct newtypes`() {
        val source = """
            newtype Email = Email String
            newtype UserId = UserId Int
            newtype Name = Name String

            let email = Email {value = "user@test.com"}
            let userId = UserId {value = 123}
            let name = Name {value = "John Doe"}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val email = variables["email"] as MezzeVariant
        assertEquals("Email", email.constructor)

        val userId = variables["userId"] as MezzeVariant
        assertEquals("UserId", userId.constructor)

        val name = variables["name"] as MezzeVariant
        assertEquals("Name", name.constructor)

        println("✅ Multiple distinct newtypes work!")
    }

    @Test
    fun `test newtype type safety with distinct wrappers`() {
        val source = """
            newtype Email = Email String
            newtype Name = Name String

            let processEmail = {e} -> match e is
                Email {value} -> value

            let processName = {n} -> match n is
                Name {value} -> value

            let email = Email {value = "test@example.com"}
            let name = Name {value = "Alice"}

            let emailStr = processEmail {e = email}
            let nameStr = processName {n = name}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("test@example.com", (variables["emailStr"] as MezzeString).value)
        assertEquals("Alice", (variables["nameStr"] as MezzeString).value)

        println("✅ Newtype type safety with distinct wrappers works!")
    }

    // ========== Nested Newtypes Tests ==========

    @Test
    fun `test nested newtype construction`() {
        val source = """
            newtype UserId = UserId Int
            newtype User = User { id: UserId, name: String }

            let userId = UserId {value = 456}
            let user = User {id = userId, name = "Bob"}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val user = variables["user"] as MezzeVariant
        assertEquals("User", user.constructor)
        val userData = user.value as MezzeRecord

        val userId = userData.fields["id"] as MezzeVariant
        assertEquals("UserId", userId.constructor)

        assertEquals("Bob", (userData.fields["name"] as MezzeString).value)

        println("✅ Nested newtype construction works!")
    }

    @Test
    fun `test nested newtype pattern matching`() {
        val source = """
            newtype UserId = UserId Int
            newtype User = User { id: UserId, name: String }

            # Helper to extract UserId
            let unwrapUserId = {uid} -> match uid is
                UserId {value} -> value

            # Extract user's id and unwrap it
            let getUserId = {u} -> match u is
                User {id, name} -> unwrapUserId {uid = id}

            let userId = UserId {value = 789}
            let user = User {id = userId, name = "Charlie"}
            let extractedId = getUserId {u = user}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(789L, (variables["extractedId"] as MezzeInt).value)

        println("✅ Nested newtype pattern matching works!")
    }

    // ========== Function Parameter/Return Tests ==========

    @Test
    fun `test newtype as function parameter`() {
        val source = """
            newtype Distance = Distance Float

            let double = {d} -> match d is
                Distance {value} -> Distance {value = value * 2.0}

            let dist = Distance {value = 10.0}
            let doubled = double {d = dist}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val doubled = variables["doubled"] as MezzeVariant
        assertEquals("Distance", doubled.constructor)
        val doubledData = doubled.value as MezzeRecord
        assertEquals(20.0, (doubledData.fields["value"] as MezzeFloat).value, 0.001)

        println("✅ Newtype as function parameter works!")
    }

    @Test
    fun `test newtype as function return value`() {
        val source = """
            newtype Result = Result String

            let createResult = {msg} -> Result {value = msg}

            let result1 = createResult {msg = "Success"}
            let result2 = createResult {msg = "Failure"}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val result1 = variables["result1"] as MezzeVariant
        assertEquals("Result", result1.constructor)
        val result1Data = result1.value as MezzeRecord
        assertEquals("Success", (result1Data.fields["value"] as MezzeString).value)

        val result2 = variables["result2"] as MezzeVariant
        assertEquals("Result", result2.constructor)
        val result2Data = result2.value as MezzeRecord
        assertEquals("Failure", (result2Data.fields["value"] as MezzeString).value)

        println("✅ Newtype as function return value works!")
    }

    // ========== Complex Use Cases ==========

    @Test
    fun `test newtype with complex business logic`() {
        val source = """
            newtype Price = Price Float
            newtype Quantity = Quantity Float
            newtype Total = Total Float

            let calculateTotal = {price, qty} ->
                match price is
                    Price {value = p} -> match qty is
                        Quantity {value = q} -> Total {value = p * q}

            let price = Price {value = 19.99}
            let qty = Quantity {value = 3.0}
            let total = calculateTotal {price = price, qty = qty}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val total = variables["total"] as MezzeVariant
        assertEquals("Total", total.constructor)
        val totalData = total.value as MezzeRecord
        assertEquals(59.97, (totalData.fields["value"] as MezzeFloat).value, 0.01)

        println("✅ Newtype with complex business logic works!")
    }

    @Test
    fun `test newtype composition`() {
        val source = """
            newtype FirstName = FirstName String
            newtype LastName = LastName String
            newtype FullName = FullName String

            let combineNames = {first, last} ->
                match first is
                    FirstName {value = f} -> match last is
                        LastName {value = l} -> FullName {value = f}

            let firstName = FirstName {value = "John"}
            let lastName = LastName {value = "Smith"}
            let fullName = combineNames {first = firstName, last = lastName}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val fullName = variables["fullName"] as MezzeVariant
        assertEquals("FullName", fullName.constructor)
        val fullNameData = fullName.value as MezzeRecord
        assertEquals("John", (fullNameData.fields["value"] as MezzeString).value)

        println("✅ Newtype composition works!")
    }

    @Test
    fun `test newtype with conditional logic`() {
        val source = """
            newtype Score = Score Int
            newtype Grade = Grade String

            let calculateGrade = {s} -> match s is
                Score {value} ->
                    if value >= 90
                    then Grade {value = "A"}
                    else if value >= 80
                    then Grade {value = "B"}
                    else if value >= 70
                    then Grade {value = "C"}
                    else Grade {value = "F"}

            let score1 = Score {value = 95}
            let score2 = Score {value = 85}
            let score3 = Score {value = 75}
            let score4 = Score {value = 65}

            let grade1 = calculateGrade {s = score1}
            let grade2 = calculateGrade {s = score2}
            let grade3 = calculateGrade {s = score3}
            let grade4 = calculateGrade {s = score4}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val grade1 = variables["grade1"] as MezzeVariant
        val grade1Data = grade1.value as MezzeRecord
        assertEquals("A", (grade1Data.fields["value"] as MezzeString).value)

        val grade2 = variables["grade2"] as MezzeVariant
        val grade2Data = grade2.value as MezzeRecord
        assertEquals("B", (grade2Data.fields["value"] as MezzeString).value)

        val grade3 = variables["grade3"] as MezzeVariant
        val grade3Data = grade3.value as MezzeRecord
        assertEquals("C", (grade3Data.fields["value"] as MezzeString).value)

        val grade4 = variables["grade4"] as MezzeVariant
        val grade4Data = grade4.value as MezzeRecord
        assertEquals("F", (grade4Data.fields["value"] as MezzeString).value)

        println("✅ Newtype with conditional logic works!")
    }
}
