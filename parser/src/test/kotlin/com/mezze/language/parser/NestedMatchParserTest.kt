package com.mezze.language.parser

import com.mezze.language.parsedast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for nested match expressions and wildcard handling.
 *
 * These tests verify that the parser correctly handles indentation-based
 * scoping for nested match expressions, ensuring wildcards at the same
 * indentation level as outer cases are parsed as part of the outer match,
 * not the inner match.
 */
class NestedMatchParserTest {

    private val parser = MezzeParser()

    @Test
    fun `test simple match with wildcard`() {
        val source = """
let simpleWild = {v} -> match v is
  A -> 1
  _ -> 2

simpleWild
""".trim()

        val module = parser.parseParsedModule(source)
        assertNotNull(module)

        // Find the let binding
        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        assertEquals("simpleWild", letBinding.variableName)

        // Verify it's a lambda with a match expression
        val lambda = letBinding.value as ParsedLambda
        val matchExpr = lambda.body as ParsedMatch

        // Should have 2 cases: A and wildcard
        assertEquals(2, matchExpr.cases.size, "Match should have exactly 2 cases")

        // First case: A -> 1
        val firstCase = matchExpr.cases[0]
        assertEquals(1, firstCase.patterns.size)
        assertTrue(firstCase.patterns[0] is ParsedConstructorPattern)
        assertEquals("A", (firstCase.patterns[0] as ParsedConstructorPattern).constructor)
        assertTrue(firstCase.body is ParsedIntLit)
        assertEquals(1L, (firstCase.body as ParsedIntLit).value)

        // Second case: _ -> 2
        val secondCase = matchExpr.cases[1]
        assertEquals(1, secondCase.patterns.size)
        assertTrue(secondCase.patterns[0] is ParsedWildcardPattern)
        assertTrue(secondCase.body is ParsedIntLit)
        assertEquals(2L, (secondCase.body as ParsedIntLit).value)
    }

    @Test
    fun `test nested match with wildcards - multiWild case`() {
        val source = """
let multiWild = {v1, v2} -> match v1 is
  A -> match v2 is
    B -> 1
    _ -> 2
  _ -> 3

multiWild
""".trim()

        val module = parser.parseParsedModule(source)
        assertNotNull(module)

        // Find the let binding
        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        assertEquals("multiWild", letBinding.variableName)

        // Verify it's a lambda with a match expression
        val lambda = letBinding.value as ParsedLambda
        val outerMatch = lambda.body as ParsedMatch

        // Outer match should have 2 cases: A and wildcard
        assertEquals(2, outerMatch.cases.size, "Outer match should have exactly 2 cases")

        // First case: A -> (inner match)
        val firstCase = outerMatch.cases[0]
        assertEquals(1, firstCase.patterns.size)
        assertTrue(firstCase.patterns[0] is ParsedConstructorPattern)
        assertEquals("A", (firstCase.patterns[0] as ParsedConstructorPattern).constructor)

        // The expression should be an inner match
        assertTrue(firstCase.body is ParsedMatch, "First case should contain a nested match expression")
        val innerMatch = firstCase.body as ParsedMatch

        // Inner match should have 2 cases: B and wildcard
        assertEquals(2, innerMatch.cases.size, "Inner match should have exactly 2 cases")

        // Inner first case: B -> 1
        val innerFirstCase = innerMatch.cases[0]
        assertTrue(innerFirstCase.patterns[0] is ParsedConstructorPattern)
        assertEquals("B", (innerFirstCase.patterns[0] as ParsedConstructorPattern).constructor)
        assertTrue(innerFirstCase.body is ParsedIntLit)
        assertEquals(1L, (innerFirstCase.body as ParsedIntLit).value)

        // Inner second case: _ -> 2
        val innerSecondCase = innerMatch.cases[1]
        assertTrue(innerSecondCase.patterns[0] is ParsedWildcardPattern)
        assertTrue(innerSecondCase.body is ParsedIntLit)
        assertEquals(2L, (innerSecondCase.body as ParsedIntLit).value)

        // Second case of outer match: _ -> 3
        val secondCase = outerMatch.cases[1]
        assertEquals(1, secondCase.patterns.size)
        assertTrue(secondCase.patterns[0] is ParsedWildcardPattern, "Second outer case should be a wildcard")
        assertTrue(secondCase.body is ParsedIntLit)
        assertEquals(3L, (secondCase.body as ParsedIntLit).value, "Second outer case should return 3")
    }

    @Test
    fun `test deeply nested match with wildcards`() {
        val source = """
let deepNest = {v1, v2, v3} -> match v1 is
  A -> match v2 is
    B -> match v3 is
      C -> 1
      _ -> 2
    _ -> 3
  _ -> 4

deepNest
""".trim()

        val module = parser.parseParsedModule(source)
        assertNotNull(module)

        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        val lambda = letBinding.value as ParsedLambda
        val outerMatch = lambda.body as ParsedMatch

        // Outer match: 2 cases (A, wildcard)
        assertEquals(2, outerMatch.cases.size, "Outer match should have 2 cases")

        // Get middle match from first case
        val middleMatch = outerMatch.cases[0].body as ParsedMatch
        assertEquals(2, middleMatch.cases.size, "Middle match should have 2 cases")

        // Get inner match from middle's first case
        val innerMatch = middleMatch.cases[0].body as ParsedMatch
        assertEquals(2, innerMatch.cases.size, "Inner match should have 2 cases")

        // Verify values at each level
        assertEquals(1L, (innerMatch.cases[0].body as ParsedIntLit).value)  // C -> 1
        assertEquals(2L, (innerMatch.cases[1].body as ParsedIntLit).value)  // _ -> 2 (inner)
        assertEquals(3L, (middleMatch.cases[1].body as ParsedIntLit).value) // _ -> 3 (middle)
        assertEquals(4L, (outerMatch.cases[1].body as ParsedIntLit).value)  // _ -> 4 (outer)
    }

