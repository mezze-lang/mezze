package com.mezze.interpreter;

import com.mezze.interpreter.runtime.MezzeLanguage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration tests for destructuring with renaming.
 *
 * Tests verify that renaming in destructuring patterns works correctly
 * at runtime, including:
 * 1. Single field renaming in let bindings
 * 2. Multiple field renaming in let bindings
 * 3. Renaming in match statement patterns
 * 4. Renaming in match guards
 * 5. Nested destructuring with renaming
 * 6. Variant pattern renaming
 *
 * Run with: ./gradlew :interpreter:test --tests "*DestructuringRenameIntegrationTest*"
 */
class DestructuringRenameIntegrationTest {
    private Map<String, Object> runAndGetContext(String source) {
        MezzeTestHelper.run(source);
        return MezzeLanguage.getLastExecutionContext();
    }

    // ==================== SINGLE FIELD RENAMING ====================

    @Nested
    class SingleFieldRenaming {

        @Test
        void testRenameSingleField() {
            var ctx = runAndGetContext("""
                let point = { x = 100, y = 200 }
                let { x = myX, y } = point
                let result = myX + y
                """);
            assertEquals(100L, ctx.get("myX"));
            assertEquals(200L, ctx.get("y"));
            assertEquals(300L, ctx.get("result"));
        }

        @Test
        void testRenamePreservesIntType() {
            var ctx = runAndGetContext("""
                let data = { value = 42 }
                let { value = v } = data
                let doubled = v * 2
                """);
            assertEquals(42L, ctx.get("v"));
            assertEquals(84L, ctx.get("doubled"));
        }

        @Test
        void testRenamePreservesStringType() {
            var ctx = runAndGetContext("""
                let data = { name = "Alice" }
                let { name = personName } = data
                """);
            assertEquals("Alice", ctx.get("personName"));
        }

        @Test
        void testRenamePreservesBoolType() {
            var ctx = runAndGetContext("""
                let config = { enabled = True }
                let { enabled = isEnabled } = config
                let result = if isEnabled then 1 else 0
                """);
            assertEquals(true, ctx.get("isEnabled"));
            assertEquals(1L, ctx.get("result"));
        }
    }

    // ==================== MULTIPLE FIELD RENAMING ====================

    @Nested
    class MultipleFieldRenaming {

        @Test
        void testRenameAllFields() {
            var ctx = runAndGetContext("""
                let point = { x = 1, y = 2, z = 3 }
                let { x = a, y = b, z = c } = point
                let sum = a + b + c
                """);
            assertEquals(1L, ctx.get("a"));
            assertEquals(2L, ctx.get("b"));
            assertEquals(3L, ctx.get("c"));
            assertEquals(6L, ctx.get("sum"));
        }

        @Test
        void testMixRenamedAndNonRenamed() {
            var ctx = runAndGetContext("""
                let data = { first = 10, second = 20, third = 30 }
                let { first, second = middle, third } = data
                let sum = first + middle + third
                """);
            assertEquals(10L, ctx.get("first"));
            assertEquals(20L, ctx.get("middle"));
            assertEquals(30L, ctx.get("third"));
            assertEquals(60L, ctx.get("sum"));
        }

        @Test
        void testRenameMixedTypes() {
            var ctx = runAndGetContext("""
                let person = { name = "Bob", age = 25, active = True }
                let { name = n, age = years, active = isActive } = person
                let canVote = years >= 18
                """);
            assertEquals("Bob", ctx.get("n"));
            assertEquals(25L, ctx.get("years"));
            assertEquals(true, ctx.get("isActive"));
            assertEquals(true, ctx.get("canVote"));
        }

        @Test
        void testRenameWithEllipsis() {
            var ctx = runAndGetContext("""
                let data = { a = 1, b = 2, c = 3, d = 4 }
                let { a = first, b = second, ... } = data
                let sum = first + second
                """);
            assertEquals(1L, ctx.get("first"));
            assertEquals(2L, ctx.get("second"));
            assertEquals(3L, ctx.get("sum"));
            assertFalse(ctx.containsKey("c"));
            assertFalse(ctx.containsKey("d"));
        }
    }

    // ==================== MATCH PATTERN RENAMING ====================

    @Nested
    class MatchPatternRenaming {

        @Test
        void testRenameInSimpleMatch() {
            Object result = MezzeTestHelper.run("""
                let process = {data} ->
                    match data is
                      { value = v } -> v * 2
                process { data = { value = 21 } }
                """);
            assertEquals(42L, result);
        }

