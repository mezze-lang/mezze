package com.mezze.language.parser

import com.mezze.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class StdlibTest {

    private val parser = MezzeParser()

    @Test
    fun `test parse effect declaration from stdlib`() {
        // Effects are always public - no 'pub' keyword needed
        val source = """
effect Log where {
    info: { msg: String } -> {},
    warn: { msg: String } -> {},
    error: { msg: String } -> {}
}
""".trim()

        assertDoesNotThrow {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)
            assertEquals(1, program.items.size)

            val effect = program.items[0] as EffectDeclaration
            assertEquals("Log", effect.name)
            // Effects are always public - no visibility field
            assertEquals(3, effect.operations.size)

            val info = effect.operations[0]
            assertEquals("info", info.name)

            println("✅ Effect declaration from stdlib parsed successfully!")
        }
    }

    @Test
    fun `test pub effect gives error`() {
        val source = """
pub effect Log where {
    info: { msg: String } -> {}
}
""".trim()

        val exception = assertThrows(ParseException::class.java) {
            parser.parseParsedModule(source)
        }
        assertTrue(exception.message?.contains("'pub' is not allowed on effect declarations") == true)
        println("✅ pub effect correctly rejected with helpful error message")
    }

    @Test
    fun `test parse option map from stdlib`() {
        val source = """
pub let map: {opt: Option 'a, f: 'a -> 'b} -> Option 'b = { opt, f } ->
    match opt is
        Some { val } -> Some { val = f { val } }
        None -> None
""".trim()

        assertDoesNotThrow {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)
            assertEquals(1, program.items.size)

            val letBinding = program.items[0] as LetBinding
            assertEquals("map", letBinding.variableName)
            assertEquals(Visibility.PUBLIC, letBinding.visibility)
            assertNotNull(letBinding.typeAnnotation)

            println("✅ Option map from stdlib parsed successfully!")
        }
    }

    @Test
    fun `test parse variant type declaration`() {
        val source = "type Color = | Red, Blue { val: Int }, Green |"

        assertDoesNotThrow {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)
            assertEquals(1, program.items.size)

            val typeDecl = program.items[0] as TypeDeclaration
            assertEquals("Color", typeDecl.name)
            assertEquals(Visibility.PRIVATE, typeDecl.visibility)
            assertEquals(emptyList<String>(), typeDecl.typeParams)

            val variantDef = typeDecl.definition as VariantDefinition
            assertEquals(3, variantDef.constructors.size)

            val red = variantDef.constructors[0]
            assertEquals("Red", red.name)
            assertNull(red.dataType)

            val blue = variantDef.constructors[1]
            assertEquals("Blue", blue.name)
            assertNotNull(blue.dataType)

            val green = variantDef.constructors[2]
            assertEquals("Green", green.name)
            assertNull(green.dataType)

            println("✅ Variant type declaration parsed successfully!")
        }
    }

    @Test
    fun `test parse pub variant type with type params`() {
        val source = "pub type Option 'a = | Some { val: 'a }, None |"

        assertDoesNotThrow {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)
            assertEquals(1, program.items.size)

            val typeDecl = program.items[0] as TypeDeclaration
            assertEquals("Option", typeDecl.name)
            assertEquals(Visibility.PUBLIC, typeDecl.visibility)
            assertEquals(listOf("'a"), typeDecl.typeParams)

            val variantDef = typeDecl.definition as VariantDefinition
            assertEquals(2, variantDef.constructors.size)

            val some = variantDef.constructors[0]
            assertEquals("Some", some.name)
            assertNotNull(some.dataType)

            val none = variantDef.constructors[1]
            assertEquals("None", none.name)
            assertNull(none.dataType)

            println("✅ Public variant type with type params parsed successfully!")
        }
    }

    @Test
    fun `test parse simple impl declaration`() {
        val source = "impl Log for Console where { }"

        try {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)
            assertEquals(1, program.items.size)

            val impl = program.items[0] as ImplDeclaration
            assertEquals("Log", impl.effect.localName)
            assertEquals("Console", impl.performerName)

            println("✅ Simple impl declaration parsed successfully!")
        } catch (e: Exception) {
            println("❌ Error in simple impl: ${e.message}")
            throw e
        }
    }

    @Test
    fun `test parse type annotation in isolation`() {
        // First verify we can parse the type annotation
        val source = "let x: { msg: String } -> Int = 42"

        val program = parser.parseParsedModule(source)
        assertNotNull(program)
        println("✅ Type annotation in let binding works!")
    }

    @Test
    fun `test parse impl with one operation simple type`() {
        val source = "impl Log for Console where { info: Int -> Int = 42 }"

        try {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)
            assertEquals(1, program.items.size)

            val impl = program.items[0] as ImplDeclaration
            assertEquals("Log", impl.effect.localName)
            assertEquals("Console", impl.performerName)
            assertEquals(1, impl.operations.size)

            val op = impl.operations[0]
            assertEquals("info", op.name)

            println("✅ Impl with simple type operation parsed successfully!")
        } catch (e: Exception) {
            println("❌ Parse error: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    @Test
    fun `test parse impl with one operation`() {
        val source = "impl Log for Console where { info: { msg: String } -> Int = 42 }"

        try {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)
            assertEquals(1, program.items.size)

            val impl = program.items[0] as ImplDeclaration
            assertEquals("Log", impl.effect.localName)
            assertEquals("Console", impl.performerName)
            assertEquals(1, impl.operations.size)

            val op = impl.operations[0]
            assertEquals("info", op.name)

            println("✅ Impl with one operation parsed successfully!")
        } catch (e: Exception) {
            println("❌ Parse error: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    @Test
    fun `test parse impl declaration from stdlib`() {
        val source = """
impl Log for Console where {
    info: { msg: String } -> {} = { msg } -> __native_log_info { msg = msg },
    warn: { msg: String } -> {} = { msg } -> __native_log_warn { msg = msg }
}
""".trim()

        try {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)
            assertEquals(1, program.items.size)

            val impl = program.items[0] as ImplDeclaration
            assertEquals("Log", impl.effect.localName)
            assertEquals("Console", impl.performerName)
            assertEquals(emptyList<PerformerParameter>(), impl.performerParams)
            assertEquals(2, impl.operations.size)

            val info = impl.operations[0]
            assertEquals("info", info.name)

            println("✅ Impl declaration parsed successfully!")
        } catch (e: Exception) {
            println("❌ Parse error: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    @Test
    fun `test parse impl declaration with params`() {
        val source = """
impl Random for SeededRandom { seed: Int } where {
    nextInt: { min: Int, max: Int } -> Int = { min, max } -> __native_random { min = min, max = max }
}
""".trim()

        assertDoesNotThrow {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)
            assertEquals(1, program.items.size)

            val impl = program.items[0] as ImplDeclaration
            assertEquals("Random", impl.effect.localName)
            assertEquals("SeededRandom", impl.performerName)
            assertEquals(1, impl.performerParams.size)

            val param = impl.performerParams[0]
            assertEquals("seed", param.name)

            assertEquals(1, impl.operations.size)

            println("✅ Impl declaration with params parsed successfully!")
        }
    }

    @Test
    fun `test parse simple use declaration`() {
        val source = "use std::math::basic"

        assertDoesNotThrow {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)
            assertEquals(1, program.items.size)

            val use = program.items[0] as UseDeclaration
            val path = use.path as ModulePath
            assertEquals(listOf("std", "math", "basic"), path.segments)
            assertNull(use.alias)

            println("✅ Simple use declaration parsed successfully!")
        }
    }

    @Test
    fun `test parse use declaration with alias`() {
        val source = "use std::math::basic as m"

        assertDoesNotThrow {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)
            assertEquals(1, program.items.size)

            val use = program.items[0] as UseDeclaration
            val path = use.path as ModulePath
            assertEquals(listOf("std", "math", "basic"), path.segments)
            assertEquals("m", use.alias)

            println("✅ Use declaration with alias parsed successfully!")
        }
    }

    @Test
    fun `test parse pub use declaration`() {
        val source = "pub use std::math::trig"

        assertDoesNotThrow {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)
            assertEquals(1, program.items.size)

            val use = program.items[0] as PubUseDeclaration
            val path = use.path as ModulePath
            assertEquals(listOf("std", "math", "trig"), path.segments)
            assertNull(use.alias)

            println("✅ Pub use declaration parsed successfully!")
        }
    }

    @Test
    fun `test parse selective use declaration`() {
        val source = "use std::math::{trig, random}"

        assertDoesNotThrow {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)
            assertEquals(1, program.items.size)

            val use = program.items[0] as UseDeclaration
            val path = use.path as SelectivePath
            assertEquals(listOf("std", "math"), path.module)
            assertEquals(2, path.items.size)
            assertEquals("trig", path.items[0].name)
            assertNull(path.items[0].alias)
            assertEquals("random", path.items[1].name)
            assertNull(path.items[1].alias)

            println("✅ Selective use declaration parsed successfully!")
        }
    }

    @Test
    fun `test parse selective use with aliases`() {
        val source = "use std::math::{trig as t, random as r}"

        assertDoesNotThrow {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)
            assertEquals(1, program.items.size)

            val use = program.items[0] as UseDeclaration
            val path = use.path as SelectivePath
            assertEquals(listOf("std", "math"), path.module)
            assertEquals(2, path.items.size)
            assertEquals("trig", path.items[0].name)
            assertEquals("t", path.items[0].alias)
            assertEquals("random", path.items[1].name)
            assertEquals("r", path.items[1].alias)

            println("✅ Selective use with aliases parsed successfully!")
        }
    }

    @Test
    fun `test parse use with multiline from stdlib`() {
        val source = """
use std::math::basic
use std::math::trig
""".trim()

        assertDoesNotThrow {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)
            assertEquals(2, program.items.size)

            val use1 = program.items[0] as UseDeclaration
            val path1 = use1.path as ModulePath
            assertEquals(listOf("std", "math", "basic"), path1.segments)

            val use2 = program.items[1] as UseDeclaration
            val path2 = use2.path as ModulePath
            assertEquals(listOf("std", "math", "trig"), path2.segments)

            println("✅ Multiple use declarations parsed successfully!")
        }
    }

    @Test
    fun `test parse complete stdlib file with use`() {
        val source = """
# std/math/trig.mz - Trigonometric functions

use std::math::basic

# Basic trigonometric functions
pub let sin: { value: Float } -> Float = { value } ->
    __native_float_sin { value = value }

pub let cos: { value: Float } -> Float = { value } ->
    __native_float_cos { value = value }
""".trim()

        assertDoesNotThrow {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)
            assertEquals(3, program.items.size)

            // First item should be a use declaration
            val use = program.items[0] as UseDeclaration
            val path = use.path as ModulePath
            assertEquals(listOf("std", "math", "basic"), path.segments)

            // Second and third items should be let bindings
            val sin = program.items[1] as LetBinding
            assertEquals("sin", sin.variableName)
            assertEquals(Visibility.PUBLIC, sin.visibility)

            val cos = program.items[2] as LetBinding
            assertEquals("cos", cos.variableName)
            assertEquals(Visibility.PUBLIC, cos.visibility)

            println("✅ Complete stdlib file with use parsed successfully!")
        }
    }
}
