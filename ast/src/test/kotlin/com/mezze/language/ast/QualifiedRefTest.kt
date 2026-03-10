package com.mezze.language.ast

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested

/**
 * Comprehensive tests for QualifiedRef - the canonical representation
 * of qualified references to effects, abilities, performers, etc.
 *
 * Tests cover:
 * 1. Construction - local, qualified with single segment, qualified with multiple segments
 * 2. Properties - isQualified, localName, qualifier
 * 3. Factory methods - local(), qualified()
 * 4. Content hash resolution - withResolvedHash()
 * 5. Serialization - JSON round-trip
 * 6. Edge cases - empty qualifier, null fields
 */
class QualifiedRefTest {

    // =========================================================================
    // CONSTRUCTION
    // =========================================================================

    @Nested
    inner class Construction {

        @Test
        fun `unqualified reference with just localName`() {
            val ref = QualifiedRef(localName = "Console")

            assertEquals("Console", ref.localName)
            assertNull(ref.qualifier)
            assertNull(ref.resolvedContentHash)
            assertNull(ref.sourceLocation)
            assertFalse(ref.isQualified)
        }

        @Test
        fun `qualified reference with single-segment qualifier`() {
            val ref = QualifiedRef(
                localName = "Console",
                qualifier = ModulePath(listOf("log"))
            )

            assertEquals("Console", ref.localName)
            assertNotNull(ref.qualifier)
            assertEquals(listOf("log"), ref.qualifier!!.segments)
            assertTrue(ref.isQualified)
        }

        @Test
        fun `qualified reference with multi-segment qualifier`() {
            val ref = QualifiedRef(
                localName = "Console",
                qualifier = ModulePath(listOf("std", "effects", "log"))
            )

            assertEquals("Console", ref.localName)
            assertNotNull(ref.qualifier)
            assertEquals(listOf("std", "effects", "log"), ref.qualifier!!.segments)
            assertTrue(ref.isQualified)
        }

        @Test
        fun `reference with source location`() {
            val location = SourceLocation(line = 10, column = 5, file = "test.mz")
            val ref = QualifiedRef(
                localName = "Console",
                sourceLocation = location
            )

            assertEquals(location, ref.sourceLocation)
        }

        @Test
        fun `reference with resolved content hash`() {
            val ref = QualifiedRef(
                localName = "Console",
                resolvedContentHash = "abc123def456"
            )

            assertEquals("abc123def456", ref.resolvedContentHash)
        }

        @Test
        fun `reference with all fields populated`() {
            val location = SourceLocation(line = 1, column = 1)
            val ref = QualifiedRef(
                localName = "Console",
                qualifier = ModulePath(listOf("log")),
                resolvedContentHash = "hash123",
                sourceLocation = location
            )

            assertEquals("Console", ref.localName)
            assertEquals(listOf("log"), ref.qualifier!!.segments)
            assertEquals("hash123", ref.resolvedContentHash)
            assertEquals(location, ref.sourceLocation)
            assertTrue(ref.isQualified)
        }
    }

    // =========================================================================
    // IS QUALIFIED PROPERTY
    // =========================================================================

    @Nested
    inner class IsQualifiedProperty {

        @Test
        fun `isQualified is false when qualifier is null`() {
            val ref = QualifiedRef(localName = "Test")
            assertFalse(ref.isQualified)
        }

        @Test
        fun `isQualified is false when qualifier has empty segments`() {
            val ref = QualifiedRef(
                localName = "Test",
                qualifier = ModulePath(emptyList())
            )
            assertFalse(ref.isQualified)
        }

        @Test
        fun `isQualified is true when qualifier has one segment`() {
            val ref = QualifiedRef(
                localName = "Test",
                qualifier = ModulePath(listOf("module"))
            )
            assertTrue(ref.isQualified)
        }

        @Test
        fun `isQualified is true when qualifier has multiple segments`() {
            val ref = QualifiedRef(
                localName = "Test",
                qualifier = ModulePath(listOf("a", "b", "c"))
            )
            assertTrue(ref.isQualified)
        }
    }

    // =========================================================================
    // FACTORY METHODS
    // =========================================================================