        @Test
        void testRenameMultipleFieldsInMatch() {
            Object result = MezzeTestHelper.run("""
                let swap = {point} ->
                    match point is
                      { x = a, y = b } -> { x = b, y = a }
                let swapped = swap { point = { x = 1, y = 2 } }
                swapped.x
                """);
            assertEquals(2L, result);
        }

        @Test
        void testDifferentRenamesInDifferentBranches() {
            Object result = MezzeTestHelper.run("""
                let describe = {data} ->
                    match data is
                      { status = 200, body = content } -> content
                      { status = code, ... } -> "error"
                describe { data = { status = 200, body = "success" } }
                """);
            assertEquals("success", result);
        }

        @Test
        void testRenameInPatternWithLiteralField() {
            Object result = MezzeTestHelper.run("""
                let handle = {response} ->
                    match response is
                      { status = 200, data = d } -> d
                      { status = code, data = d } -> 0
                handle { response = { status = 200, data = 42 } }
                """);
            assertEquals(42L, result);
        }

        @Test
        void testRenameMatchMultipleCases() {
            Object result = MezzeTestHelper.run("""
                let categorize = {item} ->
                    match item is
                      { category = "food", price = p } -> p * 2
                      { category = "drink", price = cost } -> cost
                      { price = amount, ... } -> amount
                categorize { item = { category = "food", price = 10 } }
                """);
            assertEquals(20L, result);
        }
    }

    // ==================== MATCH GUARD RENAMING ====================

    @Nested
    class MatchGuardRenaming {

        @Test
        void testRenamedVariableInGuard() {
            Object result = MezzeTestHelper.run("""
                let classify = {person} ->
                    match person is
                      { age = years } if years >= 18 -> "adult"
                      { age = years } -> "minor"
                classify { person = { age = 25 } }
                """);
            assertEquals("adult", result);
        }

        @Test
        void testMultipleRenamedVariablesInGuard() {
            Object result = MezzeTestHelper.run("""
                let isValidRect = {rect} ->
                    match rect is
                      { width = w, height = h } if w > 0 && h > 0 -> True
                      { width = w, height = h } -> False
                isValidRect { rect = { width = 10, height = 20 } }
                """);
            assertEquals(true, result);
        }

        @Test
        void testGuardComparingRenamedFields() {
            Object result = MezzeTestHelper.run("""
                let isSquare = {rect} ->
                    match rect is
                      { width = w, height = h } if w == h -> True
                      _ -> False
                isSquare { rect = { width = 5, height = 5 } }
                """);
            assertEquals(true, result);
        }

        @Test
        void testGuardWithArithmeticOnRenamedFields() {
            Object result = MezzeTestHelper.run("""
                let isPythagorean = {triple} ->
                    match triple is
                      { a = x, b = y, c = z } if x*x + y*y == z*z -> True
                      _ -> False
                isPythagorean { triple = { a = 3, b = 4, c = 5 } }
                """);
            assertEquals(true, result);
        }

        @Test
        void testCascadingGuardsWithSameRename() {
            Object result = MezzeTestHelper.run("""
                let classifyAge = {person} ->
                    match person is
                      { age = years } if years >= 65 -> "senior"
                      { age = years } if years >= 18 -> "adult"
                      { age = years } if years >= 13 -> "teen"
                      { age = years } -> "child"
                classifyAge { person = { age = 70 } }
                """);
            assertEquals("senior", result);
        }

        @Test
        void testGuardNotMatching() {
            Object result = MezzeTestHelper.run("""
                let classifyAge = {person} ->
                    match person is
                      { age = years } if years >= 18 -> "adult"
                      { age = years } -> "minor"
                classifyAge { person = { age = 10 } }
                """);
            assertEquals("minor", result);
        }
    }

    // ==================== NESTED DESTRUCTURING WITH RENAMING ====================

    @Nested
    class NestedDestructuringRenaming {

        @Test
        void testRenameInNestedDestructuring() {
            var ctx = runAndGetContext("""
                let outer = { inner = { value = 42, extra = 10 } }
                let { inner = { value = v, extra = e } } = outer
                let sum = v + e
                """);
            assertEquals(42L, ctx.get("v"));
            assertEquals(10L, ctx.get("e"));
            assertEquals(52L, ctx.get("sum"));
        }

