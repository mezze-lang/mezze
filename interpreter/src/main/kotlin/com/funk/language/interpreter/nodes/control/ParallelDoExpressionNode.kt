package com.funk.language.interpreter.nodes.control

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.funk.language.ast.DoExpression
import com.funk.language.interpreter.nodes.FunkNode
import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkNull
import com.funk.language.interpreter.effects.EffectContext
import com.funk.language.interpreter.concurrency.RuntimeParallelismDetector
import com.funk.language.interpreter.concurrency.LoomConcurrencyManager
import com.funk.language.interpreter.concurrency.LazyParallelExecutionPlan
import com.funk.language.interpreter.concurrency.ParallelErrorPropagationManager
import com.funk.language.interpreter.concurrency.ErrorPropagationStrategy
import java.util.concurrent.CompletableFuture

/**
 * Enhanced do-expression node that automatically detects and executes independent operations in parallel
 * using Project Loom virtual threads and lazy evaluation
 */
@NodeInfo(shortName = "parallel-do")
class ParallelDoExpressionNode(
    private val doExpression: DoExpression,
    @field:Children private val statementNodes: Array<FunkNode>,
    @field:Child private var resultNode: FunkNode
) : FunkNode() {
    
    // Lazy initialization of parallelism components
    private var parallelismDetector: RuntimeParallelismDetector? = null
    private var errorPropagationManager: ParallelErrorPropagationManager? = null
    
    override fun execute(frame: VirtualFrame): FunkValue {
        return executeWithParallelism()
    }
    
    /**
     * Execute the do-expression with automatic parallelism detection
     */
    @TruffleBoundary
    private fun executeWithParallelism(): FunkValue {
        // Get or create the effect context
        val context = getOrCreateEffectContext()
        
        // Initialize parallelism components if needed
        initializeParallelismComponents(context)
        
        // Analyze the do-expression for parallelization opportunities
        val executionPlan = parallelismDetector!!.analyzeDoExpression(doExpression, context)
        
        return try {
            // Execute with lazy parallel evaluation
            executionPlan.execute()
        } catch (e: Exception) {
            // Handle errors with proper cleanup
            handleExecutionError(e, context)
            throw RuntimeException("Parallel do-expression execution failed: ${e.message}", e)
        }
    }
    
    /**
     * Fallback to sequential execution if parallelism fails
     */
    @TruffleBoundary
    private fun executeSequentially(): FunkValue {
        var result: FunkValue = FunkNull
        
        // For now, return a placeholder since we don't have access to the frame
        // In a real implementation, this would need to be restructured
        return com.funk.language.interpreter.FunkNull
    }
    
    /**
     * Get or create the effect context for this execution
     */
    private fun getOrCreateEffectContext(): EffectContext {
        // In a full implementation, this would get the context from the Truffle frame
        // For now, create a basic context
        return EffectContext.create(
            concurrencyManager = LoomConcurrencyManager()
        )
    }
    
    /**
     * Initialize parallelism components lazily
     */
    private fun initializeParallelismComponents(context: EffectContext) {
        if (parallelismDetector == null) {
            parallelismDetector = RuntimeParallelismDetector(
                concurrencyManager = context.concurrencyManager
            )
        }
        
        if (errorPropagationManager == null) {
            errorPropagationManager = ParallelErrorPropagationManager(
                concurrencyManager = context.concurrencyManager
            )
        }
    }
    
    /**
     * Handle execution errors with proper cleanup
     */
    private fun handleExecutionError(error: Exception, context: EffectContext) {
        try {
            // Cancel any ongoing operations
            context.cancel("Do-expression execution failed: ${error.message}")
            
            // Clean up error propagation manager
            errorPropagationManager?.cancelAllGroups("Execution error")
        } catch (cleanupError: Exception) {
            // Log cleanup errors but don't propagate them
            System.err.println("Error during cleanup: ${cleanupError.message}")
        }
    }
    
    companion object {
        /**
         * Create a ParallelDoExpressionNode from a DoExpression AST node
         */
        fun create(
            doExpression: DoExpression,
            converter: com.funk.language.interpreter.nodes.ASTToTruffleConverter
        ): ParallelDoExpressionNode {
            // Convert statements to Truffle nodes
            val statementNodes = doExpression.statements.map { statement ->
                when (statement) {
                    is com.funk.language.ast.LetStatement -> {
                        val slot = converter.getOrCreateSlot(statement.variableName)
                        val valueNode = converter.convertExpression(statement.expression)
                        com.funk.language.interpreter.nodes.variable.SimpleWriteVariableNode(slot, valueNode)
                    }
                    is com.funk.language.ast.ExpressionStatement -> {
                        converter.convertExpression(statement.expression)
                    }
                    else -> throw RuntimeException("Unsupported statement type: ${statement::class.simpleName}")
                }
            }.toTypedArray()
            
            // Convert result expression
            val resultNode = converter.convertExpression(doExpression.result)
            
            return ParallelDoExpressionNode(doExpression, statementNodes, resultNode)
        }
    }
}

