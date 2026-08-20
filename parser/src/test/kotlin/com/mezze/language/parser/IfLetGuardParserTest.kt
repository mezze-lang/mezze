package com.mezze.language.parser

import com.mezze.language.parsedast.*
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

/**
 * Tests for parsing 'if let' guards in match expressions.
 * Syntax: match x is
 *           Pattern if let SomePattern = expr -> body
 */
class IfLetGuardParserTest {

    private val parser = MezzeParser()

    // ========== Basic if let Guards ==========

    @Test
    fun `parse match with simple if let guard`() {
        val code = """
            match x is
              n if let Some {val = v} = getValue {x = n} -> v
              _ -> 0
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        assertEquals(2, matchExpr.cases.size)

        val firstCase = matchExpr.cases[0]
        assertEquals(1, firstCase.patterns.size)
        assertTrue(firstCase.patterns[0] is ParsedVariablePattern)

        // Should have if-let bindings
        assertEquals(1, firstCase.ifLetBindings.size)
        val binding = firstCase.ifLetBindings[0]
        assertTrue(binding.pattern is ParsedConstructorPattern)
        val constructorPattern = binding.pattern as ParsedConstructorPattern
        assertEquals("Some", constructorPattern.constructor)

        // No regular guard
        assertNull(firstCase.guard)

        // Body should reference v
        assertTrue(firstCase.body is ParsedIdentifier)
        assertEquals("v", (firstCase.body as ParsedIdentifier).localName)
    }

    @Test
    fun `parse match with if let using variable pattern`() {
        val code = """
            match x is
              _ if let result = compute {x = 5} -> result
              _ -> 0
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstCase = matchExpr.cases[0]

        assertEquals(1, firstCase.ifLetBindings.size)
        assertTrue(firstCase.ifLetBindings[0].pattern is ParsedVariablePattern)
        assertEquals("result", (firstCase.ifLetBindings[0].pattern as ParsedVariablePattern).name)
    }

    @Test
    fun `parse match with if let using record pattern`() {
        val code = """
            match x is
              _ if let {name, age} = getPerson {id = 1} -> name
              _ -> "unknown"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstCase = matchExpr.cases[0]

        assertEquals(1, firstCase.ifLetBindings.size)
        assertTrue(firstCase.ifLetBindings[0].pattern is ParsedRecordPattern)
        val recordPattern = firstCase.ifLetBindings[0].pattern as ParsedRecordPattern
        assertEquals(2, recordPattern.entries.size)
    }

    @Test
    fun `parse match with if let using literal pattern`() {
        val code = """
            match x is
              _ if let 42 = getValue {x = 1} -> "matched"
              _ -> "not matched"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstCase = matchExpr.cases[0]

        assertEquals(1, firstCase.ifLetBindings.size)
        assertTrue(firstCase.ifLetBindings[0].pattern is ParsedLiteralPattern)
    }

    @Test
    fun `parse match with if let using identifier expression`() {
        val code = """
            match x is
              _ if let Some {val = v} = maybeValue -> v
              _ -> 0
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstCase = matchExpr.cases[0]

        assertEquals(1, firstCase.ifLetBindings.size)
        assertTrue(firstCase.ifLetBindings[0].expr is ParsedIdentifier)
        assertEquals("maybeValue", (firstCase.ifLetBindings[0].expr as ParsedIdentifier).localName)
    }

    @Test
    fun `parse match with if let using field access`() {
        val code = """
            match x is
              _ if let Some {val = v} = obj.field -> v
              _ -> 0
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstCase = matchExpr.cases[0]

        assertEquals(1, firstCase.ifLetBindings.size)
        assertTrue(firstCase.ifLetBindings[0].expr is ParsedMemberAccess)
    }

    // ========== Backward Compatibility ==========

    @Test
    fun `parse regular guard without if let still works`() {
        val code = """
            match x is
              n if n > 0 -> "positive"
              _ -> "non-positive"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstCase = matchExpr.cases[0]

        // No if-let bindings
        assertTrue(firstCase.ifLetBindings.isEmpty())

        // Has regular guard
        assertNotNull(firstCase.guard)
    }

    @Test
    fun `guard expression may end in a record-argument call`() {
        // Regression: the guard's trailing `->` is the arm arrow, not a lambda,
        // so a `{…}` in postfix position (here `.not {}`) must parse as call
        // args. Previously the parser mistook `{} ->` for a lambda and failed
        // with "Expected '->' but got '{'".
        val code = """
            match x is
              n if (n == 0).not {} -> "non-zero"
              _ -> "zero"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstCase = matchExpr.cases[0]
        assertTrue(firstCase.ifLetBindings.isEmpty())
        // The whole `(n == 0).not {}` is the guard, ending in a function call.
        assertTrue(
            firstCase.guard is ParsedFunctionCall,
            "guard should be a call `(n == 0).not {}`, got ${firstCase.guard}"
        )
    }

