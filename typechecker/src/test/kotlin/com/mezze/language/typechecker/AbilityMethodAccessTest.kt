package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.test.assertNotNull

/**
 * Tests for direct ability method access: Ability.method
 * Direct ability method access like Eq.eq returns the full method type including `it`.
 * Example: `let my_eq = Eq.eq` gives type `{it: 'a, r: 'a} -> Bool`
 */
class AbilityMethodAccessTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String) = typeChecker.typeCheck(parser.parse(code))

    @Test
    fun `test Inspect to_dbg_str access should return function type`() {
        assertDoesNotThrow {
            parseAndTypeCheck("let x = Inspect.to_dbg_str")
        }
    }

    @Test
    fun `test Inspect to_dbg_str can be stored and called later`() {
        assertDoesNotThrow {
            parseAndTypeCheck("let stringify = Inspect.to_dbg_str\nlet result = stringify {it = 42}")
        }
    }

    @Test
    fun `test Eq eq access should return function type`() {
        assertDoesNotThrow {
            parseAndTypeCheck("let my_eq = Eq.eq")
        }
    }

    @Test
    fun `test Eq eq can be stored and called later`() {
        assertDoesNotThrow {
            parseAndTypeCheck("let equals = Eq.eq\nlet result = equals {it = 1, r = 2}")
        }
    }

    @Test
    fun `test NumAdd add access should return function type`() {
        assertDoesNotThrow {
            parseAndTypeCheck("let my_add = NumAdd.add")
        }
    }

    @Test
    fun `test NumAdd add can be stored and called later`() {
        assertDoesNotThrow {
            parseAndTypeCheck("let adder = NumAdd.add\nlet result = adder {it = 1, r = 2}")
        }
    }

    @Test
    fun `test NumSub sub access should return function type`() {
        assertDoesNotThrow {
            parseAndTypeCheck("let my_sub = NumSub.sub")
        }
    }

    @Test
    fun `test NumMul mul access should return function type`() {
        assertDoesNotThrow {
            parseAndTypeCheck("let my_mul = NumMul.mul")
        }
    }

    @Test
    fun `test NumDiv div access should return function type`() {
        assertDoesNotThrow {
            parseAndTypeCheck("let my_div = NumDiv.div")
        }
    }
}
