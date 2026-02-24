package com.mezze.language.interpreter.nodes.control

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.mezze.language.ast.DoExpression
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeNull
import com.mezze.language.interpreter.MezzeContinuationSuspended
import com.mezze.language.interpreter.effects.EffectContext
import com.mezze.language.interpreter.concurrency.RuntimeParallelismDetector
import com.mezze.language.interpreter.concurrency.LoomConcurrencyManager
import com.mezze.language.interpreter.concurrency.LazyParallelExecutionPlan
import com.mezze.language.interpreter.concurrency.ParallelErrorPropagationManager
import com.mezze.language.interpreter.concurrency.ErrorPropagationStrategy
import java.util.concurrent.CompletableFuture

/**
 * Enhanced do-expression node that automatically detects and executes independent operations in parallel
 * using Project Loom virtual threads and lazy evaluation
 */
@NodeInfo(shortName = "parallel-do")
class ParallelDoExpressionNode(
    private val doExpression: DoExpression,
    @field:Children private val statementNodes: Array<MezzeNode>,
    @field:Child private var resultNode: MezzeNode
) : MezzeNode() {
    
    // Lazy initialization of parallelism components
    private var parallelismDetector: RuntimeParallelismDetector? = null
    private var errorPropagationManager: ParallelErrorPropagationManager? = null
    
    override fun execute(frame: VirtualFrame): MezzeValue {
        return executeWithParallelism()
    }
    
    /**
     * Execute the do-expression with automatic parallelism detection
     */
    @TruffleBoundary
    private fun executeWithParallelism(): MezzeValue {
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
    private fun executeSequentially(): MezzeValue {
        var result: MezzeValue = MezzeNull
        
        // For now, return a placeholder since we don't have access to the frame
        // In a real implementation, this would need to be restructured
        return com.mezze.language.interpreter.MezzeNull
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
            converter: com.mezze.language.interpreter.nodes.ASTToTruffleConverter
        ): ParallelDoExpressionNode {
            // Convert statements to Truffle nodes using converter's convertStatement
            // This properly handles pattern destructuring and other complex patterns
            val statementNodes = doExpression.statements.map { statement ->
                convertStatementViaReflection(converter, statement)
            }.toTypedArray()

            // Convert result expression
            val resultNode = converter.convertExpression(doExpression.result)

            return ParallelDoExpressionNode(doExpression, statementNodes, resultNode)
        }

        /**
         * Helper to call converter.convertStatement via reflection since it's private
         */
        fun convertStatementViaReflection(
            converter: com.mezze.language.interpreter.nodes.ASTToTruffleConverter,
            statement: com.mezze.language.ast.Statement
        ): MezzeNode {
            return when (statement) {
                is com.mezze.language.ast.LetStatement -> {
                    when (val pattern = statement.pattern) {
                        is com.mezze.language.ast.VariablePattern -> {
                            val slot = converter.getOrCreateSlot(pattern.name)
                            val valueNode = converter.convertExpression(statement.expression)
                            com.mezze.language.interpreter.nodes.variable.SimpleWriteVariableNode(slot, valueNode, pattern.name)
                        }
                        is com.mezze.language.ast.RecordPattern -> {
                            // Pattern destructuring - need to call private method via reflection or duplicate logic
                            // For now, duplicate the logic
                            convertRecordPatternBindingHelper(converter, pattern, statement.expression)
                        }
                        is com.mezze.language.ast.WildcardPattern -> {
                            converter.convertExpression(statement.expression)
                        }
                        else -> throw RuntimeException("Unsupported pattern in let statement: ${pattern::class.simpleName}")
                    }
                }
                is com.mezze.language.ast.ExpressionStatement -> {
                    converter.convertExpression(statement.expression)
                }
                else -> throw RuntimeException("Unsupported statement type: ${statement::class.simpleName}")
            }
        }

        /**
         * Helper to convert record pattern bindings (duplicates logic from ASTToTruffleConverter)
         */
        fun convertRecordPatternBindingHelper(
            converter: com.mezze.language.interpreter.nodes.ASTToTruffleConverter,
            pattern: com.mezze.language.ast.RecordPattern,
            valueExpr: com.mezze.language.ast.Expression
        ): MezzeNode {
            val valueNode = converter.convertExpression(valueExpr)
            val bindings = collectRecordPatternBindingsHelper(pattern)

            // CRITICAL: Pre-allocate frame slots for all variables BEFORE creating the node
            val slots = bindings.map { (_, varName) ->
                varName to converter.getOrCreateSlot(varName)
            }.toMap()

            // Pre-compute slot indices as arrays for efficient access
            val varNames = bindings.map { it.second }.toTypedArray()
            val slotIndices = varNames.map { slots[it]!! }.toIntArray()
            val fieldPaths = bindings.map { it.first.toTypedArray() }.toTypedArray()

            return RecordPatternBindingNode(valueNode, varNames, slotIndices, fieldPaths)
        }

        /**
         * Helper to collect record pattern bindings (duplicates logic from ASTToTruffleConverter)
         */
        fun collectRecordPatternBindingsHelper(
            pattern: com.mezze.language.ast.RecordPattern,
            prefix: List<String> = emptyList()
        ): List<Pair<List<String>, String>> {
            val bindings = mutableListOf<Pair<List<String>, String>>()

            for (entry in pattern.entries) {
                val fieldPath = prefix + entry.name

                when (val entryPattern = entry.pattern) {
                    null -> bindings.add(fieldPath to entry.name)
                    is com.mezze.language.ast.VariablePattern -> bindings.add(fieldPath to entryPattern.name)
                    is com.mezze.language.ast.RecordPattern -> bindings.addAll(collectRecordPatternBindingsHelper(entryPattern, fieldPath))
                    is com.mezze.language.ast.WildcardPattern -> {} // Ignore
                    else -> throw RuntimeException("Unsupported pattern in record destructuring: ${entryPattern::class.simpleName}")
                }
            }

            return bindings
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
    @field:Children private val statementNodes: Array<MezzeNode>,
    @field:Child private var resultNode: MezzeNode
) : MezzeNode() {
    
    private var concurrencyManager: LoomConcurrencyManager? = null
    private var parallelismDetector: RuntimeParallelismDetector? = null
    
    override fun execute(frame: VirtualFrame): MezzeValue {
        return executeWithEffectParallelism()
    }
    
    /**
     * Execute with specialized effect parallelism
     */
    @TruffleBoundary
    private fun executeWithEffectParallelism(): MezzeValue {
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
    @field:Children private val statementNodes: Array<MezzeNode>,
    @field:Child private var resultNode: MezzeNode
) : MezzeNode() {

    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
    private val numStatements: Int = statementNodes.size

    override fun execute(frame: VirtualFrame): MezzeValue {
        var lastResult: MezzeValue = MezzeNull

        // Execute statements sequentially
        var index = 0
        while (index < numStatements) {
            lastResult = statementNodes[index].execute(frame)

            // CRITICAL: Handle suspended continuations (early return, exceptions)
            if (lastResult is MezzeContinuationSuspended) {
                return lastResult
            }

            // CRITICAL: Handle suspended effect computations with continuation composition
            if (lastResult is com.mezze.language.interpreter.MezzeSuspended) {
                // Handle suspended effects in boundary method (complex closure creation)
                // Materialize frame since @TruffleBoundary methods can't receive VirtualFrame
                return handleSuspendedEffect(lastResult, index, frame.materialize())
            }
            index++
        }

        // Execute and return result expression
        val result = resultNode.execute(frame)

        // If the result is suspended, propagate it
        if (result is com.mezze.language.interpreter.MezzeSuspended || result is MezzeContinuationSuspended) {
            return result
        }

        return result
    }

    /**
     * Handle suspended effect computations with continuation composition.
     * Marked @TruffleBoundary because this involves complex closure creation,
     * sliceArray operations, and context access that trigger JIT blockers.
     *
     * NOTE: Uses MaterializedFrame instead of VirtualFrame because @TruffleBoundary
     * methods cannot receive VirtualFrame as a parameter.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private fun handleSuspendedEffect(
        suspendedValue: com.mezze.language.interpreter.MezzeSuspended,
        index: Int,
        materializedFrame: com.oracle.truffle.api.frame.MaterializedFrame
    ): MezzeValue {
        // Create a continuation that executes the remaining statements + result
        val remainingStatements = statementNodes.sliceArray((index + 1) until statementNodes.size)

        // Capture the context for use in closures
        val context = com.mezze.language.interpreter.MezzeLanguage.getCurrentContext(this)

        // Create a composed suspended computation
        val composedFunc = com.mezze.language.interpreter.NativeMezzeFunction(
            "do-composition-${index}"
        ) { args ->
            val effectHandler = context.effectHandler
            val currentHandlers = effectHandler.getCurrentHandlers()

            if (currentHandlers == null) {
                throw RuntimeException("No handlers available in composed do-continuation")
            }

            // Execute the original suspended effect
            val suspendedResult = when (val func = suspendedValue.computation.function) {
                is com.mezze.language.interpreter.NativeMezzeFunction -> {
                    func.call(listOf(suspendedValue.computation.arguments))
                }
                is com.mezze.language.interpreter.MezzeTruffleFunction -> {
                    func.callTarget.call(
                        suspendedValue.computation.arguments,
                        func.closureVariables,
                        func.lexicalScope,
                        func.lexicalScopeSlotMap
                    ) as MezzeValue
                }
                else -> {
                    throw RuntimeException("Cannot execute thunk with function type: ${func::class.simpleName}")
                }
            }

            // Execute remaining statements using the materialized frame
            var result: MezzeValue = MezzeNull
            for (remaining in remainingStatements) {
                result = remaining.execute(materializedFrame)

                if (result is com.mezze.language.interpreter.MezzeSuspended || result is MezzeContinuationSuspended) {
                    return@NativeMezzeFunction result
                }
            }

            // Execute the result expression
            resultNode.execute(materializedFrame)
        }

        // Return the composed MezzeSuspended
        return com.mezze.language.interpreter.MezzeSuspended(
            computation = com.mezze.language.interpreter.MezzeThunk(
                function = composedFunc,
                arguments = com.mezze.language.interpreter.MezzeRecord(emptyMap())
            ),
            unhandledEffects = suspendedValue.unhandledEffects,
            capturedHandlers = suspendedValue.capturedHandlers
        )
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
        converter: com.mezze.language.interpreter.nodes.ASTToTruffleConverter
    ): MezzeNode {
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
            // DISABLED: Parallelism is currently broken, always use simple sequential execution
            shouldUseParallelism = false,
            isEffectHeavy = hasEffectCalls && statementCount > 3
        )
    }
    
    /**
     * Check if the do-expression contains effect calls
     */
    private fun containsEffectCalls(doExpression: DoExpression): Boolean {
        return doExpression.statements.any { statement ->
            when (statement) {
                is com.mezze.language.ast.LetStatement -> containsEffectCallsInExpression(statement.expression)
                is com.mezze.language.ast.ExpressionStatement -> containsEffectCallsInExpression(statement.expression)
            }
        } || containsEffectCallsInExpression(doExpression.result)
    }
    
    /**
     * Check if an expression contains effect calls
     */
    private fun containsEffectCallsInExpression(expression: com.mezze.language.ast.Expression): Boolean {
        return when (expression) {
            is com.mezze.language.ast.EffectCallExpression -> true
            // BinaryOperation and UnaryOperation removed - operators are desugared at parse time
            is com.mezze.language.ast.FunctionCall ->
                containsEffectCallsInExpression(expression.function) || containsEffectCallsInExpression(expression.arguments)
            is com.mezze.language.ast.DoExpression -> containsEffectCalls(expression)
            else -> false
        }
    }
    
    /**
     * Check if the do-expression contains complex expressions that might benefit from parallelism
     */
    private fun containsComplexExpressions(doExpression: DoExpression): Boolean {
        return doExpression.statements.any { statement ->
            when (statement) {
                is com.mezze.language.ast.LetStatement -> isComplexExpression(statement.expression)
                is com.mezze.language.ast.ExpressionStatement -> isComplexExpression(statement.expression)
            }
        } || isComplexExpression(doExpression.result)
    }
    
    /**
     * Check if an expression is complex enough to benefit from parallelism
     */
    private fun isComplexExpression(expression: com.mezze.language.ast.Expression): Boolean {
        return when (expression) {
            is com.mezze.language.ast.FunctionCall -> true
            is com.mezze.language.ast.EffectCallExpression -> true
            is com.mezze.language.ast.DoExpression -> true
            is com.mezze.language.ast.MatchExpression -> true
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
                is com.mezze.language.ast.LetStatement -> !dependsOnPreviousStatements(statement, doExpression)
                is com.mezze.language.ast.ExpressionStatement -> !dependsOnPreviousStatements(statement, doExpression)
            }
        }
        
        return maxOf(1, independentOperations)
    }
    
    /**
     * Check if a statement depends on previous statements (simplified analysis)
     */
    private fun dependsOnPreviousStatements(
        statement: com.mezze.language.ast.Statement, 
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
        converter: com.mezze.language.interpreter.nodes.ASTToTruffleConverter
    ): EffectHeavyDoExpressionNode {
        val statementNodes = doExpression.statements.map { statement ->
            ParallelDoExpressionNode.convertStatementViaReflection(converter, statement)
        }.toTypedArray()

        val resultNode = converter.convertExpression(doExpression.result)

        return EffectHeavyDoExpressionNode(doExpression, statementNodes, resultNode)
    }
    
    /**
     * Create a simple do-expression node
     */
    private fun createSimpleDoNode(
        doExpression: DoExpression,
        converter: com.mezze.language.interpreter.nodes.ASTToTruffleConverter
    ): SimpleDoExpressionNode {
        val statementNodes = doExpression.statements.map { statement ->
            ParallelDoExpressionNode.convertStatementViaReflection(converter, statement)
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

/**
 * Node for binding record pattern fields to frame slots.
 * Uses pre-computed arrays for efficient access without Map lookups in the hot path.
 */
@NodeInfo(shortName = "record-pattern-binding")
class RecordPatternBindingNode(
    @field:Child private var valueNode: MezzeNode,
    private val varNames: Array<String>,
    private val slotIndices: IntArray,
    private val fieldPaths: Array<Array<String>>
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): MezzeValue {
        val value = valueNode.execute(frame)

        if (value !is com.mezze.language.interpreter.MezzeRecord) {
            throwNotRecord(value)
        }

        // Extract values via TruffleBoundary (can't pass VirtualFrame to boundary methods)
        val extractedValues = extractFieldValues(value)

        // Bind to frame slots in the hot path
        for (i in varNames.indices) {
            frame.setObject(slotIndices[i], extractedValues[i])
        }

        // Define in context via TruffleBoundary
        defineInContext(extractedValues)

        return com.mezze.language.interpreter.MezzeRecord(emptyMap())
    }

    @TruffleBoundary
    private fun throwNotRecord(value: MezzeValue): Nothing {
        throw RuntimeException("Cannot destructure non-record value: ${value::class.simpleName}")
    }

    /**
     * Extract values from nested record fields.
     * Marked as @TruffleBoundary because MezzeRecord.fields is a Map.
     */
    @TruffleBoundary
    private fun extractFieldValues(rootValue: com.mezze.language.interpreter.MezzeRecord): Array<MezzeValue> {
        return Array(varNames.size) { i ->
            var currentValue: MezzeValue = rootValue

            for (fieldName in fieldPaths[i]) {
                if (currentValue !is com.mezze.language.interpreter.MezzeRecord) {
                    throw RuntimeException("Cannot access field '$fieldName' on non-record value: ${currentValue::class.simpleName}")
                }
                currentValue = currentValue.fields[fieldName]
                    ?: throw RuntimeException("Field '$fieldName' not found in record")
            }

            currentValue
        }
    }

    /**
     * Define variables in context.
     * Marked as @TruffleBoundary because context operations may use Maps.
     */
    @TruffleBoundary
    private fun defineInContext(values: Array<MezzeValue>) {
        val context = com.mezze.language.interpreter.MezzeLanguage.getCurrentContext(this)
        for (i in varNames.indices) {
            context.defineLocal(varNames[i], values[i])
        }
    }
}