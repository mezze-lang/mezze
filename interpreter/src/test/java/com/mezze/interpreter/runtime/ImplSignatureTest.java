package com.mezze.interpreter.runtime;

import com.mezze.language.typedast.Hash;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ImplSignature data structure.
 */
class ImplSignatureTest {

    private static final Hash HASH_1 = new Hash("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    private static final Hash HASH_2 = new Hash("fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210");
    private static final Hash HASH_3 = new Hash("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    private static final Hash HASH_4 = new Hash("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    private static final Hash HASH_5 = new Hash("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");
    private static final Hash HASH_6 = new Hash("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd");

    @Test
    void nonScopedEffectHasCorrectDefaults() {
        ImplSignature sig = new ImplSignature("Log", "Console", HASH_1);

        assertEquals("Log", sig.getEffectName());
        assertEquals("Console", sig.getPerformerName());
        assertEquals(HASH_1, sig.getContentHash());
        assertFalse(sig.hasInitializeScope());
        assertFalse(sig.hasFinalizeScope());
        assertFalse(sig.isScopedEffect());
        assertNull(sig.getInitializeScopeTarget());
        assertNull(sig.getFinalizeScopeTarget());
        assertNull(sig.getScopeFieldNames());
    }

    @Test
    void scopedEffectHasCorrectValues() {
        String[] scopeFields = new String[]{"pool", "connection"};

        ImplSignature sig = new ImplSignature(
                "DB",
                "PostgresDB",
                HASH_2,
                true,  // hasInitializeScope
                true,  // hasFinalizeScope
                null,  // initializeScopeTarget (would be CallTarget in real use)
                null,  // finalizeScopeTarget
                scopeFields
        );

        assertEquals("DB", sig.getEffectName());
        assertEquals("PostgresDB", sig.getPerformerName());
        assertEquals(HASH_2, sig.getContentHash());
        assertTrue(sig.hasInitializeScope());
        assertTrue(sig.hasFinalizeScope());
        assertTrue(sig.isScopedEffect());
        assertArrayEquals(scopeFields, sig.getScopeFieldNames());
    }

    @Test
    void builderCreatesNonScopedEffect() {
        ImplSignature sig = ImplSignature.builder("FileSystem", "LocalFS", HASH_3)
                .build();

        assertEquals("FileSystem", sig.getEffectName());
        assertEquals("LocalFS", sig.getPerformerName());
        assertEquals(HASH_3, sig.getContentHash());
        assertFalse(sig.isScopedEffect());
    }

    @Test
    void builderCreatesScopedEffect() {
        String[] scopeFields = new String[]{"handle", "path"};

        ImplSignature sig = ImplSignature.builder("Resource", "FileResource", HASH_4)
                .withInitializeScope(null, scopeFields)
                .withFinalizeScope(null)
                .build();

        assertTrue(sig.hasInitializeScope());
        assertTrue(sig.hasFinalizeScope());
        assertTrue(sig.isScopedEffect());
        assertArrayEquals(scopeFields, sig.getScopeFieldNames());
    }

    @Test
    void toStringIncludesScopeInfo() {
        String[] scopeFields = new String[]{"x", "y"};

        ImplSignature sig = ImplSignature.builder("Effect", "Handler", HASH_5)
                .withInitializeScope(null, scopeFields)
                .withFinalizeScope(null)
                .build();

        String str = sig.toString();
        assertTrue(str.contains("scoped=true"), "Should indicate scoped effect: " + str);
        assertTrue(str.contains("scopeFields="), "Should include scope fields: " + str);
        assertTrue(str.contains("x"), "Should include field name x: " + str);
        assertTrue(str.contains("y"), "Should include field name y: " + str);
    }

    @Test
    void toStringForNonScopedDoesNotIncludeScopeInfo() {
        ImplSignature sig = new ImplSignature("Log", "Console", HASH_6);

        String str = sig.toString();
        assertFalse(str.contains("scoped"), "Non-scoped should not have scope info: " + str);
    }
}
