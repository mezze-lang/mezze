package com.mezze.interpreter.v2;

import com.mezze.interpreter.v2.runtime.MezzeLanguage;
import com.mezze.language.typechecker.ModuleResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for let destructuring patterns.
 * Uses V2 unboxed values directly.
 */
class LetDestructuringIntegrationTest {

    @BeforeEach
    void setUp() {
        ModuleResolver.clearCache();
    }

    private Map<String, Object> runAndGetContext(String source) {
        MezzeTestHelper.run(source);
        return MezzeLanguage.getLastExecutionContext();
    }

    // ========== TOP-LEVEL RECORD DESTRUCTURING ==========

    @Test
    void testSimpleRecordDestructuringAtTopLevel() {
        var ctx = runAndGetContext("""
            let point = { x = 10, y = 20 }
            let { x, y } = point
            let sum = x + y
            """);
        assertEquals(10L, ctx.get("x"));
        assertEquals(20L, ctx.get("y"));
        assertEquals(30L, ctx.get("sum"));
    }

    @Test
    void testRecordDestructuringExtractsCorrectValues() {
        var ctx = runAndGetContext("""
            let record = { a = 1, b = 2, c = 3 }
            let { a, b, c } = record
            let result = a + b + c
            """);
        assertEquals(1L, ctx.get("a"));
        assertEquals(2L, ctx.get("b"));
        assertEquals(3L, ctx.get("c"));
        assertEquals(6L, ctx.get("result"));
    }

    @Test
    void testRecordDestructuringWithRenamedBindings() {
        var ctx = runAndGetContext("""
            let point = { x = 100, y = 200 }
            let { x = myX, y = myY } = point
            let sum = myX + myY
            """);
        assertEquals(100L, ctx.get("myX"));
        assertEquals(200L, ctx.get("myY"));
        assertEquals(300L, ctx.get("sum"));
    }

    @Test
    void testNestedRecordDestructuring() {
        var ctx = runAndGetContext("""
            let outer = { inner = { value = 42 } }
            let { inner = { value } } = outer
            let result = value * 2
            """);
        assertEquals(42L, ctx.get("value"));
        assertEquals(84L, ctx.get("result"));
    }

    @Test
    void testDeeplyNestedDestructuring() {
        var ctx = runAndGetContext("""
            let deep = { a = { b = { c = 100 } } }
            let { a = { b = { c } } } = deep
            let result = c + 1
            """);
        assertEquals(100L, ctx.get("c"));
        assertEquals(101L, ctx.get("result"));
    }

    // ========== DESTRUCTURING WITH DIFFERENT TYPES ==========

    @Test
    void testDestructuringWithStringFields() {
        var ctx = runAndGetContext("""
            let person = { name = "Alice", city = "NYC" }
            let { name, city } = person
            """);
        assertEquals("Alice", ctx.get("name"));
        assertEquals("NYC", ctx.get("city"));
    }

    @Test
    void testDestructuringWithBooleanFields() {
        var ctx = runAndGetContext("""
            let flags = { active = True, verified = False }
            let { active, verified } = flags
            let bothTrue = if active then (if verified then True else False) else False
            """);
        assertEquals(true, ctx.get("active"));
        assertEquals(false, ctx.get("verified"));
        assertEquals(false, ctx.get("bothTrue"));
    }

    @Test
    void testDestructuringWithMixedTypes() {
        var ctx = runAndGetContext("""
            let data = { name = "Bob", age = 30, active = True }
            let { name, age, active } = data
            let isAdult = age > 18
            """);
        assertEquals("Bob", ctx.get("name"));
        assertEquals(30L, ctx.get("age"));
        assertEquals(true, ctx.get("active"));
        assertEquals(true, ctx.get("isAdult"));
    }

    // ========== DESTRUCTURING IN DO BLOCKS ==========

    @Test
    void testDestructuringInDoBlock() {
        var ctx = runAndGetContext("""
            let getPoint = {} -> { x = 5, y = 10 }

            let compute = {} -> do
                let point = getPoint {}
                let { x, y } = point
                x * y

            let result = compute {}
            """);
        assertEquals(50L, ctx.get("result"));
    }

    @Test
    void testMultipleDestructuringsInDoBlock() {
        var ctx = runAndGetContext("""
            let p1 = { x = 1, y = 2 }
            let p2 = { x = 10, y = 20 }

            let compute = {} -> do
                let { x = a, y = b } = p1
                let { x = c, y = d } = p2
                a + b + c + d

            let result = compute {}
            """);
        assertEquals(33L, ctx.get("result"));
    }

    // ========== DESTRUCTURING IN FUNCTION ARGUMENTS ==========

    @Test
    void testUsingDestructuredValuesInFunctionCalls() {
        var ctx = runAndGetContext("""
            let add = {a, b} -> a + b
            let point = { x = 3, y = 4 }
            let { x, y } = point
            let result = add { a = x, b = y }
            """);
        assertEquals(7L, ctx.get("result"));
    }

    @Test
    void testDestructuringInLambdaBody() {
        var ctx = runAndGetContext("""
            let processPoint = {point} -> do
                let { x, y } = point
                x * y

            let result = processPoint { point = { x = 6, y = 7 } }
            """);
        assertEquals(42L, ctx.get("result"));
    }

    // ========== PARTIAL DESTRUCTURING WITH ELLIPSIS ==========

    @Test
    void testPartialDestructuringWithEllipsis() {
        var ctx = runAndGetContext("""
            let record = { a = 1, b = 2, c = 3 }
            let { a, ... } = record
            let result = a * 10
            """);
        assertEquals(1L, ctx.get("a"));
        assertEquals(10L, ctx.get("result"));
    }

    @Test
    void testPartialDestructuringExtractsOnlyNamedFields() {
        var ctx = runAndGetContext("""
            let data = { name = "Test", value = 42, extra = 100 }
            let { name, value, ... } = data
            let result = value + 1
            """);
        assertEquals("Test", ctx.get("name"));
        assertEquals(42L, ctx.get("value"));
        assertEquals(43L, ctx.get("result"));
        assertFalse(ctx.containsKey("extra"));
    }

    // ========== COMPLEX SCENARIOS ==========

    @Test
    void testDestructuringResultUsedInConditionals() {
        var ctx = runAndGetContext("""
            let config = { threshold = 10, multiplier = 2 }
            let { threshold, multiplier } = config
            let value = 15
            let result = if value > threshold then value * multiplier else value
            """);
        assertEquals(30L, ctx.get("result"));
    }

    @Test
    void testChainedDestructuringOperations() {
        var ctx = runAndGetContext("""
            let data1 = { x = 5 }
            let { x } = data1
            let data2 = { y = x * 2 }
            let { y } = data2
            let result = y + x
            """);
        assertEquals(5L, ctx.get("x"));
        assertEquals(10L, ctx.get("y"));
        assertEquals(15L, ctx.get("result"));
    }
}
