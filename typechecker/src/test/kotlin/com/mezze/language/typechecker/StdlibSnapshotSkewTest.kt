package com.mezze.language.typechecker

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * The schema-skew guard that replaced the silent "snapshot decode failed" fallback.
 * A snapshot built against a different serialization schema (e.g. after a MezzeType
 * subclass is added/removed) is now detected by a fingerprint BEFORE decode, instead
 * of throwing a cryptic SerializationException mid-decode.
 */
class StdlibSnapshotSkewTest {

    @Test
    fun `schema fingerprint is a stable 64-char hex`() {
        val fp = StdlibSnapshot.schemaFingerprint
        assertTrue(fp.matches(Regex("[0-9a-f]{64}")), "expected SHA-256 hex, got: $fp")
        assertEquals(fp, StdlibSnapshot.schemaFingerprint, "fingerprint must be deterministic")
    }

    @Test
    fun `absent fingerprint is not skew (backward compatible)`() {
        assertFalse(StdlibSnapshot.isSchemaSkew(null))
    }

    @Test
    fun `matching fingerprint is not skew`() {
        assertFalse(StdlibSnapshot.isSchemaSkew(StdlibSnapshot.schemaFingerprint))
    }

    @Test
    fun `differing fingerprint is skew`() {
        assertTrue(StdlibSnapshot.isSchemaSkew("deadbeef"))
        assertTrue(StdlibSnapshot.isSchemaSkew("0".repeat(64)))
    }
}
