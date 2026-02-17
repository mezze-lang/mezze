package com.mezze.language.interpreter.concurrency

import java.time.Duration

/**
 * Standalone demo to verify Smart Effect Scheduler implementation
 */
fun main() {
    println("=== Smart Effect Scheduler Demo ===")
    
    // Create analyzer
    val analyzer = EffectDependencyAnalyzer()
    
    // Test 1: Independent operations
    println("\n1. Testing independent operations:")
    val op1 = EffectOperation("op1", "IO", setOf("a"), setOf("x"), { 
        println("  Executing op1: reading 'a', writing 'x'")
        "result1" 
    })
    val op2 = EffectOperation("op2", "IO", setOf("b"), setOf("y"), { 
        println("  Executing op2: reading 'b', writing 'y'")
        "result2" 
    })
    
    println("  Can op1 and op2 run in parallel? ${op1.canRunInParallelWith(op2)}")
    
    // Test 2: Dependent operations
    println("\n2. Testing dependent operations:")
    val op3 = EffectOperation("op3", "IO", emptySet(), setOf("z"), { 
        println("  Executing op3: writing 'z'")
        "result3" 
    })
    val op4 = EffectOperation("op4", "IO", setOf("z"), emptySet(), { 
        println("  Executing op4: reading 'z'")
        "result4" 
    })
    
    println("  Can op3 and op4 run in parallel? ${op3.canRunInParallelWith(op4)}")
    
    // Test 3: Dependency analysis
    println("\n3. Testing dependency analysis:")
    val operations = listOf(op1, op2, op3, op4)
    val graph = analyzer.analyzeDependencies(operations)
    
    println("  Total operations: ${graph.operations.size}")
    println("  Root operations (no dependencies): ${graph.getRootOperations().map { it.id }}")
    println("  Parallelizable groups: ${graph.parallelizableGroups.size}")
    
    graph.dependencies.forEach { (op, deps) ->
        if (deps.isNotEmpty()) {
            println("  ${op.id} depends on: ${deps.map { it.id }}")
        }
    }
    
    // Test 4: Execution plan
    println("\n4. Testing execution plan creation:")
    val plan = analyzer.createExecutionPlan(graph)
    println("  Parallel groups in plan: ${plan.parallelGroups.size}")
    println("  Estimated execution time: ${plan.estimatedExecutionTime.toMillis()}ms")
    
    val stats = plan.getStatistics()
    println("  Total operations: ${stats.totalOperations}")
    println("  Max parallelism: ${stats.maxParallelism}")
    println("  Estimated speedup: ${String.format("%.2f", stats.estimatedSpeedup)}x")
    
    // Test 5: Complex dependency chain
    println("\n5. Testing complex dependency chain:")
    val chainOp1 = EffectOperation("chain1", "IO", emptySet(), setOf("step1"), { "chain_result1" })
    val chainOp2 = EffectOperation("chain2", "IO", setOf("step1"), setOf("step2"), { "chain_result2" })
    val chainOp3 = EffectOperation("chain3", "IO", setOf("step2"), setOf("step3"), { "chain_result3" })
    val chainOp4 = EffectOperation("independent", "IO", emptySet(), setOf("other"), { "independent_result" })
    
    val chainOps = listOf(chainOp1, chainOp2, chainOp3, chainOp4)
    val chainGraph = analyzer.analyzeDependencies(chainOps)
    val chainPlan = analyzer.createExecutionPlan(chainGraph)
    
    println("  Chain operations: ${chainOps.size}")
    println("  Chain parallel groups: ${chainPlan.parallelGroups.size}")
    println("  Critical path length: ${chainPlan.getCriticalPath().size}")
    println("  Critical path: ${chainPlan.getCriticalPath().map { it.id }}")
    
    println("\n=== Demo completed successfully! ===")
}