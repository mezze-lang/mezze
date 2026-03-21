package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
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
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String) = typeChecker.typeCheck(parser.parse(code)).program

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
            ability Show for 'a where {
                show: {it: 'a} -> Str
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
            effect Console where {
                print: {msg: Str} -> {} <Console>
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
            ability Show for 'a where {
                show: {it: 'a} -> Str
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
            ability Display for 'a where {
                display: {} -> Str
            }

            type Display = { text: Str }
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
            effect Logger where {
                log: {msg: Str} -> {} <Logger>
            }

            type Logger = { level: Str }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Cannot define type") == true ||
                   exception.message?.contains("ability") == true)
    }

    // ========== POSITIVE TESTS: ABILITY METHOD ACCESS STILL WORKS ==========

    @Test
    fun `test Eq method call still works`() {
        val code = """
            let my_eq = 1.eq {r = 2}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test Inspect method call still works`() {
        val code = """
            let my_str = 42.to_dbg_str {}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test user-defined ability method call still works`() {
        val code = """
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            impl Show for Int where {
                show: {it: 'a} -> Str = {it} -> "int"
            }

            let my_show = 42.show {}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test ability can be redefined by user`() {
        // Users should be able to define their own ability with the same name as a built-in
        val code = """
            ability Eq for 'a where {
                eq: {it: 'a, r: 'a} -> Bool,
                neq: {r: 'a} -> Bool
            }

            impl Eq for Bool where {
                eq: {it: Bool, r: Bool} -> Bool = {it, r} -> if it then r else if r then False else True,
                neq: {it: Bool, r: Bool} -> Bool = {it, r} -> if it then if r then False else True else r
            }

            let test = True.eq {r = False}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }
}
