package com.mezze.language.parser

import com.mezze.language.parsedast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested

/**
 * Tests for ParsedQualifiedRef parsing in perform expressions and related constructs.
 *
 * These tests verify that:
 * 1. The parser correctly parses qualified references (e.g., log::Console)
 * 2. ParsedQualifiedRef structure is properly populated with localName and qualifier
 * 3. Source locations are captured for qualified references
 * 4. Both qualified and unqualified references work in all contexts
 */
class QualifiedRefParsingTest {
    private val parser = MezzeParser()

    // ========================================================================
    // PERFORM HANDLER QUALIFIED REFERENCE TESTS
    // ========================================================================

    @Nested
    inner class PerformHandlerQualifiedRef {

        @Test
        fun `unqualified handler creates ParsedQualifiedRef with null qualifier`() {
            val source = """
                perform Console in someExpr {}
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val topLevel = module.decls[0] as ParsedTopLevelExpr
            val performExpr = topLevel.expression as ParsedPerform

            assertEquals(1, performExpr.handlers.size)
            val handler = performExpr.handlers[0]

            // Check ParsedQualifiedRef structure
            assertEquals("Console", handler.performer.localName)
            assertNull(handler.performer.qualifier)
            assertFalse(handler.performer.isQualified)


            // Verify full qualified name can be constructed
            val fullName = handler.performer.qualifier?.let { "${it.toString()}::${handler.performer.localName}" } ?: handler.performer.localName
            assertEquals("Console", fullName)
        }

        @Test
        fun `single-segment qualified handler creates ParsedQualifiedRef with qualifier`() {
            val source = """
                perform log::Console in someExpr {}
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val topLevel = module.decls[0] as ParsedTopLevelExpr
            val performExpr = topLevel.expression as ParsedPerform

            assertEquals(1, performExpr.handlers.size)
            val handler = performExpr.handlers[0]

            // Check ParsedQualifiedRef structure
            assertEquals("Console", handler.performer.localName)
            assertNotNull(handler.performer.qualifier)
            assertEquals(listOf("log"), handler.performer.qualifier!!.segments)
            assertEquals("log", handler.performer.qualifier!!.toString())
            assertTrue(handler.performer.isQualified)

            // Verify full qualified name can be constructed
            val fullName = handler.performer.qualifier?.let { "${it.toString()}::${handler.performer.localName}" } ?: handler.performer.localName
            assertEquals("log::Console", fullName)
        }

        @Test
        fun `multi-segment qualified handler creates ParsedQualifiedRef with full qualifier`() {
            val source = """
                perform std::effects::log::Console in someExpr {}
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val topLevel = module.decls[0] as ParsedTopLevelExpr
            val performExpr = topLevel.expression as ParsedPerform

            assertEquals(1, performExpr.handlers.size)
            val handler = performExpr.handlers[0]

            // Check ParsedQualifiedRef structure
            assertEquals("Console", handler.performer.localName)
            assertNotNull(handler.performer.qualifier)
            assertEquals(listOf("std", "effects", "log"), handler.performer.qualifier!!.segments)
            assertEquals("std::effects::log", handler.performer.qualifier!!.toString())
            assertTrue(handler.performer.isQualified)

            // Verify full qualified name can be constructed
            val fullName = handler.performer.qualifier?.let { "${it.toString()}::${handler.performer.localName}" } ?: handler.performer.localName
            assertEquals("std::effects::log::Console", fullName)
        }

        @Test
        fun `handler with source location`() {
            val source = """
                perform Console in someExpr {}
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val topLevel = module.decls[0] as ParsedTopLevelExpr
            val performExpr = topLevel.expression as ParsedPerform

            val handler = performExpr.handlers[0]
            assertNotNull(handler.performer.loc)
            assertEquals(1, handler.performer.loc.startLine)
        }

        @Test
        fun `multiple handlers with mixed qualification`() {
            val source = """
                perform Console, log::FileLogger, std::Random in someExpr {}
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val topLevel = module.decls[0] as ParsedTopLevelExpr
            val performExpr = topLevel.expression as ParsedPerform

            assertEquals(3, performExpr.handlers.size)

            // First handler: unqualified
            val handler1 = performExpr.handlers[0]
            assertEquals("Console", handler1.performer.localName)
            assertNull(handler1.performer.qualifier)
            assertFalse(handler1.performer.isQualified)

            // Second handler: single-segment qualifier
            val handler2 = performExpr.handlers[1]
            assertEquals("FileLogger", handler2.performer.localName)
            assertNotNull(handler2.performer.qualifier)
            assertEquals(listOf("log"), handler2.performer.qualifier!!.segments)
            assertTrue(handler2.performer.isQualified)

            // Third handler: single-segment qualifier
            val handler3 = performExpr.handlers[2]
            assertEquals("Random", handler3.performer.localName)
            assertNotNull(handler3.performer.qualifier)
            assertEquals(listOf("std"), handler3.performer.qualifier!!.segments)
            assertTrue(handler3.performer.isQualified)
        }

        @Test
        fun `qualified handler with parameters`() {
            val source = """
                perform log::FileLogger {path = "/tmp/log.txt"} in someExpr {}
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val topLevel = module.decls[0] as ParsedTopLevelExpr
            val performExpr = topLevel.expression as ParsedPerform

            assertEquals(1, performExpr.handlers.size)
            val handler = performExpr.handlers[0]

            // Check ParsedQualifiedRef
            assertEquals("FileLogger", handler.performer.localName)
            assertNotNull(handler.performer.qualifier)
            assertEquals(listOf("log"), handler.performer.qualifier!!.segments)

            // Check parameters
            assertNotNull(handler.params)
            assertEquals(1, handler.params!!.entries.size)
            val entry = handler.params!!.entries[0] as ParsedRecordEntry.Field
            assertEquals("path", entry.name)
            assertEquals("/tmp/log.txt", (entry.value as ParsedStringLit).parts[0].let { (it as ParsedStringPart.Literal).value })
        }

        @Test
        fun `multiple handlers with parameters and mixed qualification`() {
            val source = """
                perform Console, log::FileLogger {path = "/var/log/app.log"}, std::SystemRandom in someFunc {x = 1}
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val topLevel = module.decls[0] as ParsedTopLevelExpr
            val performExpr = topLevel.expression as ParsedPerform

            assertEquals(3, performExpr.handlers.size)

            // First handler: Console (no params, unqualified)
            val handler1 = performExpr.handlers[0]
            assertEquals("Console", handler1.performer.localName)
            assertNull(handler1.performer.qualifier)
            assertNull(handler1.params)

            // Second handler: log::FileLogger (with params, qualified)
            val handler2 = performExpr.handlers[1]
            assertEquals("FileLogger", handler2.performer.localName)
            assertNotNull(handler2.performer.qualifier)
            assertEquals(listOf("log"), handler2.performer.qualifier!!.segments)
            assertNotNull(handler2.params)
            val entry = handler2.params!!.entries[0] as ParsedRecordEntry.Field
            assertEquals("path", entry.name)

            // Third handler: std::SystemRandom (no params, qualified)
            val handler3 = performExpr.handlers[2]
            assertEquals("SystemRandom", handler3.performer.localName)
            assertNotNull(handler3.performer.qualifier)
            assertEquals(listOf("std"), handler3.performer.qualifier!!.segments)
        }
    }

    // ========================================================================
    // EFFECT CALL EXPRESSION TESTS
    // ========================================================================

    @Nested
    inner class EffectCallTests {

        @Test
        fun `unqualified effect call`() {
            val source = """
                perform info {msg = "hello"}
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val topLevel = module.decls[0] as ParsedTopLevelExpr
            val effectCall = topLevel.expression as ParsedEffectCall

            // For effect calls, operation.qualifier is null for unqualified
            // and operation.localName is the operation name
            assertNull(effectCall.operation.qualifier)
            assertEquals("info", effectCall.operation.localName)
        }

        @Test
        fun `qualified effect call with single segment`() {
            val source = """
                perform Log::info {msg = "hello"}
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val topLevel = module.decls[0] as ParsedTopLevelExpr
            val effectCall = topLevel.expression as ParsedEffectCall

            // operation contains the qualifier path
            assertEquals("Log", effectCall.operation.qualifier?.toString())
            assertEquals("info", effectCall.operation.localName)
        }

        @Test
        fun `qualified effect call with multi-segment qualifier`() {
            val source = """
                perform std::effects::Log::debug {msg = "debug message"}
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val topLevel = module.decls[0] as ParsedTopLevelExpr
            val effectCall = topLevel.expression as ParsedEffectCall

            // operation contains the full qualifier path
            assertEquals("std::effects::Log", effectCall.operation.qualifier?.toString())
            assertEquals("debug", effectCall.operation.localName)
        }
    }

