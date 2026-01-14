package com.funk.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

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

            newtype Promise = Promise __NativePromise

            let spawn: {computation: {} -> 'a} -> Promise = {computation} -> __native_loom_spawn {computation = computation}

            let items = [1, 2, 3]
            items |> array::map {xs = @, f = {x} -> spawn {computation = {} -> x * 2}}
        """
        val result = eval(code)
        assertTrue(result is FunkArrayList)
        val list = result as FunkArrayList
        assertEquals(3, list.elements.size)
    }
}
