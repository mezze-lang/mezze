package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested

/**
 * Comprehensive tests for import resolution.
 *
 * These tests verify that:
 * 1. The `::` syntax is purely a parser concern
 * 2. Resolution uses structured paths (List<String>), not `::` joined strings
 * 3. All import patterns parse correctly
 *
 * ## Design Principle
 *
 * After parsing, no code should:
 * - Split strings on `::`
 * - Join strings with `::`
 * - Compare qualified names as `::` strings
 *
 * Instead, all resolution uses:
 * - Path segments: List<String>
 * - Content hashes: Str (for O(1) lookup)
 */
class ImportResolutionTest {

    private val parser = MezzeParser()

    // ========================================================================
    // QUALIFIED REFERENCE PARSING TESTS
    // ========================================================================

    @Nested
    inner class QualifiedRefParsing {

        @Test
        fun `unqualified reference has single-element path`() {
            val source = "perform Console in expr {}"
            val parsedModule = parser.parseParsedModule(source, "test.mz")
            val topLevel = parsedModule.decls.filterIsInstance<ParsedTopLevelExpr>().first()
            val perform = topLevel.expression as ParsedPerform

            val handler = perform.handlers[0]
            assertEquals("Console", handler.performer.localName)
            assertFalse(handler.performer.isQualified)
        }

        @Test
        fun `qualified reference with single segment qualifier`() {
            val source = "perform log::Console in expr {}"
            val parsedModule = parser.parseParsedModule(source, "test.mz")
            val topLevel = parsedModule.decls.filterIsInstance<ParsedTopLevelExpr>().first()
            val perform = topLevel.expression as ParsedPerform

            val handler = perform.handlers[0]
            assertEquals("Console", handler.performer.localName)
            assertEquals(listOf("log"), handler.performer.qualifier?.segments)
            assertTrue(handler.performer.isQualified)
        }

        @Test
        fun `qualified reference with multi-segment qualifier`() {
            val source = "perform std::effects::log::Console in expr {}"
            val parsedModule = parser.parseParsedModule(source, "test.mz")
            val topLevel = parsedModule.decls.filterIsInstance<ParsedTopLevelExpr>().first()
            val perform = topLevel.expression as ParsedPerform

            val handler = perform.handlers[0]
            assertEquals("Console", handler.performer.localName)
            assertEquals(listOf("std", "effects", "log"), handler.performer.qualifier?.segments)
            assertTrue(handler.performer.isQualified)
        }
    }

    // ========================================================================
    // IMPL DECLARATION RESOLUTION TESTS
    // ========================================================================

    @Nested
    inner class ImplDeclarationResolution {

        @Test
        fun `impl with unqualified effect name parses correctly`() {
            val source = """
                effect Log where {
                    info: {msg: Str} -> {} <Log>
                }

                impl Log for Console {} where {
                    info = {msg} -> {}
                }
            """.trimIndent()

            val parsedModule = parser.parseParsedModule(source, "test.mz")
            val implDecl = parsedModule.decls.filterIsInstance<ParsedPerformerDecl>().first()

            assertEquals("Log", implDecl.effect.localName)
            assertFalse(implDecl.effect.isQualified)
        }

        @Test
        fun `impl with qualified effect name parses correctly`() {
            val source = """
                impl log::Log for Console {} where {
                    info = {msg} -> {}
                }
            """.trimIndent()

            val parsedModule = parser.parseParsedModule(source, "test.mz")
            val implDecl = parsedModule.decls.filterIsInstance<ParsedPerformerDecl>().first()

            assertEquals("Log", implDecl.effect.localName)
            assertEquals(listOf("log"), implDecl.effect.qualifier?.segments)
            assertTrue(implDecl.effect.isQualified)
        }

        @Test
        fun `impl with full path effect name parses correctly`() {
            val source = """
                impl std::effects::log::Log for Console {} where {
                    info = {msg} -> {}
                }
            """.trimIndent()

            val parsedModule = parser.parseParsedModule(source, "test.mz")
            val implDecl = parsedModule.decls.filterIsInstance<ParsedPerformerDecl>().first()

            assertEquals("Log", implDecl.effect.localName)
            assertEquals(listOf("std", "effects", "log"), implDecl.effect.qualifier?.segments)
            assertTrue(implDecl.effect.isQualified)
        }
    }

