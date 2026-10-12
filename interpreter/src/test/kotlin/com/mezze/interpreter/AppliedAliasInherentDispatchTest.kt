package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Inherent impls of one record alias at different arguments dispatch by the
 * receiver's arguments.
 *
 * `type Box 'a = { v: 'a }` with `impl Box Int` and `impl Box Str` type-checked
 * and then ran the `Box Str` value through the `Box Int` impl ("Cannot add
 * String and Long"). An applied record alias expands to its record, so the
 * arguments live only in the fields: no hash and no name told the two impls
 * apart. Their methods were also filed under one key (the second overwrote the
 * first) and each impl body was checked against the other's signature.
 */
class AppliedAliasInherentDispatchTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val box = """
        type Box 'a = { v: 'a }
    """.trimIndent()

    @Test
    fun `two applied impls each get their own receivers`() {
        val out = MezzeTestHelper.run("""
            $box
            impl Box Int where {
              m = { it } -> it.v + 1
            }
            impl Box Str where {
              m = { it } -> it.v.size {} > 0
            }
            let main = {} -> do
              let a: Box Int = { v = 41 }
              let b: Box Str = { v = "hi" }
              "$(a.m {}.to_str {}) $(b.m {}.to_str {})"
            main {}
        """.trimIndent())
        assertEquals("42 True", out)
    }

    @Test
    fun `a specific impl outranks a generic one, which still serves the rest`() {
        val out = MezzeTestHelper.run("""
            $box
            impl Box 'a where {
              tag = { it } -> "any"
            }
            impl Box Int where {
              tag = { it } -> "int"
            }
            let main = {} -> do
              let a: Box Int = { v = 1 }
              let b: Box Str = { v = "x" }
              "$(a.tag {}) $(b.tag {})"
            main {}
        """.trimIndent())
        assertEquals("int any", out)
    }
}
