package com.funk.language.interpreter.stdlib.effects

import com.funk.language.interpreter.*
import com.funk.language.ast.ExpressionStatement
import com.funk.language.ast.Expression
import com.funk.language.ast.LetStatement
import com.funk.language.ast.Statement

/**
 * Native implementations for the Generator effect.
 *
 * Generator provides yield/resume pattern for lazy sequences.
 * Built on top of Continuation effect.
 */
object GeneratorImplementations {

    /**
     * Execute remaining statements with proper execution state tracking.
     * This allows nested yields to capture the correct continuation.
     */
    private fun executeRemainingStatements(
        interpreter: FunkInterpreter,
        statements: List<Statement>,
        resultExpr: Expression,
        context: MutableMap<String, FunkValue>
    ): FunkValue {
        val remainingStmts = statements.toMutableList()

        while (remainingStmts.isNotEmpty()) {
            val stmt = remainingStmts.removeFirst()
            val futureStatements = remainingStmts.toList()

            // Push execution state so yield can capture the correct continuation
            val execState = FunkInterpreter.ExecutionState(
                remainingStatements = futureStatements,
                resultExpression = resultExpr,
                context = context.toMap(),
                location = "generator_resume:statement"
            )
            interpreter.pushExecutionState(execState)

            try {
                val result = when (stmt) {
                    is ExpressionStatement -> interpreter.evaluateExpression(stmt.expression, context)
                    is LetStatement -> {
                        val value = interpreter.evaluateExpression(stmt.expression, context)
                        if (value !is FunkContinuationSuspended) {
                            context[stmt.variableName] = value
                        }
                        value
                    }
                    else -> {
                        interpreter.executeStatement(stmt, context)
                        FunkNull
                    }
                }

                // If the result is a suspended continuation (from yield), return it
                // The execution state was already captured by yield with the correct remaining statements
                if (stmt is ExpressionStatement && result is FunkContinuationSuspended) {
                    return result
                }
            } finally {
                interpreter.popExecutionState()
            }
        }

        // Execute and return the result expression
        // If it yields, return the suspension (the yielded value will be captured).
        // When THAT gets resumed, we'll come back here but the yield already happened,
        // so we need a different approach.
        //
        // Solution: Don't push execution state for result expression - let it capture
        // an empty state so when resumed, executeRemainingStatements gets empty list
        // and empty result, returning unit.

        // Create a "done" state - when this is captured by a yield in the result expression,
        // resuming it will have no more work to do
        val doneState = FunkInterpreter.ExecutionState(
            remainingStatements = emptyList(),
            resultExpression = com.funk.language.ast.RecordLiteral(emptyList()), // Unit expression
            context = context.toMap(),
            location = "generator_resume:done"
        )
        interpreter.pushExecutionState(doneState)

        try {
            return interpreter.evaluateExpression(resultExpr, context)
        } finally {
            interpreter.popExecutionState()
        }
    }

    /**
     * Register all generator effect native functions.
     */
    fun registerAll(
        context: MutableMap<String, FunkValue>,
        interpreter: FunkInterpreter?
    ) {
        registerYield(context, interpreter)
        registerNext(context)
        registerToList(context)
        registerTake(context)
    }

