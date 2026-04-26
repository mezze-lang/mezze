package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.mezze.interpreter.values.MezzeVariant
import com.mezze.interpreter.values.MezzeRecord

/**
 * Tests for newtype functionality.
 * Migrated from V1 to V2 with unboxed values.
 */
class NewtypeInterpreterTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========== Basic Construction Tests ==========

    @Test
    fun `test simple newtype with String`() {
        val code = """
            newtype Email = Email String
            let email = Email {value = "test@example.com"}
            match email is
                Email {value} -> value
        """
        assertEquals("test@example.com", eval(code))
    }

    @Test
    fun `test simple newtype with Int`() {
        val code = """
            newtype UserId = UserId Int
            let userId = UserId {value = 42}
            match userId is
                UserId {value} -> value
        """
        assertEquals(42L, eval(code))
    }

    @Test
    fun `test simple newtype with Float`() {
        val code = """
            newtype Temperature = Temperature Float
            let temp = Temperature {value = 98.6}
            match temp is
                Temperature {value} -> value
        """
        assertEquals(98.6, eval(code) as Double, 0.001)
    }

    // ========== Newtype with Records ==========

    @Test
    fun `test newtype with record type - x coordinate`() {
        val code = """
            newtype Point = Point { x: Float, y: Float }
            let point = Point {x = 3.0, y = 4.0}
            match point is
                Point {x, y} -> x
        """
        assertEquals(3.0, eval(code) as Double, 0.001)
    }

    @Test
    fun `test newtype with record type - y coordinate`() {
        val code = """
            newtype Point = Point { x: Float, y: Float }
            let point = Point {x = 3.0, y = 4.0}
            match point is
                Point {x, y} -> y
        """
        assertEquals(4.0, eval(code) as Double, 0.001)
    }

    @Test
    fun `test newtype origin point`() {
        val code = """
            newtype Point = Point { x: Float, y: Float }
            let origin = Point {x = 0.0, y = 0.0}
            match origin is
                Point {x, y} -> x + y
        """
        assertEquals(0.0, eval(code) as Double, 0.001)
    }

    // ========== Pattern Matching Tests ==========

    @Test
    fun `test newtype pattern matching`() {
        val code = """
            newtype Email = Email String
            let extractEmail = {e} -> match e is
                Email {value} -> value
            let email = Email {value = "alice@example.com"}
            extractEmail {e = email}
        """
        assertEquals("alice@example.com", eval(code))
    }

    @Test
    fun `test newtype pattern matching with record - x`() {
        val code = """
            newtype Point = Point { x: Float, y: Float }
            let getX = {p} -> match p is
                Point {x, y} -> x
            let point = Point {x = 5.0, y = 12.0}
            getX {p = point}
        """
        assertEquals(5.0, eval(code) as Double, 0.001)
    }

    @Test
    fun `test newtype pattern matching with record - y`() {
        val code = """
            newtype Point = Point { x: Float, y: Float }
            let getY = {p} -> match p is
                Point {x, y} -> y
            let point = Point {x = 5.0, y = 12.0}
            getY {p = point}
        """
        assertEquals(12.0, eval(code) as Double, 0.001)
    }

    // ========== Helper Function Tests ==========

    @Test
    fun `test newtype with constructor helper function`() {
        val code = """
            newtype Email = Email String
            let mkEmail = {s} -> Email {value = s}
            let unwrapEmail = {e} -> match e is
                Email {value} -> value
            let email = mkEmail {s = "bob@test.com"}
            unwrapEmail {e = email}
        """
        assertEquals("bob@test.com", eval(code))
    }

    @Test
    fun `test newtype with validation function - valid age`() {
        val code = """
            newtype Age = Age Int
            let mkAge = {n} ->
                if n >= 0
                then Age {value = n}
                else Age {value = 0}
            let validAge = mkAge {n = 25}
            match validAge is
                Age {value} -> value
        """
        assertEquals(25L, eval(code))
    }

    @Test
    fun `test newtype with validation function - invalid age`() {
        val code = """
            newtype Age = Age Int
            let mkAge = {n} ->
                if n >= 0
                then Age {value = n}
                else Age {value = 0}
            let invalidAge = mkAge {n = -5}
            match invalidAge is
                Age {value} -> value
        """
        assertEquals(0L, eval(code))
    }

    // ========== Multiple Newtypes Tests ==========

    @Test
    fun `test multiple distinct newtypes - email`() {
        val code = """
            newtype Email = Email String
            newtype UserId = UserId Int
            newtype Name = Name String
            let email = Email {value = "user@test.com"}
            match email is
                Email {value} -> value
        """
        assertEquals("user@test.com", eval(code))
    }

    @Test
    fun `test multiple distinct newtypes - userId`() {
        val code = """
            newtype Email = Email String
            newtype UserId = UserId Int
            newtype Name = Name String
            let userId = UserId {value = 123}
            match userId is
                UserId {value} -> value
        """
        assertEquals(123L, eval(code))
    }

    @Test
    fun `test multiple distinct newtypes - name`() {
        val code = """
            newtype Email = Email String
            newtype UserId = UserId Int
            newtype Name = Name String
            let name = Name {value = "John Doe"}
            match name is
                Name {value} -> value
        """
        assertEquals("John Doe", eval(code))
    }

    @Test
    fun `test newtype type safety with distinct wrappers - email`() {
        val code = """
            newtype Email = Email String
            newtype Name = Name String
            let processEmail = {e} -> match e is
                Email {value} -> value
            let email = Email {value = "test@example.com"}
            processEmail {e = email}
        """
        assertEquals("test@example.com", eval(code))
    }

    @Test
    fun `test newtype type safety with distinct wrappers - name`() {
        val code = """
            newtype Email = Email String
            newtype Name = Name String
            let processName = {n} -> match n is
                Name {value} -> value
            let name = Name {value = "Alice"}
            processName {n = name}
        """
        assertEquals("Alice", eval(code))
    }

    // ========== Nested Newtypes Tests ==========

    @Test
    fun `test nested newtype pattern matching`() {
        val code = """
            newtype UserId = UserId Int
            newtype User = User { id: UserId, name: String }
            let unwrapUserId = {uid} -> match uid is
                UserId {value} -> value
            let getUserId = {u} -> match u is
                User {id, name} -> unwrapUserId {uid = id}
            let userId = UserId {value = 789}
            let user = User {id = userId, name = "Charlie"}
            getUserId {u = user}
        """
        assertEquals(789L, eval(code))
    }

    // ========== Function Parameter/Return Tests ==========

    @Test
    fun `test newtype as function parameter`() {
        val code = """
            newtype Distance = Distance Float
            let double = {d} -> match d is
                Distance {value} -> Distance {value = value * 2.0}
            let dist = Distance {value = 10.0}
            let doubled = double {d = dist}
            match doubled is
                Distance {value} -> value
        """
        assertEquals(20.0, eval(code) as Double, 0.001)
    }

    @Test
    fun `test newtype as function return value - success`() {
        val code = """
            newtype MyResult = MyResult String
            let createResult = {msg} -> MyResult {value = msg}
            let result1 = createResult {msg = "Success"}
            match result1 is
                MyResult {value} -> value
        """
        assertEquals("Success", eval(code))
    }

    @Test
    fun `test newtype as function return value - failure`() {
        val code = """
            newtype MyResult = MyResult String
            let createResult = {msg} -> MyResult {value = msg}
            let result2 = createResult {msg = "Failure"}
            match result2 is
                MyResult {value} -> value
        """
        assertEquals("Failure", eval(code))
    }

    // ========== Complex Use Cases ==========

    @Test
    fun `test newtype with complex business logic`() {
        val code = """
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
            match total is
                Total {value} -> value
        """
        assertEquals(59.97, eval(code) as Double, 0.01)
    }

    @Test
    fun `test newtype composition`() {
        val code = """
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
            match fullName is
                FullName {value} -> value
        """
        assertEquals("John", eval(code))
    }

    @Test
    fun `test newtype with conditional logic - grade A`() {
        val code = """
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
            let grade1 = calculateGrade {s = score1}
            match grade1 is
                Grade {value} -> value
        """
        assertEquals("A", eval(code))
    }

    @Test
    fun `test newtype with conditional logic - grade B`() {
        val code = """
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
            let score2 = Score {value = 85}
            let grade2 = calculateGrade {s = score2}
            match grade2 is
                Grade {value} -> value
        """
        assertEquals("B", eval(code))
    }

    @Test
    fun `test newtype with conditional logic - grade C`() {
        val code = """
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
            let score3 = Score {value = 75}
            let grade3 = calculateGrade {s = score3}
            match grade3 is
                Grade {value} -> value
        """
        assertEquals("C", eval(code))
    }

    @Test
    fun `test newtype with conditional logic - grade F`() {
        val code = """
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
            let score4 = Score {value = 65}
            let grade4 = calculateGrade {s = score4}
            match grade4 is
                Grade {value} -> value
        """
        assertEquals("F", eval(code))
    }
}