    @Nested
    inner class FactoryMethods {

        @Test
        fun `local() creates unqualified reference`() {
            val ref = QualifiedRef.local("Console")

            assertEquals("Console", ref.localName)
            assertNull(ref.qualifier)
            assertFalse(ref.isQualified)
        }

        @Test
        fun `local() with source location`() {
            val location = SourceLocation(line = 5, column = 10)
            val ref = QualifiedRef.local("Console", location)

            assertEquals("Console", ref.localName)
            assertEquals(location, ref.sourceLocation)
        }

        @Test
        fun `qualified() with single string qualifier`() {
            val ref = QualifiedRef.qualified("log", "Console")

            assertEquals("Console", ref.localName)
            assertEquals(listOf("log"), ref.qualifier!!.segments)
            assertTrue(ref.isQualified)
        }

        @Test
        fun `qualified() with string qualifier and source location`() {
            val location = SourceLocation(line = 1, column = 1)
            val ref = QualifiedRef.qualified("log", "Console", location)

            assertEquals("Console", ref.localName)
            assertEquals(listOf("log"), ref.qualifier!!.segments)
            assertEquals(location, ref.sourceLocation)
        }

        @Test
        fun `qualified() with list qualifier`() {
            val ref = QualifiedRef.qualified(listOf("std", "effects", "log"), "Console")

            assertEquals("Console", ref.localName)
            assertEquals(listOf("std", "effects", "log"), ref.qualifier!!.segments)
            assertTrue(ref.isQualified)
        }

        @Test
        fun `qualified() with empty list creates unqualified reference`() {
            val ref = QualifiedRef.qualified(emptyList(), "Console")

            assertEquals("Console", ref.localName)
            assertNull(ref.qualifier)
            assertFalse(ref.isQualified)
        }

        @Test
        fun `qualified() with list qualifier and source location`() {
            val location = SourceLocation(line = 3, column = 7)
            val ref = QualifiedRef.qualified(listOf("a", "b"), "Test", location)

            assertEquals("Test", ref.localName)
            assertEquals(listOf("a", "b"), ref.qualifier!!.segments)
            assertEquals(location, ref.sourceLocation)
        }
    }

    // =========================================================================
    // CONTENT HASH RESOLUTION
    // =========================================================================

    @Nested
    inner class ContentHashResolution {

        @Test
        fun `withResolvedHash() creates copy with hash`() {
            val original = QualifiedRef.local("Console")
            val resolved = original.withResolvedHash("abc123")

            // Original unchanged
            assertNull(original.resolvedContentHash)

            // Resolved has hash
            assertEquals("abc123", resolved.resolvedContentHash)

            // Other fields preserved
            assertEquals(original.localName, resolved.localName)
            assertEquals(original.qualifier, resolved.qualifier)
        }

        @Test
        fun `withResolvedHash() preserves all other fields`() {
            val location = SourceLocation(line = 5, column = 10)
            val original = QualifiedRef(
                localName = "Console",
                qualifier = ModulePath(listOf("log")),
                sourceLocation = location
            )

            val resolved = original.withResolvedHash("xyz789")

            assertEquals("Console", resolved.localName)
            assertEquals(listOf("log"), resolved.qualifier!!.segments)
            assertEquals(location, resolved.sourceLocation)
            assertEquals("xyz789", resolved.resolvedContentHash)
        }

        @Test
        fun `withResolvedHash() can override existing hash`() {
            val original = QualifiedRef(
                localName = "Test",
                resolvedContentHash = "old-hash"
            )

            val resolved = original.withResolvedHash("new-hash")

            assertEquals("new-hash", resolved.resolvedContentHash)
        }
    }

    // =========================================================================
    // SERIALIZATION
    // =========================================================================

    @Nested
    inner class Serialization {

        private val json = Json { prettyPrint = false }

        @Test
        fun `serialize and deserialize unqualified reference`() {
            val original = QualifiedRef.local("Console")
            val serialized = json.encodeToString(original)
            val deserialized = json.decodeFromString<QualifiedRef>(serialized)

            assertEquals(original, deserialized)
        }

        @Test
        fun `serialize and deserialize qualified reference`() {
            val original = QualifiedRef.qualified("log", "Console")
            val serialized = json.encodeToString(original)
            val deserialized = json.decodeFromString<QualifiedRef>(serialized)

            assertEquals(original, deserialized)
        }

        @Test
        fun `serialize and deserialize with all fields`() {
            val original = QualifiedRef(
                localName = "Console",
                qualifier = ModulePath(listOf("std", "effects", "log")),
                resolvedContentHash = "hash123",
                sourceLocation = SourceLocation(line = 10, column = 5, file = "test.mz")
            )

            val serialized = json.encodeToString(original)
            val deserialized = json.decodeFromString<QualifiedRef>(serialized)

            assertEquals(original, deserialized)
        }

        @Test
        fun `serialized JSON contains expected fields`() {
            val ref = QualifiedRef(
                localName = "Console",
                qualifier = ModulePath(listOf("log"))
            )

            val serialized = json.encodeToString(ref)

            assertTrue(serialized.contains("\"localName\":\"Console\""))
            assertTrue(serialized.contains("\"qualifier\""))
            assertTrue(serialized.contains("\"segments\":[\"log\"]"))
        }
    }

