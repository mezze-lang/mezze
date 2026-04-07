package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import com.mezze.language.parser.ParseException
import com.mezze.language.ast.LetBinding
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for ability impl orphan rules.
 *
 * Following Roc/Rust, an `impl Ability for Type` must be defined in the same module as either:
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
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            # Impl is in same module as ability - OK
            impl Show for Int where {
                show = {it} -> "int"
            }

            let result = 42.show {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Should succeed
        assertNotNull(result)
    }

    @Test
    fun `impl for built-in type in same module as ability is valid`() {
        // Built-in types (Int, Str, Bool) have no module, so impl must be with ability
        val source = """
            ability Printable for 'a where {
                print: {it: 'a} -> Str
            }

            impl Printable for Int where {
                print = {it} -> "int"
            }

            impl Printable for Str where {
                print = {it} -> it
            }

            impl Printable for Bool where {
                print = {it} ->
                    match it is
                        True -> "true"
                        False -> "false"
            }

            let x = 42.print {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(result)
    }

    // ========================================================================
    // VALID CASES - Impl in same module as type
    // ========================================================================

    @Test
    fun `impl in same module as type is valid`() {
        val source = """
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            # Define a custom variant type
            type Person = | Person { name: Str, age: Int } |

            # Impl is in same module as Person type - OK
            impl Show for Person where {
                show = {it} ->
                    match it is
                        Person { name, age } -> name
            }

            let p = Person { name = "Alice", age = 30 }
            let result = p.show {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(result)
    }

    @Test
    fun `impl for variant type in same module is valid`() {
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

            # Type annotation needed so c has type Color, not just [Red]
            let c: Color = Red
            let result = c.show {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(result)
    }

    // ========================================================================
    // VALID CASES - Multi-parameter abilities
    // ========================================================================

    @Test
    fun `multi-param ability impl in module of any target type is valid`() {
        val source = """
            ability Convert 'b for 'a where {
                convert: {it: 'a} -> 'b
            }

            type Celsius = | Celsius { degrees: Float } |
            type Fahrenheit = | Fahrenheit { degrees: Float } |

            # Impl in same module as both Celsius and Fahrenheit - OK
            impl Convert for Fahrenheit Celsius where {
                convert = {it} ->
                    match it is
                        Celsius { degrees } -> Fahrenheit { degrees = degrees * 1.8 + 32.0 }
            }

            let c = Celsius { degrees = 100.0 }
            let f = c.convert {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(result)
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
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }
        """.trimIndent())

        val typeMod = tempDir.resolve("type_mod.mz")
        Files.writeString(typeMod, """
            pub type Person = { name: Str, age: Int }
        """.trimIndent())

        val orphanMod = tempDir.resolve("orphan_mod.mz")
        Files.writeString(orphanMod, """
            use ability_mod::{Show}
            use type_mod::{Person}

            # This is an orphan impl - neither Show nor Person is defined here
            impl Show for Person where {
                show = {it} -> it.name
            }
        """.trimIndent())

        val orphanParsedModule = parser.parseParsedModule(Files.readString(orphanMod), "test.mz")

        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(orphanParsedModule, tempDir)
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
            ability Printable for 'a where {
                print: {it: 'a} -> Str
            }
        """.trimIndent())

        val orphanMod = tempDir.resolve("orphan.mz")
        Files.writeString(orphanMod, """
            use my_ability::{Printable}

            # Orphan: Printable is from my_ability, Int is built-in
            impl Printable for Int where {
                print = {it} -> "int"
            }
        """.trimIndent())

        val orphanParsedModule = parser.parseParsedModule(Files.readString(orphanMod), "test.mz")

        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(orphanParsedModule, tempDir)
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

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
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
        // Both modules try to define impl Show for Int
        // Note: impls are always exported (no 'pub' keyword needed)

        val mod1 = tempDir.resolve("mod1.mz")
        Files.writeString(mod1, """
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            impl Show for Int where {
                show = {it} -> "from mod1"
            }
        """.trimIndent())

        val mod2 = tempDir.resolve("mod2.mz")
        Files.writeString(mod2, """
            use mod1::{Show}

            # This would be a duplicate if orphan rules didn't catch it first
            # But even if orphan rules pass, duplicate check should fail
            impl Show for Int where {
                show = {it} -> "from mod2"
            }
        """.trimIndent())

        val mod2ParsedModule = parser.parseParsedModule(Files.readString(mod2), "test.mz")

        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(mod2ParsedModule, tempDir)
        }

        // Could be caught by either orphan rules or duplicate check
        assertNotNull(error.message)
    }

    @Test
    fun `different abilities for same type is valid`() {
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

            let x = 42.show {}
            let y = 42.debug {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(result)
    }

    @Test
    fun `same ability for different types is valid`() {
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

            let x = 42.show {}
            let y = "hello".show {}
            let z = True.show {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(result)
    }

    // ========================================================================
    // VISIBILITY TESTS (for Task #2 - ability impls implicitly public)
    // ========================================================================

    @Test
    fun `ability impl is visible when ability is imported`() {
        // Impl should be auto-visible when you import the ability

        val abilityMod = tempDir.resolve("showable.mz")
        Files.writeString(abilityMod, """
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            # Impl in same module as ability (valid)
            impl Show for Int where {
                show = {it} -> "int"
            }
        """.trimIndent())

        val userMod = tempDir.resolve("user.mz")
        Files.writeString(userMod, """
            use showable::{Show}

            # Should be able to use impl Show for Int without explicit import
            let result = 42.show {}
        """.trimIndent())

        val userParsedModule = parser.parseParsedModule(Files.readString(userMod), "test.mz")
        val result = typeChecker.typeCheck(userParsedModule, tempDir)

        assertNotNull(result)
    }

    @Test
    fun `ability impl is visible when type is imported`() {
        // Impl should be auto-visible when you import the type

        val typeAndImplMod = tempDir.resolve("person.mz")
        Files.writeString(typeAndImplMod, """
            use showable::{Show}

            pub type Person = | Person { name: Str } |

            pub let make_person = {name} -> Person {name}

            # Impl in same module as type (valid)
            impl Show for Person where {
                show = {it} ->
                    match it is
                        Person {name} -> name
            }
        """.trimIndent())

        val showableMod = tempDir.resolve("showable.mz")
        Files.writeString(showableMod, """
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }
        """.trimIndent())

        val userMod = tempDir.resolve("user.mz")
        Files.writeString(userMod, """
            use showable::{Show}
            use person::{Person, make_person}

            # Should be able to use impl Show for Person - it comes with Person
            let p = make_person {name = "Alice"}
            let result = p.show {}
        """.trimIndent())

        val userParsedModule = parser.parseParsedModule(Files.readString(userMod), "test.mz")
        val result = typeChecker.typeCheck(userParsedModule, tempDir)

        assertNotNull(result)
    }

    // ========================================================================
    // PUB KEYWORD NOT NEEDED TESTS
    // ========================================================================

    @Test
    fun `pub keyword on ability should fail with helpful message`() {
        val source = """
            pub ability Show for 'a where {
                show: {it: 'a} -> Str
            }
        """.trimIndent()

        // Parser now rejects 'pub' before ability
        val error = assertThrows<ParseException> {
            parser.parseParsedModule(source, "test.mz")
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
            parser.parseParsedModule(source, "test.mz")
        }

        assertTrue(
            error.message?.contains("'pub' is not allowed on effect declarations") == true,
            "Error should mention pub is not allowed, but got: ${error.message}"
        )
    }

    @Test
    fun `ability without pub keyword should work`() {
        val source = """
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            impl Show for Int where {
                show = {it} -> "int"
            }

            let x = 42.show {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(result)
    }

    @Test
    fun `effect without pub keyword should work`() {
        val source = """
            effect Log where {
                info: { msg: Str } -> {}
            }

            impl Log for Console where {
                info: { msg: Str } -> {} = { msg } -> {}
            }

            let x = 42
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(result)
    }
}
