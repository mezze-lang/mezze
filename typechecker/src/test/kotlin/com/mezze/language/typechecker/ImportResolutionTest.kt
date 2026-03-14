package com.mezze.language.typechecker

import com.mezze.language.ast.*
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
            val program = parser.parse(source)
            val topLevel = program.items[0] as TopLevelExpression
            val perform = topLevel.expression as PerformExpression

            val handler = perform.handlers[0]
            assertEquals("Console", handler.performer.localName)
            assertFalse(handler.performer.isQualified)
        }

        @Test
        fun `qualified reference with single segment qualifier`() {
            val source = "perform log::Console in expr {}"
            val program = parser.parse(source)
            val topLevel = program.items[0] as TopLevelExpression
            val perform = topLevel.expression as PerformExpression

            val handler = perform.handlers[0]
            assertEquals("Console", handler.performer.localName)
            assertEquals(listOf("log"), handler.performer.qualifier?.segments)
            assertTrue(handler.performer.isQualified)
        }

        @Test
        fun `qualified reference with multi-segment qualifier`() {
            val source = "perform std::effects::log::Console in expr {}"
            val program = parser.parse(source)
            val topLevel = program.items[0] as TopLevelExpression
            val perform = topLevel.expression as PerformExpression

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

            val program = parser.parse(source)
            val implDecl = program.items[1] as ImplDeclaration

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

            val program = parser.parse(source)
            val implDecl = program.items[0] as ImplDeclaration

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

            val program = parser.parse(source)
            val implDecl = program.items[0] as ImplDeclaration

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
            val program = parser.parse(source)
            val topLevel = program.items[0] as TopLevelExpression
            val effectCall = topLevel.expression as EffectCallExpression

            assertEquals("info", effectCall.operation.localName)
            assertFalse(effectCall.operation.isQualified)
        }

        @Test
        fun `qualified effect call parses correctly`() {
            val source = "perform Log::info {msg = \"hello\"}"
            val program = parser.parse(source)
            val topLevel = program.items[0] as TopLevelExpression
            val effectCall = topLevel.expression as EffectCallExpression

            assertEquals("info", effectCall.operation.localName)
            assertEquals(listOf("Log"), effectCall.operation.qualifier?.segments)
            assertTrue(effectCall.operation.isQualified)
        }

        @Test
        fun `fully qualified effect call parses correctly`() {
            val source = "perform std::effects::log::info {msg = \"hello\"}"
            val program = parser.parse(source)
            val topLevel = program.items[0] as TopLevelExpression
            val effectCall = topLevel.expression as EffectCallExpression

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
        fun `QualifiedRef stores path as list not string`() {
            val ref = QualifiedRef.qualified(listOf("std", "effects", "log"), "Console")

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
        fun `PerformHandler stores QualifiedRef not string`() {
            val handler = PerformHandler(
                performer = QualifiedRef.qualified("log", "Console"),
                parameters = null
            )

            // Stored as structured data
            assertEquals("Console", handler.performer.localName)
            assertEquals(listOf("log"), handler.performer.qualifier?.segments)

            // Check key string format for lookups
            assertEquals("log::Console", handler.performer.toBindingKey().toKeyString())
        }

        @Test
        fun `ImplDeclaration stores QualifiedRef for effect`() {
            val source = """
                impl std::effects::log::Log for Console {} where {
                    info = {msg} -> {}
                }
            """.trimIndent()

            val program = parser.parse(source)
            val impl = program.items[0] as ImplDeclaration

            // Stored as structured data
            assertEquals("Log", impl.effect.localName)
            assertEquals(listOf("std", "effects", "log"), impl.effect.qualifier?.segments)

            // Check full qualified path
            assertEquals("std::effects::log", impl.effect.qualifier?.fullPath)
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

            val program = parser.parse(source)
            val topLevel = program.items[0] as TopLevelExpression
            val perform = topLevel.expression as PerformExpression

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
            val program = parser.parse(source)
            val useDecl = program.items[0] as UseDeclaration

            val path = useDecl.path as ModulePath
            assertEquals(listOf("std", "effects", "log"), path.segments)
            assertNull(useDecl.alias)
        }

        @Test
        fun `module import with alias parses correctly`() {
            val source = "use std::effects::log as logging"
            val program = parser.parse(source)
            val useDecl = program.items[0] as UseDeclaration

            val path = useDecl.path as ModulePath
            assertEquals(listOf("std", "effects", "log"), path.segments)
            assertEquals("logging", useDecl.alias)
        }

        @Test
        fun `selective import parses as SelectivePath`() {
            val source = "use std::effects::log::{Console, info}"
            val program = parser.parse(source)
            val useDecl = program.items[0] as UseDeclaration

            val path = useDecl.path as SelectivePath
            assertEquals(listOf("std", "effects", "log"), path.module)
            assertEquals(2, path.items.size)
            assertEquals("Console", path.items[0].name)
            assertEquals("info", path.items[1].name)
        }

        @Test
        fun `selective import with aliases parses correctly`() {
            val source = "use std::effects::log::{Console as StdConsole, info as logInfo}"
            val program = parser.parse(source)
            val useDecl = program.items[0] as UseDeclaration

            val path = useDecl.path as SelectivePath
            assertEquals(listOf("std", "effects", "log"), path.module)
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

            val program = parser.parse(source)
            val topLevel = program.items[0] as TopLevelExpression
            val perform = topLevel.expression as PerformExpression

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

            val program = parser.parse(source)
            val impl = program.items[0] as ImplDeclaration

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
            val source = """
                impl Show Int where {
                    show = {val} -> "Int"
                }
            """.trimIndent()

            val program = parser.parse(source)
            val impl = program.items[0] as ImplAbilityDeclaration

            assertEquals("Show", impl.ability.localName)
            assertFalse(impl.ability.isQualified)
        }

        @Test
        fun `impl ability with qualified ability name`() {
            val source = """
                impl codec::Encode Json Int where {
                    encode = {val, config} -> {}
                }
            """.trimIndent()

            val program = parser.parse(source)
            val impl = program.items[0] as ImplAbilityDeclaration

            assertEquals("Encode", impl.ability.localName)
            assertEquals(listOf("codec"), impl.ability.qualifier?.segments)
            assertTrue(impl.ability.isQualified)
        }

        @Test
        fun `impl ability with full path ability name`() {
            val source = """
                impl std::codec::Encode Json Int where {
                    encode = {val, config} -> {}
                }
            """.trimIndent()

            val program = parser.parse(source)
            val impl = program.items[0] as ImplAbilityDeclaration

            assertEquals("Encode", impl.ability.localName)
            assertEquals(listOf("std", "codec"), impl.ability.qualifier?.segments)
            assertTrue(impl.ability.isQualified)

            // Check ability reference structure
            assertEquals("Encode", impl.ability.localName)
            assertEquals(listOf("std", "codec"), impl.ability.qualifier?.segments ?: emptyList<String>())
            assertEquals("std::codec::Encode", "${impl.ability.qualifier?.fullPath}::${impl.ability.localName}")
        }
    }
}
