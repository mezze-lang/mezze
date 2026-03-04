package com.mezze.interpreter.v2.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ImplSignature data structure.
 */
class ImplSignatureTest {

    @Test
    void nonScopedEffectHasCorrectDefaults() {
        ImplSignature sig = new ImplSignature("Log", "Console", "hash123");

        assertEquals("Log", sig.getEffectName());
        assertEquals("Console", sig.getPerformerName());
        assertEquals("hash123", sig.getContentHash());
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
                "hash456",
                true,  // hasInitializeScope
                true,  // hasFinalizeScope
                null,  // initializeScopeTarget (would be CallTarget in real use)
                null,  // finalizeScopeTarget
                scopeFields
        );

        assertEquals("DB", sig.getEffectName());
        assertEquals("PostgresDB", sig.getPerformerName());
        assertEquals("hash456", sig.getContentHash());
        assertTrue(sig.hasInitializeScope());
        assertTrue(sig.hasFinalizeScope());
        assertTrue(sig.isScopedEffect());
        assertArrayEquals(scopeFields, sig.getScopeFieldNames());
    }

    @Test
    void builderCreatesNonScopedEffect() {
        ImplSignature sig = ImplSignature.builder("FileSystem", "LocalFS", "hash789")
                .build();

        assertEquals("FileSystem", sig.getEffectName());
        assertEquals("LocalFS", sig.getPerformerName());
        assertEquals("hash789", sig.getContentHash());
        assertFalse(sig.isScopedEffect());
    }

    @Test
    void builderCreatesScopedEffect() {
        String[] scopeFields = new String[]{"handle", "path"};

        ImplSignature sig = ImplSignature.builder("Resource", "FileResource", "hash000")
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

        ImplSignature sig = ImplSignature.builder("Effect", "Handler", "hash")
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
        ImplSignature sig = new ImplSignature("Log", "Console", "hash");

        String str = sig.toString();
        assertFalse(str.contains("scoped"), "Non-scoped should not have scope info: " + str);
    }
}
