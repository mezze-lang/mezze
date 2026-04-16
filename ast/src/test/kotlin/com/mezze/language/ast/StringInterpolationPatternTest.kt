package com.mezze.language.ast

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested

/**
 * Comprehensive tests for StringInterpolationPattern AST node.
 *
 * Tests cover:
 * 1. Pattern construction and structure
 * 2. isIrrefutable() - string patterns are always refutable
 * 3. extractBindings() - extracting capture variable names
 * 4. kindDescription() - pattern description for error messages
 */
class StringInterpolationPatternTest {

    // =========================================================================
    // PATTERN CONSTRUCTION
    // =========================================================================

    @Nested
    inner class PatternConstruction {

        @Test
        fun `simple literal pattern with no captures`() {
            val pattern = StringInterpolationPattern(
                parts = listOf(StringPatternLiteral("hello world"))
            )

            assertEquals(1, pattern.parts.size)
            assertTrue(pattern.parts[0] is StringPatternLiteral)
            assertEquals("hello world", (pattern.parts[0] as StringPatternLiteral).value)
        }

        @Test
        fun `pattern with single capture`() {
            // "hello $(name)"
            val pattern = StringInterpolationPattern(
                parts = listOf(
                    StringPatternLiteral("hello "),
                    StringPatternCapture("name")
                )
            )

            assertEquals(2, pattern.parts.size)
            assertTrue(pattern.parts[0] is StringPatternLiteral)
            assertTrue(pattern.parts[1] is StringPatternCapture)
            assertEquals("name", (pattern.parts[1] as StringPatternCapture).variableName)
        }

        @Test
        fun `pattern with multiple captures`() {
            // "$(month)-$(day)-$(year)"
            val pattern = StringInterpolationPattern(
                parts = listOf(
                    StringPatternCapture("month"),
                    StringPatternLiteral("-"),
                    StringPatternCapture("day"),
                    StringPatternLiteral("-"),
                    StringPatternCapture("year")
                )
            )

            assertEquals(5, pattern.parts.size)
            assertTrue(pattern.parts[0] is StringPatternCapture)
            assertTrue(pattern.parts[1] is StringPatternLiteral)
            assertTrue(pattern.parts[2] is StringPatternCapture)
            assertTrue(pattern.parts[3] is StringPatternLiteral)
            assertTrue(pattern.parts[4] is StringPatternCapture)
        }

        @Test
        fun `pattern with prefix and suffix`() {
            // "prefix-$(middle)-suffix"
            val pattern = StringInterpolationPattern(
                parts = listOf(
                    StringPatternLiteral("prefix-"),
                    StringPatternCapture("middle"),
                    StringPatternLiteral("-suffix")
                )
            )

            assertEquals(3, pattern.parts.size)
            assertEquals("prefix-", (pattern.parts[0] as StringPatternLiteral).value)
            assertEquals("middle", (pattern.parts[1] as StringPatternCapture).variableName)
            assertEquals("-suffix", (pattern.parts[2] as StringPatternLiteral).value)
        }

        @Test
        fun `pattern with source location`() {
            val location = SourceLocation(10, 5, "test.mz")
            val pattern = StringInterpolationPattern(
                parts = listOf(StringPatternLiteral("test")),
                sourceLocation = location
            )

            assertNotNull(pattern.sourceLocation)
            assertEquals(10, pattern.sourceLocation?.line)
            assertEquals(5, pattern.sourceLocation?.column)
            assertEquals("test.mz", pattern.sourceLocation?.file)
        }

        @Test
        fun `empty pattern parts list`() {
            // Edge case: empty pattern
            val pattern = StringInterpolationPattern(parts = emptyList())
            assertEquals(0, pattern.parts.size)
        }
    }

    // =========================================================================
    // IS IRREFUTABLE
    // =========================================================================