    // =========================================================================
    // EQUALITY AND HASH CODE
    // =========================================================================

    @Nested
    inner class EqualityAndHashCode {

        @Test
        fun `equal references have same hashCode`() {
            val ref1 = QualifiedRef.qualified("log", "Console")
            val ref2 = QualifiedRef.qualified("log", "Console")

            assertEquals(ref1, ref2)
            assertEquals(ref1.hashCode(), ref2.hashCode())
        }

        @Test
        fun `different localName means not equal`() {
            val ref1 = QualifiedRef.local("Console")
            val ref2 = QualifiedRef.local("FileLogger")

            assertNotEquals(ref1, ref2)
        }

        @Test
        fun `different qualifier means not equal`() {
            val ref1 = QualifiedRef.qualified("log", "Console")
            val ref2 = QualifiedRef.qualified("io", "Console")

            assertNotEquals(ref1, ref2)
        }

        @Test
        fun `qualified vs unqualified means not equal`() {
            val ref1 = QualifiedRef.local("Console")
            val ref2 = QualifiedRef.qualified("log", "Console")

            assertNotEquals(ref1, ref2)
        }

        @Test
        fun `different content hash still equal - hash is resolution metadata`() {
            // Content hash is populated by typechecker, doesn't affect identity
            val ref1 = QualifiedRef(localName = "Console", resolvedContentHash = "hash1")
            val ref2 = QualifiedRef(localName = "Console", resolvedContentHash = "hash2")

            // They ARE different due to data class equality
            assertNotEquals(ref1, ref2)
        }
    }

    // =========================================================================
    // EDGE CASES
    // =========================================================================

    @Nested
    inner class EdgeCases {

        @Test
        fun `empty string localName`() {
            // While unusual, should not crash
            val ref = QualifiedRef(localName = "")
            assertEquals("", ref.localName)
        }

        @Test
        fun `single character localName`() {
            val ref = QualifiedRef(localName = "x")
            assertEquals("x", ref.localName)
        }

        @Test
        fun `localName with special characters`() {
            // Some languages allow underscores, etc.
            val ref = QualifiedRef(localName = "my_handler_123")
            assertEquals("my_handler_123", ref.localName)
        }

        @Test
        fun `deeply nested qualifier`() {
            val segments = listOf("a", "b", "c", "d", "e", "f", "g")
            val ref = QualifiedRef.qualified(segments, "Handler")

            assertEquals(segments, ref.qualifier!!.segments)
            assertTrue(ref.isQualified)
        }

        @Test
        fun `qualifier segment with single character`() {
            val ref = QualifiedRef.qualified(listOf("x"), "Y")

            assertEquals(listOf("x"), ref.qualifier!!.segments)
            assertEquals("Y", ref.localName)
        }
    }

    // =========================================================================
    // REAL-WORLD USAGE PATTERNS
    // =========================================================================

    @Nested
    inner class RealWorldPatterns {

        @Test
        fun `pattern - unqualified effect handler`() {
            // perform Console in ...
            val ref = QualifiedRef.local("Console")

            assertEquals("Console", ref.localName)
            assertFalse(ref.isQualified)
        }

        @Test
        fun `pattern - module-qualified effect handler`() {
            // use std::effects::log
            // perform log::Console in ...
            val ref = QualifiedRef.qualified("log", "Console")

            assertEquals("Console", ref.localName)
            assertEquals("log", ref.qualifier!!.shortName)
            assertTrue(ref.isQualified)
        }

        @Test
        fun `pattern - impl declaration with qualified effect`() {
            // impl log::Log for MyHandler
            val effectRef = QualifiedRef.qualified("log", "Log")

            assertEquals("Log", effectRef.localName)
            assertEquals("log", effectRef.qualifier!!.shortName)
        }

        @Test
        fun `pattern - effect call with qualified effect`() {
            // log::Log.write {msg = "hello"}
            val effectRef = QualifiedRef.qualified("log", "Log")

            assertEquals("Log", effectRef.localName)
            assertTrue(effectRef.isQualified)
        }

        @Test
        fun `pattern - resolved reference ready for interpreter`() {
            // After typechecker resolves the reference
            val ref = QualifiedRef
                .qualified("log", "Console")
                .withResolvedHash("sha256:abc123def456")

            assertTrue(ref.isQualified)
            assertEquals("sha256:abc123def456", ref.resolvedContentHash)
        }
    }
}
