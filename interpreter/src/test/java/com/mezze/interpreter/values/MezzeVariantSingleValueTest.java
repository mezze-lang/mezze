package com.mezze.interpreter.values;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MezzeVariant single-value storage optimization.
 *
 * The optimization stores single-payload variants (Some, Ok, Err, etc.)
 * using a direct field instead of allocating an Object[] array.
 *
 * Run with: ./gradlew :interpreter:test --tests "*MezzeVariantSingleValueTest*"
 */
public class MezzeVariantSingleValueTest {

    @Test
    void testNoPayloadUsesEmptyArray() {
        MezzeVariant.TagRegistry registry = new MezzeVariant.TagRegistry(new String[]{"None"});
        MezzeVariant none = MezzeVariant.noPayload(registry, 0);

        assertEquals(0, none.getPayloadLength());
        assertFalse(none.hasPayload());
        assertNull(none.getPayloadValue());
        assertFalse(none.usesSingleValueStorage());
    }

    @Test
    void testSinglePayloadUsesSingleValueStorage() {
        MezzeVariant.TagRegistry registry = new MezzeVariant.TagRegistry(new String[]{"Some"});
        MezzeVariant some = MezzeVariant.singlePayload(registry, 0, 42L);

        assertEquals(1, some.getPayloadLength());
        assertTrue(some.hasPayload());
        assertEquals(42L, some.getPayloadValue());
        assertEquals(42L, some.getPayloadField(0));
        assertTrue(some.usesSingleValueStorage(), "Single payload should use optimized storage");
    }

    @Test
    void testSinglePayloadWithNullValue() {
        MezzeVariant.TagRegistry registry = new MezzeVariant.TagRegistry(new String[]{"Some"});
        MezzeVariant some = MezzeVariant.singlePayload(registry, 0, null);

        assertEquals(1, some.getPayloadLength());
        assertTrue(some.hasPayload());
        assertNull(some.getPayloadValue());
        assertNull(some.getPayloadField(0));
        assertTrue(some.usesSingleValueStorage(), "Null payload should still use optimized storage");
    }

    @Test
    void testMultiPayloadUsesArray() {
        MezzeVariant.TagRegistry registry = new MezzeVariant.TagRegistry(new String[]{"Tuple2"});
        MezzeVariant tuple = MezzeVariant.multiPayload(registry, 0, new Object[]{1L, 2L});

        assertEquals(2, tuple.getPayloadLength());
        assertTrue(tuple.hasPayload());
        assertEquals(1L, tuple.getPayloadValue());  // First value
        assertEquals(1L, tuple.getPayloadField(0));
        assertEquals(2L, tuple.getPayloadField(1));
        assertFalse(tuple.usesSingleValueStorage(), "Multi-payload should not use single-value storage");
    }

    @Test
    void testMultiPayloadReturnsArray() {
        MezzeVariant.TagRegistry registry = new MezzeVariant.TagRegistry(new String[]{"Tuple3"});
        Object[] values = {1L, 2L, 3L};
        MezzeVariant tuple = MezzeVariant.multiPayload(registry, 0, values);

        Object[] payload = tuple.getPayload();
        assertEquals(3, payload.length);
        assertArrayEquals(values, payload);
    }

    @Test
    void testSinglePayloadGetPayloadCreatesArray() {
        MezzeVariant.TagRegistry registry = new MezzeVariant.TagRegistry(new String[]{"Some"});
        MezzeVariant some = MezzeVariant.singlePayload(registry, 0, "hello");

        // getPayload() must return an array for compatibility
        Object[] payload = some.getPayload();
        assertEquals(1, payload.length);
        assertEquals("hello", payload[0]);

        // But each call creates a new array
        Object[] payload2 = some.getPayload();
        assertNotSame(payload, payload2, "getPayload() should create new array each time for single-value");
    }

    @Test
    void testSomeHelperUsesSingleValueStorage() {
        MezzeVariant some = MezzeVariant.some("test value");

        assertTrue(some.isSome());
        assertEquals(1, some.getPayloadLength());
        assertTrue(some.usesSingleValueStorage());
        // Payload is a MezzeRecord with {val = "test value"}
        Object payload = some.getPayloadValue();
        assertInstanceOf(MezzeRecord.class, payload);
    }

    @Test
    void testNoneCachedSingleton() {
        MezzeVariant none1 = MezzeVariant.none();
        MezzeVariant none2 = MezzeVariant.none();

        assertSame(none1, none2, "None should be cached singleton");
        assertTrue(none1.isNone());
        assertFalse(none1.usesSingleValueStorage());
    }

    @Test
    void testOkHelperUsesSingleValueStorage() {
        MezzeVariant ok = MezzeVariant.ok(100L);

        assertEquals("Ok", ok.getConstructorName());
        assertEquals(1, ok.getPayloadLength());
        assertTrue(ok.usesSingleValueStorage());
    }

    @Test
    void testErrHelperUsesSingleValueStorage() {
        MezzeVariant err = MezzeVariant.err("error message");

        assertEquals("Err", err.getConstructorName());
        assertEquals(1, err.getPayloadLength());
        assertTrue(err.usesSingleValueStorage());
    }

    @Test
    void testEqualsWithSingleValueStorage() {
        MezzeVariant.TagRegistry registry = new MezzeVariant.TagRegistry(new String[]{"Some"});
        MezzeVariant some1 = MezzeVariant.singlePayload(registry, 0, "hello");
        MezzeVariant some2 = MezzeVariant.singlePayload(registry, 0, "hello");
        MezzeVariant some3 = MezzeVariant.singlePayload(registry, 0, "world");

        assertEquals(some1, some2);
        assertNotEquals(some1, some3);
    }

    @Test
    void testHashCodeWithSingleValueStorage() {
        MezzeVariant.TagRegistry registry = new MezzeVariant.TagRegistry(new String[]{"Some"});
        MezzeVariant some1 = MezzeVariant.singlePayload(registry, 0, "hello");
        MezzeVariant some2 = MezzeVariant.singlePayload(registry, 0, "hello");

        assertEquals(some1.hashCode(), some2.hashCode());
    }

    @Test
    void testToStringWithSingleValueStorage() {
        MezzeVariant.TagRegistry registry = new MezzeVariant.TagRegistry(new String[]{"Some"});
        MezzeVariant some = MezzeVariant.singlePayload(registry, 0, 42L);

        assertEquals("Some(42)", some.toString());
    }

    @Test
    void testMultiPayloadFactoryWithEmptyArray() {
        MezzeVariant.TagRegistry registry = new MezzeVariant.TagRegistry(new String[]{"Empty"});
        MezzeVariant empty = MezzeVariant.multiPayload(registry, 0, new Object[]{});

        assertEquals(0, empty.getPayloadLength());
        assertFalse(empty.usesSingleValueStorage());
    }

    @Test
    void testMultiPayloadFactoryWithSingleElement() {
        MezzeVariant.TagRegistry registry = new MezzeVariant.TagRegistry(new String[]{"Some"});
        // multiPayload with single element should optimize to single-value storage
        MezzeVariant some = MezzeVariant.multiPayload(registry, 0, new Object[]{42L});

        assertEquals(1, some.getPayloadLength());
        assertEquals(42L, some.getPayloadValue());
        assertTrue(some.usesSingleValueStorage(), "multiPayload with single element should use single-value storage");
    }
}
