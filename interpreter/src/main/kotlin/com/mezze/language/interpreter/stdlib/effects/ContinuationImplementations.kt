package com.mezze.language.interpreter.stdlib.effects

import com.mezze.language.interpreter.*
import com.mezze.language.interpreter.effects.*
import com.mezze.language.interpreter.continuations.ContinuationStateMachine
import com.mezze.language.interpreter.continuations.ContinuationStateMachineBuilder
import com.mezze.language.interpreter.continuations.ContinuationResumeFn
import java.util.UUID

/**
 * Truffle-optimized implementations for the Continuation effect using explicit state machines.
 *
 * Phase 8 of Truffle migration: Effect system optimized for JIT performance.
 * This implementation uses explicit state machines instead of closure-based captures.
 *
 * Optimizations:
 * - Explicit state machines for better Truffle optimization (vs closure captures)
 * - Inspectable and debuggable continuation state
 * - Support for multishot continuations (fork with multiple values)
 * - Thread-local delimiter stack for scoped continuations
 * - Opaque MezzeCont handles (cannot be forged by user code)
 * - Cartesian product flattening for nested choice points
 * - Proper abort handling with ContinuationAbortedException
 *
 * The continuation effect provides first-class continuations for custom control flow:
 * - Generators (yield/resume via suspend_with/extract_suspended)
 * - Nondeterministic choice (backtracking/multishot via fork)
 * - Early Exit/Abort (via abort)
 *
 * Operations: delimit, activate, capture, suspend, suspend_with, resume, fork, abort,
 *            is_suspended, extract_suspended
 *
 * Note: Loom is NOT used for continuations - only for async I/O primitives.
 *
 * Total: 10 continuation functions
 */
object ContinuationImplementations {

    /**
     * Thread-local stack to track active delimiters.
     * When a delimiter is activated, it's pushed onto this stack.
     * Native continuation functions use the top delimiter from this stack.
     */
    private val activeDelimiters = ThreadLocal.withInitial { mutableListOf<MezzeCont>() }

    /**
     * Register all continuation effect native functions.
     * Note: interpreter parameter is no longer used - we use state machines instead.
     */
    fun registerAll(
        context: MutableMap<String, MezzeValue>,
        interpreter: MezzeInterpreter?
    ) {
        registerDelimit(context)
        registerActivate(context)
        registerCapture(context)
        registerSuspend(context)
        registerSuspendWith(context)
        registerResume(context)
        registerFork(context)
        registerAbort(context)
        registerIsSuspended(context)
        registerExtractSuspended(context)
    }

    /**
     * Get the currently active delimiter from thread-local state.
     */
    private fun getCurrentDelimiter(): MezzeCont? {
        val stack = activeDelimiters.get()
        return if (stack.isNotEmpty()) stack.last() else null
    }

    /**
     * __native_continuation_delimit - Creates a delimiter for scoped continuations.
     *
     * This establishes a delimiter boundary for delimited continuations, returning
     * an opaque MezzeCont handle that cannot be forged or inspected by user code.
     * The delimiter provides a scope boundary for continuation capture and resume operations.
     *
     * In the new continuation architecture, delimiters are explicit boundaries that:
     * - Define the extent of continuation capture
     * - Ensure type safety through opaque handles
     * - Enable proper scoping of effect handlers
     *
     * Takes: {}
     * Returns: MezzeCont - Opaque delimiter handle
     */
    private fun registerDelimit(context: MutableMap<String, MezzeValue>) {
        context["__native_continuation_delimit"] = NativeMezzeFunction("__native_continuation_delimit") { args ->

            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_continuation_delimit", "expects MezzeRecord")

            // Create a unique delimiter ID
            val delimiterId = UUID.randomUUID().toString()


            // Return opaque delimiter handle
            // This cannot be forged by user code - only __native_continuation_delimit can create these
            MezzeCont(delimiterId)
        }
    }

    /**
     * __native_continuation_activate - Activates a delimiter in thread-local state.
     *
     * This pushes the delimiter onto the thread-local stack, making it the current
     * active delimiter. Subsequent continuation operations will use this delimiter.
     *
     * Takes: { delim: MezzeCont }
     * Returns: {} (unit value)
     */
    private fun registerActivate(context: MutableMap<String, MezzeValue>) {
        context["__native_continuation_activate"] = NativeMezzeFunction("__native_continuation_activate") { args ->

            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_continuation_activate", "expects MezzeRecord")

            val delim = record.fields["delim"] as? MezzeCont
                ?: throw NativeFunctionException("__native_continuation_activate", "expects 'delim' field as MezzeCont")


            // Push delimiter onto thread-local stack
            activeDelimiters.get().add(delim)

            // Return unit
            MezzeRecord(emptyMap())
        }
    }