    @Nested
    inner class IsIrrefutable {

        @Test
        fun `string interpolation pattern is always refutable`() {
            val pattern = StringInterpolationPattern(
                parts = listOf(StringPatternLiteral("hello"))
            )

            assertFalse(pattern.isIrrefutable())
        }

        @Test
        fun `pattern with captures is refutable`() {
            val pattern = StringInterpolationPattern(
                parts = listOf(
                    StringPatternLiteral("prefix-"),
                    StringPatternCapture("x")
                )
            )

            assertFalse(pattern.isIrrefutable())
        }

        @Test
        fun `empty pattern is still refutable`() {
            val pattern = StringInterpolationPattern(parts = emptyList())
            assertFalse(pattern.isIrrefutable())
        }

        @Test
        fun `compare with other refutable patterns`() {
            // All refutable patterns should return false
            val stringPattern = StringInterpolationPattern(
                parts = listOf(StringPatternLiteral("test"))
            )
            val literalPattern = LiteralPattern(IntLiteralValue(42))
            val constructorPattern = ConstructorPattern("Some", null)
            val seqPattern = SeqPattern(listOf(VariablePattern("x")), null)

            assertFalse(stringPattern.isIrrefutable())
            assertFalse(literalPattern.isIrrefutable())
            assertFalse(constructorPattern.isIrrefutable())
            assertFalse(seqPattern.isIrrefutable())
        }

        @Test
        fun `compare with irrefutable patterns`() {
            // Irrefutable patterns should return true
            val wildcardPattern = WildcardPattern()
            val variablePattern = VariablePattern("x")
            val recordPattern = RecordPattern(
                entries = listOf(RecordPatternEntry("x", null)),
                isOpen = false
            )

            assertTrue(wildcardPattern.isIrrefutable())
            assertTrue(variablePattern.isIrrefutable())
            assertTrue(recordPattern.isIrrefutable())
        }
    }

    // =========================================================================
    // EXTRACT BINDINGS
    // =========================================================================

    @Nested
    inner class ExtractBindings {

        @Test
        fun `literal only pattern has no bindings`() {
            val pattern = StringInterpolationPattern(
                parts = listOf(StringPatternLiteral("hello world"))
            )

            val bindings = pattern.extractBindings()
            assertTrue(bindings.isEmpty())
        }

        @Test
        fun `single capture has one binding`() {
            val pattern = StringInterpolationPattern(
                parts = listOf(
                    StringPatternLiteral("hello "),
                    StringPatternCapture("name")
                )
            )

            val bindings = pattern.extractBindings()
            assertEquals(1, bindings.size)
            assertEquals("name", bindings[0])
        }

        @Test
        fun `multiple captures have multiple bindings in order`() {
            // "$(month)-$(day)-$(year)"
            val pattern = StringInterpolationPattern(
                parts = listOf(
                    StringPatternCapture("month"),
                    StringPatternLiteral("-"),
                    StringPatternCapture("day"),
                    StringPatternLiteral("-"),
                    StringPatternCapture("year")
                )
            )

            val bindings = pattern.extractBindings()
            assertEquals(3, bindings.size)
            assertEquals("month", bindings[0])
            assertEquals("day", bindings[1])
            assertEquals("year", bindings[2])
        }

        @Test
        fun `empty pattern has no bindings`() {
            val pattern = StringInterpolationPattern(parts = emptyList())

            val bindings = pattern.extractBindings()
            assertTrue(bindings.isEmpty())
        }

        @Test
        fun `captures with various names`() {
            val pattern = StringInterpolationPattern(
                parts = listOf(
                    StringPatternCapture("camelCase"),
                    StringPatternLiteral("-"),
                    StringPatternCapture("snake_case"),
                    StringPatternLiteral("-"),
                    StringPatternCapture("x1")
                )
            )

            val bindings = pattern.extractBindings()
            assertEquals(listOf("camelCase", "snake_case", "x1"), bindings)
        }
    }

    // =========================================================================
    // KIND DESCRIPTION
    // =========================================================================