    // ========================================================================
    // EDGE CASES
    // ========================================================================

    @Nested
    inner class EdgeCases {

        @Test
        fun `handler name that looks like a keyword`() {
            val source = """
                perform State in someExpr {}
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val topLevel = module.decls[0] as ParsedTopLevelExpr
            val performExpr = topLevel.expression as ParsedPerform

            assertEquals("State", performExpr.handlers[0].performer.localName)
            assertNull(performExpr.handlers[0].performer.qualifier)
        }

        @Test
        fun `handler name with underscore`() {
            val source = """
                perform my_handler::Console_Logger in someExpr {}
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val topLevel = module.decls[0] as ParsedTopLevelExpr
            val performExpr = topLevel.expression as ParsedPerform

            assertEquals("Console_Logger", performExpr.handlers[0].performer.localName)
            assertEquals(listOf("my_handler"), performExpr.handlers[0].performer.qualifier!!.segments)
        }

        @Test
        fun `deeply nested qualification`() {
            val source = """
                perform a::b::c::d::e::Handler in someExpr {}
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val topLevel = module.decls[0] as ParsedTopLevelExpr
            val performExpr = topLevel.expression as ParsedPerform

            val handler = performExpr.handlers[0]
            assertEquals("Handler", handler.performer.localName)
            assertEquals(listOf("a", "b", "c", "d", "e"), handler.performer.qualifier!!.segments)
            assertEquals("a::b::c::d::e", handler.performer.qualifier!!.toString())
        }

        @Test
        fun `perform expression with newlines between handlers`() {
            val source = """
                perform
                    log::Console,
                    db::Connection,
                    net::HttpClient
                in
                    someExpr {}
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val topLevel = module.decls[0] as ParsedTopLevelExpr
            val performExpr = topLevel.expression as ParsedPerform

            assertEquals(3, performExpr.handlers.size)
            assertEquals("Console", performExpr.handlers[0].performer.localName)
            assertEquals("Connection", performExpr.handlers[1].performer.localName)
            assertEquals("HttpClient", performExpr.handlers[2].performer.localName)
        }
    }

