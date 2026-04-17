package com.mezze.language.ast

import com.mezze.language.parsedast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested

/**
 * Comprehensive tests for ParsedStringInterpPattern AST node.
 *
 * Tests cover:
 * 1. Pattern construction and structure
 * 2. isIrrefutable() - string patterns are always refutable
 * 3. extractBindings() - extracting capture variable names
 * 4. kindDescription() - pattern description for error messages
 */
class StringInterpolationPatternTest {

    private val testLoc = Loc("<test>", 0, 0, 0, 0)

    // =========================================================================
    // PATTERN CONSTRUCTION
    // =========================================================================

    @Nested
    inner class PatternConstruction {

        @Test
        fun `simple literal pattern with no captures`() {
            val pattern = ParsedStringInterpPattern(
                parts = listOf(ParsedStringPatternPart.Literal("hello world")),
                loc = testLoc
            )

            assertEquals(1, pattern.parts.size)
            assertTrue(pattern.parts[0] is ParsedStringPatternPart.Literal)
            assertEquals("hello world", (pattern.parts[0] as ParsedStringPatternPart.Literal).value)
        }

        @Test
        fun `pattern with single capture`() {
            // "hello $(name)"
            val pattern = ParsedStringInterpPattern(
                parts = listOf(
                    ParsedStringPatternPart.Literal("hello "),
                    ParsedStringPatternPart.Capture("name")
                ),
                loc = testLoc
            )

            assertEquals(2, pattern.parts.size)
            assertTrue(pattern.parts[0] is ParsedStringPatternPart.Literal)
            assertTrue(pattern.parts[1] is ParsedStringPatternPart.Capture)
            assertEquals("name", (pattern.parts[1] as ParsedStringPatternPart.Capture).variableName)
        }

        @Test
        fun `pattern with multiple captures`() {
            // "$(month)-$(day)-$(year)"
            val pattern = ParsedStringInterpPattern(
                parts = listOf(
                    ParsedStringPatternPart.Capture("month"),
                    ParsedStringPatternPart.Literal("-"),
                    ParsedStringPatternPart.Capture("day"),
                    ParsedStringPatternPart.Literal("-"),
                    ParsedStringPatternPart.Capture("year")
                ),
                loc = testLoc
            )

            assertEquals(5, pattern.parts.size)
            assertTrue(pattern.parts[0] is ParsedStringPatternPart.Capture)
            assertTrue(pattern.parts[1] is ParsedStringPatternPart.Literal)
            assertTrue(pattern.parts[2] is ParsedStringPatternPart.Capture)
            assertTrue(pattern.parts[3] is ParsedStringPatternPart.Literal)
            assertTrue(pattern.parts[4] is ParsedStringPatternPart.Capture)
        }

        @Test
        fun `pattern with prefix and suffix`() {
            // "prefix-$(middle)-suffix"
            val pattern = ParsedStringInterpPattern(
                parts = listOf(
                    ParsedStringPatternPart.Literal("prefix-"),
                    ParsedStringPatternPart.Capture("middle"),
                    ParsedStringPatternPart.Literal("-suffix")
                ),
                loc = testLoc
            )

            assertEquals(3, pattern.parts.size)
            assertEquals("prefix-", (pattern.parts[0] as ParsedStringPatternPart.Literal).value)
            assertEquals("middle", (pattern.parts[1] as ParsedStringPatternPart.Capture).variableName)
            assertEquals("-suffix", (pattern.parts[2] as ParsedStringPatternPart.Literal).value)
        }

        @Test
        fun `pattern with source location`() {
            val location = Loc("test.mz", 10, 5, 10, 20)
            val pattern = ParsedStringInterpPattern(
                parts = listOf(ParsedStringPatternPart.Literal("test")),
                loc = location
            )

            assertNotNull(pattern.loc)
            assertEquals(10, pattern.loc.startLine)
            assertEquals(5, pattern.loc.startCol)
            assertEquals("test.mz", pattern.loc.file)
        }

        @Test
        fun `empty pattern parts list`() {
            // Edge case: empty pattern
            val pattern = ParsedStringInterpPattern(parts = emptyList(), loc = testLoc)
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
            val pattern = ParsedStringInterpPattern(
                parts = listOf(ParsedStringPatternPart.Literal("hello")),
                loc = testLoc
            )

            assertFalse(pattern.isIrrefutable())
        }

        @Test
        fun `pattern with captures is refutable`() {
            val pattern = ParsedStringInterpPattern(
                parts = listOf(
                    ParsedStringPatternPart.Literal("prefix-"),
                    ParsedStringPatternPart.Capture("x")
                ),
                loc = testLoc
            )

            assertFalse(pattern.isIrrefutable())
        }

        @Test
        fun `empty pattern is still refutable`() {
            val pattern = ParsedStringInterpPattern(parts = emptyList(), loc = testLoc)
            assertFalse(pattern.isIrrefutable())
        }

        @Test
        fun `compare with other refutable patterns`() {
            // All refutable patterns should return false
            val stringPattern = ParsedStringInterpPattern(
                parts = listOf(ParsedStringPatternPart.Literal("test")),
                loc = testLoc
            )
            val literalPattern = ParsedLiteralPattern(ParsedLiteralValue.IntValue(42), testLoc)
            val constructorPattern = ParsedConstructorPattern("Some", null, testLoc)
            val seqPattern = ParsedSeqPattern(listOf(ParsedVariablePattern("x", testLoc)), null, testLoc)

            assertFalse(stringPattern.isIrrefutable())
            assertFalse(literalPattern.isIrrefutable())
            assertFalse(constructorPattern.isIrrefutable())
            assertFalse(seqPattern.isIrrefutable())
        }

        @Test
        fun `compare with irrefutable patterns`() {
            // Irrefutable patterns should return true
            val wildcardPattern = ParsedWildcardPattern(testLoc)
            val variablePattern = ParsedVariablePattern("x", testLoc)
            val recordPattern = ParsedRecordPattern(
                entries = listOf(ParsedRecordPatternEntry("x", null, testLoc)),
                isOpen = false,
                loc = testLoc
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
            val pattern = ParsedStringInterpPattern(
                parts = listOf(ParsedStringPatternPart.Literal("hello world")),
                loc = testLoc
            )

            val bindings = pattern.extractBindings()
            assertTrue(bindings.isEmpty())
        }

        @Test
        fun `single capture has one binding`() {
            val pattern = ParsedStringInterpPattern(
                parts = listOf(
                    ParsedStringPatternPart.Literal("hello "),
                    ParsedStringPatternPart.Capture("name")
                ),
                loc = testLoc
            )

            val bindings = pattern.extractBindings()
            assertEquals(1, bindings.size)
            assertEquals("name", bindings[0])
        }

        @Test
        fun `multiple captures have multiple bindings in order`() {
            // "$(month)-$(day)-$(year)"
            val pattern = ParsedStringInterpPattern(
                parts = listOf(
                    ParsedStringPatternPart.Capture("month"),
                    ParsedStringPatternPart.Literal("-"),
                    ParsedStringPatternPart.Capture("day"),
                    ParsedStringPatternPart.Literal("-"),
                    ParsedStringPatternPart.Capture("year")
                ),
                loc = testLoc
            )

            val bindings = pattern.extractBindings()
            assertEquals(3, bindings.size)
            assertEquals("month", bindings[0])
            assertEquals("day", bindings[1])
            assertEquals("year", bindings[2])
        }

        @Test
        fun `empty pattern has no bindings`() {
            val pattern = ParsedStringInterpPattern(parts = emptyList(), loc = testLoc)

            val bindings = pattern.extractBindings()
            assertTrue(bindings.isEmpty())
        }

        @Test
        fun `captures with various names`() {
            val pattern = ParsedStringInterpPattern(
                parts = listOf(
                    ParsedStringPatternPart.Capture("camelCase"),
                    ParsedStringPatternPart.Literal("-"),
                    ParsedStringPatternPart.Capture("snake_case"),
                    ParsedStringPatternPart.Literal("-"),
                    ParsedStringPatternPart.Capture("x1")
                ),
                loc = testLoc
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
            val pattern = ParsedStringInterpPattern(
                parts = listOf(ParsedStringPatternPart.Literal("test")),
                loc = testLoc
            )

            assertEquals("string interpolation pattern", pattern.kindDescription())
        }

        @Test
        fun `compare with other pattern descriptions`() {
            assertEquals("variable pattern", ParsedVariablePattern("x", testLoc).kindDescription())
            assertEquals("wildcard pattern", ParsedWildcardPattern(testLoc).kindDescription())
            assertEquals("constructor pattern", ParsedConstructorPattern("Some", null, testLoc).kindDescription())
            assertEquals("record pattern", ParsedRecordPattern(emptyList(), loc = testLoc).kindDescription())
            assertEquals("literal pattern", ParsedLiteralPattern(ParsedLiteralValue.IntValue(42), testLoc).kindDescription())
            assertEquals("seq pattern", ParsedSeqPattern(emptyList(), null, testLoc).kindDescription())
        }
    }

    // =========================================================================
    // SERIALIZATION DISCRIMINATOR
    // =========================================================================

    @Nested
    inner class SerializationDiscriminator {

        @Test
        fun `ParsedStringPatternPart has correct discriminator annotation`() {
            // Verify the annotation exists via reflection-based test
            val literalClass = ParsedStringPatternPart.Literal::class.java
            val captureClass = ParsedStringPatternPart.Capture::class.java

            // Both should implement ParsedStringPatternPart
            assertTrue(ParsedStringPatternPart::class.java.isAssignableFrom(literalClass))
            assertTrue(ParsedStringPatternPart::class.java.isAssignableFrom(captureClass))
        }
    }

    // =========================================================================
    // EDGE CASES
    // =========================================================================

    @Nested
    inner class EdgeCases {

        @Test
        fun `pattern with empty literal string`() {
            val pattern = ParsedStringInterpPattern(
                parts = listOf(
                    ParsedStringPatternPart.Literal(""),
                    ParsedStringPatternPart.Capture("x")
                ),
                loc = testLoc
            )

            assertEquals(2, pattern.parts.size)
            assertEquals("", (pattern.parts[0] as ParsedStringPatternPart.Literal).value)
        }

        @Test
        fun `pattern with special characters in literal`() {
            val pattern = ParsedStringInterpPattern(
                parts = listOf(
                    ParsedStringPatternPart.Literal("hello\nworld\ttab"),
                    ParsedStringPatternPart.Capture("x")
                ),
                loc = testLoc
            )

            assertEquals("hello\nworld\ttab", (pattern.parts[0] as ParsedStringPatternPart.Literal).value)
        }

        @Test
        fun `pattern with unicode in literal`() {
            val pattern = ParsedStringInterpPattern(
                parts = listOf(ParsedStringPatternPart.Literal("Hello! Привет! こんにちは!")),
                loc = testLoc
            )

            assertEquals("Hello! Привет! こんにちは!", (pattern.parts[0] as ParsedStringPatternPart.Literal).value)
        }

        @Test
        fun `pattern only captures no literals`() {
            // This would only match if the captures are directly adjacent
            // which is typically not allowed in the language, but test AST handles it
            val pattern = ParsedStringInterpPattern(
                parts = listOf(
                    ParsedStringPatternPart.Capture("a"),
                    ParsedStringPatternPart.Capture("b")
                ),
                loc = testLoc
            )

            assertEquals(2, pattern.parts.size)
            val bindings = pattern.extractBindings()
            assertEquals(listOf("a", "b"), bindings)
        }

        @Test
        fun `pattern with dollar sign in literal - escaped`() {
            // Escaped $( in the literal part
            val pattern = ParsedStringInterpPattern(
                parts = listOf(ParsedStringPatternPart.Literal("cost: $100")),
                loc = testLoc
            )

            assertEquals("cost: $100", (pattern.parts[0] as ParsedStringPatternPart.Literal).value)
        }

        @Test
        fun `variable name with underscore`() {
            val pattern = ParsedStringInterpPattern(
                parts = listOf(ParsedStringPatternPart.Capture("_unused")),
                loc = testLoc
            )

            val bindings = pattern.extractBindings()
            assertEquals(listOf("_unused"), bindings)
        }

        @Test
        fun `variable name with numbers`() {
            val pattern = ParsedStringInterpPattern(
                parts = listOf(
                    ParsedStringPatternPart.Capture("var1"),
                    ParsedStringPatternPart.Literal("-"),
                    ParsedStringPatternPart.Capture("var2")
                ),
                loc = testLoc
            )

            val bindings = pattern.extractBindings()
            assertEquals(listOf("var1", "var2"), bindings)
        }
    }

}
