package com.mezze.interpreter;

import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.interpreter.values.MezzeRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the pipe operator (|>) with placeholder (@).
 * Uses V2 unboxed values directly.
 */
class PipeOperatorIntegrationTest {

    private String priorStrict;

    @BeforeEach
    void setUp() {
        // Disable strict validation for pipe operator tests due to complex type inference issues
        priorStrict = System.getProperty("mezze.typecheck.strict");
        System.setProperty("mezze.typecheck.strict", "false");
    }

    /**
     * Put it back. A system property is process-global and this module runs with
     * `maxParallelForks = 1`, so leaving `strict=false` behind relaxed
     * typechecking for EVERY test that ran after this class in the one shared
     * JVM — silently, and deterministically rather than flakily.
     *
     * The typechecker module guards its whole `mezze.*` namespace with a JUnit
     * extension (MezzeSystemPropertyIsolation). There is no shared test module to
     * put that in, and this is the only site in this module, so it restores by
     * hand — the pattern SoundnessSweepTest uses.
     */
    @AfterEach
    void tearDown() {
        if (priorStrict == null) System.clearProperty("mezze.typecheck.strict");
        else System.setProperty("mezze.typecheck.strict", priorStrict);
    }

    private Map<String, Object> runAndGetContext(String source) {
        MezzeTestHelper.run(source);
        return MezzeLanguage.getLastExecutionContext();
    }

    @Test
    void testBasicPipeOperatorWithPlaceholder() {
        var ctx = runAndGetContext("""
            let square = {x} -> x * x
            let result = 5 |> square {x = @}
            """);
        assertEquals(25L, ctx.get("result"));
    }

    @Test
    void testPipeOperatorWithMultipleParameters() {
        var ctx = runAndGetContext("""
            let add = {x, y} -> x + y
            let result1 = 5 |> add {x = 10, y = @}
            let result2 = 3 |> add {x = @, y = 7}
            """);
        assertEquals(15L, ctx.get("result1")); // 10 + 5
        assertEquals(10L, ctx.get("result2")); // 3 + 7
    }

    @Test
    void testChainedPipeOperations() {
        var ctx = runAndGetContext("""
            let square = {x} -> x * x
            let double = {x} -> x * 2
            let result = 3 |> square {x = @} |> double {x = @}
            """);
        assertEquals(18L, ctx.get("result")); // (3^2 * 2) = 18
    }

    @Test
    void testPipeWithStringOperations() {
        var ctx = runAndGetContext("""
            let greet = {name} -> "Hello, $(name)!"
            let shout = {text} -> "$(text)!!!"
            let result = "World" |> greet {name = @} |> shout {text = @}
            """);
        assertEquals("Hello, World!!!!", ctx.get("result"));
    }

    @Test
    void testPipeWithComplexExpressions() {
        var ctx = runAndGetContext("""
            let square = {x} -> x * x
            let result1 = (5 + 3) |> square {x = @}
            let result2 = (10 - 2) |> square {x = @}
            """);
        assertEquals(64L, ctx.get("result1")); // (5+3)^2 = 64
        assertEquals(64L, ctx.get("result2")); // (10-2)^2 = 64
    }

    @Test
    void testPipeWithRecordOperations() {
        var ctx = runAndGetContext("""
            let person = {name = "Alice", age = 30}
            let getName: {p: {name: Str, ...}} -> Str = {p} -> p.name
            let greet = {name} -> "Hello, $(name)!"
            let result = person |> getName {p = @} |> greet {name = @}
            """);
        assertEquals("Hello, Alice!", ctx.get("result"));
    }

    @Test
    void testPipeWithArithmeticChains() {
        var ctx = runAndGetContext("""
            let add = {x, y} -> x + y
            let multiply = {x, y} -> x * y
            let result = 2 |> add {x = 3, y = @} |> multiply {x = 4, y = @}
            """);
        assertEquals(20L, ctx.get("result")); // (3 + 2) * 4 = 20
    }

    @Test
    void testPipeWithRecordUpdates() {
        var ctx = runAndGetContext("""
            let updateAge: {person: {name: Str, age: Int}, newAge: Int} -> {name: Str, age: Int} = {person, newAge} -> {...person, age = newAge}
            let person = {name = "Bob", age = 25}
            let result = person |> updateAge {person = @, newAge = 30}
            """);
        var result = (MezzeRecord) ctx.get("result");
        assertEquals("Bob", result.getField("name"));
        assertEquals(30L, result.getField("age"));
    }

    @Test
    void testPipeWithConditionalFunctions() {
        var ctx = runAndGetContext("""
            let max = {x, y} -> if x > y then x else y
            let result1 = 5 |> max {x = 3, y = @}
            let result2 = 2 |> max {x = 8, y = @}
            """);
        assertEquals(5L, ctx.get("result1"));
        assertEquals(8L, ctx.get("result2"));
    }

    @Test
    void testPipeWithDoExpressions() {
        var ctx = runAndGetContext("""
            let processNumber = {n} -> do
                let doubled = n * 2
                let squared = doubled * doubled
                squared + 1
            let result = 3 |> processNumber {n = @}
            """);
        assertEquals(37L, ctx.get("result")); // ((3*2)^2 + 1) = 37
    }

    @Test
    void testMultiplePlaceholderPositions() {
        var ctx = runAndGetContext("""
            let combine = {x, y, z} -> x + y * z
            let result1 = 5 |> combine {x = @, y = 2, z = 3}
            let result2 = 4 |> combine {x = 1, y = @, z = 2}
            let result3 = 3 |> combine {x = 2, y = 4, z = @}
            """);
        assertEquals(11L, ctx.get("result1")); // 5 + 2*3 = 11
        assertEquals(9L, ctx.get("result2"));  // 1 + 4*2 = 9
        assertEquals(14L, ctx.get("result3")); // 2 + 4*3 = 14
    }

    @Test
    void testPipeWithHigherOrderFunctions() {
        var ctx = runAndGetContext("""
            let applyTwice = {f, x} -> f {x = f {x = x}}
            let increment = {x} -> x + 1
            let result = 5 |> applyTwice {f = increment, x = @}
            """);
        assertEquals(7L, ctx.get("result")); // increment(increment(5)) = 7
    }

    @Test
    void testComplexPipeChainWithMixedOperations() {
        var ctx = runAndGetContext("""
            let add = {x, y} -> x + y
            let square = {x} -> x * x
            let toString = {x} -> "Result: calculated"
            let result = 2 |> add {x = 3, y = @} |> square {x = @} |> toString {x = @}
            """);
        assertEquals("Result: calculated", ctx.get("result"));
    }

    @Test
    void testPipeWithSimpleRecordOperations() {
        var ctx = runAndGetContext("""
            let person = {name = "Charlie", age = 25}
            let getAge: {p: {age: Int, ...}} -> Int = {p} -> p.age
            let addOne = {x} -> x + 1
            let result = person |> getAge {p = @} |> addOne {x = @}
            """);
        assertEquals(26L, ctx.get("result")); // 25 + 1 = 26
    }
}
