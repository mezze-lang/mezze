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
        registerAbort(context, interpreter)
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
}

/**
 * Exception thrown when a continuation is aborted.
 * Effect handlers should catch this and handle it appropriately.
 */
class ContinuationAbortedException(message: String) : RuntimeException(message)
