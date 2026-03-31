package com.mezze.interpreter.v2;

import com.mezze.interpreter.v2.runtime.MezzeLanguage;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.interpreter.v2.values.MezzeVariant;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration tests for Record and Variant operations.
 *
 * Tests verify that optimizations work correctly end-to-end:
 * - Record CoW (Copy-on-Write) with structural sharing
 * - Record typed storage preservation
 * - Variant single-value specialization
 * - Pattern matching optimizations
 *
 * Run with: ./gradlew :interpreter:test --tests "*RecordVariantIntegrationTest*"
 */
class RecordVariantIntegrationTest {
    private Map<String, Object> runAndGetContext(String source) {
        MezzeTestHelper.run(source);
        return MezzeLanguage.getLastExecutionContext();
    }

    // ==================== RECORD CREATION ====================

    @Nested
    class RecordCreation {

        @Test
        void testEmptyRecord() {
            Object result = MezzeTestHelper.run("{}");
            assertInstanceOf(MezzeRecord.class, result);
            MezzeRecord rec = (MezzeRecord) result;
            assertEquals(0, rec.getFieldCount());
        }

        @Test
        void testSingleFieldRecord() {
            Object result = MezzeTestHelper.run("{ x = 42 }");
            assertInstanceOf(MezzeRecord.class, result);
            MezzeRecord rec = (MezzeRecord) result;
            assertEquals(1, rec.getFieldCount());
            assertEquals(42L, rec.getField("x"));
        }

        @Test
        void testMultiFieldRecord() {
            Object result = MezzeTestHelper.run("{ a = 1, b = 2, c = 3 }");
            assertInstanceOf(MezzeRecord.class, result);
            MezzeRecord rec = (MezzeRecord) result;
            assertEquals(3, rec.getFieldCount());
            assertEquals(1L, rec.getField("a"));
            assertEquals(2L, rec.getField("b"));
            assertEquals(3L, rec.getField("c"));
        }

        @Test
        void testRecordWithMixedTypes() {
            var ctx = runAndGetContext("""
                let rec = { name = "test", count = 42, active = True }
                let name = rec.name
                let count = rec.count
                let active = rec.active
                """);
            assertEquals("test", ctx.get("name"));
            assertEquals(42L, ctx.get("count"));
            assertEquals(true, ctx.get("active"));
        }

        @Test
        void testNestedRecord() {
            var ctx = runAndGetContext("""
                let outer = { inner = { value = 100 } }
                let result = outer.inner.value
                """);
            assertEquals(100L, ctx.get("result"));
        }
    }

    // ==================== RECORD UPDATES ====================

    @Nested
    class RecordUpdates {

        @Test
        void testSingleFieldUpdate() {
            var ctx = runAndGetContext("""
                let rec = { x = 1, y = 2 }
                let updated = { ...rec, x = 10 }
                let origX = rec.x
                let newX = updated.x
                """);
            assertEquals(1L, ctx.get("origX"), "Original should be unchanged");
            assertEquals(10L, ctx.get("newX"), "Updated should have new value");
        }

        @Test
        void testMultiFieldUpdate() {
            var ctx = runAndGetContext("""
                let rec = { a = 1, b = 2, c = 3 }
                let updated = { ...rec, a = 10, c = 30 }
                let origA = rec.a
                let origB = rec.b
                let origC = rec.c
                let newA = updated.a
                let newB = updated.b
                let newC = updated.c
                """);
            // Original unchanged
            assertEquals(1L, ctx.get("origA"));
            assertEquals(2L, ctx.get("origB"));
            assertEquals(3L, ctx.get("origC"));
            // Updated fields
            assertEquals(10L, ctx.get("newA"));
            assertEquals(2L, ctx.get("newB"), "Unchanged field should be preserved");
            assertEquals(30L, ctx.get("newC"));
        }