/**
 * Specialized do-expression node for effect-heavy operations
 * This node is optimized for do-expressions that contain many effect calls
 */
@NodeInfo(shortName = "effect-heavy-do")
class EffectHeavyDoExpressionNode(
    private val doExpression: DoExpression,
    @field:Children private val statementNodes: Array<FunkNode>,
    @field:Child private var resultNode: FunkNode
) : FunkNode() {
    
    private var concurrencyManager: LoomConcurrencyManager? = null
    private var parallelismDetector: RuntimeParallelismDetector? = null
    
    override fun execute(frame: VirtualFrame): FunkValue {
        return executeWithEffectParallelism()
    }
    
    /**
     * Execute with specialized effect parallelism
     */
    @TruffleBoundary
    private fun executeWithEffectParallelism(): FunkValue {
        val context = getOrCreateEffectContext()
        
        // Initialize components
        if (concurrencyManager == null) {
            concurrencyManager = LoomConcurrencyManager()
        }
        
        if (parallelismDetector == null) {
            parallelismDetector = RuntimeParallelismDetector(concurrencyManager!!)
        }
        
        // Create a structured concurrency scope for all effects
        return concurrencyManager!!.withStructuredScope("effect-heavy-do") { scope ->
            try {
                // Analyze and execute with effect-specific optimizations
                val executionPlan = parallelismDetector!!.analyzeDoExpression(doExpression, context)
                executionPlan.execute()
            } catch (e: Exception) {
                // Cancel the scope on error - scope will be automatically cancelled when the withStructuredScope block exits
                throw RuntimeException("Effect-heavy do-expression failed: ${e.message}", e)
            }
        }
    }
    
    private fun getOrCreateEffectContext(): EffectContext {
        return EffectContext.create(
            concurrencyManager = concurrencyManager ?: LoomConcurrencyManager()
        )
    }
}

/**
 * Simple do-expression node for cases where parallelism is not beneficial
 * This is used as a fallback or for small do-expressions
 */
@NodeInfo(shortName = "simple-do")
class SimpleDoExpressionNode(
    @field:Children private val statementNodes: Array<FunkNode>,
    @field:Child private var resultNode: FunkNode
) : FunkNode() {
    
    override fun execute(frame: VirtualFrame): FunkValue {
        var lastResult: FunkValue = FunkNull
        
        // Execute statements sequentially
        statementNodes.forEach { statementNode ->
            lastResult = statementNode.execute(frame)
        }
        
        // Execute and return result expression
        return resultNode.execute(frame)
    }
}

/**
 * Factory for creating appropriate do-expression nodes based on analysis
 */
object DoExpressionNodeFactory {
    
    /**
     * Create the most appropriate do-expression node based on the AST analysis
     */
    fun createDoExpressionNode(
        doExpression: DoExpression,
        converter: com.funk.language.interpreter.nodes.ASTToTruffleConverter
    ): FunkNode {
        // Analyze the do-expression to determine the best node type
        val analysis = analyzeDoExpression(doExpression)
        
        return when {
            analysis.shouldUseParallelism -> {
                if (analysis.isEffectHeavy) {
                    createEffectHeavyDoNode(doExpression, converter)
                } else {
                    ParallelDoExpressionNode.create(doExpression, converter)
                }
            }
            else -> createSimpleDoNode(doExpression, converter)
        }
    }
    
    /**
     * Analyze a do-expression to determine execution strategy
     */
    private fun analyzeDoExpression(doExpression: DoExpression): DoExpressionAnalysis {
        val statementCount = doExpression.statements.size
        val hasEffectCalls = containsEffectCalls(doExpression)
        val hasComplexExpressions = containsComplexExpressions(doExpression)
        val estimatedParallelism = estimateParallelismPotential(doExpression)
        
        return DoExpressionAnalysis(
            statementCount = statementCount,
            hasEffectCalls = hasEffectCalls,
            hasComplexExpressions = hasComplexExpressions,
            estimatedParallelism = estimatedParallelism,
            shouldUseParallelism = statementCount > 2 && estimatedParallelism > 1,
            isEffectHeavy = hasEffectCalls && statementCount > 3
        )
    }
    
    /**
     * Check if the do-expression contains effect calls
     */
    private fun containsEffectCalls(doExpression: DoExpression): Boolean {
        return doExpression.statements.any { statement ->
            when (statement) {
                is com.funk.language.ast.LetStatement -> containsEffectCallsInExpression(statement.expression)
                is com.funk.language.ast.ExpressionStatement -> containsEffectCallsInExpression(statement.expression)
            }
        } || containsEffectCallsInExpression(doExpression.result)
    }
    