    @Test
    fun `parse match without any guard still works`() {
        val code = """
            match x is
              Some {val = v} -> v
              None -> 0
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch

        for (case in matchExpr.cases) {
            assertTrue(case.ifLetBindings.isEmpty())
            assertNull(case.guard)
        }
    }

    // ========== Multiple Match Cases ==========

    @Test
    fun `parse multiple cases with different guard types`() {
        val code = """
            match x is
              0 -> "zero"
              n if n < 0 -> "negative"
              n if let Some {val = v} = check {x = n} -> v
              _ -> "other"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        assertEquals(4, matchExpr.cases.size)

        // Case 0: literal, no guard
        assertTrue(matchExpr.cases[0].ifLetBindings.isEmpty())
        assertNull(matchExpr.cases[0].guard)

        // Case 1: regular guard only
        assertTrue(matchExpr.cases[1].ifLetBindings.isEmpty())
        assertNotNull(matchExpr.cases[1].guard)

        // Case 2: if-let only
        assertEquals(1, matchExpr.cases[2].ifLetBindings.size)
        assertNull(matchExpr.cases[2].guard)

        // Case 3: wildcard, no guard
        assertTrue(matchExpr.cases[3].ifLetBindings.isEmpty())
        assertNull(matchExpr.cases[3].guard)
    }

    // ========== Source Location Tests ==========

    @Test
    fun `if let guard has source location`() {
        val code = """
            match x is
              n if let Some {val = v} = getValue {x = n} -> v
              _ -> 0
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val binding = matchExpr.cases[0].ifLetBindings[0]

        // Should have source location
        assertNotNull(binding.loc)
    }

    // ========== Multiple If-Let Bindings (chained with &&) ==========

    @Test
    fun `parse multiple if let bindings with &&`() {
        val code = """
            match x is
              n if let Some {val = a} = getA {x = n} && let Some {val = b} = getB {y = a} -> a + b
              _ -> 0
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstCase = matchExpr.cases[0]

        // Should have 2 if-let bindings
        assertEquals(2, firstCase.ifLetBindings.size)

        // First binding: Some {val = a} = getA {x = n}
        val binding1 = firstCase.ifLetBindings[0]
        assertTrue(binding1.pattern is ParsedConstructorPattern)
        assertEquals("Some", (binding1.pattern as ParsedConstructorPattern).constructor)

        // Second binding: Some {val = b} = getB {y = a}
        val binding2 = firstCase.ifLetBindings[1]
        assertTrue(binding2.pattern is ParsedConstructorPattern)
        assertEquals("Some", (binding2.pattern as ParsedConstructorPattern).constructor)

        // No regular guard
        assertNull(firstCase.guard)
    }

    @Test
    fun `parse three if let bindings with &&`() {
        val code = """
            match x is
              n if let Some {val = a} = f1 {x = n} && let Some {val = b} = f2 {x = a} && let Some {val = c} = f3 {x = b} -> a + b + c
              _ -> 0
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstCase = matchExpr.cases[0]

        // Should have 3 if-let bindings
        assertEquals(3, firstCase.ifLetBindings.size)
    }

    // ========== Error Cases ==========

    @Test
    fun `parse if let without equals fails`() {
        val code = """
            match x is
              n if let Some {val = v} getValue {x = n} -> v
              _ -> 0
        """.trimIndent()

        assertFailsWith<ParseException> {
            parser.parseParsedModule(code)
        }
    }

    @Test
    fun `parse if let without expression fails`() {
        val code = """
            match x is
              n if let Some {val = v} = -> v
              _ -> 0
        """.trimIndent()

        assertFailsWith<ParseException> {
            parser.parseParsedModule(code)
        }
    }

    @Test
    fun `|| is not allowed for if let chaining - gives helpful error`() {
        // || should NOT work for chaining if-let bindings - only && is allowed
        val code = """
            match x is
              n if let Some {val = a} = getA {x = n} || let Some {val = b} = getB {x = n} -> a
              _ -> 0
        """.trimIndent()

        // This should fail with a helpful error message
        val exception = assertFailsWith<ParseException> {
            parser.parseParsedModule(code)
        }
        // Error message should explain that || is not allowed and suggest using &&
        assertTrue(exception.message?.contains("||") == true,
            "Error should mention '||'. Actual: ${exception.message}")
        assertTrue(exception.message?.contains("&&") == true,
            "Error should suggest using '&&'. Actual: ${exception.message}")
        // Verify error has line/column info
        assertTrue(exception.message?.contains("-->") == true || exception.line > 0,
            "Error should have source location. Actual: ${exception.message}")
    }

    @Test
    fun `&& without let after if let fails with helpful message`() {
        // After if let, && must be followed by let
        val code = """
            match x is
              n if let Some {val = a} = getA {x = n} && a > 0 -> a
              _ -> 0
        """.trimIndent()

        val exception = assertFailsWith<ParseException> {
            parser.parseParsedModule(code)
        }
        // Error message should mention 'let' is expected after &&
        assertTrue(exception.message?.contains("let") == true,
            "Error should mention 'let' expected after '&&'. Actual: ${exception.message}")
    }
}
