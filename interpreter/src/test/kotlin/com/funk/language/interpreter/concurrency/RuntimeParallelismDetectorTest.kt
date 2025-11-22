package com.funk.language.interpreter.concurrency

import com.funk.language.ast.*
import com.funk.language.interpreter.effects.EffectContext
import com.funk.language.interpreter.FunkInt
import com.funk.language.interpreter.FunkString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import java.time.Duration

class RuntimeParallelismDetectorTest {
    
    private lateinit var detector: RuntimeParallelismDetector
    private lateinit var concurrencyManager: LoomConcurrencyManager
    private lateinit var context: EffectContext
    
    @BeforeEach
    fun setUp() {
        concurrencyManager = LoomConcurrencyManager()
        detector = RuntimeParallelismDetector(concurrencyManager)
        context = EffectContext.create(concurrencyManager = concurrencyManager)
    }
    
    @Test
    fun `test simple do expression with independent operations`() {
        // Create a do expression with independent let statements
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
        
        val plan = detector.analyzeDoExpression(doExpr, context)
        
        // Should detect that x, y, z can be computed in parallel
        assertNotNull(plan)
        
        // Execute the plan
        val result = plan.execute()
        assertNotNull(result)
    }
    
    @Test
    fun `test do expression with dependencies`() {
        // Create a do expression with dependent operations
        val doExpr = DoExpression(
            statements = listOf(
                LetStatement.simple("x", IntLiteral(10)),
                LetStatement.simple("y", BinaryOperation(
                    left = Identifier("x"),
                    operator = "*",
                    right = IntLiteral(2)
                )),
                LetStatement.simple("z", BinaryOperation(
                    left = Identifier("y"),
                    operator = "+",
                    right = IntLiteral(5)
                ))
            ),
            result = Identifier("z")
        )
        
        val plan = detector.analyzeDoExpression(doExpr, context)
        
        // Should detect dependencies: y depends on x, z depends on y
        assertNotNull(plan)
        
        // Execute the plan
        val result = plan.execute()
        assertNotNull(result)
    }
    
    @Test
    fun `test do expression with mixed independent and dependent operations`() {
        // Create a do expression with both independent and dependent operations
        val doExpr = DoExpression(
            statements = listOf(
                LetStatement.simple("a", IntLiteral(1)),      // Independent
                LetStatement.simple("b", IntLiteral(2)),      // Independent
                LetStatement.simple("c", BinaryOperation(     // Depends on a
                    left = Identifier("a"),
                    operator = "*",
                    right = IntLiteral(3)
                )),
                LetStatement.simple("d", StringLiteral("test")), // Independent
                LetStatement.simple("e", BinaryOperation(     // Depends on b and c
                    left = Identifier("b"),
                    operator = "+",
                    right = Identifier("c")
                ))
            ),
            result = BinaryOperation(
                left = Identifier("e"),
                operator = "+",
                right = IntLiteral(10)
            )
        )
        
        val plan = detector.analyzeDoExpression(doExpr, context)
        
        // Should detect that a, b, d can run in parallel
        // c depends on a, e depends on b and c
        assertNotNull(plan)
        
        val result = plan.execute()
        assertNotNull(result)
    }
    
    @Test
    fun `test do expression with effect calls`() {
        // Create a do expression with effect calls
        val doExpr = DoExpression(
            statements = listOf(
                LetStatement.simple("x", EffectCallExpression(
                    effectName = "IO",
                    operationName = "read",
                    arguments = StringLiteral("file1.txt")
                )),
                LetStatement.simple("y", EffectCallExpression(
                    effectName = "IO", 
                    operationName = "read",
                    arguments = StringLiteral("file2.txt")
                )),
                LetStatement.simple("z", StringLiteral(
                    value = "",
                    parts = listOf(
                        ExpressionPart(Identifier("x")),
                        ExpressionPart(Identifier("y"))
                    )
                ))
            ),
            result = Identifier("z")
        )
        
        val plan = detector.analyzeDoExpression(doExpr, context)
        
        // Should detect that x and y (effect calls) can run in parallel
        // z depends on both x and y
        assertNotNull(plan)
        
        val result = plan.execute()
        assertNotNull(result)
    }
    
    @Test
    fun `test nested do expressions`() {
        // Create nested do expressions
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
        
        val plan = detector.analyzeDoExpression(outerDo, context)
        
        // Should handle nested do expressions
        assertNotNull(plan)
        
        val result = plan.execute()
        assertNotNull(result)
    }
    
    @Test
    fun `test lazy operation creation`() {
        val letStatement = LetStatement.simple("x", IntLiteral(42))
        val variableDependencies = emptyMap<String, Set<String>>()
        
        // Use reflection to access private method for testing
        val method = detector::class.java.getDeclaredMethod(
            "analyzeLetStatement",
            LetStatement::class.java,
            Map::class.java
        )
        method.isAccessible = true
        
        @Suppress("UNCHECKED_CAST")
        val operation = method.invoke(detector, letStatement, variableDependencies) as LazyOperation
        
        assertEquals("x", operation.variableName)
        assertEquals(LazyOperationType.LET_BINDING, operation.type)
        assertEquals(setOf("x"), operation.writeVariables)
        assertTrue(operation.readVariables.isEmpty())
        assertFalse(operation.isEffectful)
    }
    