        @Test
        void testChainedUpdates() {
            var ctx = runAndGetContext("""
                let rec = { x = 0, y = 0, z = 0 }
                let r1 = { ...rec, x = 1 }
                let r2 = { ...r1, y = 2 }
                let r3 = { ...r2, z = 3 }
                let origX = rec.x
                let finalX = r3.x
                let finalY = r3.y
                let finalZ = r3.z
                """);
            assertEquals(0L, ctx.get("origX"), "Original should be unchanged");
            assertEquals(1L, ctx.get("finalX"));
            assertEquals(2L, ctx.get("finalY"));
            assertEquals(3L, ctx.get("finalZ"));
        }

        @Test
        void testNestedRecordUpdate() {
            var ctx = runAndGetContext("""
                let rec = { point = { x = 1, y = 2 }, name = "p1" }
                let updated = { ...rec, point = { ...rec.point, x = 10 } }
                let origX = rec.point.x
                let newX = updated.point.x
                let newY = updated.point.y
                """);
            assertEquals(1L, ctx.get("origX"), "Original nested field unchanged");
            assertEquals(10L, ctx.get("newX"));
            assertEquals(2L, ctx.get("newY"), "Unchanged nested field preserved");
        }
    }

    // ==================== RECORD FIELD ACCESS ====================

    @Nested
    class RecordFieldAccess {

        @Test
        void testDotNotation() {
            var ctx = runAndGetContext("""
                let rec = { foo = 42 }
                let result = rec.foo
                """);
            assertEquals(42L, ctx.get("result"));
        }

        @Test
        void testChainedDotNotation() {
            var ctx = runAndGetContext("""
                let rec = { a = { b = { c = 100 } } }
                let result = rec.a.b.c
                """);
            assertEquals(100L, ctx.get("result"));
        }

        @Test
        void testFieldAccessInExpression() {
            var ctx = runAndGetContext("""
                let p1 = { x = 3, y = 4 }
                let p2 = { x = 1, y = 2 }
                let result = p1.x * p2.y + p1.y * p2.x
                """);
            assertEquals(10L, ctx.get("result"));
        }
    }

    // ==================== VARIANT CREATION ====================

    @Nested
    class VariantCreation {

        @Test
        void testVariantNoPayload() {
            Object result = MezzeTestHelper.run("""
                use std::option
                let opt: (Option Int) = None
                opt
                """);
            assertInstanceOf(MezzeVariant.class, result);
            MezzeVariant v = (MezzeVariant) result;
            assertEquals("None", v.getConstructorName());
            assertFalse(v.hasPayload());
        }

        @Test
        void testVariantWithPayload() {
            Object result = MezzeTestHelper.run("""
                use std::option
                let opt: (Option Int) = Some { val = 42 }
                opt
                """);
            assertInstanceOf(MezzeVariant.class, result);
            MezzeVariant v = (MezzeVariant) result;
            assertEquals("Some", v.getConstructorName());
            assertTrue(v.hasPayload());
        }

        @Test
        void testResultOk() {
            Object result = MezzeTestHelper.run("""
                use std::result
                let res: (Result Str Str) = Ok { val = "success" }
                res
                """);
            assertInstanceOf(MezzeVariant.class, result);
            MezzeVariant v = (MezzeVariant) result;
            assertEquals("Ok", v.getConstructorName());
        }

        @Test
        void testResultErr() {
            Object result = MezzeTestHelper.run("""
                use std::result
                let res: (Result Str Str) = Err { error = "failed" }
                res
                """);
            assertInstanceOf(MezzeVariant.class, result);
            MezzeVariant v = (MezzeVariant) result;
            assertEquals("Err", v.getConstructorName());
        }
    }

    // ==================== PATTERN MATCHING (using stdlib) ====================

    @Nested
    class PatternMatching {

        @Test
        void testMatchNone() {
            Object result = MezzeTestHelper.run("""
                use std::option
                let opt: (Option Int) = None
                option::fold {
                    opt = opt,
                    on_none = {} -> 0,
                    on_some = { val } -> val
                }
                """);
            assertEquals(0L, result);
        }

