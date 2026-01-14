package com.funk.language.typechecker

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class VerySimpleTest {

    @Test
    fun `super simple test`() {
        println("TEST IS RUNNING!")
        System.out.flush()
        assertEquals(1 + 1, 2)
        println("TEST PASSED!")
        System.out.flush()
    }
}
