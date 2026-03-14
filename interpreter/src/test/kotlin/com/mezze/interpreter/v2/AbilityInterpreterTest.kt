package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.mezze.interpreter.v2.values.MezzeRecord

/**
 * Tests for ability declarations and implementations.
 * Migrated from V1 to V2 with unboxed values.
 */
class AbilityInterpreterTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun testSimpleAbilityDeclaration() {
        val code = """
            ability Encoder 'a where {
              encode : {val: 'a} -> Str
            }

            1
        """
        // Should not throw any exceptions
        assertDoesNotThrow { eval(code) }
    }

    @Test
    fun testImplAbilityDeclaration() {
        val code = """
            ability Encoder 'a where {
              encode : {val: 'a} -> Str
            }

            impl Encoder Int where {
              encode : {val: Int} -> Str = {val} -> "number"
            }

            1
        """
        // Should not throw any exceptions
        assertDoesNotThrow { eval(code) }
    }

    @Test
    fun testMultipleAbilityImplementations() {
        val code = """
            ability Encoder 'a where {
              encode : {val: 'a} -> Str
            }

            impl Encoder Int where {
              encode : {val: Int} -> Str = {val} -> "number"
            }

            impl Encoder Str where {
              encode : {val: Str} -> Str = {val} -> val
            }

            1
        """
        // Should not throw any exceptions
        assertDoesNotThrow { eval(code) }
    }

    @Test
    fun testAbilityWithMultipleOperations() {
        val code = """
            ability Show 'a where {
              show : {val: 'a} -> Str,
              debug : {val: 'a} -> Str
            }

            impl Show Str where {
              show : {val: Str} -> Str = {val} -> val,
              debug : {val: Str} -> Str = {val} -> "\"$(val)\""
            }

            1
        """
        assertDoesNotThrow { eval(code) }
    }

    @Test
    fun testComplexAbilityExample() {
        val code = """
            use std::int

            ability Serializer 'a where {
              serialize : {val: 'a} -> Str
            }

            impl Serializer Int where {
              serialize : {val: Int} -> Str = {val} -> int::to_str {n = val}
            }

            1
        """
        assertDoesNotThrow { eval(code) }
    }

    @Test
    fun testAbilityAndImplTogether() {
        val code = """
            use std::int

            ability Encoder 'a where {
              encode : {val: 'a} -> Str
            }

            impl Encoder Int where {
              encode : {val: Int} -> Str = {val} -> int::to_str {n = val}
            }

            42
        """
        assertEquals(42L, eval(code))
    }
}
