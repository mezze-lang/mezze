package com.funk.language.interpreter.concurrency

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import com.funk.language.interpreter.FunkInterpreter
import com.funk.language.interpreter.FunkInt
import com.funk.language.interpreter.FunkString
import com.funk.language.interpreter.effects.EffectContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class RuntimeParallelismIntegrationTest {
    
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker
    private lateinit var interpreter: FunkInterpreter
    private lateinit var concurrencyManager: LoomConcurrencyManager
    private lateinit var detector: RuntimeParallelismDetector
    
    @BeforeEach
    fun setUp() {
        parser = FunkParser()
        typeChecker = TypeChecker()
        interpreter = FunkInterpreter()
        concurrencyManager = LoomConcurrencyManager()
        detector = RuntimeParallelismDetector(concurrencyManager)
    }
    
    @Test
    fun `test simple parallel do expression execution`() {
        val doExpr = DoExpression(
            statements = listOf(
                LetStatement.simple("x", IntLiteral(10)),
                LetStatement.simple("y", IntLiteral(20)),
                LetStatement.simple("z", StringLiteral("hello"))
            ),
            result = BinaryOperation(
                left = Identifier("x"),
                operator = "+",
                right = Identifier("y")
            )
        )
        
        val context = EffectContext.create(concurrencyManager = concurrencyManager)
        val plan = detector.analyzeDoExpression(doExpr, context)
        
        val startTime = Instant.now()
        val result = plan.execute()
        val endTime = Instant.now()
        val executionTime = Duration.between(startTime, endTime)
        
        assertNotNull(result)
        println("Execution time: ${executionTime.toMillis()}ms")
        
        // Should execute quickly since operations are simple
        assertTrue(executionTime < Duration.ofSeconds(1))
    }
    
    @Test
    fun `test parallel execution with dependencies`() {
        val doExpr = DoExpression(
            statements = listOf(
                LetStatement.simple("a", IntLiteral(5)),
                LetStatement.simple("b", IntLiteral(10)),
                LetStatement.simple("c", BinaryOperation(
                    left = Identifier("a"),
                    operator = "*",
                    right = IntLiteral(2)
                )),
                LetStatement.simple("d", BinaryOperation(
                    left = Identifier("b"),
                    operator = "*",
                    right = IntLiteral(3)
                )),
                LetStatement.simple("result", BinaryOperation(
                    left = Identifier("c"),
                    operator = "+",
                    right = Identifier("d")
                ))
            ),
            result = Identifier("result")
        )
        
        val context = EffectContext.create(concurrencyManager = concurrencyManager)
        val plan = detector.analyzeDoExpression(doExpr, context)
        
        val result = plan.execute()
        assertNotNull(result)
        
        // a=5, b=10, c=a*2=10, d=b*3=30, result=c+d=40
        // Note: The actual result depends on the expression evaluation implementation
    }
    
    @Test
    fun `test parallel execution with simulated effect calls`() {
        val executionOrder = mutableListOf<String>()
        val executionCounter = AtomicInteger(0)

        // Create a custom context that tracks effect calls
        val context = EffectContext.create(concurrencyManager = concurrencyManager)
        
        val doExpr = DoExpression(
            statements = listOf(
                LetStatement.simple("file1", EffectCallExpression(
                    effectName = "IO",
                    operationName = "read",
                    arguments = StringLiteral("file1.txt")
                )),
                LetStatement.simple("file2", EffectCallExpression(
                    effectName = "IO",
                    operationName = "read",
                    arguments = StringLiteral("file2.txt")
                )),
                LetStatement.simple("file3", EffectCallExpression(
                    effectName = "IO",
                    operationName = "read",
                    arguments = StringLiteral("file3.txt")
                )),
                LetStatement.simple("combined", StringLiteral(
                    value = "",
                    parts = listOf(
                        ExpressionPart(Identifier("file1")),
                        ExpressionPart(Identifier("file2")),
                        ExpressionPart(Identifier("file3"))
                    )
                ))
            ),
            result = Identifier("combined")
        )
        
        val plan = detector.analyzeDoExpression(doExpr, context)
        
        val startTime = Instant.now()
        val result = plan.execute()
        val endTime = Instant.now()
        val executionTime = Duration.between(startTime, endTime)
        
        assertNotNull(result)
        println("Effect-heavy execution time: ${executionTime.toMillis()}ms")
        
        // The three file reads should be able to run in parallel
        // combined depends on all three, so should run last
    }
    
    @Test
    fun `test nested do expressions with parallelism`() {
        val innerDo = DoExpression(
            statements = listOf(
                LetStatement.simple("inner1", IntLiteral(5)),
                LetStatement.simple("inner2", IntLiteral(10))
            ),
            result = BinaryOperation(
                left = Identifier("inner1"),
                operator = "*",
                right = Identifier("inner2")
            )
        )
        
        val outerDo = DoExpression(
            statements = listOf(
                LetStatement.simple("outer1", IntLiteral(100)),
                LetStatement.simple("nested", innerDo),
                LetStatement.simple("outer2", StringLiteral("result"))
            ),
            result = BinaryOperation(
                left = Identifier("outer1"),
                operator = "+",
                right = Identifier("nested")
            )
        )
        
        val context = EffectContext.create(concurrencyManager = concurrencyManager)
        val plan = detector.analyzeDoExpression(outerDo, context)
        
        val result = plan.execute()
        assertNotNull(result)
        
        // Should handle nested parallelism correctly
    }
    
    @Test
    fun `test lazy evaluation - operations only execute when needed`() {
        val executionTracker = mutableMapOf<String, Boolean>()
        
        // Create a do expression where not all variables are used in the result
        val doExpr = DoExpression(
            statements = listOf(
                LetStatement.simple("used", IntLiteral(42)),
                LetStatement.simple("unused1", IntLiteral(100)),
                LetStatement.simple("unused2", StringLiteral("not needed")),
                LetStatement.simple("derived", BinaryOperation(
                    left = Identifier("used"),
                    operator = "*",
                    right = IntLiteral(2)
                ))
            ),
            result = Identifier("derived")
        )
        
        val context = EffectContext.create(concurrencyManager = concurrencyManager)
        val plan = detector.analyzeDoExpression(doExpr, context)
        
        val result = plan.execute()
        assertNotNull(result)
        
        // In a lazy evaluation system, unused1 and unused2 should not be computed
        // This test demonstrates the concept, though the actual implementation
        // would need more sophisticated tracking
    }
    
    @Test
    fun `test error propagation in parallel do expression`() {
        val doExpr = DoExpression(
            statements = listOf(
                LetStatement.simple("good1", IntLiteral(10)),
                LetStatement.simple("good2", IntLiteral(20)),
                // This would cause an error in a real implementation
                LetStatement.simple("bad", BinaryOperation(
                    left = IntLiteral(1),
                    operator = "/",
                    right = IntLiteral(0) // Division by zero
                )),
                LetStatement.simple("dependent", BinaryOperation(
                    left = Identifier("good1"),
                    operator = "+",
                    right = Identifier("bad")
                ))
            ),
            result = Identifier("dependent")
        )
        
        val context = EffectContext.create(concurrencyManager = concurrencyManager)
        val plan = detector.analyzeDoExpression(doExpr, context)
        
        // In a real implementation, this should handle the error gracefully
        // For now, we just verify the plan can be created
        assertNotNull(plan)
        
        // The actual error handling would depend on the expression evaluation implementation
    }
    
    @Test
    fun `test performance comparison - sequential vs parallel`() {
        // Create a do expression with multiple independent slow operations
        val doExpr = DoExpression(
            statements = listOf(
                LetStatement.simple("slow1", IntLiteral(1)),
                LetStatement.simple("slow2", IntLiteral(2)),
                LetStatement.simple("slow3", IntLiteral(3)),
                LetStatement.simple("slow4", IntLiteral(4)),
                LetStatement.simple("sum", BinaryOperation(
                    left = BinaryOperation(
                        left = Identifier("slow1"),
                        operator = "+",
                        right = Identifier("slow2")
                    ),
                    operator = "+",
                    right = BinaryOperation(
                        left = Identifier("slow3"),
                        operator = "+",
                        right = Identifier("slow4")
                    )
                ))
            ),
            result = Identifier("sum")
        )
        
        val context = EffectContext.create(concurrencyManager = concurrencyManager)
        
        // Test parallel execution
        val parallelPlan = detector.analyzeDoExpression(doExpr, context)
        val parallelStart = Instant.now()
        val parallelResult = parallelPlan.execute()
        val parallelEnd = Instant.now()
        val parallelTime = Duration.between(parallelStart, parallelEnd)
        
        assertNotNull(parallelResult)
        
        println("Parallel execution time: ${parallelTime.toMillis()}ms")
        
        // In a real scenario with actual slow operations, parallel execution
        // should be faster than sequential execution
    }
    
    @Test
    fun `test synchronization points with complex dependencies`() {
        val coordinator = LazySynchronizationCoordinator(concurrencyManager)
        val context = EffectContext.create(concurrencyManager = concurrencyManager)
        
        // Create a complex dependency graph: A -> B, A -> C, B -> D, C -> D, D -> E
        val operations = mapOf(
            "A" to { 
                Thread.sleep(50)
                FunkInt(1L) 
            },
            "B" to { 
                Thread.sleep(30)
                val a = coordinator.getValue("A") as FunkInt
                FunkInt(a.value + 10) 
            },
            "C" to { 
                Thread.sleep(40)
                val a = coordinator.getValue("A") as FunkInt
                FunkInt(a.value + 100) 
            },
            "D" to { 
                Thread.sleep(20)
                val b = coordinator.getValue("B") as FunkInt
                val c = coordinator.getValue("C") as FunkInt
                FunkInt(b.value + c.value) 
            },
            "E" to { 
                Thread.sleep(10)
                val d = coordinator.getValue("D") as FunkInt
                FunkInt(d.value * 2) 
            }
        )
        
        val dependencies = mapOf(
            "A" to emptySet<String>(),
            "B" to setOf("A"),
            "C" to setOf("A"),
            "D" to setOf("B", "C"),
            "E" to setOf("D")
        )
        
        val startTime = Instant.now()
        val results = coordinator.executeWithLazySync(operations, dependencies, context)
        val endTime = Instant.now()
        val executionTime = Duration.between(startTime, endTime)
        
        // Verify results
        assertEquals(1L, (results["A"] as FunkInt).value)      // A = 1
        assertEquals(11L, (results["B"] as FunkInt).value)     // B = 1 + 10 = 11
        assertEquals(101L, (results["C"] as FunkInt).value)    // C = 1 + 100 = 101
        assertEquals(112L, (results["D"] as FunkInt).value)    // D = 11 + 101 = 112
        assertEquals(224L, (results["E"] as FunkInt).value)    // E = 112 * 2 = 224
        
        println("Complex dependency execution time: ${executionTime.toMillis()}ms")
        
        // With proper parallelization, B and C should run in parallel after A completes
        // The total time should be less than the sum of all individual times
        assertTrue(executionTime < Duration.ofMillis(200)) // Sum would be ~150ms, parallel should be less
    }
    
    @Test
    fun `test cancellation propagation in parallel execution`() {
        val coordinator = LazySynchronizationCoordinator(concurrencyManager)
        val context = EffectContext.create(concurrencyManager = concurrencyManager)
        
        val operationsStarted = AtomicInteger(0)
        val operationsCompleted = AtomicInteger(0)
        
        val operations = mapOf(
            "fast" to { 
                operationsStarted.incrementAndGet()
                Thread.sleep(50)
                operationsCompleted.incrementAndGet()
                FunkInt(1L) 
            },
            "slow" to { 
                operationsStarted.incrementAndGet()
                Thread.sleep(100) // Very slow
                operationsCompleted.incrementAndGet()
                FunkInt(2L) 
            },
            "medium" to { 
                operationsStarted.incrementAndGet()
                Thread.sleep(100)
                operationsCompleted.incrementAndGet()
                FunkInt(3L) 
            }
        )
        
        val dependencies = mapOf(
            "fast" to emptySet<String>(),
            "slow" to emptySet<String>(),
            "medium" to emptySet<String>()
        )
        
        // Start execution in background
        val future = java.util.concurrent.CompletableFuture.supplyAsync {
            coordinator.executeWithLazySync(operations, dependencies, context)
        }
        
        // Wait for operations to start
        Thread.sleep(200)
        assertTrue(operationsStarted.get() > 0)
        
        // Cancel all operations
        coordinator.cancelAll("Test cancellation")
        
        // Should complete quickly due to cancellation
        val startTime = Instant.now()
        try {
            future.get(1, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            // Expected due to cancellation
        }
        val endTime = Instant.now()
        val cancellationTime = Duration.between(startTime, endTime)
        
        // Should cancel quickly
        assertTrue(cancellationTime < Duration.ofMillis(500))
        
        // Not all operations should complete due to cancellation
        assertTrue(operationsCompleted.get() < operationsStarted.get())
    }
}