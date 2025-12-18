package com.funk.language.interpreter.stdlib.effects

import com.funk.language.interpreter.*
import com.funk.language.interpreter.effects.*
import com.funk.language.interpreter.concurrency.LoomConcurrencyManager
import java.util.UUID

/**
 * Native implementations for the Continuation effect using state machines + Loom virtual threads.
 *
 * This implementation uses explicit state machines (as per docs/implementation/continuation_state_machines.md)
 * instead of closure-based captures, providing:
 * - Better Truffle optimization
 * - Inspectability and debugging
 * - Support for multishot continuations
 * - Serialization (future)
 *
 * The continuation effect provides first-class continuations for custom control flow:
 * - Generators (yield/resume)
 * - Async/Await (callback-based APIs)
 * - Backtracking (multi-shot continuations)
 * - Early Exit/Abort
 */
object ContinuationImplementations {

    /**
     * Register all continuation effect native functions.
     * Note: interpreter parameter is no longer used - we use state machines instead.
     */
    fun registerAll(
        context: MutableMap<String, FunkValue>,
        interpreter: FunkInterpreter?
    ) {
        registerSuspendWith(context)
        registerResume(context)
        registerFork(context)
        registerAbort(context)
        registerIsSuspended(context)
        registerExtractSuspended(context)
    }

    /**
     * __native_continuation_suspend_with - Suspends with a value attached using state machines.
     *
     * This is the key primitive for implementing generators in userland.
     * Instead of capturing the old map-based interpreter state, we create a continuation
     * state machine that can be resumed.
     *
     * Takes: { value: 'a }
     * Returns: FunkContinuationSuspended containing both the value and continuation
     */
    private fun registerSuspendWith(context: MutableMap<String, FunkValue>) {
        context["__native_continuation_suspend_with"] = NativeFunkFunction("__native_continuation_suspend_with") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_continuation_suspend_with", "expects FunkRecord")

            val value = record.fields["value"]
                ?: throw NativeFunctionException("__native_continuation_suspend_with", "expects 'value' field")

            // Create a suspension point using the state machine infrastructure
            // The continuation is represented as a resumable state machine
            val suspensionId = UUID.randomUUID().toString()

            // Build a simple state machine that can be resumed
            // In a full implementation, this would capture the actual continuation point
            val stateMachine = EffectStateMachineBuilder()
                .addComputeState("resume_point") { effectContext ->
                    // When resumed, return the resume value
                    effectContext.getVariable("resume_value") ?: FunkNull
                }
                .setFinalStateId("resume_point")
                .build()

            // Create a suspension point
            val suspensionPoint = SuspensionPoint(
                id = suspensionId,
                stateMachineId = stateMachine.id,
                currentStateId = "resume_point",
                context = EffectContext.empty(),
                continuationCallback = { resumeValue: FunkValue, ctx: EffectContext ->
                    ctx.withVariable("resume_value", resumeValue)
                },
                abortCallback = { error: Throwable, ctx: EffectContext ->
                    ctx // Just return context on abort
                }
            )

            // Create a continuation function that wraps the state machine
            val resumeFunction = NativeFunkFunction("suspended_with@$suspensionId") { resumeArgs ->
                val argRecord = resumeArgs.firstOrNull() as? FunkRecord
                    ?: throw FunkException("Resume expects record argument")

                val resumeValue = argRecord.fields["value"] ?: FunkNull

                // Resume the state machine with the value
                val effectContext = EffectContext.empty()
                    .withVariable("resume_value", resumeValue)

                stateMachine.execute(effectContext)
            }

            val continuation = FunkContinuation(resumeFunction, suspensionId)

            // Return suspended continuation with the value attached
            FunkContinuationSuspended(continuation, value)
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
    private fun registerResume(context: MutableMap<String, FunkValue>) {
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
     * __native_continuation_fork - Multi-shot continuation: resume with multiple values.
     *
     * Takes:
     * - cont: The continuation to resume
     * - values: Array of values to resume with
     *
     * Returns: Array of results from resuming the continuation with each value
     *
     * This enables nondeterministic choice (amb/backtracking) by resuming the same
     * continuation multiple times with different values using Loom virtual threads.
     */
    private fun registerFork(context: MutableMap<String, FunkValue>) {
        context["__native_continuation_fork"] = NativeFunkFunction("__native_continuation_fork") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_continuation_fork", "expects FunkRecord")

            val cont = record.fields["cont"]
                ?: throw NativeFunctionException("__native_continuation_fork", "expects 'cont' field")
            val values = record.fields["values"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_continuation_fork", "expects 'values' field as array")

            when (cont) {
                is FunkContinuation -> {
                    // Use Loom virtual threads to resume the continuation multiple times in parallel
                    val concurrencyManager = LoomConcurrencyManager()

                    // Create tasks for each resume
                    val tasks = values.elements.map { value ->
                        java.util.concurrent.Callable {
                            try {
                                val arg = FunkRecord(mapOf("value" to value))
                                when (val resumeFn = cont.resumeFunction) {
                                    is NativeFunkFunction -> resumeFn.call(listOf(arg))
                                    else -> throw NativeFunctionException("__native_continuation_fork", "Invalid continuation")
                                }
                            } catch (e: ContinuationAbortedException) {
                                // If this path aborts, return null (will be filtered)
                                null
                            }
                        }
                    }

                    // Execute all tasks using virtual threads with structured concurrency
                    val results = concurrencyManager.executeAll(tasks).filterNotNull()

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
    private fun registerAbort(context: MutableMap<String, FunkValue>) {
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
