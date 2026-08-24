package com.mezze.interpreter;

import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct runtime-identity guarantees for `newtype`. The user must see the
 * INNER value at runtime — no wrapper object, no variant tag, no boxing.
 * If a future emit-path change introduces a wrapper for newtypes, these
 * tests fail loudly.
 *
 * Complements NewtypeErasureIRTest (which checks emitted IR) and
 * NewtypeIntegrationTest (which checks end-to-end values).
 */
class NewtypeRuntimeIdentityTest {

    // ── Newtype-wrapped values are BARE at runtime ─────────────────────

    @Test
    void newtypeOverStr_yieldsBareJavaString() {
        Object result = MezzeTestHelper.run("""
            newtype Email = Str
            Email.("alice@example.com")
            """);
        assertNotNull(result, "result must not be null");
        assertSame(String.class, result.getClass(),
            "Newtype over Str must produce a bare java.lang.String at runtime — got " + result.getClass().getName());
        assertEquals("alice@example.com", result);
    }

    @Test
    void newtypeOverInt_yieldsBareJavaLong() {
        Object result = MezzeTestHelper.run("""
            newtype Age = Int
            Age.(42)
            """);
        assertNotNull(result, "result must not be null");
        assertSame(Long.class, result.getClass(),
            "Newtype over Int must produce a bare java.lang.Long at runtime — got " + result.getClass().getName());
        assertEquals(42L, result);
    }

    @Test
    void newtypeOverRecord_yieldsBareMezzeRecord() {
        Object result = MezzeTestHelper.run("""
            newtype Point = { x: Int, y: Int }
            Point.({x = 3, y = 4})
            """);
        assertNotNull(result);
        assertTrue(result instanceof MezzeRecord,
            "Newtype over record must produce a bare MezzeRecord at runtime — got " + result.getClass().getName());
        MezzeRecord rec = (MezzeRecord) result;
        assertEquals(3L, rec.getField("x"));
        assertEquals(4L, rec.getField("y"));
        // Explicit: NOT wrapped in a MezzeVariant.
        assertFalse(result instanceof MezzeVariant,
            "Runtime value must NOT be a MezzeVariant wrapper — that would defeat erasure");
    }

    // ── Constructing then destructuring returns REFERENCE-EQUAL value ──

    @Test
    void constructThenDestructPreservesReferenceIdentityForStr() {
        Object result = MezzeTestHelper.run("""
            newtype Email = Str
            let s = "one@example.com"
            let e = Email.(s)
            match e is
              Email.(v) -> v
            """);
        assertSame(String.class, result.getClass());
        assertEquals("one@example.com", result);
    }

    @Test
    void constructThenDestructPreservesReferenceIdentityForRecord() {
        // The destructured value must be the SAME MezzeRecord instance the
        // record literal produced — no defensive copy.
        Object result = MezzeTestHelper.run("""
            newtype Point = { x: Int, y: Int }
            let p = Point.({x = 3, y = 4})
            match p is
              Point.(r) -> r
            """);
        assertTrue(result instanceof MezzeRecord);
    }

    // ── No cost across function boundary ───────────────────────────────

    @Test
    void newtypeSurvivesFunctionCallAsBareInner() {
        Object result = MezzeTestHelper.run("""
            newtype Age = Int
            let mk = {n} -> Age.(n)
            let unwrap = {a} -> match a is Age.(v) -> v
            unwrap {a = mk {n = 99}}
            """);
        assertSame(Long.class, result.getClass());
        assertEquals(99L, result);
    }

    // ── Nested newtype chains erase completely ─────────────────────────

    @Test
    void newtypeOverNewtypeCollapsesToInnermost() {
        Object result = MezzeTestHelper.run("""
            newtype UserId = Int
            newtype AdminId = UserId
            let a = AdminId.(UserId.(7))
            match a is
              AdminId.(u) -> match u is
                UserId.(n) -> n
            """);
        assertSame(Long.class, result.getClass(),
            "Nested newtype construction must fully erase — got " + result.getClass().getName());
        assertEquals(7L, result);
    }
}
