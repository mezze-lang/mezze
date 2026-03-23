package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.parser.ParseException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Comprehensive tests for abilities, effects, their imports, impls,
 * auto-resolution, orphan rules, and duplicate detection.
 *
 * This test suite covers:
 * 1. Ability declaration and usage
 * 2. Effect declaration and usage
 * 3. Ability impl declaration and validation
 * 4. Effect impl (performer) declaration and validation
 * 5. Module imports for abilities and effects
 * 6. Auto-resolution of ability impls
 * 7. Orphan rules enforcement
 * 8. Duplicate detection
 * 9. Visibility rules (no pub needed)
 */
class AbilityEffectComprehensiveTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    // ========================================================================
    // SECTION 1: ABILITY DECLARATION TESTS
    // ========================================================================

    @Nested
    inner class AbilityDeclarationTests {

        @Test
        fun `simple ability declaration with one type parameter`() {
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                let x = 1
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `ability declaration with multiple type parameters`() {
            val source = """
                ability Convert 'b for 'a where {
                    convert: {it: 'a} -> 'b
                }

                let x = 1
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `ability declaration with multiple operations`() {
            val source = """
                ability Eq for 'a where {
                    eq: { it: 'a, right: 'a } -> Bool,
                    neq: { it: 'a, right: 'a } -> Bool
                }

                let x = 1
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `ability declaration with complex return types`() {
            val source = """
                ability Container for 'a where {
                    first: {it: 'a} -> Option Int,
                    size: {it: 'a} -> Int
                }

                let x = 1
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `ability with record parameter type`() {
            val source = """
                ability Serializable for 'a where {
                    serialize: { it: 'a, format: Str } -> Str,
                    deserialize: { data: Str, format: Str } -> 'a
                }

                let x = 1
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }
    }

    // ========================================================================
    // SECTION 2: EFFECT DECLARATION TESTS
    // ========================================================================

    @Nested
    inner class EffectDeclarationTests {

        @Test
        fun `simple effect declaration`() {
            val source = """
                effect Log where {
                    info: { msg: Str } -> {},
                    warn: { msg: Str } -> {},
                    error: { msg: Str } -> {}
                }

                let x = 1
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `effect declaration with return values`() {
            val source = """
                effect Random where {
                    nextInt: { min: Int, max: Int } -> Int,
                    nextFloat: {} -> Float,
                    nextBool: {} -> Bool
                }

                let x = 1
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `effect declaration with associated types`() {
            val source = """
                effect State having 'state where {
                    get: {} -> 'state,
                    put: { value: 'state } -> {}
                }

                let x = 1
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `effect declaration with multiple associated types`() {
            // Multiple associated types use comma-separated syntax
            val source = """
                effect KeyValue having 'key, 'value where {
                    get: { key: 'key } -> Option 'value,
                    put: { key: 'key, value: 'value } -> {},
                    delete: { key: 'key } -> Bool
                }

                let x = 1
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }
    }

    // ========================================================================
    // SECTION 3: ABILITY IMPL DECLARATION TESTS
    // ========================================================================

    @Nested
    inner class AbilityImplTests {

        @Test
        fun `impl ability for built-in type Int`() {
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                impl Show for Int where {
                    show = {it} -> "int"
                }

                let result = 42.show {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `impl ability for built-in type String`() {
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                impl Show for Str where {
                    show = {it} -> it
                }

                let result = "hello".show {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `impl ability for built-in type Bool`() {
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                impl Show for Bool where {
                    show = {it} ->
                        match it is
                            True -> "true"
                            False -> "false"
                }

                let result = True.show {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `impl ability for built-in type Float`() {
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                impl Show for Float where {
                    show = {it} -> "float"
                }

                let result = 3.14.show {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `impl ability for custom variant type`() {
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                type Color = | Red, Green, Blue |

                impl Show for Color where {
                    show = {it} ->
                        match it is
                            Red -> "red"
                            Green -> "green"
                            Blue -> "blue"
                }

                let c: Color = Red
                let result = c.show {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `impl ability for custom variant type with payloads`() {
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                type Person = | Person { name: Str, age: Int } |

                impl Show for Person where {
                    show = {it} ->
                        match it is
                            Person { name, age } -> name
                }

                let p = Person { name = "Alice", age = 30 }
                let result = p.show {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `impl ability for record type alias`() {
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                type Point = { x: Int, y: Int }

                impl Show for Point where {
                    show = {it} -> "point"
                }

                let p: Point = { x = 1, y = 2 }
                let result = p.show {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `impl multi-param ability`() {
            val source = """
                ability Convert 'b for 'a where {
                    convert: {it: 'a} -> 'b
                }

                impl Convert for Str Int where {
                    convert = {it} -> "converted"
                }

                let result = 42.convert {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `impl ability with multiple operations`() {
            val source = """
                ability Describable for 'a where {
                    describe: {it: 'a} -> Str,
                    shortDesc: {it: 'a} -> Str
                }

                impl Describable for Int where {
                    describe = {it} -> "integer value",
                    shortDesc = {it} -> "int"
                }

                let a = 42.describe {}
                let b = 42.shortDesc {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `multiple impls for same ability different types`() {
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                impl Show for Int where {
                    show = {it} -> "int"
                }

                impl Show for Str where {
                    show = {it} -> it
                }

                impl Show for Bool where {
                    show = {it} ->
                        match it is
                            True -> "true"
                            False -> "false"
                }

                let a = 42.show {}
                let b = "hello".show {}
                let c = True.show {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `different abilities for same type`() {
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                ability Debug for 'a where {
                    debug: {it: 'a} -> Str
                }

                impl Show for Int where {
                    show = {it} -> "show"
                }

                impl Debug for Int where {
                    debug = {it} -> "debug"
                }

                let a = 42.show {}
                let b = 42.debug {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }
    }

    // ========================================================================
    // SECTION 4: EFFECT IMPL (PERFORMER) TESTS
    // ========================================================================

    @Nested
    inner class EffectImplTests {

        @Test
        fun `simple effect impl`() {
            val source = """
                effect Log where {
                    info: { msg: Str } -> {}
                }

                impl Log for Console where {
                    info: { msg: Str } -> {} = { msg } -> {}
                }

                let x = 1
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `effect impl with multiple operations`() {
            val source = """
                effect Log where {
                    info: { msg: Str } -> {},
                    warn: { msg: Str } -> {},
                    error: { msg: Str } -> {}
                }

                impl Log for Console where {
                    info: { msg: Str } -> {} = { msg } -> {},
                    warn: { msg: Str } -> {} = { msg } -> {},
                    error: { msg: Str } -> {} = { msg } -> {}
                }

                let x = 1
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `effect impl with return values`() {
            val source = """
                effect Random where {
                    nextInt: { min: Int, max: Int } -> Int
                }

                impl Random for FixedRandom where {
                    nextInt: { min: Int, max: Int } -> Int = { min, max } -> min
                }

                let x = 1
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `effect impl with associated types`() {
            val source = """
                effect State having 'state where {
                    get: {} -> 'state,
                    put: { value: 'state } -> {}
                }

                impl State for Counter having 'state = Int where {
                    get: {} -> Int = {} -> 0,
                    put: { value: Int } -> {} = { value } -> {}
                }

                let x = 1
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `effect impl with parameters`() {
            val source = """
                effect Random where {
                    nextInt: { min: Int, max: Int } -> Int
                }

                impl Random for SeededRandom { seed: Int } where {
                    nextInt: { min: Int, max: Int } -> Int = { min, max } -> min
                }

                let x = 1
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `multiple effect impls for same effect`() {
            val source = """
                effect Log where {
                    info: { msg: Str } -> {}
                }

                impl Log for Console where {
                    info: { msg: Str } -> {} = { msg } -> {}
                }

                impl Log for FileLogger where {
                    info: { msg: Str } -> {} = { msg } -> {}
                }

                let x = 1
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }
    }

    // ========================================================================
    // SECTION 5: MODULE IMPORT TESTS
    // ========================================================================

    @Nested
    inner class ModuleImportTests {

        @Test
        fun `import ability from another module`() {
            // Create ability module
            val abilityMod = tempDir.resolve("showable.mz")
            Files.writeString(abilityMod, """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                impl Show for Int where {
                    show = {it} -> "int"
                }
            """.trimIndent())

            // Create user module
            val userMod = tempDir.resolve("user.mz")
            Files.writeString(userMod, """
                use showable::{Show}

                let result = 42.show {}
            """.trimIndent())

            val userProgram = parser.parse(Files.readString(userMod))
            val result = typeChecker.typeCheck(userProgram, tempDir)
            assertNotNull(result.program)
        }

        @Test
        fun `import effect from another module`() {
            // Create effect module
            val effectMod = tempDir.resolve("logging.mz")
            Files.writeString(effectMod, """
                effect Log where {
                    info: { msg: Str } -> {}
                }

                impl Log for Console where {
                    info: { msg: Str } -> {} = { msg } -> {}
                }
            """.trimIndent())

            // Create user module
            val userMod = tempDir.resolve("user.mz")
            Files.writeString(userMod, """
                use logging::{Log, Console}

                let x = 1
            """.trimIndent())

            val userProgram = parser.parse(Files.readString(userMod))
            val result = typeChecker.typeCheck(userProgram, tempDir)
            assertNotNull(result.program)
        }

        @Test
        fun `import type and use with local ability impl`() {
            // Create type module
            val typeMod = tempDir.resolve("types.mz")
            Files.writeString(typeMod, """
                pub type Person = | Person { name: Str, age: Int } |
            """.trimIndent())

            // Create user module that defines ability and impls for imported type
            val userMod = tempDir.resolve("user.mz")
            Files.writeString(userMod, """
                use types::{Person}

                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                impl Show for Person where {
                    show = {it} ->
                        match it is
                            Person { name, age } -> name
                }

                let p = Person { name = "Alice", age = 30 }
                let result = p.show {}
            """.trimIndent())

            val userProgram = parser.parse(Files.readString(userMod))
            val result = typeChecker.typeCheck(userProgram, tempDir)
            assertNotNull(result.program)
        }

        @Test
        fun `import ability with qualified name in impl`() {
            // Create ability module
            val abilityMod = tempDir.resolve("serializer.mz")
            Files.writeString(abilityMod, """
                ability Serializable for 'a where {
                    serialize: {it: 'a} -> Str
                }
            """.trimIndent())

            // Create user module with qualified ability name
            val userMod = tempDir.resolve("user.mz")
            Files.writeString(userMod, """
                use serializer

                type MyData = | MyData { val: Str } |

                impl serializer::Serializable for MyData where {
                    serialize = {it} ->
                        match it is
                            MyData { val } -> val
                }

                let d = MyData { val = "test" }
            """.trimIndent())

            val userProgram = parser.parse(Files.readString(userMod))
            val result = typeChecker.typeCheck(userProgram, tempDir)
            assertNotNull(result.program)
        }

        @Test
        fun `module import with alias`() {
            // Create ability module
            val abilityMod = tempDir.resolve("showable.mz")
            Files.writeString(abilityMod, """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                impl Show for Int where {
                    show = {it} -> "int"
                }
            """.trimIndent())

            // Create user module with alias
            val userMod = tempDir.resolve("user.mz")
            Files.writeString(userMod, """
                use showable as s

                let result = 42.show {}
            """.trimIndent())

            val userProgram = parser.parse(Files.readString(userMod))
            val result = typeChecker.typeCheck(userProgram, tempDir)
            assertNotNull(result.program)
        }
    }

    // ========================================================================
    // SECTION 6: AUTO-RESOLUTION OF ABILITY IMPLS
    // ========================================================================

    @Nested
    inner class AbilityImplAutoResolutionTests {

        @Test
        fun `ability impl is auto-resolved for Int`() {
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                impl Show for Int where {
                    show = {it} -> "int"
                }

                # Auto-resolve impl Show for Int
                let result = 42.show {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `ability impl is auto-resolved based on argument type`() {
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                impl Show for Int where {
                    show = {it} -> "int"
                }

                impl Show for Str where {
                    show = {it} -> it
                }

                # Should resolve to Show Int
                let a = 42.show {}

                # Should resolve to Show String
                let b = "hello".show {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `ability impl is auto-resolved for custom type`() {
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                type Color = | Red, Green, Blue |

                impl Show for Color where {
                    show = {it} ->
                        match it is
                            Red -> "red"
                            Green -> "green"
                            Blue -> "blue"
                }

                let c: Color = Red
                let result = c.show {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `ability impl auto-resolution with imported ability`() {
            // Create ability module with impl
            val abilityMod = tempDir.resolve("showable.mz")
            Files.writeString(abilityMod, """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                impl Show for Int where {
                    show = {it} -> "int"
                }
            """.trimIndent())

            // Create user module - should auto-resolve imported impl
            val userMod = tempDir.resolve("user.mz")
            Files.writeString(userMod, """
                use showable::{Show}

                # Should auto-resolve to impl Show for Int from showable
                let result = 42.show {}
            """.trimIndent())

            val userProgram = parser.parse(Files.readString(userMod))
            val result = typeChecker.typeCheck(userProgram, tempDir)
            assertNotNull(result.program)
        }

        @Test
        fun `multi-param ability impl auto-resolution`() {
            val source = """
                ability Convert 'b for 'a where {
                    convert: {it: 'a} -> 'b
                }

                impl Convert for Str Int where {
                    convert = {it} -> "converted"
                }

                impl Convert for Int Str where {
                    convert = {it} -> 0
                }

                # Should resolve based on argument and expected return type
                let a: Str = 42.convert {}
                let b: Int = "hello".convert {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }
    }

    // ========================================================================
    // SECTION 7: ORPHAN RULES TESTS
    // ========================================================================

    @Nested
    inner class OrphanRulesTests {

        @Test
        fun `impl in same module as ability is valid`() {
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                impl Show for Int where {
                    show = {it} -> "int"
                }

                let result = 42.show {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `impl in same module as type is valid`() {
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                type Person = | Person { name: Str } |

                impl Show for Person where {
                    show = {it} ->
                        match it is
                            Person { name } -> name
                }

                let p = Person { name = "Alice" }
                let result = p.show {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `orphan impl - imported ability and built-in type - should fail`() {
            // Create ability module
            val abilityMod = tempDir.resolve("my_ability.mz")
            Files.writeString(abilityMod, """
                ability Printable for 'a where {
                    print: {it: 'a} -> Str
                }
            """.trimIndent())

            // Create orphan module - imports ability, impls for built-in
            val orphanMod = tempDir.resolve("orphan.mz")
            Files.writeString(orphanMod, """
                use my_ability::{Printable}

                # Orphan: Printable is imported, Int is built-in
                impl Printable for Int where {
                    print = {it} -> "int"
                }
            """.trimIndent())

            val orphanProgram = parser.parse(Files.readString(orphanMod))

            val error = assertThrows<TypeCheckError> {
                typeChecker.typeCheck(orphanProgram, tempDir)
            }

            assertTrue(
                error.message?.contains("Orphan", ignoreCase = true) == true,
                "Error should mention orphan impl, but got: ${error.message}"
            )
        }

        @Test
        fun `orphan impl - imported ability and imported type - should fail`() {
            // Create ability module
            val abilityMod = tempDir.resolve("ability_mod.mz")
            Files.writeString(abilityMod, """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }
            """.trimIndent())

            // Create type module
            val typeMod = tempDir.resolve("type_mod.mz")
            Files.writeString(typeMod, """
                pub type Person = { name: Str, age: Int }
            """.trimIndent())

            // Create orphan module
            val orphanMod = tempDir.resolve("orphan_mod.mz")
            Files.writeString(orphanMod, """
                use ability_mod::{Show}
                use type_mod::{Person}

                # Orphan: both Show and Person are imported
                impl Show for Person where {
                    show = {it} -> it.name
                }
            """.trimIndent())

            val orphanProgram = parser.parse(Files.readString(orphanMod))

            val error = assertThrows<TypeCheckError> {
                typeChecker.typeCheck(orphanProgram, tempDir)
            }

            assertTrue(
                error.message?.contains("Orphan", ignoreCase = true) == true,
                "Error should mention orphan impl, but got: ${error.message}"
            )
        }

        @Test
        fun `impl for imported ability with local type is valid`() {
            // Create ability module
            val abilityMod = tempDir.resolve("showable.mz")
            Files.writeString(abilityMod, """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }
            """.trimIndent())

            // Create user module with local type
            val userMod = tempDir.resolve("user.mz")
            Files.writeString(userMod, """
                use showable::{Show}

                # Local type - impl is valid
                type MyInt = | MyInt { val: Int } |

                impl Show for MyInt where {
                    show = {it} ->
                        match it is
                            MyInt { val } -> "myint"
                }

                let m = MyInt { val = 42 }
                let result = m.show {}
            """.trimIndent())

            val userProgram = parser.parse(Files.readString(userMod))
            val result = typeChecker.typeCheck(userProgram, tempDir)
            assertNotNull(result.program)
        }

        @Test
        fun `impl for local ability with imported type is valid`() {
            // Create type module
            val typeMod = tempDir.resolve("types.mz")
            Files.writeString(typeMod, """
                pub type Person = | Person { name: Str } |
            """.trimIndent())

            // Create user module with local ability
            val userMod = tempDir.resolve("user.mz")
            Files.writeString(userMod, """
                use types::{Person}

                # Local ability - impl is valid
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                impl Show for Person where {
                    show = {it} ->
                        match it is
                            Person { name } -> name
                }

                let p = Person { name = "Alice" }
                let result = p.show {}
            """.trimIndent())

            val userProgram = parser.parse(Files.readString(userMod))
            val result = typeChecker.typeCheck(userProgram, tempDir)
            assertNotNull(result.program)
        }

        @Test
        fun `multi-param ability - impl valid if any target type is local`() {
            val source = """
                ability Convert 'b for 'a where {
                    convert: {it: 'a} -> 'b
                }

                type Celsius = | Celsius { degrees: Float } |
                type Fahrenheit = | Fahrenheit { degrees: Float } |

                # Valid: both types are local
                impl Convert for Fahrenheit Celsius where {
                    convert = {it} ->
                        match it is
                            Celsius { degrees } -> Fahrenheit { degrees = degrees * 1.8 + 32.0 }
                }

                let c = Celsius { degrees = 100.0 }
                let f = c.convert {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }
    }

    // ========================================================================
    // SECTION 8: DUPLICATE DETECTION TESTS
    // ========================================================================

    @Nested
    inner class DuplicateDetectionTests {

        @Test
        fun `duplicate ability declaration should fail`() {
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }
            """.trimIndent()

            val program = parser.parse(source)

            val error = assertThrows<TypeCheckError> {
                typeChecker.typeCheck(program)
            }

            assertTrue(
                error.message?.contains("Duplicate", ignoreCase = true) == true ||
                error.message?.contains("already", ignoreCase = true) == true,
                "Error should mention duplicate, but got: ${error.message}"
            )
        }

        @Test
        fun `duplicate effect declaration should fail`() {
            val source = """
                effect Log where {
                    info: { msg: Str } -> {}
                }

                effect Log where {
                    info: { msg: Str } -> {}
                }
            """.trimIndent()

            val program = parser.parse(source)

            val error = assertThrows<TypeCheckError> {
                typeChecker.typeCheck(program)
            }

            assertTrue(
                error.message?.contains("Duplicate", ignoreCase = true) == true ||
                error.message?.contains("already", ignoreCase = true) == true,
                "Error should mention duplicate, but got: ${error.message}"
            )
        }

        @Test
        fun `duplicate ability impl for same type should fail`() {
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                impl Show for Int where {
                    show = {it} -> "first"
                }

                impl Show for Int where {
                    show = {it} -> "second"
                }
            """.trimIndent()

            val program = parser.parse(source)

            val error = assertThrows<TypeCheckError> {
                typeChecker.typeCheck(program)
            }

            assertTrue(
                error.message?.contains("Duplicate", ignoreCase = true) == true ||
                error.message?.contains("already", ignoreCase = true) == true,
                "Error should mention duplicate impl, but got: ${error.message}"
            )
        }

        @Test
        fun `duplicate effect impl (performer) should fail`() {
            val source = """
                effect Log where {
                    info: { msg: Str } -> {}
                }

                impl Log for Console where {
                    info: { msg: Str } -> {} = { msg } -> {}
                }

                impl Log for Console where {
                    info: { msg: Str } -> {} = { msg } -> {}
                }
            """.trimIndent()

            val program = parser.parse(source)

            val error = assertThrows<TypeCheckError> {
                typeChecker.typeCheck(program)
            }

            assertTrue(
                error.message?.contains("Duplicate", ignoreCase = true) == true ||
                error.message?.contains("already", ignoreCase = true) == true,
                "Error should mention duplicate, but got: ${error.message}"
            )
        }

        @Test
        fun `duplicate multi-param ability impl should fail`() {
            val source = """
                ability Convert 'b for 'a where {
                    convert: {it: 'a} -> 'b
                }

                impl Convert for Str Int where {
                    convert = {it} -> "first"
                }

                impl Convert for Str Int where {
                    convert = {it} -> "second"
                }
            """.trimIndent()

            val program = parser.parse(source)

            val error = assertThrows<TypeCheckError> {
                typeChecker.typeCheck(program)
            }

            assertTrue(
                error.message?.contains("Duplicate", ignoreCase = true) == true ||
                error.message?.contains("already", ignoreCase = true) == true,
                "Error should mention duplicate impl, but got: ${error.message}"
            )
        }

        @Test
        fun `different abilities for same type is valid - not duplicate`() {
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                ability Debug for 'a where {
                    debug: {it: 'a} -> Str
                }

                impl Show for Int where {
                    show = {it} -> "show"
                }

                impl Debug for Int where {
                    debug = {it} -> "debug"
                }

                let a = 42.show {}
                let b = 42.debug {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `same ability for different types is valid - not duplicate`() {
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                impl Show for Int where {
                    show = {it} -> "int"
                }

                impl Show for Str where {
                    show = {it} -> it
                }

                let a = 42.show {}
                let b = "hello".show {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `different performers for same effect is valid - not duplicate`() {
            val source = """
                effect Log where {
                    info: { msg: Str } -> {}
                }

                impl Log for Console where {
                    info: { msg: Str } -> {} = { msg } -> {}
                }

                impl Log for FileLogger where {
                    info: { msg: Str } -> {} = { msg } -> {}
                }

                let x = 1
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }
    }

    // ========================================================================
    // SECTION 9: VISIBILITY RULES (NO PUB NEEDED) TESTS
    // ========================================================================

    @Nested
    inner class VisibilityRulesTests {

        @Test
        fun `ability without pub keyword works`() {
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                impl Show for Int where {
                    show = {it} -> "int"
                }

                let result = 42.show {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `effect without pub keyword works`() {
            val source = """
                effect Log where {
                    info: { msg: Str } -> {}
                }

                impl Log for Console where {
                    info: { msg: Str } -> {} = { msg } -> {}
                }

                let x = 1
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `pub keyword on ability should fail with helpful message`() {
            val source = """
                pub ability Show for 'a where {
                    show: {it: 'a} -> Str
                }
            """.trimIndent()

            // Parser now rejects 'pub' before ability
            val error = assertThrows<ParseException> {
                parser.parse(source)
            }

            assertTrue(
                error.message?.contains("'pub' is not allowed on ability declarations") == true,
                "Error should mention pub is not allowed, but got: ${error.message}"
            )
        }

        @Test
        fun `pub keyword on effect should fail with helpful message`() {
            val source = """
                pub effect Log where {
                    info: { msg: Str } -> {}
                }
            """.trimIndent()

            // Parser now rejects 'pub' before effect
            val error = assertThrows<ParseException> {
                parser.parse(source)
            }

            assertTrue(
                error.message?.contains("'pub' is not allowed on effect declarations") == true,
                "Error should mention pub is not allowed, but got: ${error.message}"
            )
        }

        @Test
        fun `ability without pub is exported from module`() {
            // Create ability module WITHOUT pub
            val abilityMod = tempDir.resolve("showable.mz")
            Files.writeString(abilityMod, """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                impl Show for Int where {
                    show = {it} -> "int"
                }
            """.trimIndent())

            // Import in another module - should work
            val userMod = tempDir.resolve("user.mz")
            Files.writeString(userMod, """
                use showable::{Show}

                let result = 42.show {}
            """.trimIndent())

            val userProgram = parser.parse(Files.readString(userMod))
            val result = typeChecker.typeCheck(userProgram, tempDir)
            assertNotNull(result.program)
        }

        @Test
        fun `effect without pub is exported from module`() {
            // Create effect module WITHOUT pub
            val effectMod = tempDir.resolve("logging.mz")
            Files.writeString(effectMod, """
                effect Log where {
                    info: { msg: Str } -> {}
                }

                impl Log for Console where {
                    info: { msg: Str } -> {} = { msg } -> {}
                }
            """.trimIndent())

            // Import in another module - should work
            val userMod = tempDir.resolve("user.mz")
            Files.writeString(userMod, """
                use logging::{Log, Console}

                let x = 1
            """.trimIndent())

            val userProgram = parser.parse(Files.readString(userMod))
            val result = typeChecker.typeCheck(userProgram, tempDir)
            assertNotNull(result.program)
        }
    }

    // ========================================================================
    // SECTION 10: ERROR CASES AND EDGE CASES
    // ========================================================================

    @Nested
    inner class ErrorCasesTests {

        @Test
        fun `impl missing required operation should fail`() {
            val source = """
                ability Eq for 'a where {
                    eq: { it: 'a, right: 'a } -> Bool,
                    neq: { it: 'a, right: 'a } -> Bool
                }

                impl Eq for Int where {
                    eq = { it, right } -> True
                    # Missing neq operation
                }
            """.trimIndent()

            val program = parser.parse(source)

            val error = assertThrows<TypeCheckError> {
                typeChecker.typeCheck(program)
            }

            assertTrue(
                error.message?.contains("missing", ignoreCase = true) == true,
                "Error should mention missing operation, but got: ${error.message}"
            )
        }

        @Test
        fun `impl with extra operation should fail`() {
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                impl Show for Int where {
                    show = {it} -> "int",
                    extra = {} -> "extra"
                }
            """.trimIndent()

            val program = parser.parse(source)

            val error = assertThrows<TypeCheckError> {
                typeChecker.typeCheck(program)
            }

            assertTrue(
                error.message?.contains("extra", ignoreCase = true) == true,
                "Error should mention extra operation, but got: ${error.message}"
            )
        }

        @Test
        fun `impl for non-existent ability should fail`() {
            val source = """
                impl NonExistent for Int where {
                    op = {} -> "test"
                }
            """.trimIndent()

            val program = parser.parse(source)

            val error = assertThrows<TypeCheckError> {
                typeChecker.typeCheck(program)
            }

            assertTrue(
                error.message?.contains("not found", ignoreCase = true) == true ||
                error.message?.contains("not defined", ignoreCase = true) == true,
                "Error should mention ability not found/not defined, but got: ${error.message}"
            )
        }

        @Test
        fun `effect impl with wrong operation parameter type should fail`() {
            // Effect declares { msg: Str }, but impl provides { msg: Int }
            val source = """
                effect Log where {
                    info: { msg: Str } -> {}
                }

                impl Log for Console where {
                    info: { msg: Int } -> {} = { msg } -> {}
                }
            """.trimIndent()

            val program = parser.parse(source)

            val error = assertThrows<TypeCheckError> {
                typeChecker.typeCheck(program)
            }

            assertTrue(
                error.message?.contains("wrong parameter type", ignoreCase = true) == true ||
                error.message?.contains("type", ignoreCase = true) == true,
                "Error should mention wrong type, but got: ${error.message}"
            )
        }

        @Test
        fun `effect impl with different parameter name should fail`() {
            // Parameter names must match exactly for both abilities and effects
            val source = """
                effect Log where {
                    info: { msg: Str } -> {}
                }

                impl Log for Console where {
                    info: { message: Str } -> {} = { message } -> {}
                }
            """.trimIndent()

            val program = parser.parse(source)

            val error = assertThrows<TypeCheckError> {
                typeChecker.typeCheck(program)
            }

            assertTrue(
                error.message?.contains("wrong parameter type", ignoreCase = true) == true ||
                error.message?.contains("type", ignoreCase = true) == true,
                "Error should mention parameter mismatch, but got: ${error.message}"
            )
        }

        @Test
        fun `effect impl with matching parameter name and type should pass`() {
            // Parameter names and types must match exactly
            val source = """
                effect Log where {
                    info: { msg: Str } -> {}
                }

                impl Log for Console where {
                    info: { msg: Str } -> {} = { msg } -> {}
                }

                let x = 1
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `effect impl with same effect in return annotation should fail`() {
            // Impl cannot have the same effect it's implementing - that would be circular
            val source = """
                effect Log where {
                    info: { msg: Str } -> {} <Log>
                }

                impl Log for Console where {
                    info: { msg: Str } -> {} <Log> = { msg } -> {}
                }
            """.trimIndent()

            val program = parser.parse(source)

            val error = assertThrows<TypeCheckError> {
                typeChecker.typeCheck(program)
            }

            assertTrue(
                error.message?.contains("same effect", ignoreCase = true) == true ||
                error.message?.contains("circular", ignoreCase = true) == true ||
                error.message?.contains("cannot require", ignoreCase = true) == true,
                "Error should mention circular/same effect, but got: ${error.message}"
            )
        }

        @Test
        fun `effect impl with same effect among others should fail`() {
            // Even if Log is among other effects, it should still fail
            val source = """
                effect Log where {
                    info: { msg: Str } -> {} <Log>
                }

                effect IO where {
                    read: {} -> Str
                }

                impl Log for Console where {
                    info: { msg: Str } -> {} <Log, IO> = { msg } -> {}
                }
            """.trimIndent()

            val program = parser.parse(source)

            val error = assertThrows<TypeCheckError> {
                typeChecker.typeCheck(program)
            }

            assertTrue(
                error.message?.contains("same effect", ignoreCase = true) == true ||
                error.message?.contains("cannot require", ignoreCase = true) == true,
                "Error should mention same effect, but got: ${error.message}"
            )
        }

        @Test
        fun `effect impl transforming to different effect should pass`() {
            // Impl can transform one effect to another
            val source = """
                effect Log where {
                    info: { msg: Str } -> {} <Log>
                }

                effect IO where {
                    write: { data: Str } -> {}
                }

                impl Log for ConsoleLog where {
                    info: { msg: Str } -> {} <IO> = { msg } -> {}
                }

                let x = 1
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `effect impl with polymorphic effect row should pass`() {
            // Impl can have polymorphic effect row <*>
            val source = """
                effect Log where {
                    info: { msg: Str } -> {} <Log>
                }

                impl Log for ConsoleLog where {
                    info: { msg: Str } -> {} <*> = { msg } -> {}
                }

                let x = 1
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `effect impl with multiple different effects should pass`() {
            // Impl can have multiple different effects
            val source = """
                effect Log where {
                    info: { msg: Str } -> {} <Log>
                }

                effect IO where {
                    write: { data: Str } -> {}
                }

                effect Filesystem where {
                    read: { path: Str } -> Str
                }

                impl Log for ConsoleLog where {
                    info: { msg: Str } -> {} <IO, Filesystem> = { msg } -> {}
                }

                let x = 1
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `effect impl with same effect and polymorphic should fail`() {
            // Even with <*>, having the same effect is invalid
            val source = """
                effect Log where {
                    info: { msg: Str } -> {} <Log>
                }

                impl Log for ConsoleLog where {
                    info: { msg: Str } -> {} <Log, *> = { msg } -> {}
                }
            """.trimIndent()

            val program = parser.parse(source)

            val error = assertThrows<TypeCheckError> {
                typeChecker.typeCheck(program)
            }

            assertTrue(
                error.message?.contains("same effect", ignoreCase = true) == true ||
                error.message?.contains("cannot require", ignoreCase = true) == true,
                "Error should mention same effect, but got: ${error.message}"
            )
        }

        @Test
        fun `ability impl type parameter count mismatch should fail`() {
            val source = """
                ability Convert 'b for 'a where {
                    convert: {it: 'a} -> 'b
                }

                # Wrong: only one type parameter, ability expects two
                impl Convert for Int where {
                    convert = {it} -> "test"
                }
            """.trimIndent()

            val program = parser.parse(source)

            val error = assertThrows<TypeCheckError> {
                typeChecker.typeCheck(program)
            }

            assertTrue(
                error.message?.contains("mismatch", ignoreCase = true) == true ||
                error.message?.contains("parameter", ignoreCase = true) == true,
                "Error should mention type parameter mismatch, but got: ${error.message}"
            )
        }

        @Test
        fun `using ability method without impl should fail`() {
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                type MyType = | MyType |

                # No impl Show for MyType

                let m: MyType = MyType
                let result = m.show {}
            """.trimIndent()

            val program = parser.parse(source)

            val error = assertThrows<TypeCheckError> {
                typeChecker.typeCheck(program)
            }

            assertTrue(
                error.message?.contains("implementation", ignoreCase = true) == true ||
                error.message?.contains("impl", ignoreCase = true) == true ||
                error.message?.contains("Show", ignoreCase = true) == true,
                "Error should mention missing impl, but got: ${error.message}"
            )
        }
    }

    // ========================================================================
    // SECTION 11: COMPLEX SCENARIOS
    // ========================================================================

    @Nested
    inner class ComplexScenarioTests {

        @Test
        fun `chained ability method calls`() {
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                impl Show for Int where {
                    show = {it} -> "int"
                }

                impl Show for Str where {
                    show = {it} -> it
                }

                let a = 42.show {}
                let b = a.show {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `ability used in higher-order function`() {
            // Test that ability methods work in function composition
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                impl Show for Int where {
                    show = {it} -> "int"
                }

                impl Show for Str where {
                    show = {it} -> it
                }

                # Use ability in a simple function
                let showInt = { val: Int } -> val.show {}

                let a = showInt { val = 42 }
                let b = "hello".show {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `ability impl for custom variant type`() {
            // Test ability impl for a locally-defined variant type
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                type MyOption = | MySome { val: Int }, MyNone |

                impl Show for MyOption where {
                    show = {it} ->
                        match it is
                            MySome { val } -> "some"
                            MyNone -> "none"
                }

                let a: MyOption = MySome { val = 42 }
                let b: MyOption = MyNone
                let x = a.show {}
                let y = b.show {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `effect and ability in same module`() {
            val source = """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                effect Log where {
                    info: { msg: Str } -> {}
                }

                impl Show for Int where {
                    show = {it} -> "int"
                }

                impl Log for Console where {
                    info: { msg: Str } -> {} = { msg } -> {}
                }

                let result = 42.show {}
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `multiple modules with abilities and effects`() {
            // Create ability module
            val abilityMod = tempDir.resolve("abilities.mz")
            Files.writeString(abilityMod, """
                ability Show for 'a where {
                    show: {it: 'a} -> Str
                }

                ability Debug for 'a where {
                    debug: {it: 'a} -> Str
                }

                impl Show for Int where {
                    show = {it} -> "int"
                }

                impl Debug for Int where {
                    debug = {it} -> "debug_int"
                }
            """.trimIndent())

            // Create effect module
            val effectMod = tempDir.resolve("effects.mz")
            Files.writeString(effectMod, """
                effect Log where {
                    info: { msg: Str } -> {}
                }

                impl Log for Console where {
                    info: { msg: Str } -> {} = { msg } -> {}
                }
            """.trimIndent())

            // Create user module that uses both
            val userMod = tempDir.resolve("user.mz")
            Files.writeString(userMod, """
                use abilities::{Show, Debug}
                use effects::{Log, Console}

                let a = 42.show {}
                let b = 42.debug {}
            """.trimIndent())

            val userProgram = parser.parse(Files.readString(userMod))
            val result = typeChecker.typeCheck(userProgram, tempDir)
            assertNotNull(result.program)
        }

        @Test
        fun `import ability and use in nested call`() {
            // Create ability module with multiple impls
            val abilityMod = tempDir.resolve("formatting.mz")
            Files.writeString(abilityMod, """
                ability Format for 'a where {
                    format: {it: 'a} -> Str
                }

                impl Format for Int where {
                    format = {it} -> "int"
                }

                impl Format for Str where {
                    format = {it} -> it
                }
            """.trimIndent())

            // Create user module that uses the formatting ability
            val userMod = tempDir.resolve("user.mz")
            Files.writeString(userMod, """
                use formatting::{Format}

                # Use formatting in a nested expression
                let a = 42.format {}
                let b = "nested".format {}.format {}
            """.trimIndent())

            val userProgram = parser.parse(Files.readString(userMod))
            val result = typeChecker.typeCheck(userProgram, tempDir)
            assertNotNull(result.program)
        }
    }
}