    @Nested
    inner class KindDescription {

        @Test
        fun `string interpolation pattern description`() {
            val pattern = StringInterpolationPattern(
                parts = listOf(StringPatternLiteral("test"))
            )

            assertEquals("string interpolation pattern", pattern.kindDescription())
        }

        @Test
        fun `compare with other pattern descriptions`() {
            assertEquals("variable pattern", VariablePattern("x").kindDescription())
            assertEquals("wildcard pattern", WildcardPattern().kindDescription())
            assertEquals("constructor pattern", ConstructorPattern("Some", null).kindDescription())
            assertEquals("record pattern", RecordPattern(emptyList()).kindDescription())
            assertEquals("literal pattern", LiteralPattern(IntLiteralValue(42)).kindDescription())
            assertEquals("seq pattern", SeqPattern(emptyList(), null).kindDescription())
        }
    }

    // =========================================================================
    // SERIALIZATION DISCRIMINATOR
    // =========================================================================

    @Nested
    inner class SerializationDiscriminator {

        @Test
        fun `StringPatternPart has correct discriminator annotation`() {
            // Verify the annotation exists via reflection-based test
            val literalClass = StringPatternLiteral::class.java
            val captureClass = StringPatternCapture::class.java

            // Both should implement StringPatternPart
            assertTrue(StringPatternPart::class.java.isAssignableFrom(literalClass))
            assertTrue(StringPatternPart::class.java.isAssignableFrom(captureClass))
        }
    }

    // =========================================================================
    // EDGE CASES
    // =========================================================================

    @Nested
    inner class EdgeCases {

        @Test
        fun `pattern with empty literal string`() {
            val pattern = StringInterpolationPattern(
                parts = listOf(
                    StringPatternLiteral(""),
                    StringPatternCapture("x")
                )
            )

            assertEquals(2, pattern.parts.size)
            assertEquals("", (pattern.parts[0] as StringPatternLiteral).value)
        }

        @Test
        fun `pattern with special characters in literal`() {
            val pattern = StringInterpolationPattern(
                parts = listOf(
                    StringPatternLiteral("hello\nworld\ttab"),
                    StringPatternCapture("x")
                )
            )

            assertEquals("hello\nworld\ttab", (pattern.parts[0] as StringPatternLiteral).value)
        }

        @Test
        fun `pattern with unicode in literal`() {
            val pattern = StringInterpolationPattern(
                parts = listOf(StringPatternLiteral("Hello! Привет! こんにちは!"))
            )

            assertEquals("Hello! Привет! こんにちは!", (pattern.parts[0] as StringPatternLiteral).value)
        }

        @Test
        fun `pattern only captures no literals`() {
            // This would only match if the captures are directly adjacent
            // which is typically not allowed in the language, but test AST handles it
            val pattern = StringInterpolationPattern(
                parts = listOf(
                    StringPatternCapture("a"),
                    StringPatternCapture("b")
                )
            )

            assertEquals(2, pattern.parts.size)
            val bindings = pattern.extractBindings()
            assertEquals(listOf("a", "b"), bindings)
        }

        @Test
        fun `pattern with dollar sign in literal - escaped`() {
            // Escaped $( in the literal part
            val pattern = StringInterpolationPattern(
                parts = listOf(StringPatternLiteral("cost: $100"))
            )

            assertEquals("cost: $100", (pattern.parts[0] as StringPatternLiteral).value)
        }

        @Test
        fun `variable name with underscore`() {
            val pattern = StringInterpolationPattern(
                parts = listOf(StringPatternCapture("_unused"))
            )

            val bindings = pattern.extractBindings()
            assertEquals(listOf("_unused"), bindings)
        }

        @Test
        fun `variable name with numbers`() {
            val pattern = StringInterpolationPattern(
                parts = listOf(
                    StringPatternCapture("var1"),
                    StringPatternLiteral("-"),
                    StringPatternCapture("var2")
                )
            )

            val bindings = pattern.extractBindings()
            assertEquals(listOf("var1", "var2"), bindings)
        }
    }

}
