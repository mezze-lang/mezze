package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.parser.ParseException
import com.mezze.language.ast.LetBinding
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for ability impl orphan rules.
 *
 * Following Roc/Rust, an `impl Ability Type` must be defined in the same module as either:
 * 1. The ability declaration, OR
 * 2. One of the target types
 *
 * This prevents "orphan impls" where a third-party module defines impls for
 * types and abilities it doesn't own, which can cause coherence issues.
 */
class AbilityOrphanRulesTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker
    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
        tempDir = Files.createTempDirectory("mezze_orphan_test")
    }

    // ========================================================================
    // VALID CASES - Impl in same module as ability
    // ========================================================================

    @Test
    fun `impl in same module as ability is valid`() {
        val source = """
            ability Show 'a where {
                show: { val: 'a } -> String
            }

            # Impl is in same module as ability - OK
            impl Show Int where {
                show: { val: Int } -> String = { val } -> "int"
            }

            let result = Show.show { val = 42 }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)

        // Should succeed
        assertNotNull(result.program)
    }

    @Test
    fun `impl for built-in type in same module as ability is valid`() {
        // Built-in types (Int, String, Bool) have no module, so impl must be with ability
        val source = """
            ability Printable 'a where {
                print: { val: 'a } -> String
            }

            impl Printable Int where {
                print: { val: Int } -> String = { val } -> "int"
            }

            impl Printable String where {
                print: { val: String } -> String = { val } -> val
            }

            impl Printable Bool where {
                print: { val: Bool } -> String = { val } ->
                    match val is
                        True -> "true"
                        False -> "false"
            }

            let x = Printable.print { val = 42 }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)

        assertNotNull(result.program)
    }

    // ========================================================================
    // VALID CASES - Impl in same module as type
    // ========================================================================

    @Test
    fun `impl in same module as type is valid`() {
        val source = """
            ability Show 'a where {
                show: { val: 'a } -> String
            }

            # Define a custom variant type
            type Person = | Person { name: String, age: Int } |

            # Impl is in same module as Person type - OK
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
    fun `impl for variant type in same module is valid`() {
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

            # Type annotation needed so c has type Color, not just [Red]
            let c: Color = Red
            let result = Show.show { val = c }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)

        assertNotNull(result.program)
    }

    // ========================================================================
    // VALID CASES - Multi-parameter abilities
    // ========================================================================

    @Test
    fun `multi-param ability impl in module of any target type is valid`() {
        val source = """
            ability Convert 'a 'b where {
                convert: { val: 'a } -> 'b
            }

            type Celsius = | Celsius { degrees: Float } |
            type Fahrenheit = | Fahrenheit { degrees: Float } |

            # Impl in same module as both Celsius and Fahrenheit - OK
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

    // ========================================================================
    // INVALID CASES - Orphan impls (cross-module)
    // ========================================================================

    @Test
    fun `orphan impl in third module should fail`() {
        // Create module structure:
        // - ability_mod.mz: defines Show ability
        // - type_mod.mz: defines Person type
        // - orphan_mod.mz: tries to impl Show for Person (SHOULD FAIL)

        val abilityMod = tempDir.resolve("ability_mod.mz")
        Files.writeString(abilityMod, """
            ability Show 'a where {
                show: { val: 'a } -> String
            }
        """.trimIndent())

        val typeMod = tempDir.resolve("type_mod.mz")
        Files.writeString(typeMod, """
            pub type Person = { name: String, age: Int }
        """.trimIndent())

        val orphanMod = tempDir.resolve("orphan_mod.mz")
        Files.writeString(orphanMod, """
            use ability_mod::{Show}
            use type_mod::{Person}

            # This is an orphan impl - neither Show nor Person is defined here
            impl Show Person where {
                show: { val: Person } -> String = { val } -> val.name
            }
        """.trimIndent())

        val orphanProgram = parser.parse(Files.readString(orphanMod))

        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(orphanProgram, tempDir)
        }

        assertTrue(
            error.message?.contains("Orphan", ignoreCase = true) == true ||
            error.message?.contains("must be in the same module") == true,
            "Error should mention orphan impl, but got: ${error.message}"
        )
    }

    @Test
    fun `orphan impl for imported ability and built-in type should fail`() {
        // Importing an ability and trying to impl it for a built-in type
        // in a different module should fail

        val abilityMod = tempDir.resolve("my_ability.mz")
        Files.writeString(abilityMod, """
            ability Printable 'a where {
                print: { val: 'a } -> String
            }
        """.trimIndent())

        val orphanMod = tempDir.resolve("orphan.mz")
        Files.writeString(orphanMod, """
            use my_ability::{Printable}

            # Orphan: Printable is from my_ability, Int is built-in
            impl Printable Int where {
                print: { val: Int } -> String = { val } -> "int"
            }
        """.trimIndent())

        val orphanProgram = parser.parse(Files.readString(orphanMod))

        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(orphanProgram, tempDir)
        }

        assertTrue(
            error.message?.contains("Orphan", ignoreCase = true) == true ||
            error.message?.contains("must be in the same module") == true,
            "Error should mention orphan impl, but got: ${error.message}"
        )
    }

    // ========================================================================
    // DUPLICATE IMPL TESTS
    // ========================================================================

    @Test
    fun `duplicate impl in same file should fail`() {
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
            error.message?.contains("duplicate") == true ||
            error.message?.contains("Conflicting") == true ||
            error.message?.contains("already") == true,
            "Error should mention duplicate impl, but got: ${error.message}"
        )
    }

    @Test
    fun `duplicate impl across modules should fail`() {
        // Both modules try to define impl Show Int
        // Note: impls are always exported (no 'pub' keyword needed)

        val mod1 = tempDir.resolve("mod1.mz")
        Files.writeString(mod1, """
            ability Show 'a where {
                show: { val: 'a } -> String
            }

            impl Show Int where {
                show: { val: Int } -> String = { val } -> "from mod1"
            }
        """.trimIndent())

        val mod2 = tempDir.resolve("mod2.mz")
        Files.writeString(mod2, """
            use mod1::{Show}

            # This would be a duplicate if orphan rules didn't catch it first
            # But even if orphan rules pass, duplicate check should fail
            impl Show Int where {
                show: { val: Int } -> String = { val } -> "from mod2"
            }
        """.trimIndent())

        val mod2Program = parser.parse(Files.readString(mod2))

        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(mod2Program, tempDir)
        }

        // Could be caught by either orphan rules or duplicate check
        assertNotNull(error.message)
    }

    @Test
    fun `different abilities for same type is valid`() {
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

            let x = Show.show { val = 42 }
            let y = Debug.debug { val = 42 }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)

        assertNotNull(result.program)
    }

    @Test
    fun `same ability for different types is valid`() {
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

            let x = Show.show { val = 42 }
            let y = Show.show { val = "hello" }
            let z = Show.show { val = True }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)

        assertNotNull(result.program)
    }

    // ========================================================================
    // VISIBILITY TESTS (for Task #2 - ability impls implicitly public)
    // ========================================================================

    @Test
    fun `ability impl is visible when ability is imported`() {
        // Impl should be auto-visible when you import the ability

        val abilityMod = tempDir.resolve("showable.mz")
        Files.writeString(abilityMod, """
            ability Show 'a where {
                show: { val: 'a } -> String
            }

            # Impl in same module as ability (valid)
            impl Show Int where {
                show: { val: Int } -> String = { val } -> "int"
            }
        """.trimIndent())

        val userMod = tempDir.resolve("user.mz")
        Files.writeString(userMod, """
            use showable::{Show}

            # Should be able to use impl Show Int without explicit import
            let result = Show.show { val = 42 }
        """.trimIndent())

        val userProgram = parser.parse(Files.readString(userMod))
        val result = typeChecker.typeCheck(userProgram, tempDir)

        assertNotNull(result.program)
    }

    @Test
    fun `ability impl is visible when type is imported`() {
        // Impl should be auto-visible when you import the type

        val typeAndImplMod = tempDir.resolve("person.mz")
        Files.writeString(typeAndImplMod, """
            use showable::{Show}

            pub type Person = { name: String }

            # Impl in same module as type (valid)
            impl Show Person where {
                show: { val: Person } -> String = { val } -> val.name
            }
        """.trimIndent())

        val showableMod = tempDir.resolve("showable.mz")
        Files.writeString(showableMod, """
            ability Show 'a where {
                show: { val: 'a } -> String
            }
        """.trimIndent())

        val userMod = tempDir.resolve("user.mz")
        Files.writeString(userMod, """
            use showable::{Show}
            use person::{Person}

            # Should be able to use impl Show Person - it comes with Person
            # No type annotation needed - Person is in scope, impl should be found
            let p = { name = "Alice" }
            let result = Show.show { val = p }
        """.trimIndent())

        val userProgram = parser.parse(Files.readString(userMod))
        val result = typeChecker.typeCheck(userProgram, tempDir)

        assertNotNull(result.program)
    }

    // ========================================================================
    // PUB KEYWORD NOT NEEDED TESTS
    // ========================================================================

    @Test
    fun `pub keyword on ability should fail with helpful message`() {
        val source = """
            pub ability Show 'a where {
                show: { val: 'a } -> String
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
                info: { msg: String } -> {}
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
    fun `ability without pub keyword should work`() {
        val source = """
            ability Show 'a where {
                show: { val: 'a } -> String
            }

            impl Show Int where {
                show: { val: Int } -> String = { val } -> "int"
            }

            let x = Show.show { val = 42 }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)

        assertNotNull(result.program)
    }

    @Test
    fun `effect without pub keyword should work`() {
        val source = """
            effect Log where {
                info: { msg: String } -> {}
            }

            impl Log for Console where {
                info: { msg: String } -> {} = { msg } -> {}
            }

            let x = 42
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)

        assertNotNull(result.program)
    }
}
