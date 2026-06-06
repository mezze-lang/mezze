package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * #188 — bidirectional nominal-record promotion when the alias is in scope.
 *
 * When an anonymous record literal flows into a context that expects a named
 * record (alias `Point = { x: Int, y: Int }`) and the alias is reachable at
 * the unification site, the typechecker should promote the anonymous record
 * to nominal so impl methods declared on the alias fire.
 *
 * Conversely, when the named record flows into an anonymous context, no
 * promotion is needed — the named identity is already strictly tighter.
 */
class NominalRecordPromotionTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `anonymous record literal promotes to nominal at impl method receiver`() {
        val result = eval(
            """
            type Point = { x: Int, y: Int }
            impl Point where {
              magnitude_sq = { it } -> it.x * it.x + it.y * it.y,
            }
            let p = { x = 3, y = 4 }
            p.magnitude_sq {}
            """
        )
        // 3*3 + 4*4 = 9 + 16 = 25
        assertEquals(25L, result)
    }

    @Test
    fun `annotated nominal record dispatches to its impl method`() {
        // Baseline — confirms the impl dispatch path itself works.
        val result = eval(
            """
            type Point = { x: Int, y: Int }
            impl Point where {
              magnitude_sq = { it } -> it.x * it.x + it.y * it.y,
            }
            let p: Point = { x = 3, y = 4 }
            p.magnitude_sq {}
            """
        )
        assertEquals(25L, result)
    }

    @Test
    fun `anonymous record literal passed to function with named param type`() {
        val result = eval(
            """
            type Point = { x: Int, y: Int }
            impl Point where {
              first = { it } -> it.x,
            }
            let take : { p: Point } -> Int = { p } -> p.first {}
            take { p = { x = 7, y = 9 } }
            """
        )
        assertEquals(7L, result)
    }

    @Test
    fun `anonymous record flowing through let binding still picks impl`() {
        val result = eval(
            """
            type Point = { x: Int, y: Int }
            impl Point where {
              sum = { it } -> it.x + it.y,
            }
            let p1 = { x = 1, y = 2 }
            let p2 = { x = 10, y = 20 }
            p1.sum {} + p2.sum {}
            """
        )
        assertEquals(33L, result)
    }
}
