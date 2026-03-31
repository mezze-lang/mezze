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
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun testSimpleAbilityDeclaration() {
        val code = """
            ability Encoder for 'a where {
              encode : {it: 'a} -> Str
            }

            1
        """
        // Should not throw any exceptions
        assertDoesNotThrow { eval(code) }
    }

    @Test
    fun testImplAbilityDeclaration() {
        val code = """
            ability Encoder for 'a where {
              encode : {it: 'a} -> Str
            }

            impl Encoder for Int where {
              encode : {it: Int} -> Str = {it} -> "number"
            }

            1
        """
        // Should not throw any exceptions
        assertDoesNotThrow { eval(code) }
    }

    @Test
    fun testMultipleAbilityImplementations() {
        val code = """
            ability Encoder for 'a where {
              encode : {it: 'a} -> Str
            }

            impl Encoder for Int where {
              encode : {it: Int} -> Str = {it} -> "number"
            }

            impl Encoder for Str where {
              encode : {it: Str} -> Str = {it} -> it
            }

            1
        """
        // Should not throw any exceptions
        assertDoesNotThrow { eval(code) }
    }

    @Test
    fun testAbilityWithMultipleOperations() {
        val code = """
            ability Show for 'a where {
              show : {it: 'a} -> Str,
              debug : {it: 'a} -> Str
            }

            impl Show for Str where {
              show : {it: Str} -> Str = {it} -> it,
              debug : {it: Str} -> Str = {it} -> "\"$(it)\""
            }

            1
        """
        assertDoesNotThrow { eval(code) }
    }

    @Test
    fun testComplexAbilityExample() {
        val code = """
            use std::int

            ability Serializer for 'a where {
              serialize : {it: 'a} -> Str
            }

            impl Serializer for Int where {
              serialize : {it: Int} -> Str = {it} -> int::to_str {n = it}
            }

            1
        """
        assertDoesNotThrow { eval(code) }
    }

    @Test
    fun testAbilityAndImplTogether() {
        val code = """
            use std::int

            ability Encoder for 'a where {
              encode : {it: 'a} -> Str
            }

            impl Encoder for Int where {
              encode : {it: Int} -> Str = {it} -> int::to_str {n = it}
            }

            42
        """
        assertEquals(42L, eval(code))
    }
}
