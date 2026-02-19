package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
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
                ability Show 'a where {
                    show: { val: 'a } -> String
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
                ability Convert 'a 'b where {
                    convert: { val: 'a } -> 'b
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
                ability Eq 'a where {
                    eq: { left: 'a, right: 'a } -> Bool,
                    neq: { left: 'a, right: 'a } -> Bool
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
                ability Container 'a where {
                    first: { container: 'a } -> Option Int,
                    size: { container: 'a } -> Int
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
                ability Serializable 'a where {
                    serialize: { value: 'a, format: String } -> String,
                    deserialize: { data: String, format: String } -> 'a
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
                    info: { msg: String } -> {},
                    warn: { msg: String } -> {},
                    error: { msg: String } -> {}
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
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                impl Show Int where {
                    show: { val: Int } -> String = { val } -> "int"
                }

                let result = Show.show { val = 42 }
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `impl ability for built-in type String`() {
            val source = """
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                impl Show String where {
                    show: { val: String } -> String = { val } -> val
                }

                let result = Show.show { val = "hello" }
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `impl ability for built-in type Bool`() {
            val source = """
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                impl Show Bool where {
                    show: { val: Bool } -> String = { val } ->
                        match val is
                            True -> "true"
                            False -> "false"
                }

                let result = Show.show { val = True }
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `impl ability for built-in type Float`() {
            val source = """
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                impl Show Float where {
                    show: { val: Float } -> String = { val } -> "float"
                }

                let result = Show.show { val = 3.14 }
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `impl ability for custom variant type`() {
            val source = """
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                type Color = | Red, Green, Blue |

                impl Show Color where {
                    show: { val: Color } -> String = { val } ->
                        match val is
                            Red -> "red"
                            Green -> "green"
                            Blue -> "blue"
                }

                let c: Color = Red
                let result = Show.show { val = c }
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `impl ability for custom variant type with payloads`() {
            val source = """
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                type Person = | Person { name: String, age: Int } |

                impl Show Person where {
                    show: { val: Person } -> String = { val } ->
                        match val is
                            Person { name, age } -> name
                }

                let p = Person { name = "Alice", age = 30 }
                let result = Show.show { val = p }
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `impl ability for record type alias`() {
            val source = """
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                type Point = { x: Int, y: Int }

                impl Show Point where {
                    show: { val: Point } -> String = { val } -> "point"
                }

                let p: Point = { x = 1, y = 2 }
                let result = Show.show { val = p }
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `impl multi-param ability`() {
            val source = """
                ability Convert 'a 'b where {
                    convert: { val: 'a } -> 'b
                }

                impl Convert Int String where {
                    convert: { val: Int } -> String = { val } -> "converted"
                }

                let result = Convert.convert { val = 42 }
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `impl ability with multiple operations`() {
            val source = """
                ability Describable 'a where {
                    describe: { val: 'a } -> String,
                    shortDesc: { val: 'a } -> String
                }

                impl Describable Int where {
                    describe: { val: Int } -> String = { val } -> "integer value",
                    shortDesc: { val: Int } -> String = { val } -> "int"
                }

                let a = Describable.describe { val = 42 }
                let b = Describable.shortDesc { val = 42 }
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `multiple impls for same ability different types`() {
            val source = """
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                impl Show Int where {
                    show: { val: Int } -> String = { val } -> "int"
                }

                impl Show String where {
                    show: { val: String } -> String = { val } -> val
                }

                impl Show Bool where {
                    show: { val: Bool } -> String = { val } ->
                        match val is
                            True -> "true"
                            False -> "false"
                }

                let a = Show.show { val = 42 }
                let b = Show.show { val = "hello" }
                let c = Show.show { val = True }
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `different abilities for same type`() {
            val source = """
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                ability Debug 'a where {
                    debug: { val: 'a } -> String
                }

                impl Show Int where {
                    show: { val: Int } -> String = { val } -> "show"
                }

                impl Debug Int where {
                    debug: { val: Int } -> String = { val } -> "debug"
                }

                let a = Show.show { val = 42 }
                let b = Debug.debug { val = 42 }
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
                    info: { msg: String } -> {}
                }

                impl Log for Console where {
                    info: { msg: String } -> {} = { msg } -> {}
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
                    info: { msg: String } -> {},
                    warn: { msg: String } -> {},
                    error: { msg: String } -> {}
                }

                impl Log for Console where {
                    info: { msg: String } -> {} = { msg } -> {},
                    warn: { msg: String } -> {} = { msg } -> {},
                    error: { msg: String } -> {} = { msg } -> {}
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
                    info: { msg: String } -> {}
                }

                impl Log for Console where {
                    info: { msg: String } -> {} = { msg } -> {}
                }

                impl Log for FileLogger where {
                    info: { msg: String } -> {} = { msg } -> {}
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
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                impl Show Int where {
                    show: { val: Int } -> String = { val } -> "int"
                }
            """.trimIndent())

            // Create user module
            val userMod = tempDir.resolve("user.mz")
            Files.writeString(userMod, """
                use showable::{Show}

                let result = Show.show { val = 42 }
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
                    info: { msg: String } -> {}
                }

                impl Log for Console where {
                    info: { msg: String } -> {} = { msg } -> {}
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
                pub type Person = | Person { name: String, age: Int } |
            """.trimIndent())

            // Create user module that defines ability and impls for imported type
            val userMod = tempDir.resolve("user.mz")
            Files.writeString(userMod, """
                use types::{Person}

                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                impl Show Person where {
                    show: { val: Person } -> String = { val } ->
                        match val is
                            Person { name, age } -> name
                }

                let p = Person { name = "Alice", age = 30 }
                let result = Show.show { val = p }
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
                ability Serializable 'a where {
                    serialize: { value: 'a } -> String
                }
            """.trimIndent())

            // Create user module with qualified ability name
            val userMod = tempDir.resolve("user.mz")
            Files.writeString(userMod, """
                use serializer

                type MyData = | MyData { val: String } |

                impl serializer::Serializable MyData where {
                    serialize: { value: MyData } -> String = { value } ->
                        match value is
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
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                impl Show Int where {
                    show: { val: Int } -> String = { val } -> "int"
                }
            """.trimIndent())

            // Create user module with alias
            val userMod = tempDir.resolve("user.mz")
            Files.writeString(userMod, """
                use showable as s

                let result = s::Show.show { val = 42 }
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
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                impl Show Int where {
                    show: { val: Int } -> String = { val } -> "int"
                }

                # Auto-resolve impl Show Int
                let result = Show.show { val = 42 }
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `ability impl is auto-resolved based on argument type`() {
            val source = """
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                impl Show Int where {
                    show: { val: Int } -> String = { val } -> "int"
                }

                impl Show String where {
                    show: { val: String } -> String = { val } -> val
                }

                # Should resolve to Show Int
                let a = Show.show { val = 42 }

                # Should resolve to Show String
                let b = Show.show { val = "hello" }
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `ability impl is auto-resolved for custom type`() {
            val source = """
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                type Color = | Red, Green, Blue |

                impl Show Color where {
                    show: { val: Color } -> String = { val } ->
                        match val is
                            Red -> "red"
                            Green -> "green"
                            Blue -> "blue"
                }

                let c: Color = Red
                let result = Show.show { val = c }
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
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                impl Show Int where {
                    show: { val: Int } -> String = { val } -> "int"
                }
            """.trimIndent())

            // Create user module - should auto-resolve imported impl
            val userMod = tempDir.resolve("user.mz")
            Files.writeString(userMod, """
                use showable::{Show}

                # Should auto-resolve to impl Show Int from showable
                let result = Show.show { val = 42 }
            """.trimIndent())

            val userProgram = parser.parse(Files.readString(userMod))
            val result = typeChecker.typeCheck(userProgram, tempDir)
            assertNotNull(result.program)
        }

        @Test
        fun `multi-param ability impl auto-resolution`() {
            val source = """
                ability Convert 'a 'b where {
                    convert: { val: 'a } -> 'b
                }

                impl Convert Int String where {
                    convert: { val: Int } -> String = { val } -> "converted"
                }

                impl Convert String Int where {
                    convert: { val: String } -> Int = { val } -> 0
                }

                # Should resolve based on argument and expected return type
                let a: String = Convert.convert { val = 42 }
                let b: Int = Convert.convert { val = "hello" }
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
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                impl Show Int where {
                    show: { val: Int } -> String = { val } -> "int"
                }

                let result = Show.show { val = 42 }
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `impl in same module as type is valid`() {
            val source = """
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                type Person = | Person { name: String } |

                impl Show Person where {
                    show: { val: Person } -> String = { val } ->
                        match val is
                            Person { name } -> name
                }

                let p = Person { name = "Alice" }
                let result = Show.show { val = p }
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
                ability Printable 'a where {
                    print: { val: 'a } -> String
                }
            """.trimIndent())

            // Create orphan module - imports ability, impls for built-in
            val orphanMod = tempDir.resolve("orphan.mz")
            Files.writeString(orphanMod, """
                use my_ability::{Printable}

                # Orphan: Printable is imported, Int is built-in
                impl Printable Int where {
                    print: { val: Int } -> String = { val } -> "int"
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
                ability Show 'a where {
                    show: { val: 'a } -> String
                }
            """.trimIndent())

            // Create type module
            val typeMod = tempDir.resolve("type_mod.mz")
            Files.writeString(typeMod, """
                pub type Person = { name: String, age: Int }
            """.trimIndent())

            // Create orphan module
            val orphanMod = tempDir.resolve("orphan_mod.mz")
            Files.writeString(orphanMod, """
                use ability_mod::{Show}
                use type_mod::{Person}

                # Orphan: both Show and Person are imported
                impl Show Person where {
                    show: { val: Person } -> String = { val } -> val.name
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
                ability Show 'a where {
                    show: { val: 'a } -> String
                }
            """.trimIndent())

            // Create user module with local type
            val userMod = tempDir.resolve("user.mz")
            Files.writeString(userMod, """
                use showable::{Show}

                # Local type - impl is valid
                type MyInt = | MyInt { val: Int } |

                impl Show MyInt where {
                    show: { val: MyInt } -> String = { val } ->
                        match val is
                            MyInt { val } -> "myint"
                }

                let m = MyInt { val = 42 }
                let result = Show.show { val = m }
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
                pub type Person = | Person { name: String } |
            """.trimIndent())

            // Create user module with local ability
            val userMod = tempDir.resolve("user.mz")
            Files.writeString(userMod, """
                use types::{Person}

                # Local ability - impl is valid
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                impl Show Person where {
                    show: { val: Person } -> String = { val } ->
                        match val is
                            Person { name } -> name
                }

                let p = Person { name = "Alice" }
                let result = Show.show { val = p }
            """.trimIndent())

            val userProgram = parser.parse(Files.readString(userMod))
            val result = typeChecker.typeCheck(userProgram, tempDir)
            assertNotNull(result.program)
        }

        @Test
        fun `multi-param ability - impl valid if any target type is local`() {
            val source = """
                ability Convert 'a 'b where {
                    convert: { val: 'a } -> 'b
                }

                type Celsius = | Celsius { degrees: Float } |
                type Fahrenheit = | Fahrenheit { degrees: Float } |

                # Valid: both types are local
                impl Convert Celsius Fahrenheit where {
                    convert: { val: Celsius } -> Fahrenheit = { val } ->
                        match val is
                            Celsius { degrees } -> Fahrenheit { degrees = degrees * 1.8 + 32.0 }
                }

                let c = Celsius { degrees = 100.0 }
                let f = Convert.convert { val = c }
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
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                ability Show 'a where {
                    show: { val: 'a } -> String
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
                    info: { msg: String } -> {}
                }

                effect Log where {
                    info: { msg: String } -> {}
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
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                impl Show Int where {
                    show: { val: Int } -> String = { val } -> "first"
                }

                impl Show Int where {
                    show: { val: Int } -> String = { val } -> "second"
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
                    info: { msg: String } -> {}
                }

                impl Log for Console where {
                    info: { msg: String } -> {} = { msg } -> {}
                }

                impl Log for Console where {
                    info: { msg: String } -> {} = { msg } -> {}
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
                ability Convert 'a 'b where {
                    convert: { val: 'a } -> 'b
                }

                impl Convert Int String where {
                    convert: { val: Int } -> String = { val } -> "first"
                }

                impl Convert Int String where {
                    convert: { val: Int } -> String = { val } -> "second"
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
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                ability Debug 'a where {
                    debug: { val: 'a } -> String
                }

                impl Show Int where {
                    show: { val: Int } -> String = { val } -> "show"
                }

                impl Debug Int where {
                    debug: { val: Int } -> String = { val } -> "debug"
                }

                let a = Show.show { val = 42 }
                let b = Debug.debug { val = 42 }
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `same ability for different types is valid - not duplicate`() {
            val source = """
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                impl Show Int where {
                    show: { val: Int } -> String = { val } -> "int"
                }

                impl Show String where {
                    show: { val: String } -> String = { val } -> val
                }

                let a = Show.show { val = 42 }
                let b = Show.show { val = "hello" }
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `different performers for same effect is valid - not duplicate`() {
            val source = """
                effect Log where {
                    info: { msg: String } -> {}
                }

                impl Log for Console where {
                    info: { msg: String } -> {} = { msg } -> {}
                }

                impl Log for FileLogger where {
                    info: { msg: String } -> {} = { msg } -> {}
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
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                impl Show Int where {
                    show: { val: Int } -> String = { val } -> "int"
                }

                let result = Show.show { val = 42 }
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `effect without pub keyword works`() {
            val source = """
                effect Log where {
                    info: { msg: String } -> {}
                }

                impl Log for Console where {
                    info: { msg: String } -> {} = { msg } -> {}
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
                pub ability Show 'a where {
                    show: { val: 'a } -> String
                }
            """.trimIndent()

            val program = parser.parse(source)

            val error = assertThrows<TypeCheckError> {
                typeChecker.typeCheck(program)
            }

            assertTrue(
                error.message?.contains("pub", ignoreCase = true) == true &&
                error.message?.contains("not needed", ignoreCase = true) == true,
                "Error should mention pub is not needed, but got: ${error.message}"
            )
        }

        @Test
        fun `pub keyword on effect should fail with helpful message`() {
            val source = """
                pub effect Log where {
                    info: { msg: String } -> {}
                }
            """.trimIndent()

            val program = parser.parse(source)

            val error = assertThrows<TypeCheckError> {
                typeChecker.typeCheck(program)
            }

            assertTrue(
                error.message?.contains("pub", ignoreCase = true) == true &&
                error.message?.contains("not needed", ignoreCase = true) == true,
                "Error should mention pub is not needed, but got: ${error.message}"
            )
        }

        @Test
        fun `ability without pub is exported from module`() {
            // Create ability module WITHOUT pub
            val abilityMod = tempDir.resolve("showable.mz")
            Files.writeString(abilityMod, """
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                impl Show Int where {
                    show: { val: Int } -> String = { val } -> "int"
                }
            """.trimIndent())

            // Import in another module - should work
            val userMod = tempDir.resolve("user.mz")
            Files.writeString(userMod, """
                use showable::{Show}

                let result = Show.show { val = 42 }
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
                    info: { msg: String } -> {}
                }

                impl Log for Console where {
                    info: { msg: String } -> {} = { msg } -> {}
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
                ability Eq 'a where {
                    eq: { left: 'a, right: 'a } -> Bool,
                    neq: { left: 'a, right: 'a } -> Bool
                }

                impl Eq Int where {
                    eq: { left: Int, right: Int } -> Bool = { left, right } -> True
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
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                impl Show Int where {
                    show: { val: Int } -> String = { val } -> "int",
                    extra: { val: Int } -> String = { val } -> "extra"
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
                impl NonExistent Int where {
                    op: { val: Int } -> String = { val } -> "test"
                }
            """.trimIndent()

            val program = parser.parse(source)

            val error = assertThrows<TypeCheckError> {
                typeChecker.typeCheck(program)
            }

            assertTrue(
                error.message?.contains("not found", ignoreCase = true) == true,
                "Error should mention ability not found, but got: ${error.message}"
            )
        }

        @Test
        fun `effect impl with wrong operation parameter type should fail`() {
            // Effect declares { msg: String }, but impl provides { msg: Int }
            val source = """
                effect Log where {
                    info: { msg: String } -> {}
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
                    info: { msg: String } -> {}
                }

                impl Log for Console where {
                    info: { message: String } -> {} = { message } -> {}
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
                    info: { msg: String } -> {}
                }

                impl Log for Console where {
                    info: { msg: String } -> {} = { msg } -> {}
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
                    info: { msg: String } -> {} <Log>
                }

                impl Log for Console where {
                    info: { msg: String } -> {} <Log> = { msg } -> {}
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
                    info: { msg: String } -> {} <Log>
                }

                effect IO where {
                    read: {} -> String
                }

                impl Log for Console where {
                    info: { msg: String } -> {} <Log, IO> = { msg } -> {}
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
                    info: { msg: String } -> {} <Log>
                }

                effect IO where {
                    write: { data: String } -> {}
                }

                impl Log for ConsoleLog where {
                    info: { msg: String } -> {} <IO> = { msg } -> {}
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
                    info: { msg: String } -> {} <Log>
                }

                impl Log for ConsoleLog where {
                    info: { msg: String } -> {} <*> = { msg } -> {}
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
                    info: { msg: String } -> {} <Log>
                }

                effect IO where {
                    write: { data: String } -> {}
                }

                effect Filesystem where {
                    read: { path: String } -> String
                }

                impl Log for ConsoleLog where {
                    info: { msg: String } -> {} <IO, Filesystem> = { msg } -> {}
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
                    info: { msg: String } -> {} <Log>
                }

                impl Log for ConsoleLog where {
                    info: { msg: String } -> {} <Log, *> = { msg } -> {}
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
                ability Convert 'a 'b where {
                    convert: { val: 'a } -> 'b
                }

                # Wrong: only one type parameter, ability expects two
                impl Convert Int where {
                    convert: { val: Int } -> String = { val } -> "test"
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
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                type MyType = | MyType |

                # No impl Show MyType

                let m: MyType = MyType
                let result = Show.show { val = m }
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
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                impl Show Int where {
                    show: { val: Int } -> String = { val } -> "int"
                }

                impl Show String where {
                    show: { val: String } -> String = { val } -> val
                }

                let a = Show.show { val = 42 }
                let b = Show.show { val = a }
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `ability used in higher-order function`() {
            // Test that ability methods work in function composition
            val source = """
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                impl Show Int where {
                    show: { val: Int } -> String = { val } -> "int"
                }

                impl Show String where {
                    show: { val: String } -> String = { val } -> val
                }

                # Use ability in a simple function
                let showInt = { val: Int } -> Show.show { val = val }

                let a = showInt { val = 42 }
                let b = Show.show { val = "hello" }
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `ability impl for custom variant type`() {
            // Test ability impl for a locally-defined variant type
            val source = """
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                type MyOption = | MySome { val: Int }, MyNone |

                impl Show MyOption where {
                    show: { val: MyOption } -> String = { val } ->
                        match val is
                            MySome { val } -> "some"
                            MyNone -> "none"
                }

                let a: MyOption = MySome { val = 42 }
                let b: MyOption = MyNone
                let x = Show.show { val = a }
                let y = Show.show { val = b }
            """.trimIndent()

            val program = parser.parse(source)
            val result = typeChecker.typeCheck(program)
            assertNotNull(result.program)
        }

        @Test
        fun `effect and ability in same module`() {
            val source = """
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                effect Log where {
                    info: { msg: String } -> {}
                }

                impl Show Int where {
                    show: { val: Int } -> String = { val } -> "int"
                }

                impl Log for Console where {
                    info: { msg: String } -> {} = { msg } -> {}
                }

                let result = Show.show { val = 42 }
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
                ability Show 'a where {
                    show: { val: 'a } -> String
                }

                ability Debug 'a where {
                    debug: { val: 'a } -> String
                }

                impl Show Int where {
                    show: { val: Int } -> String = { val } -> "int"
                }

                impl Debug Int where {
                    debug: { val: Int } -> String = { val } -> "debug_int"
                }
            """.trimIndent())

            // Create effect module
            val effectMod = tempDir.resolve("effects.mz")
            Files.writeString(effectMod, """
                effect Log where {
                    info: { msg: String } -> {}
                }

                impl Log for Console where {
                    info: { msg: String } -> {} = { msg } -> {}
                }
            """.trimIndent())

            // Create user module that uses both
            val userMod = tempDir.resolve("user.mz")
            Files.writeString(userMod, """
                use abilities::{Show, Debug}
                use effects::{Log, Console}

                let a = Show.show { val = 42 }
                let b = Debug.debug { val = 42 }
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
                ability Format 'a where {
                    format: { val: 'a } -> String
                }

                impl Format Int where {
                    format: { val: Int } -> String = { val } -> "int"
                }

                impl Format String where {
                    format: { val: String } -> String = { val } -> val
                }
            """.trimIndent())

            // Create user module that uses the formatting ability
            val userMod = tempDir.resolve("user.mz")
            Files.writeString(userMod, """
                use formatting::{Format}

                # Use formatting in a nested expression
                let a = Format.format { val = 42 }
                let b = Format.format { val = Format.format { val = "nested" } }
            """.trimIndent())

            val userProgram = parser.parse(Files.readString(userMod))
            val result = typeChecker.typeCheck(userProgram, tempDir)
            assertNotNull(result.program)
        }
    }
}
