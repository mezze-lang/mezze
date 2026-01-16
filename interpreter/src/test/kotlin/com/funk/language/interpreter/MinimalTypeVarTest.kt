package com.funk.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled

@Disabled("Needs to be updated to use async effect system instead of direct Loom calls")
class MinimalTypeVarTest {

    private val parser = com.funk.language.parser.FunkParser()
    private val typeChecker = com.funk.language.typechecker.TypeChecker()

    private fun eval(source: String): FunkValue {
        val projectRoot = java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk"))
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        val result = FunkTestHelper.execute(
            typeCheckResult.program,
            typeCheckResult.monomorphicInstances,
            projectRoot
        )
        return result
    }

    @Test
    fun testMinimalCase() {
        val code = """
            use std::collections::array
            use std::async::loom

            let items = [1, 2, 3]
            let promises = items |> array::map {xs = @, f = {x} -> loom::spawn {computation = {} -> x * 2}}
            let results = promises |> array::map {xs = @, f = {p} -> loom::await {promise = p}}
            results
        """
        val result = eval(code)
        assertTrue(result is FunkArrayList)
        val list = result as FunkArrayList
        assertEquals(3, list.elements.size)
        // Verify the results
        assertEquals(2L, (list.elements[0] as FunkInt).value)
        assertEquals(4L, (list.elements[1] as FunkInt).value)
        assertEquals(6L, (list.elements[2] as FunkInt).value)
    }
}
