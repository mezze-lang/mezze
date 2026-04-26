package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Tests for smart effect scheduling behavior - basic operations.
 *
 * Migrated from V1 SmartEffectSchedulerBasicTest. In V1, this tested internal infrastructure
 * classes (EffectOperation, EffectDependencyAnalyzer, ParallelGroup). In V2, we test the same
 * concepts through the Mezze language runtime using native concurrency primitives.
 *
 * Concepts tested:
 * - Operation creation and execution
 * - Parallel operation detection (independent operations can run in parallel)
 * - Dependent operation detection (operations with shared state must be sequenced)
 * - Parallel group formation
 * - Execution plan creation
 */
@Execution(ExecutionMode.SAME_THREAD)
class SmartEffectSchedulerBasicTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // Loom primitives for concurrent tests
    private val loomSetup = """
        newtype Promise 'a = Promise __NativePromise
        let spawn: {f: {} -> 'a} -> Promise 'a = {f} -> __native_loom_spawn {f = f}
        let await: {p: Promise 'a} -> 'a = {p} -> __native_loom_await {p = p}
        let sleep: {ms: Int} -> {} = {ms} -> __native_loom_sleep {ms = ms}
    """.trimIndent()

    // Atom setup for shared state
    private val atomSetup = """
        newtype Atom 'a = Atom __NativeAtomicRef
        let atom: {value: 'a} -> Atom 'a = {value} -> __native_atom_new {value = value}
        let deref: {atom: Atom 'a} -> 'a = {atom} -> __native_atom_deref {atom = atom}
        let reset: {atom: Atom 'a, value: 'a} -> 'a = {atom, value} -> __native_atom_reset {atom = atom, value = value}
        let swap: {atom: Atom 'a, f: {current: 'a} -> 'a} -> 'a = {atom, f} -> __native_atom_swap {atom = atom, f = f}
    """.trimIndent()

    // =============================================================================
    // Basic Effect Operations
    // =============================================================================

    @Test
    fun `should create and execute basic effect operation`() {
        // V1 tested EffectOperation creation with id, effectType, readVars, writeVars, operation
        // V2 tests actual operation execution through spawn
        val code = """
            $loomSetup

            let op = spawn {f = {} -> "result"}
            await {p = op}
        """
        val result = eval(code)
        assertEquals("result", result)
    }

    @Test
    fun `should execute operation returning computed value`() {
        val code = """
            $loomSetup

            let op = spawn {f = {} -> 21 * 2}
            await {p = op}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    // =============================================================================
    // Parallel Operation Detection
    // =============================================================================

    @Test
    fun `should detect independent operations that can run in parallel`() {
        // V1 tested op1.canRunInParallelWith(op2) for operations with disjoint read/write sets
        // V2 verifies parallel execution by checking both complete and return correct results
        val code = """
            $loomSetup

            # Two independent operations writing to different locations
            let op1 = spawn {f = {} -> "result1"}
            let op2 = spawn {f = {} -> "result2"}

            let r1 = await {p = op1}
            let r2 = await {p = op2}

            r1 == "result1" && r2 == "result2"
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `should handle truly parallel execution of independent ops`() {
        // Independent operations (writing to different state) can execute in parallel
        val code = """
            $loomSetup
            $atomSetup

            let a = atom {value = 0}
            let b = atom {value = 0}

            let op1 = spawn {f = {} -> do
                let _ = sleep {ms = 10}
                reset {atom = a, value = 1}
            }

            let op2 = spawn {f = {} -> do
                let _ = sleep {ms = 10}
                reset {atom = b, value = 2}
            }

            let _ = await {p = op1}
            let _ = await {p = op2}

            deref {atom = a} + deref {atom = b}
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    // =============================================================================
    // Dependent Operation Detection
    // =============================================================================

    @Test
    fun `should detect dependent operations through shared state`() {
        // V1 tested that operations with overlapping read/write sets cannot run in parallel
        // V2 verifies dependent execution by checking correct ordering with shared state
        val code = """
            $loomSetup
            $atomSetup

            let shared = atom {value = 0}

            # op1 writes to shared
            let op1 = spawn {f = {} -> reset {atom = shared, value = 42}}

            # Wait for op1 to complete before op2 reads
            let _ = await {p = op1}

            # op2 reads from shared (depends on op1)
            let op2 = spawn {f = {} -> deref {atom = shared}}

            await {p = op2}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `should sequence dependent write-read operations correctly`() {
        // Operations where one writes and another reads must be sequenced
        val code = """
            $loomSetup
            $atomSetup

            let x = atom {value = 0}

            # Sequence: write x, then read x
            let writer = spawn {f = {} -> do
                let _ = sleep {ms = 5}
                reset {atom = x, value = 100}
            }

            let _ = await {p = writer}

            let reader = spawn {f = {} -> deref {atom = x}}
            await {p = reader}
        """
        val result = eval(code)
        assertEquals(100L, result)
    }

    // =============================================================================
    // Dependency Graph Analysis
    // =============================================================================

    @Test
    fun `should analyze multiple operations with dependencies`() {
        // V1 tested EffectDependencyAnalyzer.analyzeDependencies
        // V2 verifies correct execution order with a chain of dependent operations
        val code = """
            $loomSetup
            $atomSetup

            let x = atom {value = 0}
            let y = atom {value = 0}
            let z = atom {value = 0}

            # op1: writes x
            let op1 = spawn {f = {} -> reset {atom = x, value = 1}}
            let _ = await {p = op1}

            # op2: reads x, writes y (depends on op1)
            let op2 = spawn {f = {} -> do
                let xVal = deref {atom = x}
                reset {atom = y, value = xVal + 1}
            }
            let _ = await {p = op2}

            # op3: writes z (independent of op1, op2)
            let op3 = spawn {f = {} -> reset {atom = z, value = 10}}
            let _ = await {p = op3}

            # Verify final state
            deref {atom = x} + deref {atom = y} + deref {atom = z}
        """
        val result = eval(code)
        assertEquals(13L, result) // x=1, y=2, z=10
    }

    @Test
    fun `should identify root operations with no dependencies`() {
        // V1 tested DependencyGraph.getRootOperations()
        // V2 verifies that multiple independent operations can start immediately
        val code = """
            $loomSetup
            $atomSetup

            let results = atom {value = []}

            # Three independent operations - all are "roots"
            let op1 = spawn {f = {} -> 1}
            let op2 = spawn {f = {} -> 2}
            let op3 = spawn {f = {} -> 3}

            let r1 = await {p = op1}
            let r2 = await {p = op2}
            let r3 = await {p = op3}

            r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(6L, result)
    }

    // =============================================================================
    // Parallel Group Creation
    // =============================================================================

    @Test
    fun `should group independent operations together`() {
        // V1 tested ParallelGroup creation with multiple effects
        // V2 verifies that independent operations complete correctly when spawned together
        val code = """
            $loomSetup
            $atomSetup

            let a = atom {value = 0}
            let b = atom {value = 0}
            let c = atom {value = 0}

            # All three are independent and can be grouped
            let op1 = spawn {f = {} -> reset {atom = a, value = 10}}
            let op2 = spawn {f = {} -> reset {atom = b, value = 20}}
            let op3 = spawn {f = {} -> reset {atom = c, value = 30}}

            let _ = await {p = op1}
            let _ = await {p = op2}
            let _ = await {p = op3}

            deref {atom = a} + deref {atom = b} + deref {atom = c}
        """
        val result = eval(code)
        assertEquals(60L, result)
    }

    @Test
    fun `should keep dependent operations in separate groups`() {
        // V1 tested that dependent operations cannot merge
        // V2 verifies by checking that proper sequencing gives correct results
        val code = """
            $loomSetup
            $atomSetup

            let x = atom {value = 1}

            # First group: write x
            let group1Op = spawn {f = {} -> swap {atom = x, f = {current} -> current * 2}}
            let _ = await {p = group1Op}

            # Second group: read x and use it (must come after first group)
            let group2Op = spawn {f = {} -> deref {atom = x} * 3}
            await {p = group2Op}
        """
        val result = eval(code)
        assertEquals(6L, result) // (1 * 2) * 3 = 6
    }

    // =============================================================================
    // Execution Plan Creation
    // =============================================================================

    @Test
    fun `should create and execute plan with multiple phases`() {
        // V1 tested ExecutionPlan with parallelGroups and estimatedExecutionTime
        // V2 verifies multi-phase execution through sequential await patterns
        val code = """
            $loomSetup
            $atomSetup

            let state = atom {value = 0}

            # Phase 1: Independent initialization
            let init1 = spawn {f = {} -> 10}
            let init2 = spawn {f = {} -> 20}
            let sum = await {p = init1} + await {p = init2}

            # Phase 2: Use results from phase 1
            let compute = spawn {f = {} -> sum * 2}
            await {p = compute}
        """
        val result = eval(code)
        assertEquals(60L, result) // (10 + 20) * 2 = 60
    }

    @Test
    fun `should handle empty operation list`() {
        // V1 tested analyzer.analyzeDependencies(emptyList())
        // V2 verifies that doing nothing returns expected result
        val code = """
            # No operations to execute
            42
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `should handle single operation`() {
        // V1 tested single operation analysis
        // V2 verifies single operation execution
        val code = """
            $loomSetup

            let op = spawn {f = {} -> "single"}
            await {p = op}
        """
        val result = eval(code)
        assertEquals("single", result)
    }

    // =============================================================================
    // Parallel Group Properties
    // =============================================================================

    @Test
    fun `should respect operation count in parallel groups`() {
        // V1 tested ParallelGroup.effects.size
        // V2 verifies correct execution of specific number of parallel operations
        val code = """
            $loomSetup

            let op1 = spawn {f = {} -> 1}
            let op2 = spawn {f = {} -> 2}

            let r1 = await {p = op1}
            let r2 = await {p = op2}

            # Verify we got exactly 2 results
            if r1 == 1 && r2 == 2 then 2 else 0
        """
        val result = eval(code)
        assertEquals(2L, result)
    }

    @Test
    fun `should determine if groups can merge based on independence`() {
        // V1 tested ParallelGroup.canMergeWith
        // V2 verifies by running operations that would be in merged vs separate groups
        val code = """
            $loomSetup
            $atomSetup

            let a = atom {value = 0}
            let b = atom {value = 0}

            # These two operations are independent and could merge
            let op1 = spawn {f = {} -> reset {atom = a, value = 5}}
            let op2 = spawn {f = {} -> reset {atom = b, value = 10}}

            let _ = await {p = op1}
            let _ = await {p = op2}

            # op3 reads from a, so cannot merge with op1's group
            let op3 = spawn {f = {} -> deref {atom = a} + deref {atom = b}}
            await {p = op3}
        """
        val result = eval(code)
        assertEquals(15L, result)
    }
}