    /**
     * __native_continuation_capture - Captures the current delimited continuation.
     *
     * This captures "the rest of the computation" up to the delimiter (perform boundary)
     * as a function that can be called later. This is a DELIMITED continuation.
     *
     * Phase 2: Uses compiler-generated state machines. When called from an effect handler,
     * it captures the CALLER's continuation (not the handler's). The captured continuation
     * represents "the rest of the caller's computation after the effect call" up to the
     * perform boundary.
     *
     * Example:
     *   perform AllPaths in do
     *       let x = Choice.choose { options = [1,2] }  // <- capture happens here
     *       x + 10                                      // <- this is what gets captured
     *
     * The handler (Choice.choose) calls capture, which returns a continuation representing
     * "x + 10" where x is the hole to fill.
     *
     * Takes: {}
     * Returns: { value: 'a } -> 'b - A function that resumes the captured continuation
     */
    private fun registerCapture(context: MutableMap<String, MezzeValue>) {
        context["__native_continuation_capture"] = NativeMezzeFunction("__native_continuation_capture") { args ->

            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_continuation_capture", "expects MezzeRecord")

            // Get delimiter from parameter (bound in closure)
            val delim = record.fields["delim"] as? MezzeCont
                ?: throw NativeFunctionException("__native_continuation_capture", "expects 'delim' field as MezzeCont")

            // Get current execution context from state machine
            val execContext = ContinuationStateMachine.getCurrentContext()

            // If no state machine context exists (e.g., called from test without perform block),
            // return a trivial continuation that just returns its input
            if (execContext == null) {

                val trivialMachine = ContinuationStateMachineBuilder()
                    .addComputeState { _, input -> input }
                    .build(emptyMap())

                val resumeFunction = ContinuationResumeFn(trivialMachine)
                return@NativeMezzeFunction MezzeContinuation(resumeFunction, "capture_outside_context@${delim.delimiterId}")
            }

            val currentMachine = execContext.machine
            val currentStateId = execContext.currentStateId

            val nextStateId = currentStateId + 1

            if (nextStateId >= currentMachine.states.size) {
                // No more states - return trivial continuation
                val trivialMachine = ContinuationStateMachineBuilder()
                    .addComputeState { _, input -> input }
                    .build(currentMachine.capturedLocals)

                val resumeFunction = ContinuationResumeFn(trivialMachine)
                MezzeContinuation(resumeFunction, "capture_terminal@${delim.delimiterId}")
            } else {
                // Create continuation from next state
                val continuationMachine = currentMachine.transitionTo(
                    nextStateId,
                    currentMachine.capturedLocals
                )

                val resumeFunction = ContinuationResumeFn(continuationMachine)
                MezzeContinuation(resumeFunction, "capture@${delim.delimiterId}@$currentStateId")
            }
        }
    }