    /**
     * __native_generator_yield - Yields a value and suspends execution.
     *
     * Takes a value to yield and returns a FunkContinuationSuspended that wraps
     * both the continuation and the yielded value.
     *
     * This is similar to Continuation.suspend but includes the yielded value.
     */
    private fun registerYield(
        context: MutableMap<String, FunkValue>,
        interpreter: FunkInterpreter?
    ) {
        context["__native_generator_yield"] = NativeFunkFunction("__native_generator_yield") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_generator_yield", "expects FunkRecord")

            val yieldedValue = record.fields["value"]
                ?: throw NativeFunctionException("__native_generator_yield", "expects 'value' field")

            // Get interpreter from context
            val interpreterRef = context["__interpreter_instance"] as? InterpreterReference
                ?: throw NativeFunctionException("__native_generator_yield", "Interpreter not available in context")
            val interpreter = interpreterRef.interpreter

            // Get the current execution state (similar to capture)
            val executionState = interpreter.getCurrentExecutionState()
                ?: throw FunkException("Generator.yield can only be called inside a do-block")

            val capturePoint = executionState.location

            // Create a continuation function that resumes from the captured state
            // This function will execute remaining statements with proper execution state tracking
            val capturedRemaining = executionState.remainingStatements
            val capturedResult = executionState.resultExpression
            val capturedContext = executionState.context

            val resumeFunction = NativeFunkFunction("generator_resume@$capturePoint") { resumeArgs ->
                executeRemainingStatements(
                    interpreter,
                    capturedRemaining,
                    capturedResult,
                    capturedContext.toMutableMap()
                )
            }

            // Create the continuation
            val continuation = FunkContinuation(resumeFunction, capturePoint)

            // Return suspended continuation with the yielded value
            FunkContinuationSuspended(continuation, yieldedValue)
        }
    }

    /**
     * __native_generator_next - Get the next value from a generator.
     *
     * Takes a suspended generator and returns a record with:
     * - value: Option (Some { val } if there's a value, None if done)
     * - gen: The next generator state (suspended or done)
     *
     * If the generator is done (not a FunkContinuationSuspended), returns None.
     */
    private fun registerNext(context: MutableMap<String, FunkValue>) {
        context["__native_generator_next"] = NativeFunkFunction("__native_generator_next") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_generator_next", "expects FunkRecord")

            val gen = record.fields["gen"]
                ?: throw NativeFunctionException("__native_generator_next", "expects 'gen' field")

            when (gen) {
                is FunkContinuationSuspended -> {
                    // Extract the yielded value
                    val yieldedValue = gen.suspendedValue

                    // Resume the continuation to get the next state
                    val continuation = gen.continuation
                    val nextState = try {
                        when (val resumeFn = continuation.resumeFunction) {
                            is NativeFunkFunction -> resumeFn.call(listOf(FunkRecord(mapOf("value" to FunkNull))))
                            else -> FunkNull
                        }
                    } catch (e: Exception) {
                        // Generator threw an exception or finished
                        FunkNull
                    }

                    // Return { value: Some { val }, gen: nextState }
                    FunkRecord(mapOf(
                        "value" to FunkVariant("Some", FunkRecord(mapOf("val" to yieldedValue))),
                        "gen" to nextState
                    ))
                }
                else -> {
                    // Generator is done
                    FunkRecord(mapOf(
                        "value" to FunkVariant("None", null),
                        "gen" to FunkNull
                    ))
                }
            }
        }
    }

    /**
     * __native_generator_to_list - Consume entire generator into a list.
     *
     * Takes a suspended generator and repeatedly gets values until done.
     * Returns a FunkList with all yielded values.
     */
    private fun registerToList(context: MutableMap<String, FunkValue>) {
        context["__native_generator_to_list"] = NativeFunkFunction("__native_generator_to_list") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_generator_to_list", "expects FunkRecord")

            var gen: FunkValue = record.fields["gen"]
                ?: throw NativeFunctionException("__native_generator_to_list", "expects 'gen' field")

            val values = mutableListOf<FunkValue>()

            // Consume all values from the generator
            while (gen is FunkContinuationSuspended) {
                // Add the yielded value
                values.add(gen.suspendedValue)

                // Resume to get next state
                val continuation = gen.continuation
                gen = try {
                    when (val resumeFn = continuation.resumeFunction) {
                        is NativeFunkFunction -> resumeFn.call(listOf(FunkRecord(mapOf("value" to FunkNull))))
                        else -> FunkNull
                    }
                } catch (e: Exception) {
                    FunkNull
                }
            }

            FunkList(values)
        }
    }

    /**
     * __native_generator_take - Get first n values from a generator.
     *
     * Takes a generator and a count, returns a list of up to n values.
     * Also returns the remaining generator state.
     */
    private fun registerTake(context: MutableMap<String, FunkValue>) {
        context["__native_generator_take"] = NativeFunkFunction("__native_generator_take") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_generator_take", "expects FunkRecord")

            var gen: FunkValue = record.fields["gen"]
                ?: throw NativeFunctionException("__native_generator_take", "expects 'gen' field")

            val n = (record.fields["n"] as? FunkInt)?.value
                ?: throw NativeFunctionException("__native_generator_take", "expects 'n' field as Int")

            val values = mutableListOf<FunkValue>()

            // Take up to n values
            var count = 0
            while (count < n && gen is FunkContinuationSuspended) {
                // Add the yielded value
                values.add(gen.suspendedValue)
                count++

                if (count < n) {
                    // Resume to get next state
                    val continuation = gen.continuation
                    gen = try {
                        when (val resumeFn = continuation.resumeFunction) {
                            is NativeFunkFunction -> resumeFn.call(listOf(FunkRecord(mapOf("value" to FunkNull))))
                            else -> FunkNull
                        }
                    } catch (e: Exception) {
                        FunkNull
                    }
                }
            }

            // Return { values: List, gen: remaining state }
            FunkRecord(mapOf(
                "values" to FunkList(values),
                "gen" to if (count < n) FunkNull else gen
            ))
        }
    }
}