        @Test
        void testMatchSome() {
            Object result = MezzeTestHelper.run("""
                use std::option
                let opt: (Option Int) = Some { val = 42 }
                option::fold {
                    opt = opt,
                    on_none = {} -> 0,
                    on_some = { val } -> val
                }
                """);
            assertEquals(42L, result);
        }

        @Test
        void testMatchResult() {
            Object result = MezzeTestHelper.run("""
                use std::result
                let okResult: (Result Int Str) = Ok { val = 100 }
                result::fold {
                    result = okResult,
                    on_ok = { val } -> val,
                    on_err = { error } -> 0
                }
                """);
            assertEquals(100L, result);
        }
    }

    // ==================== DESTRUCTURING WITH RENAMING ====================

    @Nested
    class DestructuringWithRenaming {

        @Test
        void testRecordDestructuringWithRename() {
            var ctx = runAndGetContext("""
                let point = { x = 100, y = 200 }
                let { x = a, y = b } = point
                let sum = a + b
                """);
            assertEquals(100L, ctx.get("a"));
            assertEquals(200L, ctx.get("b"));
            assertEquals(300L, ctx.get("sum"));
        }

        @Test
        void testMultipleRenames() {
            var ctx = runAndGetContext("""
                let data = { firstName = "Alice", lastName = "Smith", age = 30 }
                let { firstName = first, lastName = last, age = years } = data
                """);
            assertEquals("Alice", ctx.get("first"));
            assertEquals("Smith", ctx.get("last"));
            assertEquals(30L, ctx.get("years"));
        }

        @Test
        void testPartialRename() {
            var ctx = runAndGetContext("""
                let rec = { a = 1, b = 2, c = 3 }
                let { a, b = renamed, c } = rec
                let sum = a + renamed + c
                """);
            assertEquals(1L, ctx.get("a"));
            assertEquals(2L, ctx.get("renamed"));
            assertEquals(3L, ctx.get("c"));
            assertEquals(6L, ctx.get("sum"));
        }

        @Test
        void testNestedDestructuringWithRename() {
            var ctx = runAndGetContext("""
                let outer = { inner = { value = 42, extra = 10 } }
                let { inner = { value = v, extra = e } } = outer
                let sum = v + e
                """);
            assertEquals(42L, ctx.get("v"));
            assertEquals(10L, ctx.get("e"));
            assertEquals(52L, ctx.get("sum"));
        }
    }

    // ==================== COMPLEX SCENARIOS ====================

    @Nested
    class ComplexScenarios {

        @Test
        void testRecordInFunction() {
            var ctx = runAndGetContext("""
                let makePoint = {x: Int, y: Int} -> { x = x, y = y }
                let movePoint = {point: { x: Int, y: Int }, dx: Int, dy: Int} -> { ...point, x = point.x + dx, y = point.y + dy }

                let p1 = makePoint { x = 0, y = 0 }
                let p2 = movePoint { point = p1, dx = 5, dy = 10 }
                let x = p2.x
                let y = p2.y
                """);
            assertEquals(5L, ctx.get("x"));
            assertEquals(10L, ctx.get("y"));
        }

        @Test
        void testManyUpdates() {
            var ctx = runAndGetContext("""
                let rec = { a = 0, b = 0, c = 0, d = 0, e = 0 }
                let r1 = { ...rec, a = 1 }
                let r2 = { ...r1, b = 2 }
                let r3 = { ...r2, c = 3 }
                let r4 = { ...r3, d = 4 }
                let r5 = { ...r4, e = 5 }
                let sum = r5.a + r5.b + r5.c + r5.d + r5.e
                """);
            assertEquals(15L, ctx.get("sum"));
        }

        @Test
        void testRecordEquality() {
            var ctx = runAndGetContext("""
                let r1 = { x = 1, y = 2 }
                let r2 = { x = 1, y = 2 }
                let r3 = { x = 1, y = 3 }
                let eq1 = r1 == r2
                let eq2 = r1 == r3
                """);
            assertEquals(true, ctx.get("eq1"));
            assertEquals(false, ctx.get("eq2"));
        }
    }
}