    @Test
    fun `test dependency graph construction`() {
        val operations = listOf(
            LazyOperation(
                id = "op1",
                type = LazyOperationType.LET_BINDING,
                variableName = "x",
                expression = IntLiteral(10),
                readVariables = emptySet(),
                writeVariables = setOf("x"),
                isEffectful = false,
                estimatedDuration = Duration.ofMillis(1)
            ),
            LazyOperation(
                id = "op2",
                type = LazyOperationType.LET_BINDING,
                variableName = "y",
                expression = Identifier("x"),
                readVariables = setOf("x"),
                writeVariables = setOf("y"),
                isEffectful = false,
                estimatedDuration = Duration.ofMillis(1)
            ),
            LazyOperation(
                id = "op3",
                type = LazyOperationType.LET_BINDING,
                variableName = "z",
                expression = IntLiteral(20),
                readVariables = emptySet(),
                writeVariables = setOf("z"),
                isEffectful = false,
                estimatedDuration = Duration.ofMillis(1)
            )
        )
        
        // Use reflection to access private method
        val method = detector::class.java.getDeclaredMethod(
            "buildDependencyGraph",
            List::class.java
        )
        method.isAccessible = true
        
        val graph = method.invoke(detector, operations) as LazyDependencyGraph
        
        // op1 and op3 should have no dependencies
        val rootOps = graph.getRootOperations()
        assertEquals(2, rootOps.size)
        assertTrue(rootOps.contains(operations[0])) // op1
        assertTrue(rootOps.contains(operations[2])) // op3
        
        // op2 should depend on op1
        val op2Deps = graph.dependencies[operations[1]]
        assertNotNull(op2Deps)
        assertEquals(1, op2Deps!!.size)
        assertTrue(op2Deps.contains(operations[0])) // op1
    }
    
    @Test
    fun `test parallel operation detection`() {
        val op1 = LazyOperation(
            id = "op1",
            type = LazyOperationType.LET_BINDING,
            variableName = "x",
            expression = IntLiteral(10),
            readVariables = emptySet(),
            writeVariables = setOf("x"),
            isEffectful = false,
            estimatedDuration = Duration.ofMillis(1)
        )
        
        val op2 = LazyOperation(
            id = "op2",
            type = LazyOperationType.LET_BINDING,
            variableName = "y",
            expression = IntLiteral(20),
            readVariables = emptySet(),
            writeVariables = setOf("y"),
            isEffectful = false,
            estimatedDuration = Duration.ofMillis(1)
        )
        
        val op3 = LazyOperation(
            id = "op3",
            type = LazyOperationType.LET_BINDING,
            variableName = "z",
            expression = Identifier("x"),
            readVariables = setOf("x"),
            writeVariables = setOf("z"),
            isEffectful = false,
            estimatedDuration = Duration.ofMillis(1)
        )
        
        // op1 and op2 can run in parallel
        assertTrue(op1.canRunInParallelWith(op2))
        assertTrue(op2.canRunInParallelWith(op1))
        
        // op1 and op3 cannot run in parallel (op3 reads what op1 writes)
        assertFalse(op1.canRunInParallelWith(op3))
        assertFalse(op3.canRunInParallelWith(op1))
        
        // op2 and op3 can run in parallel
        assertTrue(op2.canRunInParallelWith(op3))
        assertTrue(op3.canRunInParallelWith(op2))
    }
    
    @Test
    fun `test effect call detection`() {
        val effectCallExpr = EffectCallExpression(
            effectName = "IO",
            operationName = "read",
            arguments = StringLiteral("file.txt")
        )
        
        val stringWithEffect = StringLiteral(
            value = "",
            parts = listOf(
                ExpressionPart(effectCallExpr),
                StringPart(" suffix")
            )
        )
        
        val simpleExpr = IntLiteral(42)
        
        // Use reflection to access private method
        val method = detector::class.java.getDeclaredMethod(
            "containsEffectCalls",
            Expression::class.java
        )
        method.isAccessible = true
        
        assertTrue(method.invoke(detector, effectCallExpr) as Boolean)
        assertTrue(method.invoke(detector, stringWithEffect) as Boolean)
        assertFalse(method.invoke(detector, simpleExpr) as Boolean)
    }
    
    @Test
    fun `test execution time estimation`() {
        val effectCall = EffectCallExpression(
            effectName = "IO",
            operationName = "read",
            arguments = StringLiteral("file.txt")
        )
        
        val functionCall = FunctionCall(
            function = Identifier("someFunction"),
            arguments = IntLiteral(42)
        )
        
        val literal = IntLiteral(42)
        
        // Use reflection to access private method
        val method = detector::class.java.getDeclaredMethod(
            "estimateExecutionTime",
            Expression::class.java
        )
        method.isAccessible = true
        
        val effectTime = method.invoke(detector, effectCall) as Duration
        val functionTime = method.invoke(detector, functionCall) as Duration
        val literalTime = method.invoke(detector, literal) as Duration
        
        // Effect calls should take longer than function calls
        assertTrue(effectTime > functionTime)
        
        // Function calls should take longer than literals
        assertTrue(functionTime > literalTime)
        
        // All should be positive
        assertTrue(effectTime > Duration.ZERO)
        assertTrue(functionTime > Duration.ZERO)
        assertTrue(literalTime > Duration.ZERO)
    }
}