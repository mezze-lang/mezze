package com.funk.language.typechecker.types.effects

import com.funk.language.ast.FunkType

/**
 * Type definitions for the Continuation effect.
 *
 * The Continuation effect provides first-class continuations for custom control flow.
 * These are low-level operations used to implement generators, backtracking, async/await, etc.
 */
object ContinuationTypes {

    /**
     * Register all continuation effect native function types.
     */
    fun registerAll(context: MutableMap<String, FunkType>) {
        // __native_continuation_capture: {} -> { value: 'a } -> 'b
        // Captures the current continuation as a function
        context["__native_continuation_capture"] = FunkType.FunctionType(
            // Parameter type: {}
            FunkType.RecordType(emptyMap()),
            // Return type: { value: 'a } -> 'b (a function that takes value and returns result)
            FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "value" to FunkType.VarType(com.funk.language.ast.TypeVar("a"))
                )),
                FunkType.VarType(com.funk.language.ast.TypeVar("b"))
            )
        )

        // __native_continuation_resume: { cont: { value: 'a } -> 'b, value: 'a } -> 'b
        // Resumes a continuation with a value
        context["__native_continuation_resume"] = FunkType.FunctionType(
            // Parameter type: { cont: function, value: 'a }
            FunkType.RecordType(mapOf(
                "cont" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf(
                        "value" to FunkType.VarType(com.funk.language.ast.TypeVar("a"))
                    )),
                    FunkType.VarType(com.funk.language.ast.TypeVar("b"))
                ),
                "value" to FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            )),
            // Return type: 'b
            FunkType.VarType(com.funk.language.ast.TypeVar("b"))
        )

        // __native_continuation_suspend: {} -> 'a
        // Suspends the current computation, returning a suspended value
        context["__native_continuation_suspend"] = FunkType.FunctionType(
            // Parameter type: {}
            FunkType.RecordType(emptyMap()),
            // Return type: 'a (can be any type, signaling suspension)
            FunkType.VarType(com.funk.language.ast.TypeVar("a"))
        )

        // __native_continuation_suspend_with: { value: 'a } -> 'b
        // Suspends with a value attached - packages value with the suspended continuation
        // This is the key primitive for implementing generators in userland
        context["__native_continuation_suspend_with"] = FunkType.FunctionType(
            // Parameter type: { value: 'a }
            FunkType.RecordType(mapOf(
                "value" to FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            )),
            // Return type: 'b (suspended state containing value + continuation)
            FunkType.VarType(com.funk.language.ast.TypeVar("b"))
        )

        // __native_continuation_abort: {} -> 'b
        // Aborts the current computation without resuming
        context["__native_continuation_abort"] = FunkType.FunctionType(
            // Parameter type: {}
            FunkType.RecordType(emptyMap()),
            // Return type: 'b (never actually returns, but type is polymorphic)
            FunkType.VarType(com.funk.language.ast.TypeVar("b"))
        )
    }
}
