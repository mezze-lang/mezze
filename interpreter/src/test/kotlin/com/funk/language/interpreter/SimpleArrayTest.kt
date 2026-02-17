package com.funk.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SimpleArrayTest {

    private val parser = com.funk.language.parser.FunkParser()
    private val typeChecker = com.funk.language.typechecker.TypeChecker()

    private fun eval(source: String): FunkValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (result, _) = FunkTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return result
    }

    // Test: Simple non-recursive array match
    @Test
    fun testSimpleArrayMatch() {
        val code = """
            let test = match [1, 2, 3] is
                [] -> 0
                [x] -> x
                [x, y] -> x + y
                [x, y, z] -> x + y + z
                [x, y, z, ...rest] -> x + y + z

            test
        """
        val result = eval(code)
        assertEquals(FunkInt(6), result)
    }

    // Test: Recursive map using stdlib array::map
    @Test
    fun testRecursiveMap() {
        val code = """
            use std::collections::array

            let double = {x} -> x * 2
            array::map {f = double, xs = [1, 2, 3]}
        """
        val result = eval(code)
        assertTrue(result is FunkArrayList)
        val arr = result as FunkArrayList
        assertEquals(3, arr.size())
        assertEquals(FunkInt(2), arr.get(0))
        assertEquals(FunkInt(4), arr.get(1))
        assertEquals(FunkInt(6), arr.get(2))
    }
}