    // ========================================================================
    // EFFECT CALL RESOLUTION TESTS
    // ========================================================================

    @Nested
    inner class EffectCallResolution {

        @Test
        fun `unqualified effect call parses correctly`() {
            val source = "perform info {msg = \"hello\"}"
            val parsedModule = parser.parseParsedModule(source, "test.mz")
            val topLevel = parsedModule.decls.filterIsInstance<ParsedTopLevelExpr>().first()
            val effectCall = topLevel.expression as ParsedEffectCall

            assertEquals("info", effectCall.operation.localName)
            assertFalse(effectCall.operation.isQualified)
        }

        @Test
        fun `qualified effect call parses correctly`() {
            val source = "perform Log::info {msg = \"hello\"}"
            val parsedModule = parser.parseParsedModule(source, "test.mz")
            val topLevel = parsedModule.decls.filterIsInstance<ParsedTopLevelExpr>().first()
            val effectCall = topLevel.expression as ParsedEffectCall

            assertEquals("info", effectCall.operation.localName)
            assertEquals(listOf("Log"), effectCall.operation.qualifier?.segments)
            assertTrue(effectCall.operation.isQualified)
        }

        @Test
        fun `fully qualified effect call parses correctly`() {
            val source = "perform std::effects::log::info {msg = \"hello\"}"
            val parsedModule = parser.parseParsedModule(source, "test.mz")
            val topLevel = parsedModule.decls.filterIsInstance<ParsedTopLevelExpr>().first()
            val effectCall = topLevel.expression as ParsedEffectCall

            assertEquals("info", effectCall.operation.localName)
            assertEquals(listOf("std", "effects", "log"), effectCall.operation.qualifier?.segments)
            assertTrue(effectCall.operation.isQualified)
        }
    }

    // ========================================================================
    // NO :: STRING MANIPULATION TESTS
    // ========================================================================

    @Nested
    inner class NoColonColonStringManipulation {

        @Test
        fun `ParsedQualifiedRef stores path as list not string`() {
            val ref = ParsedQualifiedRef("Console", ParsedModulePath(listOf("std", "effects", "log")), Loc("test.mz", 1, 1, 1, 1))

            // Path is stored as list
            assertEquals(listOf("std", "effects", "log"), ref.qualifier?.segments)
            assertEquals("Console", ref.localName)

            // No :: in the stored data (only in convenience properties for display)
        }

        @Test
        fun `ModulePath stores segments as list`() {
            val path = ModulePath(listOf("std", "effects", "log"))

            assertEquals(listOf("std", "effects", "log"), path.segments)
            assertEquals("log", path.shortName)

            // fullPath is only for display, not for resolution
            assertEquals("std::effects::log", path.fullPath)
        }

        @Test
        fun `ParsedPerformHandler stores ParsedQualifiedRef not string`() {
            val handler = ParsedPerformHandler(
                performer = ParsedQualifiedRef("Console", ParsedModulePath(listOf("log")), Loc("test.mz", 1, 1, 1, 1)),
                params = null
            )

            // Stored as structured data
            assertEquals("Console", handler.performer.localName)
            assertEquals(listOf("log"), handler.performer.qualifier?.segments)

            // Check string format for display
            assertEquals("log::Console", handler.performer.toString())
        }

        @Test
        fun `ImplDeclaration stores QualifiedRef for effect`() {
            val source = """
                impl std::effects::log::Log for Console {} where {
                    info = {msg} -> {}
                }
            """.trimIndent()

            val parsedModule = parser.parseParsedModule(source, "test.mz")
            val impl = parsedModule.decls.filterIsInstance<ParsedPerformerDecl>().first()

            // Stored as structured data
            assertEquals("Log", impl.effect.localName)
            assertEquals(listOf("std", "effects", "log"), impl.effect.qualifier?.segments)

            // Check full qualified path
            assertEquals("std::effects::log", impl.effect.qualifier.toString())
        }
    }

