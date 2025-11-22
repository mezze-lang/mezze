package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for Bug #8: Ability and effect name validation
 * - Abilities and effects cannot be used as values
 * - Type names cannot conflict with ability/effect names
 * - Variant constructor names cannot conflict with ability/effect names
 */
class AbilityEffectNameCollisionTest {
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String) = typeChecker.typeCheck(parser.parse(code))

    // ========== BUG #8a: ABILITIES CANNOT BE USED AS VALUES ==========

    @Test
    fun `test built-in ability Eq cannot be used as value`() {
        val code = """
            let x = Eq
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Ability") == true ||
                   exception.message?.contains("cannot be used as a value") == true)
    }

    @Test
    fun `test built-in ability Ord cannot be used as value`() {
        val code = """
            let x = Ord
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Ability") == true ||
                   exception.message?.contains("cannot be used as a value") == true)
    }

    @Test
    fun `test built-in ability Inspect cannot be used as value`() {
        val code = """
            let x = Inspect
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Ability") == true ||
                   exception.message?.contains("cannot be used as a value") == true)
    }

    @Test
    fun `test built-in ability NumAdd cannot be used as value`() {
        val code = """
            let x = NumAdd
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Ability") == true ||
                   exception.message?.contains("cannot be used as a value") == true)
    }

    @Test
    fun `test user-defined ability cannot be used as value`() {
        val code = """
            ability Show 'a where {
                show: {val: 'a} -> String
            }

            let x = Show
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Ability") == true ||
                   exception.message?.contains("cannot be used as a value") == true)
    }

    // ========== BUG #8b: EFFECTS CANNOT BE USED AS VALUES ==========

    @Test
    fun `test user-defined effect cannot be used as value`() {
        val code = """
            effect Console {
                print: {msg: String} -> {}
            }

            let x = Console
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("effect") == true ||
                   exception.message?.contains("cannot be used as a value") == true)
    }

    // ========== BUG #8c: NAME COLLISION PREVENTION ==========

    @Test
    fun `test type name cannot conflict with built-in ability Eq`() {
        val code = """
            type Eq = { x: Int }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Cannot define type") == true ||
                   exception.message?.contains("built-in ability") == true)
    }

    @Test
    fun `test type name cannot conflict with built-in ability Ord`() {
        val code = """
            type Ord = | Less, Equal, Greater |
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Cannot define type") == true ||
                   exception.message?.contains("built-in ability") == true)
    }

    @Test
    fun `test type name cannot conflict with built-in ability NumAdd`() {
        val code = """
            type NumAdd = { value: Int }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Cannot define type") == true ||
                   exception.message?.contains("built-in ability") == true)
    }

    @Test
    fun `test variant constructor cannot be named Eq`() {
        val code = """
            type Comparison = | Eq, NotEq |
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Cannot define variant constructor") == true ||
                   exception.message?.contains("built-in ability") == true)
    }

    @Test
    fun `test variant constructor cannot be named Ord`() {
        val code = """
            type MyType = | Ord, Other |
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Cannot define variant constructor") == true ||
                   exception.message?.contains("built-in ability") == true)
    }

    @Test
    fun `test variant constructor cannot conflict with user-defined ability`() {
        val code = """
            ability Show 'a where {
                show: {val: 'a} -> String
            }

            type MyType = | Show, Other |
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Cannot define variant constructor") == true ||
                   exception.message?.contains("ability") == true)
    }

    @Test
    fun `test type name cannot conflict with user-defined ability`() {
        val code = """
            ability Display 'a where {
                display: {val: 'a} -> String
            }

            type Display = { text: String }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Cannot define type") == true ||
                   exception.message?.contains("ability") == true)
    }

    @Test
    fun `test type name cannot conflict with user-defined effect`() {
        val code = """
            effect Logger {
                log: {msg: String} -> {}
            }

            type Logger = { level: String }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Cannot define type") == true ||
                   exception.message?.contains("ability") == true)
    }

    // ========== POSITIVE TESTS: ABILITY METHOD ACCESS STILL WORKS ==========

    @Test
    fun `test Eq method access still works`() {
        val code = """
            let my_eq = Eq.eq
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test Inspect method access still works`() {
        val code = """
            let my_str = Inspect.to_str
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test user-defined ability method access still works`() {
        val code = """
            ability Show 'a where {
                show: {val: 'a} -> String
            }

            let my_show = Show.show
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test ability can be redefined by user`() {
        // Users should be able to define their own ability with the same name as a built-in
        val code = """
            ability Eq 'a where {
                eq: {l: 'a, r: 'a} -> Bool,
                neq: {l: 'a, r: 'a} -> Bool
            }

            impl Eq Bool where {
                eq: {l: Bool, r: Bool} -> Bool = {l, r} -> if l then r else if r then false else true,
                neq: {l: Bool, r: Bool} -> Bool = {l, r} -> if l then if r then false else true else r
            }

            let test = Eq.eq {l = true, r = false}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }
}
