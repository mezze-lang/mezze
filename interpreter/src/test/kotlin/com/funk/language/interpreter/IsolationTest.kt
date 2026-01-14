package com.funk.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class IsolationTest {

    private val parser = com.funk.language.parser.FunkParser()
    private val typeChecker = com.funk.language.typechecker.TypeChecker()

    private fun eval(source: String): FunkValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (result, _) = FunkTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return result
    }

    // Test 1: Just spawn/await (NO helper functions)
    @org.junit.jupiter.api.Disabled("FIXME: __native_loom_spawn/__native_loom_await not registered in TypeChecker")
    @Test
    fun test1_JustSpawnAwait() {
        val code = """
            newtype Promise = Promise __NativePromise

            let spawn: {computation: {} -> 'a} -> Promise = {computation} -> __native_loom_spawn {computation = computation}
            let await: {promise: Promise} -> 'a = {promise} -> __native_loom_await {promise = promise}

            let test: {} -> Int = {} -> do
                let promise = spawn {computation = {} -> 42}
                await {promise = promise}

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
    }

    // Test 2: Spawn/await + empty map function (just definition, not used)
    @org.junit.jupiter.api.Disabled("FIXME: This test hangs - needs investigation")
    @Test
    fun test2_WithMapDefinition() {
        val code = """
            newtype Promise = Promise __NativePromise

            let spawn: {computation: {} -> 'a} -> Promise = {computation} -> __native_loom_spawn {computation = computation}
            let await: {promise: Promise} -> 'a = {promise} -> __native_loom_await {promise = promise}

            let map = {f, xs} ->
                match xs is
                    [] -> []
                    [head, ...tail] -> [f {x = head}] ++ map {f = f, xs = tail}

            let test: {} -> Int = {} -> do
                let promise = spawn {computation = {} -> 42}
                await {promise = promise}

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
    }

    // Test 3: All three helper functions defined but not used
    @org.junit.jupiter.api.Disabled("FIXME: This test hangs - needs investigation")
    @Test
    fun test3_AllHelpersDefinedNotUsed() {
        val code = """
            newtype Promise = Promise __NativePromise

            let spawn: {computation: {} -> 'a} -> Promise = {computation} -> __native_loom_spawn {computation = computation}
            let await: {promise: Promise} -> 'a = {promise} -> __native_loom_await {promise = promise}

            let map = {f, xs} ->
                match xs is
                    [] -> []
                    [head, ...tail] -> [f {x = head}] ++ map {f = f, xs = tail}

            let foldl = {f, init, xs} ->
                match xs is
                    [] -> init
                    [head, ...tail] -> foldl {f = f, init = f {acc = init, x = head}, xs = tail}

            let filter = {pred, xs} ->
                match xs is
                    [] -> []
                    [head, ...tail] ->
                        if pred {x = head}
                        then [head] ++ filter {pred = pred, xs = tail}
                        else filter {pred = pred, xs = tail}

            let test: {} -> Int = {} -> do
                let promise = spawn {computation = {} -> 42}
                await {promise = promise}

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
    }

    // Test 4: Just a simple array pattern match (NO spawn/await)
    @org.junit.jupiter.api.Disabled("FIXME: This test hangs - recursive map with array patterns causes hang")
    @Test
    fun test4_JustArrayPattern() {
        val code = """
            let map = {f, xs} ->
                match xs is
                    [] -> []
                    [head, ...tail] -> [f {x = head}] ++ map {f = f, xs = tail}

            let double = {x} -> x * 2
            map {f = double, xs = [1, 2, 3]}
        """
        val result = eval(code)
        assertTrue(result is FunkArrayList)
        val list = result as FunkArrayList
        assertEquals(3, list.size())
        assertEquals(FunkInt(2), list.get(0))
        assertEquals(FunkInt(4), list.get(1))
        assertEquals(FunkInt(6), list.get(2))
    }
}
