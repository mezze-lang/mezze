package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Tests for async effect handler propagation.
 *
 * When spawning async tasks, effect handlers from the parent thread should be
 * propagated to the spawned thread so that effects like Log, STM, etc. work
 * correctly in spawned tasks.
 */
@Execution(ExecutionMode.SAME_THREAD)
class AsyncEffectHandlerPropagationTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // =============================================================================
    // Basic Effect Handler Propagation
    // =============================================================================

    @Test
    fun `test spawned task can use Log effect from parent`() {
        // The spawned task should have access to the Console handler from the parent
        val code = """
            use std::effects::log::{Console, println}
            use std::effects::async::{Async, LoomAsync}
            use std::effects::stm::{STM, tvar, deref, atomically}

            let flag = tvar {init = 0}

            perform Console in do
                # Use Log effect at top level so typechecker sees it
                println {msg = "Starting test"}
                perform LoomAsync in do
                    let p = Async.spawn {f = {} -> do
                        println {msg = "Task executed"}
                        # Use atomically to prove task ran
                        atomically {f = {} -> STM.write {var = flag, val = 1}}
                    }
                    let _ = Async.await {p = p}
                    deref {var = flag}
        """
        val result = eval(code)
        assertEquals(1L, result)
    }

    @Test
    fun `test spawned task can use STM effect from parent`() {
        val code = """
            use std::effects::stm::{STM, tvar, deref, atomically}
            use std::effects::async::{Async, LoomAsync}

            let counter = tvar {init = 100}

            perform LoomAsync in do
                let p = Async.spawn {f = {} -> atomically {f = {} -> do
                    let v = STM.read {var = counter}
                    STM.write {var = counter, val = v + 50}
                }}
                let _ = Async.await {p = p}
                deref {var = counter}
        """
        val result = eval(code)
        assertEquals(150L, result)
    }

    @Test
    fun `test multiple spawned tasks share effect handlers`() {
        val code = """
            use std::effects::stm::{STM, tvar, deref, atomically}
            use std::effects::async::{Async, LoomAsync}

            let counter = tvar {init = 0}

            perform LoomAsync in do
                let p1 = Async.spawn {f = {} -> atomically {f = {} -> STM.write {var = counter, val = 10}}}
                let _ = Async.await {p = p1}

                let p2 = Async.spawn {f = {} -> atomically {f = {} -> do
                    let v = STM.read {var = counter}
                    STM.write {var = counter, val = v + 5}
                }}
                let _ = Async.await {p = p2}

                deref {var = counter}
        """
        val result = eval(code)
        assertEquals(15L, result)
    }

    // =============================================================================
    // Nested Spawns
    // =============================================================================

    @Test
    fun `test nested spawns propagate handlers`() {
        val code = """
            use std::effects::stm::{STM, tvar, deref, atomically}
            use std::effects::async::{Async, LoomAsync}

            let counter = tvar {init = 0}

            perform LoomAsync in do
                let p1 = Async.spawn {f = {} -> do
                    # First level spawn - write 10
                    let _ = atomically {f = {} -> STM.write {var = counter, val = 10}}

                    # Second level spawn - should also have handlers
                    let p2 = Async.spawn {f = {} -> atomically {f = {} -> do
                        let v = STM.read {var = counter}
                        STM.write {var = counter, val = v + 5}
                    }}
                    Async.await {p = p2}
                }
                let _ = Async.await {p = p1}
                deref {var = counter}
        """
        val result = eval(code)
        assertEquals(15L, result)
    }

    // =============================================================================
    // Multiple Effect Handlers
    // =============================================================================

    @Test
    fun `test spawned task with multiple effect handlers`() {
        val code = """
            use std::effects::log::{Console, println}
            use std::effects::stm::{STM, tvar, deref, atomically}
            use std::effects::async::{Async, LoomAsync}

            let result_var = tvar {init = 0}

            perform Console in do
                # Use Log effect at top level so typechecker sees it
                println {msg = "Starting multi-effect test"}
                perform LoomAsync in do
                    let p = Async.spawn {f = {} -> do
                        # Use Log effect
                        println {msg = "Using Log effect"}
                        # Use STM effect via atomically
                        atomically {f = {} -> do
                            let v = STM.read {var = result_var}
                            STM.write {var = result_var, val = v + 100}
                        }
                    }
                    let _ = Async.await {p = p}
                    deref {var = result_var}
        """
        val result = eval(code)
        assertEquals(100L, result)
    }

    // =============================================================================
    // Concurrent Tasks with Handler Propagation
    // =============================================================================

    @Test
    fun `test parallel spawns all have handlers`() {
        val code = """
            use std::effects::stm::{STM, tvar, deref, atomically}
            use std::effects::async::{Async, LoomAsync}

            let counter = tvar {init = 0}

            perform LoomAsync in do
                # Spawn 3 concurrent tasks, each incrementing by different amounts
                let p1 = Async.spawn {f = {} -> atomically {f = {} -> do
                    let v = STM.read {var = counter}
                    STM.write {var = counter, val = v + 1}
                }}
                let p2 = Async.spawn {f = {} -> atomically {f = {} -> do
                    let v = STM.read {var = counter}
                    STM.write {var = counter, val = v + 2}
                }}
                let p3 = Async.spawn {f = {} -> atomically {f = {} -> do
                    let v = STM.read {var = counter}
                    STM.write {var = counter, val = v + 3}
                }}

                let _ = Async.await {p = p1}
                let _ = Async.await {p = p2}
                let _ = Async.await {p = p3}

                deref {var = counter}
        """
        val result = eval(code)
        // All three increments should succeed: 1 + 2 + 3 = 6
        assertEquals(6L, result)
    }

    @Test
    fun `test Async join propagates handlers to both branches`() {
        val code = """
            use std::effects::stm::{STM, tvar, deref, atomically}
            use std::effects::async::{Async, LoomAsync}

            let a = tvar {init = 0}
            let b = tvar {init = 0}

            perform LoomAsync in do
                let result = Async.join {
                    l = {} -> atomically {f = {} -> STM.write {var = a, val = 10}},
                    r = {} -> atomically {f = {} -> STM.write {var = b, val = 20}}
                }
                let va = deref {var = a}
                let vb = deref {var = b}
                va + vb
        """
        val result = eval(code)
        assertEquals(30L, result)
    }
}