        @Test
        void testDeeplyNestedRename() {
            var ctx = runAndGetContext("""
                let data = { level1 = { level2 = { level3 = 100 } } }
                let { level1 = { level2 = { level3 = deepValue } } } = data
                let result = deepValue * 2
                """);
            assertEquals(100L, ctx.get("deepValue"));
            assertEquals(200L, ctx.get("result"));
        }

        @Test
        void testMixedRenameAndNonRenameNested() {
            var ctx = runAndGetContext("""
                let data = { point = { x = 1, y = 2 }, name = "origin" }
                let { point = { x = px, y }, name } = data
                let sum = px + y
                """);
            assertEquals(1L, ctx.get("px"));
            assertEquals(2L, ctx.get("y"));
            assertEquals("origin", ctx.get("name"));
            assertEquals(3L, ctx.get("sum"));
        }

        @Test
        void testNestedRenameInMatch() {
            Object result = MezzeTestHelper.run("""
                let process = {data} ->
                    match data is
                      { config = { port = p, host = h } } -> p
                process { data = { config = { port = 3000, host = "localhost" } } }
                """);
            assertEquals(3000L, result);
        }
    }

    // ==================== VARIANT PATTERN RENAMING ====================

    @Nested
    class VariantPatternRenaming {

        @Test
        void testRenameVariantPayloadField() {
            Object result = MezzeTestHelper.run("""
                let unwrap = {opt} ->
                    match opt is
                      Some { val = v } -> v
                      None -> 0
                unwrap { opt = Some { val = 42 } }
                """);
            assertEquals(42L, result);
        }

        @Test
        void testRenameMultipleVariantPayloadFields() {
            Object result = MezzeTestHelper.run("""
                let process = {result} ->
                    match result is
                      Ok { val = value } -> value
                      Err { error = msg } -> 0
                process { result = Ok { val = 100 } }
                """);
            assertEquals(100L, result);
        }

        @Test
        void testVariantWithRecordPayloadAndRenaming() {
            Object result = MezzeTestHelper.run("""
                let getX = {opt} ->
                    match opt is
                      Some { val = { x = px, y = py } } -> px
                      None -> 0
                getX { opt = Some { val = { x = 10, y = 20 } } }
                """);
            assertEquals(10L, result);
        }

        @Test
        void testVariantRenameWithGuard() {
            Object result = MezzeTestHelper.run("""
                let getIfPositive = {opt} ->
                    match opt is
                      Some { val = v } if v > 0 -> v
                      Some { val = v } -> 0
                      None -> 0
                getIfPositive { opt = Some { val = 42 } }
                """);
            assertEquals(42L, result);
        }

        @Test
        void testVariantRenameWithGuardNotMatching() {
            Object result = MezzeTestHelper.run("""
                let getIfPositive = {opt} ->
                    match opt is
                      Some { val = v } if v > 0 -> v
                      Some { val = v } -> 0
                      None -> 0
                getIfPositive { opt = Some { val = -5 } }
                """);
            assertEquals(0L, result);
        }

        @Test
        void testVariantMultiplePayloadFieldsRenamed() {
            Object result = MezzeTestHelper.run("""
                let isValidRange = {range} ->
                    match range is
                      Range { min = lo, max = hi } if lo < hi -> True
                      Range { min = lo, max = hi } -> False
                      Empty -> False
                isValidRange { range = Range { min = 1, max = 10 } }
                """);
            assertEquals(true, result);
        }
    }

    // ==================== DO BLOCK RENAMING ====================

    @Nested
    class DoBlockRenaming {

        @Test
        void testRenameInDoBlockLetBinding() {
            var ctx = runAndGetContext("""
                let compute = {} -> do
                    let point = { x = 5, y = 10 }
                    let { x = a, y = b } = point
                    a * b
                let result = compute {}
                """);
            assertEquals(50L, ctx.get("result"));
        }

        @Test
        void testMultipleRenamesInDoBlock() {
            var ctx = runAndGetContext("""
                let process = {} -> do
                    let p1 = { x = 1, y = 2 }
                    let p2 = { x = 10, y = 20 }
                    let { x = x1, y = y1 } = p1
                    let { x = x2, y = y2 } = p2
                    x1 + y1 + x2 + y2
                let result = process {}
                """);
            assertEquals(33L, ctx.get("result"));
        }

        @Test
        void testRenameUsedInSubsequentExpression() {
            var ctx = runAndGetContext("""
                let transform = {} -> do
                    let data = { factor = 2, base = 100 }
                    let { factor = multiplier, base = start } = data
                    start * multiplier
                let result = transform {}
                """);
            assertEquals(200L, ctx.get("result"));
        }
    }

