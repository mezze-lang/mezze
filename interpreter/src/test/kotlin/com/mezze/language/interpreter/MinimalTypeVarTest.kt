package com.mezze.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Minimal test for type variable handling in async contexts.
 * Tests that polymorphic functions work correctly with the async effect system.
 */
@Execution(ExecutionMode.SAME_THREAD)
class MinimalTypeVarTest {

    private lateinit var parser: com.mezze.language.parser.MezzeParser
    private lateinit var typeChecker: com.mezze.language.typechecker.TypeChecker

    @BeforeEach
    fun setup() {
        parser = com.mezze.language.parser.MezzeParser()
        typeChecker = com.mezze.language.typechecker.TypeChecker()
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    @AfterEach
    fun teardown() {
        Thread.sleep(50)
        TruffleContextHolder.clearEnv()
    }

    private fun eval(source: String): MezzeValue {
        val projectRoot = java.nio.file.Paths.get(System.getProperty("mezze.project.root", TestUtils.findProjectRoot().toString()))
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        val result = MezzeTestHelper.execute(
            typeCheckResult.program,
            typeCheckResult.monomorphicInstances,
            projectRoot
        )
        return result
    }

    @Test
    fun testMinimalCase() {
        // Test type variables work correctly across async boundaries
        // Using direct native calls to avoid effect system complexity
        val code = """
            use std::collections::array

            newtype Promise 'a = Promise __NativePromise

            let spawn: {f: {} -> 'a} -> Promise 'a = {f} -> __native_loom_spawn {f = f}
            let await_p: {p: Promise 'a} -> 'a = {p} -> __native_loom_await {p = p}

            let items = [1, 2, 3]
            let promises = items |> array::map {xs = @, f = {x} -> spawn {f = {} -> x * 2}}
            let results = promises |> array::map {xs = @, f = {x} -> await_p {p = x}}
            results
        """
        val result = eval(code)
        assertTrue(result is MezzeArrayList)
        val list = result as MezzeArrayList
        assertEquals(3, list.size())
        // Verify the results
        assertEquals(2L, (list.get(0) as MezzeInt).value)
        assertEquals(4L, (list.get(1) as MezzeInt).value)
        assertEquals(6L, (list.get(2) as MezzeInt).value)
    }

    @Test
    fun testTypeVarsWithEffectSystem() {
        // Test type variables with the effect system
        val code = """
            use std::effects::async::{Async, LoomAsync}

            perform LoomAsync in do
                let p1 = Async.spawn {f = {} -> 1 * 2}
                let p2 = Async.spawn {f = {} -> 2 * 2}
                let p3 = Async.spawn {f = {} -> 3 * 2}
                let r1 = Async.await {p = p1}
                let r2 = Async.await {p = p2}
                let r3 = Async.await {p = p3}
                [r1, r2, r3]
        """
        val result = eval(code)
        assertTrue(result is MezzeArrayList)
        val list = result as MezzeArrayList
        assertEquals(3, list.size())
        assertEquals(2L, (list.get(0) as MezzeInt).value)
        assertEquals(4L, (list.get(1) as MezzeInt).value)
        assertEquals(6L, (list.get(2) as MezzeInt).value)
    }
}