    @Test
    fun `test nested match with constructor patterns`() {
        val source = """
let processList = {items} -> match items is
  Cons {head, tail} -> match head is
    Some -> 1
    _ -> 0
  _ -> 0

processList
""".trim()

        val module = parser.parseParsedModule(source)
        assertNotNull(module)

        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        val lambda = letBinding.value as ParsedLambda
        val outerMatch = lambda.body as ParsedMatch

        // Outer match should have 2 cases
        assertEquals(2, outerMatch.cases.size)

        // First case: Cons {head, tail}
        val firstCase = outerMatch.cases[0]
        val constructorPattern = firstCase.patterns[0] as ParsedConstructorPattern
        assertEquals("Cons", constructorPattern.constructor)
        assertNotNull(constructorPattern.payload)

        // Should contain nested match
        assertTrue(firstCase.body is ParsedMatch)
        val innerMatch = firstCase.body as ParsedMatch
        assertEquals(2, innerMatch.cases.size)

        // Second case: wildcard at outer level
        assertTrue(outerMatch.cases[1].patterns[0] is ParsedWildcardPattern)
    }

    @Test
    fun `test nested match with multiple constructors at same level`() {
        val source = """
let multiCase = {v1, v2} -> match v1 is
  A -> match v2 is
    X -> 1
    Y -> 2
    Z -> 3
  B -> 4
  C -> 5

multiCase
""".trim()

        val module = parser.parseParsedModule(source)
        assertNotNull(module)

        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        val lambda = letBinding.value as ParsedLambda
        val outerMatch = lambda.body as ParsedMatch

        // Outer match should have 3 cases: A, B, C
        assertEquals(3, outerMatch.cases.size, "Outer match should have 3 cases")

        // First case contains nested match
        val innerMatch = outerMatch.cases[0].body as ParsedMatch
        assertEquals(3, innerMatch.cases.size, "Inner match should have 3 cases")

        // Second and third cases at outer level
        assertEquals("B", (outerMatch.cases[1].patterns[0] as ParsedConstructorPattern).constructor)
        assertEquals("C", (outerMatch.cases[2].patterns[0] as ParsedConstructorPattern).constructor)
    }

    @Test
    fun `test nested match preserves indentation structure`() {
        val source = """
let chain = {v} -> match v is
  First {next} -> match next is
    Second {val} -> val
    _ -> 0
  _ -> 0

chain
""".trim()

        val module = parser.parseParsedModule(source)
        assertNotNull(module)

        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        val lambda = letBinding.value as ParsedLambda
        val outerMatch = lambda.body as ParsedMatch

        // Verify structure: 2 outer cases
        assertEquals(2, outerMatch.cases.size)

        // First outer case has nested match
        val innerMatch = outerMatch.cases[0].body as ParsedMatch
        assertEquals(2, innerMatch.cases.size)

        // Verify patterns
        assertEquals("First", (outerMatch.cases[0].patterns[0] as ParsedConstructorPattern).constructor)
        assertEquals("Second", (innerMatch.cases[0].patterns[0] as ParsedConstructorPattern).constructor)

        // Both outer and inner have wildcards
        assertTrue(innerMatch.cases[1].patterns[0] is ParsedWildcardPattern)
        assertTrue(outerMatch.cases[1].patterns[0] is ParsedWildcardPattern)
    }

    @Test
    fun `test match with only constructors no wildcards`() {
        val source = """
let noWild = {v} -> match v is
  A -> 1
  B -> 2
  C -> 3

noWild
""".trim()

        val module = parser.parseParsedModule(source)
        assertNotNull(module)

        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        val lambda = letBinding.value as ParsedLambda
        val matchExpr = lambda.body as ParsedMatch

        // Should have 3 cases, all constructors
        assertEquals(3, matchExpr.cases.size)
        matchExpr.cases.forEach { case ->
            assertTrue(case.patterns[0] is ParsedConstructorPattern)
        }
    }

    @Test
    fun `test nested match where inner has no wildcard but outer does`() {
        val source = """
let asymmetric = {v1, v2} -> match v1 is
  A -> match v2 is
    X -> 1
    Y -> 2
  _ -> 3

asymmetric
""".trim()

        val module = parser.parseParsedModule(source)
        assertNotNull(module)

        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        val lambda = letBinding.value as ParsedLambda
        val outerMatch = lambda.body as ParsedMatch

        // Outer: 2 cases (A with nested match, wildcard)
        assertEquals(2, outerMatch.cases.size)

        // Inner: 2 cases (X, Y) - no wildcard
        val innerMatch = outerMatch.cases[0].body as ParsedMatch
        assertEquals(2, innerMatch.cases.size)
        assertTrue(innerMatch.cases[0].patterns[0] is ParsedConstructorPattern)
        assertTrue(innerMatch.cases[1].patterns[0] is ParsedConstructorPattern)

        // Outer has wildcard
        assertTrue(outerMatch.cases[1].patterns[0] is ParsedWildcardPattern)
    }

    @Test
    fun `test single case match with wildcard`() {
        val source = """
let catchAll = {v} -> match v is
  _ -> 42

catchAll
""".trim()

        val module = parser.parseParsedModule(source)
        assertNotNull(module)

        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        val lambda = letBinding.value as ParsedLambda
        val matchExpr = lambda.body as ParsedMatch

        // Should have 1 case with wildcard
        assertEquals(1, matchExpr.cases.size)
        assertTrue(matchExpr.cases[0].patterns[0] is ParsedWildcardPattern)
        assertEquals(42L, (matchExpr.cases[0].body as ParsedIntLit).value)
    }
}