    // ==================== COMPLEX SCENARIOS ====================

    @Nested
    class ComplexScenarios {

        @Test
        void testRenameInFunctionParameterDestructuringBody() {
            var ctx = runAndGetContext("""
                let processPoint = {point} -> do
                    let { x = px, y = py } = point
                    px * py
                let result = processPoint { point = { x = 6, y = 7 } }
                """);
            assertEquals(42L, ctx.get("result"));
        }

        @Test
        void testChainedOperationsWithRenamedValues() {
            var ctx = runAndGetContext("""
                let step1 = { value = 10 }
                let { value = v1 } = step1
                let step2 = { value = v1 * 2 }
                let { value = v2 } = step2
                let result = v1 + v2
                """);
            assertEquals(10L, ctx.get("v1"));
            assertEquals(20L, ctx.get("v2"));
            assertEquals(30L, ctx.get("result"));
        }

        @Test
        void testRenameWithRecordSpreadUpdate() {
            var ctx = runAndGetContext("""
                let original = { x = 1, y = 2, z = 3 }
                let { x = origX, y = origY, z = origZ } = original
                let updated = { ...original, x = origX * 2 }
                let { x = newX, ... } = updated
                let result = newX
                """);
            assertEquals(1L, ctx.get("origX"));
            assertEquals(2L, ctx.get("newX"));
            assertEquals(2L, ctx.get("result"));
        }

        @Test
        void testVariantMatchWithNestedRecordRenameAndGuard() {
            Object result = MezzeTestHelper.run("""
                let handleResponse = {response} ->
                    match response is
                      Ok { val = { status = code, data = d } } if code == 200 -> d
                      Ok { val = { status = code, data = d } } -> 0
                      Err { error = msg } -> 0
                handleResponse { response = Ok { val = { status = 200, data = 42 } } }
                """);
            assertEquals(42L, result);
        }

        @Test
        void testVariantMatchWithNestedRecordGuardNotMatching() {
            Object result = MezzeTestHelper.run("""
                let handleResponse = {response} ->
                    match response is
                      Ok { val = { status = code, data = d } } if code == 200 -> d
                      Ok { val = { status = code, data = d } } -> 0
                      Err { error = msg } -> 0
                handleResponse { response = Ok { val = { status = 404, data = 42 } } }
                """);
            assertEquals(0L, result);
        }
    }

    // ==================== EDGE CASES ====================

    @Nested
    class EdgeCases {

        @Test
        void testRenameToSameNameAsField() {
            // { x = x } should work - explicit binding to same name
            var ctx = runAndGetContext("""
                let point = { x = 10, y = 20 }
                let { x = x, y = y } = point
                let sum = x + y
                """);
            assertEquals(10L, ctx.get("x"));
            assertEquals(20L, ctx.get("y"));
            assertEquals(30L, ctx.get("sum"));
        }

        @Test
        void testRenameSingleFieldRecord() {
            var ctx = runAndGetContext("""
                let data = { only = 42 }
                let { only = theValue } = data
                let result = theValue * 2
                """);
            assertEquals(42L, ctx.get("theValue"));
            assertEquals(84L, ctx.get("result"));
        }

        @Test
        void testRenameWithLongIdentifierNames() {
            var ctx = runAndGetContext("""
                let data = { firstName = "Alice", lastName = "Smith" }
                let { firstName = thePersonsFirstName, lastName = thePersonsLastName } = data
                """);
            assertEquals("Alice", ctx.get("thePersonsFirstName"));
            assertEquals("Smith", ctx.get("thePersonsLastName"));
        }

        @Test
        void testRenamedValueInArithmetic() {
            var ctx = runAndGetContext("""
                let data = { a = 5, b = 3 }
                let { a = x, b = y } = data
                let sum = x + y
                let diff = x - y
                let prod = x * y
                """);
            assertEquals(8L, ctx.get("sum"));
            assertEquals(2L, ctx.get("diff"));
            assertEquals(15L, ctx.get("prod"));
        }

        @Test
        void testRenamedValueInComparison() {
            var ctx = runAndGetContext("""
                let data = { value = 10 }
                let { value = v } = data
                let isPositive = v > 0
                let isLarge = v >= 10
                let isSmall = v < 5
                """);
            assertEquals(true, ctx.get("isPositive"));
            assertEquals(true, ctx.get("isLarge"));
            assertEquals(false, ctx.get("isSmall"));
        }
    }
}
