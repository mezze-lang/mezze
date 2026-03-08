package com.mezze.interpreter.v2;

import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.interpreter.v2.values.MezzeVariant;
import com.mezze.language.typechecker.ModuleResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for newtype declarations and operations.
 * Uses V2 unboxed values directly.
 */
class NewtypeIntegrationTest {

    @BeforeEach
    void setUp() {
        ModuleResolver.clearCache();
    }

    // ========== Basic Construction Tests ==========

    @Test
    void simpleNewtypeWithString() {
        Object result = MezzeTestHelper.run("""
            newtype Email = Email String

            let email = Email {value = "test@example.com"}
            match email is
                Email {value} -> value
            """);
        assertEquals("test@example.com", result);
    }

    @Test
    void simpleNewtypeWithInt() {
        Object result = MezzeTestHelper.run("""
            newtype UserId = UserId Int

            let userId = UserId {value = 42}
            match userId is
                UserId {value} -> value
            """);
        assertEquals(42L, result);
    }

    @Test
    void simpleNewtypeWithFloat() {
        Object result = MezzeTestHelper.run("""
            newtype Temperature = Temperature Float

            let temp = Temperature {value = 98.6}
            match temp is
                Temperature {value} -> value
            """);
        assertEquals(98.6, (Double) result, 0.001);
    }

    // ========== Newtype with Records ==========

    @Test
    void newtypeWithRecordType() {
        Object result = MezzeTestHelper.run("""
            newtype Point = Point { x: Float, y: Float }

            let point = Point {x = 3.0, y = 4.0}
            match point is
                Point {x, y} -> x + y
            """);
        assertEquals(7.0, (Double) result, 0.001);
    }

    // ========== Pattern Matching Tests ==========

    @Test
    void newtypePatternMatching() {
        Object result = MezzeTestHelper.run("""
            newtype Email = Email String

            let extractEmail = {e} -> match e is
                Email {value} -> value

            let email = Email {value = "alice@example.com"}
            extractEmail {e = email}
            """);
        assertEquals("alice@example.com", result);
    }

    @Test
    void newtypePatternMatchingWithRecord() {
        Object result = MezzeTestHelper.run("""
            newtype Point = Point { x: Float, y: Float }

            let getX = {p} -> match p is
                Point {x, y} -> x

            let point = Point {x = 5.0, y = 12.0}
            getX {p = point}
            """);
        assertEquals(5.0, (Double) result, 0.001);
    }

    // ========== Helper Function Tests ==========

    @Test
    void newtypeWithConstructorHelperFunction() {
        Object result = MezzeTestHelper.run("""
            newtype Email = Email String

            let mkEmail = {s} -> Email {value = s}
            let unwrapEmail = {e} -> match e is
                Email {value} -> value

            let email = mkEmail {s = "bob@test.com"}
            unwrapEmail {e = email}
            """);
        assertEquals("bob@test.com", result);
    }

    @Test
    void newtypeWithValidationFunction() {
        Object result = MezzeTestHelper.run("""
            newtype Age = Age Int

            let mkAge = {n} ->
                if n >= 0
                then Age {value = n}
                else Age {value = 0}

            let unwrapAge = {a} -> match a is
                Age {value} -> value

            let invalidAge = mkAge {n = -5}
            unwrapAge {a = invalidAge}
            """);
        assertEquals(0L, result);
    }

    // ========== Multiple Newtypes Tests ==========

    @Test
    void multipleDistinctNewtypes() {
        Object result = MezzeTestHelper.run("""
            newtype Email = Email String
            newtype UserId = UserId Int
            newtype Name = Name String

            let unwrapUserId = {uid} -> match uid is
                UserId {value} -> value

            let userId = UserId {value = 123}
            unwrapUserId {uid = userId}
            """);
        assertEquals(123L, result);
    }

    @Test
    void newtypeTypeSafetyWithDistinctWrappers() {
        Object result = MezzeTestHelper.run("""
            newtype Email = Email String
            newtype Name = Name String

            let processEmail = {e} -> match e is
                Email {value} -> value

            let processName = {n} -> match n is
                Name {value} -> value

            let email = Email {value = "test@example.com"}
            let name = Name {value = "Alice"}

            processEmail {e = email}
            """);
        assertEquals("test@example.com", result);
    }

    // ========== Nested Newtypes Tests ==========

    @Test
    void nestedNewtypeConstruction() {
        Object result = MezzeTestHelper.run("""
            newtype UserId = UserId Int
            newtype User = User { id: UserId, name: String }

            let userId = UserId {value = 456}
            let user = User {id = userId, name = "Bob"}
            match user is
                User {id, name} -> name
            """);
        assertEquals("Bob", result);
    }

    @Test
    void nestedNewtypePatternMatching() {
        Object result = MezzeTestHelper.run("""
            newtype UserId = UserId Int
            newtype User = User { id: UserId, name: String }

            let unwrapUserId = {uid} -> match uid is
                UserId {value} -> value

            let getUserId = {u} -> match u is
                User {id, name} -> unwrapUserId {uid = id}

            let userId = UserId {value = 789}
            let user = User {id = userId, name = "Charlie"}
            getUserId {u = user}
            """);
        assertEquals(789L, result);
    }

    // ========== Function Parameter/Return Tests ==========

    @Test
    void newtypeAsFunctionParameter() {
        Object result = MezzeTestHelper.run("""
            newtype Distance = Distance Float

            let double = {d} -> match d is
                Distance {value} -> Distance {value = value * 2.0}

            let unwrapDistance = {d} -> match d is
                Distance {value} -> value

            let dist = Distance {value = 10.0}
            let doubled = double {d = dist}
            unwrapDistance {d = doubled}
            """);
        assertEquals(20.0, (Double) result, 0.001);
    }

    @Test
    void newtypeAsFunctionReturnValue() {
        Object result = MezzeTestHelper.run("""
            newtype Result = Result String

            let createResult = {msg} -> Result {value = msg}
            let unwrapResult = {r} -> match r is
                Result {value} -> value

            let res = createResult {msg = "Success"}
            unwrapResult {r = res}
            """);
        assertEquals("Success", result);
    }

    // ========== Complex Use Cases ==========

    @Test
    void newtypeWithComplexBusinessLogic() {
        Object result = MezzeTestHelper.run("""
            newtype Price = Price Float
            newtype Quantity = Quantity Float
            newtype Total = Total Float

            let calculateTotal = {price, qty} ->
                match price is
                    Price {value = p} -> match qty is
                        Quantity {value = q} -> Total {value = p * q}

            let unwrapTotal = {t} -> match t is
                Total {value} -> value

            let price = Price {value = 19.99}
            let qty = Quantity {value = 3.0}
            let total = calculateTotal {price = price, qty = qty}
            unwrapTotal {t = total}
            """);
        assertEquals(59.97, (Double) result, 0.01);
    }

    @Test
    void newtypeWithConditionalLogic() {
        Object result = MezzeTestHelper.run("""
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

            let unwrapGrade = {g} -> match g is
                Grade {value} -> value

            let score = Score {value = 95}
            let grade = calculateGrade {s = score}
            unwrapGrade {g = grade}
            """);
        assertEquals("A", result);
    }
}
