package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Assertions.*
import com.mezze.datastructures.seq.MezzeSeq

/**
 * Tests for the Workflow effect pattern.
 *
 * Workflows are durable computations that can be:
 * - Suspended at step boundaries
 * - Checkpointed to storage
 * - Restored and resumed
 */
@Disabled("Workflow rewrite (journal-replay) deprioritized; std::control::continuation deleted in Phase 6. Tasks #65-67 will re-enable these.")
class WorkflowEffectTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `test simple workflow with steps`() {
        val source = """
            use std::effects::workflow::{Workflow, LocalWorkflow}

            perform LocalWorkflow in do
                let step1 = Workflow.step {name = "first", f = {} -> 10}
                let step2 = Workflow.step {name = "second", f = {} -> step1 * 2}
                step2
        """.trimIndent()

        val result = eval(source)
        assertEquals(20L, result)
    }

    @Test
    fun `test workflow run_id`() {
        val source = """
            use std::effects::workflow::{Workflow, LocalWorkflow}

            perform LocalWorkflow in do
                Workflow.run_id {}
        """.trimIndent()

        val result = eval(source)
        assertEquals("local-workflow-1", result)
    }

    @Test
    fun `test workflow sleep`() {
        val source = """
            use std::effects::workflow::{Workflow, LocalWorkflow}

            perform LocalWorkflow in do
                Workflow.sleep {ms = 100}
                42
        """.trimIndent()

        val result = eval(source)
        assertEquals(42L, result)
    }

    @Test
    fun `test workflow checkpoint`() {
        val source = """
            use std::effects::workflow::{Workflow, LocalWorkflow}

            perform LocalWorkflow in do
                let x = Workflow.step {name = "compute", f = {} -> 100}
                Workflow.checkpoint {}
                x + 1
        """.trimIndent()

        val result = eval(source)
        assertEquals(101L, result)
    }

    @Test
    fun `test run_local helper`() {
        val source = """
            use std::effects::workflow::{Workflow, run_local}

            run_local {workflow = {} -> do
                Workflow.step {name = "work", f = {} -> "done"}
            }
        """.trimIndent()

        val result = eval(source)
        assertEquals("done", result)
    }

    @Test
    fun `test workflow with multiple steps and data flow`() {
        val source = """
            use std::effects::workflow::{Workflow, LocalWorkflow}

            perform LocalWorkflow in do
                let a = Workflow.step {name = "step_a", f = {} -> 5}
                let b = Workflow.step {name = "step_b", f = {} -> 10}
                let c = Workflow.step {name = "step_c", f = {} -> a + b}
                let d = Workflow.step {name = "step_d", f = {} -> c * 2}
                d
        """.trimIndent()

        val result = eval(source)
        assertEquals(30L, result)
    }

    @Test
    fun `test retry pattern`() {
        val source = """
            use std::effects::workflow::{Workflow, LocalWorkflow, retry}

            perform LocalWorkflow in do
                retry {name = "flaky_task", max_attempts = 3, f = {} -> 42}
        """.trimIndent()

        val result = eval(source)
        assertEquals(42L, result)
    }

    @Test
    fun `test with_timeout pattern`() {
        val source = """
            use std::effects::workflow::{Workflow, LocalWorkflow, with_timeout}

            perform LocalWorkflow in do
                match with_timeout {name = "quick_task", timeout_ms = 1000, f = {} -> 99} is
                    Some {val} -> val
                    None -> 0
        """.trimIndent()

        val result = eval(source)
        assertEquals(99L, result)
    }

    @Test
    fun `test steps helper`() {
        val source = """
            use std::effects::workflow::{Workflow, LocalWorkflow, steps}
            use std::collections::seq

            perform LocalWorkflow in do
                let results = steps {work = [
                    {name = "task1", f = {} -> 1},
                    {name = "task2", f = {} -> 2},
                    {name = "task3", f = {} -> 3}
                ]}
                seq::fold {xs = results, init = 0, f = {acc, x} -> acc + x}
        """.trimIndent()

        val result = eval(source)
        assertEquals(6L, result)
    }
}