    // ========================================================================
    // MIXED QUALIFIED/UNQUALIFIED IN SAME EXPRESSION
    // ========================================================================

    @Nested
    inner class MixedQualification {

        @Test
        fun `multiple handlers with mixed qualification`() {
            val source = """
                perform Console, log::FileLogger, std::Random in someExpr {}
            """.trimIndent()

            val parsedModule = parser.parseParsedModule(source, "test.mz")
            val topLevel = parsedModule.decls.filterIsInstance<ParsedTopLevelExpr>().first()
            val perform = topLevel.expression as ParsedPerform

            assertEquals(3, perform.handlers.size)

            // First: unqualified
            assertFalse(perform.handlers[0].performer.isQualified)
            assertEquals("Console", perform.handlers[0].performer.localName)

            // Second: single-segment qualifier
            assertTrue(perform.handlers[1].performer.isQualified)
            assertEquals("FileLogger", perform.handlers[1].performer.localName)
            assertEquals(listOf("log"), perform.handlers[1].performer.qualifier?.segments)

            // Third: single-segment qualifier
            assertTrue(perform.handlers[2].performer.isQualified)
            assertEquals("Random", perform.handlers[2].performer.localName)
            assertEquals(listOf("std"), perform.handlers[2].performer.qualifier?.segments)
        }
    }

    // ========================================================================
    // USE DECLARATION PARSING
    // ========================================================================

    @Nested
    inner class UseDeclarationParsing {

        @Test
        fun `simple module import parses as ModulePath`() {
            val source = "use std::effects::log"
            val parsedModule = parser.parseParsedModule(source, "test.mz")
            val useDecl = parsedModule.decls.filterIsInstance<ParsedUseDecl>().first()

            val path = useDecl.path as ParsedUsePath.Module
            assertEquals(listOf("std", "effects", "log"), path.path.segments)
            assertNull(useDecl.alias)
        }

        @Test
        fun `module import with alias parses correctly`() {
            val source = "use std::effects::log as logging"
            val parsedModule = parser.parseParsedModule(source, "test.mz")
            val useDecl = parsedModule.decls.filterIsInstance<ParsedUseDecl>().first()

            val path = useDecl.path as ParsedUsePath.Module
            assertEquals(listOf("std", "effects", "log"), path.path.segments)
            assertEquals("logging", useDecl.alias)
        }

        @Test
        fun `selective import parses as SelectivePath`() {
            val source = "use std::effects::log::{Console, info}"
            val parsedModule = parser.parseParsedModule(source, "test.mz")
            val useDecl = parsedModule.decls.filterIsInstance<ParsedUseDecl>().first()

            val path = useDecl.path as ParsedUsePath.Selective
            assertEquals(listOf("std", "effects", "log"), path.modulePath.segments)
            assertEquals(2, path.items.size)
            assertEquals("Console", path.items[0].name)
            assertEquals("info", path.items[1].name)
        }

        @Test
        fun `selective import with aliases parses correctly`() {
            val source = "use std::effects::log::{Console as StdConsole, info as logInfo}"
            val parsedModule = parser.parseParsedModule(source, "test.mz")
            val useDecl = parsedModule.decls.filterIsInstance<ParsedUseDecl>().first()

            val path = useDecl.path as ParsedUsePath.Selective
            assertEquals(listOf("std", "effects", "log"), path.modulePath.segments)
            assertEquals(2, path.items.size)

            assertEquals("Console", path.items[0].name)
            assertEquals("StdConsole", path.items[0].alias)

            assertEquals("info", path.items[1].name)
            assertEquals("logInfo", path.items[1].alias)
        }
    }

    // ========================================================================
    // FULL PATH SCENARIOS
    // ========================================================================

