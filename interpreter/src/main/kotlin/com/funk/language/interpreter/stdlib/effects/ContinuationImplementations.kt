package com.funk.language.interpreter.stdlib.effects

import com.funk.language.interpreter.*

/**
 * Native implementations for the Continuation effect.
 *
 * The Continuation effect provides first-class continuations for custom control flow:
 * - Generators (yield/resume)
 * - Async/Await (callback-based APIs)
 * - Backtracking (multi-shot continuations)
 * - Early Exit/Abort
 *
 * These operations are implemented natively in Kotlin for performance and to properly
 * capture and manipulate the execution stack.
 */
object ContinuationImplementations {

    /**
     * Register all continuation effect native functions.
     */
    fun registerAll(
        context: MutableMap<String, FunkValue>,
        interpreter: FunkInterpreter?
    ) {
        registerCapture(context, interpreter)
        registerResume(context, interpreter)
        registerSuspend(context, interpreter)
        registerSuspendWith(context, interpreter)
        registerFork(context, interpreter)
        registerAbort(context, interpreter)
        registerIsSuspended(context)
        registerExtractSuspended(context)
    }

    /**
     * __native_continuation_capture - Captures the current continuation.
     *
     * Returns a function { value: 'a } -> 'b that represents "the rest of the computation".
     * When called, this function resumes execution from the capture point.
     *
     * This captures the execution state (remaining statements, result expression, and context)
     * and creates a function that can resume from that point.
     */
    private fun registerCapture(
        context: MutableMap<String, FunkValue>,
        interpreter: FunkInterpreter?
    ) {
        context["__native_continuation_capture"] = NativeFunkFunction("__native_continuation_capture") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_continuation_capture", "expects FunkRecord")

            // Get interpreter from context
            val interpreterRef = context["__interpreter_instance"] as? InterpreterReference
                ?: throw NativeFunctionException("__native_continuation_capture", "Interpreter not available in context")
            val interpreter = interpreterRef.interpreter

            // Get the current execution state
            val executionState = interpreter.getCurrentExecutionState()
                ?: throw FunkException("Continuation.capture can only be called inside a do-block")

            val capturePoint = executionState.location

            // Create a continuation function that resumes from the captured state
            // When called with {value: X}, it executes the remaining statements and returns the result
            val resumeFunction = NativeFunkFunction("continuation_resume@$capturePoint") { resumeArgs ->
                val argRecord = resumeArgs.firstOrNull() as? FunkRecord
                    ?: throw FunkException("Resume expects record argument with 'value' field")

                val resumeValue = argRecord.fields["value"] ?: FunkNull

                // Create a new context from the captured state
                val resumeContext = executionState.context.toMutableMap()

                // The resume value isn't used in a simple capture/resume scenario
                // It would be used if the continuation was created with a pattern like:
                // let x = Continuation.suspend {}
                // Then when resumed, x would be bound to the resume value

                // Execute remaining statements
                for (stmt in executionState.remainingStatements) {
                    interpreter.executeStatement(stmt, resumeContext)
                }

                // Execute and return result expression
                interpreter.evaluateExpression(executionState.resultExpression, resumeContext)
            }

            // Return the continuation as a FunkContinuation value
            FunkContinuation(resumeFunction, capturePoint)
        }
    }

    /**
     * __native_continuation_resume - Resumes a continuation with a value.
     *
     * Takes:
     * - cont: The continuation function to resume
     * - value: The value to pass to the continuation
     *
     * Returns: The result of resuming the continuation
     */
    private fun registerResume(
        context: MutableMap<String, FunkValue>,
        interpreter: FunkInterpreter?
    ) {
        context["__native_continuation_resume"] = NativeFunkFunction("__native_continuation_resume") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_continuation_resume", "expects FunkRecord")

            val cont = record.fields["cont"]
                ?: throw NativeFunctionException("__native_continuation_resume", "expects 'cont' field")
            val value = record.fields["value"]
                ?: throw NativeFunctionException("__native_continuation_resume", "expects 'value' field")

            // Call the continuation with the value
            when (cont) {
                is FunkContinuation -> {
                    // Call the resume function inside the continuation
                    val arg = FunkRecord(mapOf("value" to value))
                    when (val resumeFn = cont.resumeFunction) {
                        is NativeFunkFunction -> resumeFn.call(listOf(arg))
                        else -> throw NativeFunctionException("__native_continuation_resume", "Invalid continuation")
                    }
                }
                else -> throw NativeFunctionException("__native_continuation_resume", "cont must be a FunkContinuation")
            }
        }
    }

    /**
     * __native_continuation_suspend - Suspends the current computation.
     *
     * Returns a FunkContinuationSuspended value to signal suspension.
     * The effect handler (perform Delimited) should detect this and handle it appropriately.
     */
    private fun registerSuspend(
        context: MutableMap<String, FunkValue>,
        interpreter: FunkInterpreter?
    ) {
        context["__native_continuation_suspend"] = NativeFunkFunction("__native_continuation_suspend") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_continuation_suspend", "expects FunkRecord")

            // Create a placeholder continuation
            val capturePoint = "suspend"
            val resumeFunction = NativeFunkFunction("suspended@$capturePoint") { resumeArgs ->
                val argRecord = resumeArgs.firstOrNull() as? FunkRecord
                    ?: throw FunkException("Resume expects record argument")
                argRecord.fields["value"] ?: FunkNull
            }

            val continuation = FunkContinuation(resumeFunction, capturePoint)

            // Return suspended continuation to signal suspension
            FunkContinuationSuspended(continuation, FunkNull)
        }
    }

    /**
     * __native_continuation_suspend_with - Suspends with a value attached.
     *
     * Like suspend, but packages a value with the suspended continuation.
     * This is the key primitive for implementing generators in userland.
     *
     * Takes: { value: 'a }
     * Returns: FunkContinuationSuspended containing both the value and continuation
     *
     * This enables building generators where:
     * - The value is what was yielded
     * - The continuation is how to resume for the next value
     */
    private fun registerSuspendWith(
        context: MutableMap<String, FunkValue>,
        interpreter: FunkInterpreter?
    ) {
        context["__native_continuation_suspend_with"] = NativeFunkFunction("__native_continuation_suspend_with") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_continuation_suspend_with", "expects FunkRecord")

            val value = record.fields["value"]
                ?: throw NativeFunctionException("__native_continuation_suspend_with", "expects 'value' field")

            // Get interpreter from context
            val interpreterRef = context["__interpreter_instance"] as? InterpreterReference
                ?: throw NativeFunctionException("__native_continuation_suspend_with", "Interpreter not available in context")
            val interpreterInstance = interpreterRef.interpreter

            // Get the current execution state to capture continuation
            val executionState = interpreterInstance.getCurrentExecutionState()
                ?: throw FunkException("Continuation.suspend_with can only be called inside a do-block")

            val capturePoint = executionState.location

            // Create a continuation function that resumes from here
            val resumeFunction = NativeFunkFunction("suspended_with@$capturePoint") { resumeArgs ->
                val argRecord = resumeArgs.firstOrNull() as? FunkRecord
                    ?: throw FunkException("Resume expects record argument")

                val resumeValue = argRecord.fields["value"] ?: FunkNull

                // Create context from captured state
                val resumeContext = executionState.context.toMutableMap()

                // Execute remaining statements
                for (stmt in executionState.remainingStatements) {
                    interpreterInstance.executeStatement(stmt, resumeContext)
                }

                // Execute and return result expression
                interpreterInstance.evaluateExpression(executionState.resultExpression, resumeContext)
            }

            val continuation = FunkContinuation(resumeFunction, capturePoint)

            // Return suspended continuation with the value attached
            // This packages both the yielded value AND the continuation for resumption
            FunkContinuationSuspended(continuation, value)
        }
    }

    /**
     * __native_continuation_fork - Multi-shot continuation: resume with multiple values.
     *
     * Takes:
     * - cont: The continuation to resume
     * - values: Array of values to resume with
     *
     * Returns: Array of results from resuming the continuation with each value
     *
     * This is the key primitive for implementing nondeterministic choice (amb/backtracking).
     * It enables exploring multiple execution paths by resuming the same continuation
     * multiple times with different values.
     */
    private fun registerFork(
        context: MutableMap<String, FunkValue>,
        interpreter: FunkInterpreter?
    ) {
        context["__native_continuation_fork"] = NativeFunkFunction("__native_continuation_fork") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_continuation_fork", "expects FunkRecord")

            val cont = record.fields["cont"]
                ?: throw NativeFunctionException("__native_continuation_fork", "expects 'cont' field")
            val values = record.fields["values"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_continuation_fork", "expects 'values' field as array")

            when (cont) {
                is FunkContinuation -> {
                    // Resume the continuation once for each value, collecting results
                    val results = values.elements.map { value ->
                        // Call the continuation's resume function with this value
                        val arg = FunkRecord(mapOf("value" to value))
                        when (val resumeFn = cont.resumeFunction) {
                            is NativeFunkFunction -> {
                                try {
                                    resumeFn.call(listOf(arg))
                                } catch (e: ContinuationAbortedException) {
                                    // If this path aborts, don't include it in results
                                    null
                                }
                            }
                            else -> throw NativeFunctionException("__native_continuation_fork", "Invalid continuation")
                        }
                    }.filterNotNull() // Remove aborted paths

                    FunkArrayList(results.toMutableList())
                }
                else -> throw NativeFunctionException("__native_continuation_fork", "cont must be a FunkContinuation")
            }
        }
    }

    /**
     * __native_continuation_abort - Aborts the current computation.
     *
     * Throws a ContinuationAbortedException to stop execution.
     * The effect handler should catch this and handle it appropriately.
     */
    private fun registerAbort(
        context: MutableMap<String, FunkValue>,
        interpreter: FunkInterpreter?
    ) {
        context["__native_continuation_abort"] = NativeFunkFunction("__native_continuation_abort") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_continuation_abort", "expects FunkRecord")

            // Throw exception to abort
            throw ContinuationAbortedException("Continuation aborted")
        }
    }

    /**
     * __native_continuation_is_suspended - Check if a value is a suspended continuation.
     *
     * Takes: { value: 'a }
     * Returns: Bool - true if the value is FunkContinuationSuspended, false otherwise
     */
    private fun registerIsSuspended(context: MutableMap<String, FunkValue>) {
        context["__native_continuation_is_suspended"] = NativeFunkFunction("__native_continuation_is_suspended") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_continuation_is_suspended", "expects FunkRecord")

            val value = record.fields["value"]
                ?: throw NativeFunctionException("__native_continuation_is_suspended", "expects 'value' field")

            FunkBool(value is FunkContinuationSuspended)
        }
    }

    /**
     * __native_continuation_extract_suspended - Extract value and continuation from suspended state.
     *
     * Takes: { suspended: 'a }
     * Returns: Option { value: 'v, cont: 'c } - Some if suspended, None otherwise
     *
     * This allows userland code to work with suspended continuations by unpacking them
     * into their constituent parts (the yielded value and the continuation).
     */
    private fun registerExtractSuspended(context: MutableMap<String, FunkValue>) {
        context["__native_continuation_extract_suspended"] = NativeFunkFunction("__native_continuation_extract_suspended") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_continuation_extract_suspended", "expects FunkRecord")

            val suspended = record.fields["suspended"]
                ?: throw NativeFunctionException("__native_continuation_extract_suspended", "expects 'suspended' field")

            when (suspended) {
                is FunkContinuationSuspended -> {
                    // Return Some { value: suspended_value, cont: continuation }
                    FunkVariant("Some", FunkRecord(mapOf(
                        "val" to FunkRecord(mapOf(
                            "value" to suspended.suspendedValue,
                            "cont" to suspended.continuation
                        ))
                    )))
                }
                else -> {
                    // Return None
                    FunkVariant("None", null)
                }
            }
        }
    }

}

/**
 * Exception thrown when a continuation is aborted.
 * Effect handlers should catch this and handle it appropriately.
 */
class ContinuationAbortedException(message: String) : RuntimeException(message)
