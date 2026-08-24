package com.mezze.interpreter;

import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for newtype declarations and operations.
 * Uses V2 unboxed values directly.
 */
class NewtypeIntegrationTest {
    // ========== Basic Construction Tests ==========

    @Test
    void simpleNewtypeWithString() {
        Object result = MezzeTestHelper.run("""
            newtype Email = Str

            let email = Email.("test@example.com")
            match email is
                Email.(value) -> value
            """);
        assertEquals("test@example.com", result);
    }

    @Test
    void simpleNewtypeWithInt() {
        Object result = MezzeTestHelper.run("""
            newtype UserId = Int

            let userId = UserId.(42)
            match userId is
                UserId.(value) -> value
            """);
        assertEquals(42L, result);
    }

    @Test
    void simpleNewtypeWithFloat() {
        Object result = MezzeTestHelper.run("""
            newtype Temperature = Float

            let temp = Temperature.(98.6)
            match temp is
                Temperature.(value) -> value
            """);
        assertEquals(98.6, (Double) result, 0.001);
    }

    // ========== Newtype with Records ==========

    @Test
    void newtypeWithRecordType() {
        Object result = MezzeTestHelper.run("""
            newtype Point = { x: Float, y: Float }

            let point = Point.({x = 3.0, y = 4.0})
            match point is
                Point.({x, y}) -> x + y
            """);
        assertEquals(7.0, (Double) result, 0.001);
    }

    // ========== Pattern Matching Tests ==========

    @Test
    void newtypePatternMatching() {
        Object result = MezzeTestHelper.run("""
            newtype Email = Str

            let extractEmail = {e} -> match e is
                Email.(value) -> value

            let email = Email.("alice@example.com")
            extractEmail {e = email}
            """);
        assertEquals("alice@example.com", result);
    }

    @Test
    void newtypePatternMatchingWithRecord() {
        Object result = MezzeTestHelper.run("""
            newtype Point = { x: Float, y: Float }

            let getX = {p} -> match p is
                Point.({x, y}) -> x

            let point = Point.({x = 5.0, y = 12.0})
            getX {p = point}
            """);
        assertEquals(5.0, (Double) result, 0.001);
    }

    // ========== Helper Function Tests ==========

    @Test
    void newtypeWithConstructorHelperFunction() {
        Object result = MezzeTestHelper.run("""
            newtype Email = Str

            let mkEmail = {s} -> Email.(s)
            let unwrapEmail = {e} -> match e is
                Email.(value) -> value

            let email = mkEmail {s = "bob@test.com"}
            unwrapEmail {e = email}
            """);
        assertEquals("bob@test.com", result);
    }

    @Test
    void newtypeWithValidationFunction() {
        Object result = MezzeTestHelper.run("""
            newtype Age = Int

            let mkAge = {n} ->
                if n >= 0
                then Age.(n)
                else Age.(0)

            let unwrapAge = {a} -> match a is
                Age.(value) -> value

            let invalidAge = mkAge {n = -5}
            unwrapAge {a = invalidAge}
            """);
        assertEquals(0L, result);
    }

    // ========== Multiple Newtypes Tests ==========

    @Test
    void multipleDistinctNewtypes() {
        Object result = MezzeTestHelper.run("""
            newtype Email = Str
            newtype UserId = Int
            newtype Name = Str

            let unwrapUserId = {uid} -> match uid is
                UserId.(value) -> value

            let userId = UserId.(123)
            unwrapUserId {uid = userId}
            """);
        assertEquals(123L, result);
    }

    @Test
    void newtypeTypeSafetyWithDistinctWrappers() {
        Object result = MezzeTestHelper.run("""
            newtype Email = Str
            newtype Name = Str

            let processEmail = {e} -> match e is
                Email.(value) -> value

            let processName = {n} -> match n is
                Name.(value) -> value

            let email = Email.("test@example.com")
            let name = Name.("Alice")

            processEmail {e = email}
            """);
        assertEquals("test@example.com", result);
    }

    // ========== Nested Newtypes Tests ==========

    @Test
    void nestedNewtypeConstruction() {
        Object result = MezzeTestHelper.run("""
            newtype UserId = Int
            newtype User = { id: UserId, name: Str }

            let userId = UserId.(456)
            let user = User.({id = userId, name = "Bob"})
            match user is
                User.({id, name}) -> name
            """);
        assertEquals("Bob", result);
    }

    @Test
    void nestedNewtypePatternMatching() {
        Object result = MezzeTestHelper.run("""
            newtype UserId = Int
            newtype User = { id: UserId, name: Str }

            let unwrapUserId = {uid} -> match uid is
                UserId.(value) -> value

            let getUserId = {u} -> match u is
                User.({id, name}) -> unwrapUserId {uid = id}

            let userId = UserId.(789)
            let user = User.({id = userId, name = "Charlie"})
            getUserId {u = user}
            """);
        assertEquals(789L, result);
    }

    // ========== Function Parameter/Return Tests ==========

    @Test
    void newtypeAsFunctionParameter() {
        Object result = MezzeTestHelper.run("""
            newtype Distance = Float

            let double = {d} -> match d is
                Distance.(value) -> Distance.(value * 2.0)

            let unwrapDistance = {d} -> match d is
                Distance.(value) -> value

            let dist = Distance.(10.0)
            let doubled = double {d = dist}
            unwrapDistance {d = doubled}
            """);
        assertEquals(20.0, (Double) result, 0.001);
    }

    @Test
    void newtypeAsFunctionReturnValue() {
        Object result = MezzeTestHelper.run("""
            newtype Result = Str

            let createResult = {msg} -> Result.(msg)
            let unwrapResult = {r} -> match r is
                Result.(value) -> value

            let res = createResult {msg = "Success"}
            unwrapResult {r = res}
            """);
        assertEquals("Success", result);
    }

    // ========== Complex Use Cases ==========

    @Test
    void newtypeWithComplexBusinessLogic() {
        Object result = MezzeTestHelper.run("""
            newtype Price = Float
            newtype Quantity = Float
            newtype Total = Float

            let calculateTotal = {price, qty} ->
                match price is
                    Price.(p) -> match qty is
                        Quantity.(q) -> Total.(p * q)

            let unwrapTotal = {t} -> match t is
                Total.(value) -> value

            let price = Price.(19.99)
            let qty = Quantity.(3.0)
            let total = calculateTotal {price = price, qty = qty}
            unwrapTotal {t = total}
            """);
        assertEquals(59.97, (Double) result, 0.01);
    }

    @Test
    void newtypeWithConditionalLogic() {
        Object result = MezzeTestHelper.run("""
            newtype Score = Int
            newtype Grade = Str

            let calculateGrade = {s} -> match s is
                Score.(value) ->
                    if value >= 90
                    then Grade.("A")
                    else if value >= 80
                    then Grade.("B")
                    else if value >= 70
                    then Grade.("C")
                    else Grade.("F")

            let unwrapGrade = {g} -> match g is
                Grade.(value) -> value

            let score = Score.(95)
            let grade = calculateGrade {s = score}
            unwrapGrade {g = grade}
            """);
        assertEquals("A", result);
    }
}