    /**
     * Check if an expression contains effect calls
     */
    private fun containsEffectCallsInExpression(expression: com.funk.language.ast.Expression): Boolean {
        return when (expression) {
            is com.funk.language.ast.EffectCallExpression -> true
            is com.funk.language.ast.BinaryOperation -> 
                containsEffectCallsInExpression(expression.left) || containsEffectCallsInExpression(expression.right)
            is com.funk.language.ast.FunctionCall -> 
                containsEffectCallsInExpression(expression.function) || containsEffectCallsInExpression(expression.arguments)
            is com.funk.language.ast.DoExpression -> containsEffectCalls(expression)
            else -> false
        }
    }
    
    /**
     * Check if the do-expression contains complex expressions that might benefit from parallelism
     */
    private fun containsComplexExpressions(doExpression: DoExpression): Boolean {
        return doExpression.statements.any { statement ->
            when (statement) {
                is com.funk.language.ast.LetStatement -> isComplexExpression(statement.expression)
                is com.funk.language.ast.ExpressionStatement -> isComplexExpression(statement.expression)
            }
        } || isComplexExpression(doExpression.result)
    }
    
    /**
     * Check if an expression is complex enough to benefit from parallelism
     */
    private fun isComplexExpression(expression: com.funk.language.ast.Expression): Boolean {
        return when (expression) {
            is com.funk.language.ast.FunctionCall -> true
            is com.funk.language.ast.EffectCallExpression -> true
            is com.funk.language.ast.DoExpression -> true
            is com.funk.language.ast.MatchExpression -> true
            else -> false
        }
    }
    
    /**
     * Estimate the parallelism potential of a do-expression
     */
    private fun estimateParallelismPotential(doExpression: DoExpression): Int {
        // Simple heuristic: count independent operations
        val independentOperations = doExpression.statements.count { statement ->
            when (statement) {
                is com.funk.language.ast.LetStatement -> !dependsOnPreviousStatements(statement, doExpression)
                is com.funk.language.ast.ExpressionStatement -> !dependsOnPreviousStatements(statement, doExpression)
            }
        }
        
        return maxOf(1, independentOperations)
    }
    
    /**
     * Check if a statement depends on previous statements (simplified analysis)
     */
    private fun dependsOnPreviousStatements(
        statement: com.funk.language.ast.Statement, 
        doExpression: DoExpression
    ): Boolean {
        // This is a simplified implementation
        // In practice, this would do proper dependency analysis
        return false
    }
    
    /**
     * Create an effect-heavy do-expression node
     */
    private fun createEffectHeavyDoNode(
        doExpression: DoExpression,
        converter: com.funk.language.interpreter.nodes.ASTToTruffleConverter
    ): EffectHeavyDoExpressionNode {
        val statementNodes = doExpression.statements.map { statement ->
            when (statement) {
                is com.funk.language.ast.LetStatement -> {
                    val slot = converter.getOrCreateSlot(statement.variableName)
                    val valueNode = converter.convertExpression(statement.expression)
                    com.funk.language.interpreter.nodes.variable.SimpleWriteVariableNode(slot, valueNode)
                }
                is com.funk.language.ast.ExpressionStatement -> {
                    converter.convertExpression(statement.expression)
                }
                else -> throw RuntimeException("Unsupported statement type: ${statement::class.simpleName}")
            }
        }.toTypedArray()
        
        val resultNode = converter.convertExpression(doExpression.result)
        
        return EffectHeavyDoExpressionNode(doExpression, statementNodes, resultNode)
    }
    
    /**
     * Create a simple do-expression node
     */
    private fun createSimpleDoNode(
        doExpression: DoExpression,
        converter: com.funk.language.interpreter.nodes.ASTToTruffleConverter
    ): SimpleDoExpressionNode {
        val statementNodes = doExpression.statements.map { statement ->
            when (statement) {
                is com.funk.language.ast.LetStatement -> {
                    val slot = converter.getOrCreateSlot(statement.variableName)
                    val valueNode = converter.convertExpression(statement.expression)
                    com.funk.language.interpreter.nodes.variable.SimpleWriteVariableNode(slot, valueNode)
                }
                is com.funk.language.ast.ExpressionStatement -> {
                    converter.convertExpression(statement.expression)
                }
                else -> throw RuntimeException("Unsupported statement type: ${statement::class.simpleName}")
            }
        }.toTypedArray()
        
        val resultNode = converter.convertExpression(doExpression.result)
        
        return SimpleDoExpressionNode(statementNodes, resultNode)
    }
}

/**
 * Analysis result for a do-expression
 */
private data class DoExpressionAnalysis(
    val statementCount: Int,
    val hasEffectCalls: Boolean,
    val hasComplexExpressions: Boolean,
    val estimatedParallelism: Int,
    val shouldUseParallelism: Boolean,
    val isEffectHeavy: Boolean
)