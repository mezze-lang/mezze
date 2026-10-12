package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Inlining a trivial wrapper must still evaluate every argument.
 *
 * `{ x, unused } -> x + 1` is a trivial wrapper, and inlining it substitutes
 * each param's argument where the param is read. A param read NOWHERE had its
 * argument dropped with it — so whatever evaluating that argument would have
 * done, a failure or an effect, silently did not happen. Arguments are
 * evaluated eagerly; whether the callee uses one must not change that.
 */
class WrapperInliningArgumentTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    @Test
    fun `an argument the wrapper never reads is still evaluated`() {
        val t = assertThrows(Throwable::class.java) {
            MezzeTestHelper.run("""
                let w = { x, unused } -> x + 1
                let main = {} -> do
                  let z = 0
                  w { x = 1, unused = 1 / z }
                main {}
            """.trimIndent())
        }
        assertTrue((t.message ?: "").contains("Division by zero"), "got: ${t.message}")
    }

    @Test
    fun `an unread argument that cannot fail still inlines and answers`() {
        assertEquals(2L, MezzeTestHelper.run("""
            let w = { x, unused } -> x + 1
            let main = {} -> w { x = 1, unused = 5 }
            main {}
        """.trimIndent()))
    }
}