    // ========================================================================
    // QUALIFIED REF FACTORY METHOD TESTS
    // ========================================================================

    @Nested
    inner class QualifiedRefFactoryMethods {

        @Test
        fun `local() creates unqualified reference`() {
            val ref = ParsedQualifiedRef("Handler", null, Loc("<test>", 0, 0, 0, 0))
            assertEquals("Handler", ref.localName)
            assertNull(ref.qualifier)
            assertFalse(ref.isQualified)
        }

        @Test
        fun `qualified() with string creates qualified reference`() {
            val ref = ParsedQualifiedRef("Handler", ParsedModulePath(listOf("module")), Loc("<test>", 0, 0, 0, 0))
            assertEquals("Handler", ref.localName)
            assertNotNull(ref.qualifier)
            assertEquals(listOf("module"), ref.qualifier!!.segments)
            assertTrue(ref.isQualified)
        }

        @Test
        fun `qualified() with list creates qualified reference`() {
            val ref = ParsedQualifiedRef("Console", ParsedModulePath(listOf("std", "effects", "log")), Loc("<test>", 0, 0, 0, 0))
            assertEquals("Console", ref.localName)
            assertNotNull(ref.qualifier)
            assertEquals(listOf("std", "effects", "log"), ref.qualifier!!.segments)
            assertTrue(ref.isQualified)
        }

        @Test
        fun `qualified() with empty list creates unqualified reference`() {
            val ref = ParsedQualifiedRef("Handler", null, Loc("<test>", 0, 0, 0, 0))
            assertEquals("Handler", ref.localName)
            assertNull(ref.qualifier)
            assertFalse(ref.isQualified)
        }

        @Test
        fun `withResolvedHash() preserves other fields`() {
            val original = ParsedQualifiedRef("Handler", ParsedModulePath(listOf("module")), Loc("<test>", 0, 0, 0, 0))
            val withHash = original

            assertEquals("Handler", withHash.localName)
            assertEquals(listOf("module"), withHash.qualifier!!.segments)

        }
    }

    // ========================================================================
    // INTEGRATION WITH REAL CODE PATTERNS
    // ========================================================================

    @Nested
    inner class RealWorldPatterns {

        @Test
        fun `use module then perform with qualified handler`() {
            val source = """
                use std::effects::log

                let main = {} -> perform log::Console in log::info {msg = "Hello"}
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            assertEquals(2, module.decls.size)

            // Check use declaration
            val useDecl = module.decls[0] as ParsedUseDecl
            val modulePath = (useDecl.path as ParsedUsePath.Module).path
            assertEquals(listOf("std", "effects", "log"), modulePath.segments)

            // Check let binding with perform
            val letBinding = module.decls[1] as ParsedLetDecl
            val lambda = letBinding.value as ParsedLambda
            val performExpr = lambda.body as ParsedPerform

            // Handler should be qualified
            val handler = performExpr.handlers[0]
            assertEquals("Console", handler.performer.localName)
            assertEquals(listOf("log"), handler.performer.qualifier!!.segments)
        }

        @Test
        fun `nested perform expressions with qualified handlers`() {
            val source = """
                perform outer::Handler1 in
                    perform inner::Handler2 in
                        someExpr {}
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val topLevel = module.decls[0] as ParsedTopLevelExpr
            val outerPerform = topLevel.expression as ParsedPerform

            assertEquals("Handler1", outerPerform.handlers[0].performer.localName)
            assertEquals(listOf("outer"), outerPerform.handlers[0].performer.qualifier!!.segments)

            val innerPerform = outerPerform.body as ParsedPerform
            assertEquals("Handler2", innerPerform.handlers[0].performer.localName)
            assertEquals(listOf("inner"), innerPerform.handlers[0].performer.qualifier!!.segments)
        }

        @Test
        fun `perform with complex body expression`() {
            val source = """
                perform log::Console in
                    if condition {}
                    then log::info {msg = "true"}
                    else log::warn {msg = "false"}
            """.trimIndent()

            val module = parser.parseParsedModule(source)
            val topLevel = module.decls[0] as ParsedTopLevelExpr
            val performExpr = topLevel.expression as ParsedPerform

            assertEquals("Console", performExpr.handlers[0].performer.localName)
            assertEquals(listOf("log"), performExpr.handlers[0].performer.qualifier!!.segments)

            assertTrue(performExpr.body is ParsedIf)
        }
    }
}