    @Nested
    inner class FullPathScenarios {

        @Test
        fun `perform with full path handler`() {
            val source = """
                perform std::effects::log::Console in someExpr {}
            """.trimIndent()

            val parsedModule = parser.parseParsedModule(source, "test.mz")
            val topLevel = parsedModule.decls.filterIsInstance<ParsedTopLevelExpr>().first()
            val perform = topLevel.expression as ParsedPerform

            // Handler has full path
            val handler = perform.handlers[0]
            assertEquals("Console", handler.performer.localName)
            assertEquals(listOf("std", "effects", "log"), handler.performer.qualifier?.segments)
        }

        @Test
        fun `impl declaration with full path effect`() {
            val source = """
                impl std::effects::log::Log for MyConsole {} where {
                    info = {msg} -> {}
                }
            """.trimIndent()

            val parsedModule = parser.parseParsedModule(source, "test.mz")
            val impl = parsedModule.decls.filterIsInstance<ParsedPerformerDecl>().first()

            assertEquals("Log", impl.effect.localName)
            assertEquals(listOf("std", "effects", "log"), impl.effect.qualifier?.segments)
            assertEquals("MyConsole", impl.performerName)
        }
    }

    // ========================================================================
    // IMPL ABILITY DECLARATION TESTS
    // ========================================================================

    @Nested
    inner class ImplAbilityDeclarationResolution {

        @Test
        fun `impl ability with unqualified ability name`() {
            // When the ability is declared in the same file, resolveImplDeclarations converts it
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                impl Show for Int where {
                    show = {} -> "Int"
                }
            """.trimIndent()

            val parsedModule = parser.parseParsedModule(source, "test.mz")
            val impl = parsedModule.decls.filterIsInstance<ParsedImplAbilityDecl>().first()

            assertEquals("Show", impl.abilityRef!!.localName)
            assertFalse(impl.abilityRef!!.isQualified)
        }

        @Test
        fun `impl ability with qualified ability name`() {
            val source = """
                impl codec::Encode for Json Int where {
                    encode = {config} -> {}
                }
            """.trimIndent()

            val parsedModule = parser.parseParsedModule(source, "test.mz")
            // Parser resolves qualified ability refs — check on v2 AST
            val implDecl = parsedModule.decls.filterIsInstance<ParsedPerformerDecl>().firstOrNull()
                ?: parsedModule.decls.filterIsInstance<ParsedImplAbilityDecl>().firstOrNull()
            assertNotNull(implDecl, "Should have an impl declaration")
            // Check the ability/effect name from the parsed decl
            when (implDecl) {
                is ParsedPerformerDecl -> {
                    assertEquals("Encode", implDecl.effect.localName)
                    assertEquals(listOf("codec"), implDecl.effect.qualifier?.segments)
                }
                is ParsedImplAbilityDecl -> {
                    val target = implDecl.target as? ParsedImplTarget.Named
                    assertNotNull(target)
                    assertEquals("Encode", target!!.ability.localName)
                }
                else -> fail("Unexpected decl type")
            }
        }

        @Test
        fun `impl ability with full path ability name`() {
            val source = """
                impl std::codec::Encode for Json Int where {
                    encode = {config} -> {}
                }
            """.trimIndent()

            val parsedModule = parser.parseParsedModule(source, "test.mz")
            val implDecl = parsedModule.decls.filterIsInstance<ParsedPerformerDecl>().firstOrNull()
                ?: parsedModule.decls.filterIsInstance<ParsedImplAbilityDecl>().firstOrNull()
            assertNotNull(implDecl, "Should have an impl declaration")
            when (implDecl) {
                is ParsedPerformerDecl -> {
                    assertEquals("Encode", implDecl.effect.localName)
                    assertEquals(listOf("std", "codec"), implDecl.effect.qualifier?.segments)
                    assertTrue(implDecl.effect.isQualified)
                }
                is ParsedImplAbilityDecl -> {
                    val target = implDecl.target as? ParsedImplTarget.Named
                    assertNotNull(target)
                    assertEquals("Encode", target!!.ability.localName)
                    assertEquals(listOf("std", "codec"), target.ability.qualifier?.segments)
                    assertTrue(target.ability.isQualified)
                }
                else -> fail("Unexpected decl type")
            }
        }
    }
}