    /**
     * __native_continuation_suspend - Suspends the current computation without a value.
     *
     * This is like suspend_with but doesn't attach a value. Returns a suspended continuation
     * that can be resumed later.
     *
     * Takes: {}
     * Returns: MezzeContinuationSuspended
     */
    private fun registerSuspend(context: MutableMap<String, MezzeValue>) {
        context["__native_continuation_suspend"] = NativeMezzeFunction("__native_continuation_suspend") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_continuation_suspend", "expects MezzeRecord")

            val suspensionId = UUID.randomUUID().toString()

            // Build a continuation state machine
            val stateMachine = ContinuationStateMachineBuilder()
                .addComputeState { machine, input ->
                    // When resumed, return the resume value
                    input
                }
                .build()

            // Create the resume function using the state machine
            val resumeFunction = ContinuationResumeFn(stateMachine)
            val continuation = MezzeContinuation(resumeFunction, "suspend@$suspensionId")

            // Return suspended continuation without a value (using MezzeNull)
            MezzeContinuationSuspended(continuation, MezzeNull)
        }
    }

    /**
     * __native_continuation_suspend_with - Suspends with a value attached using state machines.
     *
     * This is the key primitive for implementing generators in userland.
     * We create a continuation state machine that can be resumed.
     *
     * Takes: { delim: MezzeCont, value: 'a }
     * Returns: MezzeContinuationSuspended containing both the value and continuation
     */
    private fun registerSuspendWith(context: MutableMap<String, MezzeValue>) {
        context["__native_continuation_suspend_with"] = NativeMezzeFunction("__native_continuation_suspend_with") { args ->

            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_continuation_suspend_with", "expects MezzeRecord")

            val delim = record.fields["delim"] as? MezzeCont
                ?: throw NativeFunctionException("__native_continuation_suspend_with", "expects 'delim' field as MezzeCont")
            val value = record.fields["value"]
                ?: throw NativeFunctionException("__native_continuation_suspend_with", "expects 'value' field")

            val suspensionId = UUID.randomUUID().toString()

            // Build a continuation state machine
            val stateMachine = ContinuationStateMachineBuilder()
                .addComputeState { machine, input ->
                    // When resumed, simply return the resume value
                    input
                }
                .build()

            // Create the resume function that wraps the state machine
            val resumeFunction = ContinuationResumeFn(stateMachine)
            val continuation = MezzeContinuation(resumeFunction, "suspend_with@$suspensionId")

            // Return suspended continuation with the value attached
            MezzeContinuationSuspended(continuation, value)
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
    private fun registerResume(context: MutableMap<String, MezzeValue>) {
        context["__native_continuation_resume"] = NativeMezzeFunction("__native_continuation_resume") { args ->

            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_continuation_resume", "expects MezzeRecord")

            val delim = record.fields["delim"] as? MezzeCont
                ?: throw NativeFunctionException("__native_continuation_resume", "expects 'delim' field as MezzeCont")
            val cont = record.fields["cont"]
                ?: throw NativeFunctionException("__native_continuation_resume", "expects 'cont' field")
            val value = record.fields["value"]
                ?: throw NativeFunctionException("__native_continuation_resume", "expects 'value' field")

            // Call the continuation with the value
            when (cont) {
                is MezzeContinuation -> {
                    // Call the resume function inside the continuation
                    val arg = MezzeRecord(mapOf("value" to value))
                    when (val resumeFn = cont.resumeFunction) {
                        is NativeMezzeFunction -> resumeFn.call(listOf(arg))
                        else -> throw NativeFunctionException("__native_continuation_resume", "Invalid continuation")
                    }
                }
                else -> throw NativeFunctionException("__native_continuation_resume", "cont must be a MezzeContinuation")
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
     * continuation multiple times with different values using STATE MACHINES (not Loom).
     *
     * Nested Fork Handling:
     * When fork is called from within a forked continuation (nested choice points),
     * instead of returning an array immediately, it throws ForkedExecutionException.
     * The top-level fork catches this and recursively forks, flattening all results.
     * This enables proper cartesian product for nested choices:
     *   choose [1,2]; choose [10,20] -> [11, 21, 12, 22]
     */
    private fun registerFork(context: MutableMap<String, MezzeValue>) {
        context["__native_continuation_fork"] = NativeMezzeFunction("__native_continuation_fork") { args ->

            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_continuation_fork", "expects MezzeRecord")

            val delim = record.fields["delim"] as? MezzeCont
                ?: throw NativeFunctionException("__native_continuation_fork", "expects 'delim' field as MezzeCont")
            val cont = record.fields["cont"]
                ?: throw NativeFunctionException("__native_continuation_fork", "expects 'cont' field")
            val values = record.fields["values"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_continuation_fork", "expects 'values' field as array")

            // Check for empty values array - this should abort
            if (values.elements.isEmpty()) {
                throw ContinuationAbortedException("Continuation aborted: cannot fork with empty values array")
            }

            // Check if we're inside a forked continuation using call stack detection
            if (isInsideFork) {
                // Nested fork: throw exception instead of executing
                // This will be caught by the top-level fork
                throw ForkedExecutionException(cont, values.toList())
            }

            when (cont) {
                is MezzeContinuation -> {
                    // Top-level fork: execute state machine multiple times and throw exception
                    // to prevent the original state machine from continuing

                    val results = executeFork(cont, values.toList())

                    // Throw exception to abort the current execution and return fork results
                    throw ForkedExecutionException(cont, results.toList())
                }
                else -> throw NativeFunctionException("__native_continuation_fork", "cont must be a MezzeContinuation")
            }
        }
    }

    /**
     * Flag indicating whether we're currently inside a forked continuation.
     * Used to detect nested forks.
     */
    @Volatile
    private var isInsideFork = false

    /**
     * Execute a fork operation using state machines (NO LOOM).
     *
     * This executes the continuation state machine multiple times with different values.
     * Since state machines are immutable, each execution is independent (multishot semantics).
     *
     * Returns an array with results from all execution paths.
     */
    private fun executeFork(cont: MezzeContinuation, values: List<MezzeValue>): MezzeArrayList {
        val results = mutableListOf<MezzeValue>()


        for ((index, value) in values.withIndex()) {
            try {

                // Set flag to indicate we're inside a forked continuation
                val wasInsideFork = isInsideFork
                isInsideFork = true
                try {
                    val arg = MezzeRecord(mapOf("value" to value))
                    when (val resumeFn = cont.resumeFunction) {
                        is NativeMezzeFunction -> {
                            val result = resumeFn.call(listOf(arg))

                            results.add(result)
                        }
                        else -> throw NativeFunctionException("__native_continuation_fork", "Invalid continuation")
                    }
                } catch (e: ForkedExecutionException) {
                    // Nested fork detected! Recursively fork the continuation
                    // from the exception with the values from the exception

                    val nestedCont = e.continuation as? MezzeContinuation
                        ?: throw NativeFunctionException("__native_continuation_fork", "Invalid nested continuation")

                    // Fork the nested continuation
                    val nestedResults = executeFork(nestedCont, e.values)


                    // Add all nested results (cartesian product)
                    results.addAll(nestedResults.elements)
                } finally {
                    // Restore previous flag state
                    isInsideFork = wasInsideFork
                }
            } catch (e: ContinuationAbortedException) {
                // If this path aborts, skip it (don't add to results)

            }
        }


        return MezzeArrayList(results)
    }

    /**
     * __native_continuation_abort - Aborts the current computation.
     *
     * Takes: {}
     *
     * Throws a ContinuationAbortedException to stop execution.
     * The effect handler should catch this and handle it appropriately.
     */
    private fun registerAbort(context: MutableMap<String, MezzeValue>) {
        context["__native_continuation_abort"] = NativeMezzeFunction("__native_continuation_abort") { args ->

            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_continuation_abort", "expects MezzeRecord")

            val delim = record.fields["delim"] as? MezzeCont
                ?: throw NativeFunctionException("__native_continuation_abort", "expects 'delim' field as MezzeCont")

            // Throw exception to abort
            throw ContinuationAbortedException("Continuation aborted for delimiter ${delim.delimiterId}")
        }
    }

    /**
     * __native_continuation_is_suspended - Check if a value is a suspended continuation.
     *
     * Takes: { value: 'a }
     * Returns: Bool - true if the value is MezzeContinuationSuspended, false otherwise
     */
    private fun registerIsSuspended(context: MutableMap<String, MezzeValue>) {
        context["__native_continuation_is_suspended"] = NativeMezzeFunction("__native_continuation_is_suspended") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_continuation_is_suspended", "expects MezzeRecord")

            val value = record.fields["value"]
                ?: throw NativeFunctionException("__native_continuation_is_suspended", "expects 'value' field")

            MezzeBool(value is MezzeContinuationSuspended)
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
    private fun registerExtractSuspended(context: MutableMap<String, MezzeValue>) {
        context["__native_continuation_extract_suspended"] = NativeMezzeFunction("__native_continuation_extract_suspended") { args ->

            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_continuation_extract_suspended", "expects MezzeRecord")

            val delim = record.fields["delim"] as? MezzeCont
                ?: throw NativeFunctionException("__native_continuation_extract_suspended", "expects 'delim' field as MezzeCont")
            val suspended = record.fields["suspended"]
                ?: throw NativeFunctionException("__native_continuation_extract_suspended", "expects 'suspended' field")

            when (suspended) {
                is MezzeContinuationSuspended -> {
                    // Return Some { val = { value: suspended_value, cont: continuation } }
                    // The Some variant has a "val" field containing the record
                    MezzeVariant("Some", MezzeRecord(mapOf(
                        "val" to MezzeRecord(mapOf(
                            "value" to suspended.suspendedValue,
                            "cont" to suspended.continuation
                        ))
                    )))
                }
                else -> {
                    // Return None
                    mezzeOptionNone()
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
