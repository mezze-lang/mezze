package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.nio.file.Paths

/**
 * Tests for std/async/structured module.
 *
 * This module provides structured concurrency helpers built on top of the Async effect:
 * - parallel: Run all tasks in parallel and wait for all
 * - race: Run all tasks in parallel and return first to complete
 * - with_timeout: Run a task with a timeout
 * - parallel_map: Map a function over a list in parallel
 * - parallel_filter: Filter a list in parallel
 * - join2/join3: Run 2-3 computations in parallel
 * - scoped: Structured concurrency scope
 * - any/settle: Error handling helpers
 */
@Execution(ExecutionMode.SAME_THREAD)
class StructuredConcurrencyModuleTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
        TestUtils.setupProjectRoot()
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    @AfterEach
    fun teardown() {
        Thread.sleep(50)
        TruffleContextHolder.clearEnv()
    }

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
        return result
    }

    // =============================================================================
    // Module Import Tests
    // =============================================================================

    @Test
    fun `test structured module can be imported`() {
        val result = eval("""
            use std::async::structured::{parallel, race, with_timeout, parallel_map, parallel_filter, join2, join3, parallel_for_each}
            42
        """)
        assertEquals(MezzeInt(42), result)
    }

    // =============================================================================
    // parallel Tests
    // =============================================================================

    @Test
    fun `test parallel runs all tasks`() {
        val result = eval("""
            use std::async::{Async, run_coop}
            use std::async::structured::{parallel}

            run_coop {computation = {} -> do
                let tasks = [
                    {} -> 1,
                    {} -> 2,
                    {} -> 3
                ]
                let results = parallel {tasks = tasks}
                __native_arraylist_length {xs = results}
            }
        """)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `test parallel returns results in order`() {
        val result = eval("""
            use std::async::{Async, run_coop}
            use std::async::structured::{parallel}

            run_coop {computation = {} -> do
                let tasks = [
                    {} -> 10,
                    {} -> 20,
                    {} -> 30
                ]
                let results = parallel {tasks = tasks}
                match __native_arraylist_get {xs = results, i = 0} is
                    Some {val} -> val
                    None -> 0
            }
        """)
        assertEquals(MezzeInt(10), result)
    }

    // =============================================================================
    // race Tests
    // =============================================================================

    @Test
    fun `test race returns first completed`() {
        val result = eval("""
            use std::async::{Async, run_coop}
            use std::async::structured::{race}

            run_coop {computation = {} -> do
                let tasks = [
                    {} -> 1,
                    {} -> 2,
                    {} -> 3
                ]
                race {tasks = tasks}
            }
        """)
        // In coop scheduler, first task completes first
        assertEquals(MezzeInt(1), result)
    }

    // =============================================================================
    // with_timeout Tests
    // =============================================================================

    @Test
    fun `test with_timeout returns Some when task completes`() {
        val result = eval("""
            use std::async::{Async, run_coop}
            use std::async::structured::{with_timeout}

            run_coop {computation = {} -> do
                let result = with_timeout {
                    f = {} -> 42,
                    ms = 5000
                }
                match result is
                    Some {val} -> val
                    None -> 0
            }
        """)
        assertEquals(MezzeInt(42), result)
    }

    // =============================================================================
    // parallel_map Tests
    // =============================================================================

    @Test
    fun `test parallel_map applies function to all elements`() {
        val result = eval("""
            use std::async::{Async, run_coop}
            use std::async::structured::{parallel_map}

            run_coop {computation = {} -> do
                let results = parallel_map {
                    f = {x} -> x * 2,
                    xs = [1, 2, 3]
                }
                __native_arraylist_length {xs = results}
            }
        """)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `test parallel_map preserves order`() {
        val result = eval("""
            use std::async::{Async, run_coop}
            use std::async::structured::{parallel_map}

            run_coop {computation = {} -> do
                let results = parallel_map {
                    f = {x} -> x * 10,
                    xs = [1, 2, 3]
                }
                match __native_arraylist_get {xs = results, i = 1} is
                    Some {val} -> val
                    None -> 0
            }
        """)
        assertEquals(MezzeInt(20), result)
    }

    // =============================================================================
    // parallel_filter Tests
    // =============================================================================

    @Test
    fun `test parallel_filter keeps matching elements`() {
        val result = eval("""
            use std::async::{Async, run_coop}
            use std::async::structured::{parallel_filter}

            run_coop {computation = {} -> do
                let results = parallel_filter {
                    f = {x} -> x > 2,
                    xs = [1, 2, 3, 4, 5]
                }
                __native_arraylist_length {xs = results}
            }
        """)
        assertEquals(MezzeInt(3), result) // 3, 4, 5
    }

    // =============================================================================
    // join2 Tests
    // =============================================================================

    @Test
    fun `test join2 runs both computations`() {
        val result = eval("""
            use std::async::{Async, run_coop}
            use std::async::structured::{join2}

            run_coop {computation = {} -> do
                let r = join2 {
                    left = {} -> 10,
                    right = {} -> 20
                }
                r.left + r.right
            }
        """)
        assertEquals(MezzeInt(30), result)
    }

    // =============================================================================
    // join3 Tests
    // =============================================================================

    @Test
    fun `test join3 runs all three computations`() {
        val result = eval("""
            use std::async::{Async, run_coop}
            use std::async::structured::{join3}

            run_coop {computation = {} -> do
                let r = join3 {
                    a = {} -> 1,
                    b = {} -> 2,
                    c = {} -> 3
                }
                r.a + r.b + r.c
            }
        """)
        assertEquals(MezzeInt(6), result)
    }

    // =============================================================================
    // parallel_for_each Tests
    // =============================================================================

    @Test
    fun `test parallel_for_each returns unit`() {
        val result = eval("""
            use std::async::{Async, run_coop}
            use std::async::structured::{parallel_for_each}

            run_coop {computation = {} -> do
                parallel_for_each {
                    f = {x} -> {},
                    xs = [1, 2, 3]
                }
                42
            }
        """)
        assertEquals(MezzeInt(42), result)
    }
}
