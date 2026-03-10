package com.mezze.language.ast

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested

/**
 * Comprehensive tests for BindingKey.
 *
 * BindingKey replaces the pattern of using "$alias::$name" string concatenation
 * for import binding map keys. These tests verify that:
 * 1. Key string generation is consistent
 * 2. Factory methods work correctly
 * 3. Integration with QualifiedRef and Identifier works
 */
class BindingKeyTest {

    // ========================================================================
    // BASIC CREATION
    // ========================================================================

    @Nested
    inner class BasicCreation {

        @Test
        fun `unqualified key has null qualifier`() {
            val key = BindingKey.unqualified("info")

            assertNull(key.qualifier)
            assertEquals("info", key.localName)
        }

        @Test
        fun `qualified key has qualifier and localName`() {
            val key = BindingKey.qualified("log", "info")

            assertEquals("log", key.qualifier)
            assertEquals("info", key.localName)
        }

        @Test
        fun `direct constructor works`() {
            val key = BindingKey(qualifier = "math", localName = "pi")

            assertEquals("math", key.qualifier)
            assertEquals("pi", key.localName)
        }
    }

    // ========================================================================
    // KEY STRING GENERATION
    // ========================================================================

    @Nested
    inner class KeyStringGeneration {

        @Test
        fun `unqualified key returns just localName`() {
            val key = BindingKey.unqualified("info")

            assertEquals("info", key.toKeyString())
        }

        @Test
        fun `qualified key returns qualifier separator localName`() {
            val key = BindingKey.qualified("log", "info")

            // Note: The :: is an implementation detail of the key format
            assertEquals("log::info", key.toKeyString())
        }

        @Test
        fun `empty qualifier is treated as unqualified`() {
            val key = BindingKey(qualifier = "", localName = "info")

            assertEquals("info", key.toKeyString())
        }

        @Test
        fun `null qualifier is treated as unqualified`() {
            val key = BindingKey(qualifier = null, localName = "info")

            assertEquals("info", key.toKeyString())
        }
    }

    // ========================================================================
    // FROM QUALIFIED REF
    // ========================================================================

    @Nested
    inner class FromQualifiedRef {

        @Test
        fun `fromQualifiedRef with unqualified ref`() {
            val ref = QualifiedRef.local("Console")
            val key = BindingKey.fromQualifiedRef(ref)

            assertNull(key.qualifier)
            assertEquals("Console", key.localName)
            assertEquals("Console", key.toKeyString())
        }

        @Test
        fun `fromQualifiedRef with single-segment qualifier`() {
            val ref = QualifiedRef.qualified("log", "Console")
            val key = BindingKey.fromQualifiedRef(ref)

            assertEquals("log", key.qualifier)
            assertEquals("Console", key.localName)
            assertEquals("log::Console", key.toKeyString())
        }

        @Test
        fun `fromQualifiedRef with multi-segment qualifier uses shortName`() {
            val ref = QualifiedRef.qualified(listOf("std", "effects", "log"), "Console")
            val key = BindingKey.fromQualifiedRef(ref)

            // Uses the SHORT name (last segment) as qualifier
            assertEquals("log", key.qualifier)
            assertEquals("Console", key.localName)
            assertEquals("log::Console", key.toKeyString())
        }

        @Test
        fun `QualifiedRef toBindingKey helper works`() {
            val ref = QualifiedRef.qualified("log", "info")
            val key = ref.toBindingKey()

            assertEquals("log", key.qualifier)
            assertEquals("info", key.localName)
        }
    }

    // ========================================================================
    // FROM IDENTIFIER
    // ========================================================================

    @Nested
    inner class FromIdentifier {

        @Test
        fun `Identifier toBindingKey with unqualified identifier`() {
            val id = Identifier(localName = "myVar")
            val key = id.toBindingKey()

            assertNull(key.qualifier)
            assertEquals("myVar", key.localName)
            assertEquals("myVar", key.toKeyString())
        }

        @Test
        fun `Identifier toBindingKey with single-segment qualifier`() {
            val id = Identifier(localName = "from_string", qualifier = ModulePath(listOf("bytes")))
            val key = id.toBindingKey()

            assertEquals("bytes", key.qualifier)
            assertEquals("from_string", key.localName)
            assertEquals("bytes::from_string", key.toKeyString())
        }

        @Test
        fun `Identifier toBindingKey with multi-segment qualifier uses shortName`() {
            val id = Identifier(localName = "map", qualifier = ModulePath(listOf("std", "collections", "seq")))
            val key = id.toBindingKey()

            // Uses the SHORT name (last segment) as qualifier
            assertEquals("seq", key.qualifier)
            assertEquals("map", key.localName)
            assertEquals("seq::map", key.toKeyString())
        }
    }

    // ========================================================================
    // EQUALITY AND HASHING
    // ========================================================================

    @Nested
    inner class EqualityAndHashing {

        @Test
        fun `same keys are equal`() {
            val key1 = BindingKey.qualified("log", "info")
            val key2 = BindingKey.qualified("log", "info")

            assertEquals(key1, key2)
            assertEquals(key1.hashCode(), key2.hashCode())
        }

        @Test
        fun `different qualifiers are not equal`() {
            val key1 = BindingKey.qualified("log", "info")
            val key2 = BindingKey.qualified("console", "info")

            assertNotEquals(key1, key2)
        }

        @Test
        fun `different localNames are not equal`() {
            val key1 = BindingKey.qualified("log", "info")
            val key2 = BindingKey.qualified("log", "warn")

            assertNotEquals(key1, key2)
        }

        @Test
        fun `qualified vs unqualified are not equal`() {
            val key1 = BindingKey.qualified("log", "info")
            val key2 = BindingKey.unqualified("info")

            assertNotEquals(key1, key2)
        }
    }

    // ========================================================================
    // REAL-WORLD USAGE PATTERNS
    // ========================================================================

    @Nested
    inner class RealWorldPatterns {

        @Test
        fun `pattern - looking up qualified import`() {
            // Simulates: use std::effects::log (module import makes items qualified)
            val bindings = mutableMapOf<String, String>()

            // When importing, we create qualified keys
            val key = BindingKey.qualified("log", "info")
            bindings[key.toKeyString()] = "log::info type"

            // When resolving an identifier like log::info
            val identifier = Identifier(localName = "info", qualifier = ModulePath(listOf("log")))
            val lookupKey = identifier.toBindingKey().toKeyString()

            assertEquals("log::info type", bindings[lookupKey])
        }

        @Test
        fun `pattern - looking up selective import`() {
            // Simulates: use std::effects::log::{info, Console} (selective import)
            val bindings = mutableMapOf<String, String>()

            // Selective imports are unqualified
            val key = BindingKey.unqualified("info")
            bindings[key.toKeyString()] = "info type"

            // When resolving an identifier like info (no qualifier)
            val identifier = Identifier(localName = "info")
            val lookupKey = identifier.toBindingKey().toKeyString()

            assertEquals("info type", bindings[lookupKey])
        }

        @Test
        fun `pattern - perform handler lookup`() {
            // Simulates: perform log::Console in ...
            val handlers = mutableMapOf<String, String>()

            // Handler is registered with qualified key
            val handlerRef = QualifiedRef.qualified("log", "Console")
            val key = handlerRef.toBindingKey()
            handlers[key.toKeyString()] = "Console handler"

            // When performing, we look up using the same key format
            val performRef = QualifiedRef.qualified("log", "Console")
            val lookupKey = performRef.toBindingKey().toKeyString()

            assertEquals("Console handler", handlers[lookupKey])
        }
    }
}
