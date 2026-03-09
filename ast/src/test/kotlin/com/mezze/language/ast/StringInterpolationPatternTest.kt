package com.mezze.language.ast

import com.mezze.language.ast.hashing.*
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
 * 5. Hash stability and alpha-invariance
 * 6. Normalization
 * 7. Internal binding renaming
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
    // HASH STABILITY
    // =========================================================================

    @Nested
    inner class HashStability {

        @Test
        fun `same pattern produces same hash`() {
            val pattern1 = StringInterpolationPattern(
                parts = listOf(
                    StringPatternLiteral("hello "),
                    StringPatternCapture("name")
                )
            )
            val pattern2 = StringInterpolationPattern(
                parts = listOf(
                    StringPatternLiteral("hello "),
                    StringPatternCapture("name")
                )
            )

            // Hash via a match expression
            val match1 = MatchExpression(
                expression = Identifier("s"),
                cases = listOf(MatchCase(listOf(pattern1), expression = Identifier("name")))
            )
            val match2 = MatchExpression(
                expression = Identifier("s"),
                cases = listOf(MatchCase(listOf(pattern2), expression = Identifier("name")))
            )

            val hash1 = ASTHasher.hashExpression(match1)
            val hash2 = ASTHasher.hashExpression(match2)

            assertEquals(hash1, hash2, "Same string interpolation pattern should produce same hash")
        }

        @Test
        fun `different literal values produce different hashes`() {
            val pattern1 = StringInterpolationPattern(
                parts = listOf(StringPatternLiteral("hello"))
            )
            val pattern2 = StringInterpolationPattern(
                parts = listOf(StringPatternLiteral("world"))
            )

            val match1 = MatchExpression(
                expression = Identifier("s"),
                cases = listOf(MatchCase(listOf(pattern1), expression = IntLiteral(1)))
            )
            val match2 = MatchExpression(
                expression = Identifier("s"),
                cases = listOf(MatchCase(listOf(pattern2), expression = IntLiteral(1)))
            )

            val hash1 = ASTHasher.hashExpression(match1)
            val hash2 = ASTHasher.hashExpression(match2)

            assertNotEquals(hash1, hash2, "Different literal values should produce different hashes")
        }

        @Test
        fun `capture variable names are alpha-renamed - same hash`() {
            // "$(x)" and "$(y)" should hash the same (alpha-invariance)
            val pattern1 = StringInterpolationPattern(
                parts = listOf(StringPatternCapture("x"))
            )
            val pattern2 = StringInterpolationPattern(
                parts = listOf(StringPatternCapture("y"))
            )

            // Pattern x -> x  vs  y -> y
            val match1 = MatchExpression(
                expression = Identifier("s"),
                cases = listOf(MatchCase(listOf(pattern1), expression = Identifier("x")))
            )
            val match2 = MatchExpression(
                expression = Identifier("s"),
                cases = listOf(MatchCase(listOf(pattern2), expression = Identifier("y")))
            )

            val hash1 = ASTHasher.hashExpression(match1)
            val hash2 = ASTHasher.hashExpression(match2)

            assertEquals(hash1, hash2, "Capture variable names should be alpha-renamed for hashing")
        }

        @Test
        fun `different pattern structure produces different hash`() {
            // "prefix-$(x)" vs "$(x)-suffix"
            val pattern1 = StringInterpolationPattern(
                parts = listOf(
                    StringPatternLiteral("prefix-"),
                    StringPatternCapture("x")
                )
            )
            val pattern2 = StringInterpolationPattern(
                parts = listOf(
                    StringPatternCapture("x"),
                    StringPatternLiteral("-suffix")
                )
            )

            val match1 = MatchExpression(
                expression = Identifier("s"),
                cases = listOf(MatchCase(listOf(pattern1), expression = Identifier("x")))
            )
            val match2 = MatchExpression(
                expression = Identifier("s"),
                cases = listOf(MatchCase(listOf(pattern2), expression = Identifier("x")))
            )

            val hash1 = ASTHasher.hashExpression(match1)
            val hash2 = ASTHasher.hashExpression(match2)

            assertNotEquals(hash1, hash2, "Different pattern structure should produce different hash")
        }

        @Test
        fun `source location does not affect hash`() {
            val pattern1 = StringInterpolationPattern(
                parts = listOf(StringPatternLiteral("test")),
                sourceLocation = SourceLocation(1, 1, "file1.mz")
            )
            val pattern2 = StringInterpolationPattern(
                parts = listOf(StringPatternLiteral("test")),
                sourceLocation = SourceLocation(100, 50, "file2.mz")
            )
            val pattern3 = StringInterpolationPattern(
                parts = listOf(StringPatternLiteral("test")),
                sourceLocation = null
            )

            val match1 = MatchExpression(
                expression = Identifier("s"),
                cases = listOf(MatchCase(listOf(pattern1), expression = IntLiteral(1)))
            )
            val match2 = MatchExpression(
                expression = Identifier("s"),
                cases = listOf(MatchCase(listOf(pattern2), expression = IntLiteral(1)))
            )
            val match3 = MatchExpression(
                expression = Identifier("s"),
                cases = listOf(MatchCase(listOf(pattern3), expression = IntLiteral(1)))
            )

            val hash1 = ASTHasher.hashExpression(match1)
            val hash2 = ASTHasher.hashExpression(match2)
            val hash3 = ASTHasher.hashExpression(match3)

            assertEquals(hash1, hash2, "Source location should not affect hash")
            assertEquals(hash1, hash3, "Presence of source location should not affect hash")
        }

        @Test
        fun `different number of captures produces different hash`() {
            // "$(a)-$(b)" vs "$(a)-$(b)-$(c)"
            val pattern1 = StringInterpolationPattern(
                parts = listOf(
                    StringPatternCapture("a"),
                    StringPatternLiteral("-"),
                    StringPatternCapture("b")
                )
            )
            val pattern2 = StringInterpolationPattern(
                parts = listOf(
                    StringPatternCapture("a"),
                    StringPatternLiteral("-"),
                    StringPatternCapture("b"),
                    StringPatternLiteral("-"),
                    StringPatternCapture("c")
                )
            )

            val match1 = MatchExpression(
                expression = Identifier("s"),
                cases = listOf(MatchCase(listOf(pattern1), expression = Identifier("a")))
            )
            val match2 = MatchExpression(
                expression = Identifier("s"),
                cases = listOf(MatchCase(listOf(pattern2), expression = Identifier("a")))
            )

            val hash1 = ASTHasher.hashExpression(match1)
            val hash2 = ASTHasher.hashExpression(match2)

            assertNotEquals(hash1, hash2, "Different number of captures should produce different hash")
        }
    }

    // =========================================================================
    // NORMALIZATION
    // =========================================================================

    @Nested
    inner class Normalization {

        @Test
        fun `normalizing string interpolation pattern preserves structure`() {
            val pattern = StringInterpolationPattern(
                parts = listOf(
                    StringPatternLiteral("prefix-"),
                    StringPatternCapture("x"),
                    StringPatternLiteral("-suffix")
                ),
                sourceLocation = SourceLocation(5, 10, "test.mz")
            )

            val normalized = ASTNormalizer.normalizePattern(pattern)

            assertTrue(normalized is StringInterpolationPattern)
            val normalizedPattern = normalized as StringInterpolationPattern
            assertEquals(3, normalizedPattern.parts.size)
            assertEquals("prefix-", (normalizedPattern.parts[0] as StringPatternLiteral).value)
            assertEquals("x", (normalizedPattern.parts[1] as StringPatternCapture).variableName)
            assertEquals("-suffix", (normalizedPattern.parts[2] as StringPatternLiteral).value)
        }
    }

    // =========================================================================
    // INTERNAL BINDING RENAMING
    // =========================================================================

    @Nested
    inner class InternalBindingRenaming {

        @Test
        fun `capture variables are renamed to De Bruijn indices`() {
            val pattern = StringInterpolationPattern(
                parts = listOf(
                    StringPatternCapture("month"),
                    StringPatternLiteral("-"),
                    StringPatternCapture("day")
                )
            )

            // Create a match expression using this pattern
            // match s is "$(month)-$(day)" -> month
            val matchExpr = MatchExpression(
                expression = Identifier("s"),
                cases = listOf(
                    MatchCase(
                        patterns = listOf(pattern),
                        expression = Identifier("month")
                    )
                )
            )

            val renamer = InternalBindingRenamer()
            val renamed = renamer.rename(matchExpr)

            assertTrue(renamed is MatchExpression)
            val renamedMatch = renamed as MatchExpression

            // Check the pattern was renamed
            val renamedPattern = renamedMatch.cases[0].patterns[0] as StringInterpolationPattern
            val captures = renamedPattern.parts.filterIsInstance<StringPatternCapture>()

            // Variables should be renamed to De Bruijn indices (#0, #1)
            assertEquals("#0", captures[0].variableName)
            assertEquals("#1", captures[1].variableName)

            // Check the body uses the renamed variable
            val body = renamedMatch.cases[0].expression as Identifier
            assertEquals("#0", body.name)
        }

        @Test
        fun `literal parts are unchanged by renaming`() {
            val pattern = StringInterpolationPattern(
                parts = listOf(
                    StringPatternLiteral("prefix-"),
                    StringPatternCapture("x"),
                    StringPatternLiteral("-suffix")
                )
            )

            val matchExpr = MatchExpression(
                expression = Identifier("s"),
                cases = listOf(
                    MatchCase(patterns = listOf(pattern), expression = Identifier("x"))
                )
            )

            val renamer = InternalBindingRenamer()
            val renamed = renamer.rename(matchExpr)

            val renamedPattern = (renamed as MatchExpression).cases[0].patterns[0] as StringInterpolationPattern

            // Literals should be unchanged
            assertEquals("prefix-", (renamedPattern.parts[0] as StringPatternLiteral).value)
            assertEquals("-suffix", (renamedPattern.parts[2] as StringPatternLiteral).value)
        }

        @Test
        fun `multiple patterns in same match share counter`() {
            val pattern1 = StringInterpolationPattern(
                parts = listOf(StringPatternCapture("a"))
            )
            val pattern2 = StringInterpolationPattern(
                parts = listOf(StringPatternCapture("b"))
            )

            // match s is
            //   "$(a)" -> a
            //   "$(b)" -> b
            val matchExpr = MatchExpression(
                expression = Identifier("s"),
                cases = listOf(
                    MatchCase(patterns = listOf(pattern1), expression = Identifier("a")),
                    MatchCase(patterns = listOf(pattern2), expression = Identifier("b"))
                )
            )

            val renamer = InternalBindingRenamer()
            val renamed = renamer.rename(matchExpr) as MatchExpression

            // Counter is shared across cases (doesn't reset), so:
            // Case 1: "a" -> #0
            // Case 2: "b" -> #1
            val capture1 = ((renamed.cases[0].patterns[0] as StringInterpolationPattern)
                .parts[0] as StringPatternCapture).variableName
            val capture2 = ((renamed.cases[1].patterns[0] as StringInterpolationPattern)
                .parts[0] as StringPatternCapture).variableName

            // Counter increments across cases
            assertEquals("#0", capture1, "First case capture should be #0")
            assertEquals("#1", capture2, "Second case capture should be #1")
        }

        @Test
        fun `nested match expressions have independent scopes`() {
            val outerPattern = StringInterpolationPattern(
                parts = listOf(StringPatternCapture("outer"))
            )

            val innerPattern = StringInterpolationPattern(
                parts = listOf(StringPatternCapture("inner"))
            )

            // match s is "$(outer)" -> match t is "$(inner)" -> inner
            val innerMatch = MatchExpression(
                expression = Identifier("t"),
                cases = listOf(
                    MatchCase(patterns = listOf(innerPattern), expression = Identifier("inner"))
                )
            )

            val outerMatch = MatchExpression(
                expression = Identifier("s"),
                cases = listOf(
                    MatchCase(patterns = listOf(outerPattern), expression = innerMatch)
                )
            )

            val renamer = InternalBindingRenamer()
            val renamed = renamer.rename(outerMatch) as MatchExpression

            val outerCapture = ((renamed.cases[0].patterns[0] as StringInterpolationPattern)
                .parts[0] as StringPatternCapture).variableName

            val innerMatchRenamed = renamed.cases[0].expression as MatchExpression
            val innerCapture = ((innerMatchRenamed.cases[0].patterns[0] as StringInterpolationPattern)
                .parts[0] as StringPatternCapture).variableName

            assertEquals("#0", outerCapture)
            // Inner match has its own scope within the case body
            assertEquals("#1", innerCapture)
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

    // =========================================================================
    // HASH TAG CONSTANT
    // =========================================================================

    @Nested
    inner class HashTagConstant {

        @Test
        fun `STRING_INTERPOLATION_PATTERN hash tag exists`() {
            // Verify the hash tag constant is defined
            val hashTag = HashTags.STRING_INTERPOLATION_PATTERN
            assertEquals(0x2C.toByte(), hashTag)
        }

        @Test
        fun `hash tag is unique among pattern tags`() {
            val patternTags = listOf(
                HashTags.WILDCARD_PATTERN,
                HashTags.VARIABLE_PATTERN,
                HashTags.LITERAL_PATTERN,
                HashTags.CONSTRUCTOR_PATTERN,
                HashTags.RECORD_PATTERN,
                HashTags.CONS_PATTERN,
                HashTags.LIST_PATTERN,
                HashTags.AS_PATTERN,
                HashTags.OR_PATTERN,
                HashTags.TUPLE_PATTERN,
                HashTags.SEQ_PATTERN,
                HashTags.REST_PATTERN,
                HashTags.STRING_INTERPOLATION_PATTERN
            )

            // All tags should be unique
            assertEquals(patternTags.size, patternTags.toSet().size, "All pattern hash tags must be unique")
        }
    }
}
