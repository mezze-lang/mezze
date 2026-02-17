package com.mezze.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SimpleArrayTest {

    private val parser = com.mezze.language.parser.MezzeParser()
    private val typeChecker = com.mezze.language.typechecker.TypeChecker()

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
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
        assertEquals(MezzeInt(6), result)
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
        assertTrue(result is MezzeArrayList)
        val arr = result as MezzeArrayList
        assertEquals(3, arr.size())
        assertEquals(MezzeInt(2), arr.get(0))
        assertEquals(MezzeInt(4), arr.get(1))
        assertEquals(MezzeInt(6), arr.get(2))
    }
}
