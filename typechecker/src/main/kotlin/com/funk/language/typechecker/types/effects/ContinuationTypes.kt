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
        // __native_continuation_delimit: {} -> 'c
        // Creates an opaque delimiter handle for scoped delimited continuations
        // The delimiter establishes a boundary for continuation capture and resume operations
        context["__native_continuation_delimit"] = FunkType.FunctionType(
            // Parameter type: {}
            FunkType.RecordType(emptyMap()),
            // Return type: 'c (opaque delimiter handle - FunkCont)
            FunkType.VarType(com.funk.language.ast.TypeVar("c"))
        )

        // __native_continuation_activate: { delim: 'c } -> {}
        // Activates a delimiter in thread-local state, making it the current active delimiter
        // This pushes the delimiter onto the thread-local stack for use by continuation operations
        context["__native_continuation_activate"] = FunkType.FunctionType(
            // Parameter type: { delim: 'c }
            FunkType.RecordType(mapOf(
                "delim" to FunkType.VarType(com.funk.language.ast.TypeVar("c"))
            )),
            // Return type: {} (unit value)
            FunkType.RecordType(emptyMap())
        )

        // __native_continuation_capture: { delim: 'c } -> { value: 'a } -> 'b
        // Captures the current continuation as a function
        context["__native_continuation_capture"] = FunkType.FunctionType(
            // Parameter type: { delim: 'c }
            FunkType.RecordType(mapOf(
                "delim" to FunkType.VarType(com.funk.language.ast.TypeVar("c"))
            )),
            // Return type: { value: 'a } -> 'b (a function that takes value and returns result)
            FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "value" to FunkType.VarType(com.funk.language.ast.TypeVar("a"))
                )),
                FunkType.VarType(com.funk.language.ast.TypeVar("b"))
            )
        )

        // __native_continuation_resume: { delim: 'c, cont: { value: 'a } -> 'b, value: 'a } -> 'b
        // Resumes a continuation with a value
        context["__native_continuation_resume"] = FunkType.FunctionType(
            // Parameter type: { delim: 'c, cont: function, value: 'a }
            FunkType.RecordType(mapOf(
                "delim" to FunkType.VarType(com.funk.language.ast.TypeVar("c")),
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

        // __native_continuation_suspend_with: { delim: 'c, value: 'a } -> 'b
        // Suspends with a value attached - packages value with the suspended continuation
        // This is the key primitive for implementing generators in userland
        context["__native_continuation_suspend_with"] = FunkType.FunctionType(
            // Parameter type: { delim: 'c, value: 'a }
            FunkType.RecordType(mapOf(
                "delim" to FunkType.VarType(com.funk.language.ast.TypeVar("c")),
                "value" to FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            )),
            // Return type: 'b (suspended state containing value + continuation)
            FunkType.VarType(com.funk.language.ast.TypeVar("b"))
        )

        // __native_continuation_fork: { delim: 'c, cont: { value: 'a } -> 'b, values: ['a] } -> ['b]
        // Multi-shot: resumes continuation once for each value, collects all results
        // This is the key primitive for implementing nondeterministic choice (amb/backtracking)
        context["__native_continuation_fork"] = FunkType.FunctionType(
            // Parameter type: { delim: 'c, cont: { value: 'a } -> 'b, values: ['a] }
            FunkType.RecordType(mapOf(
                "delim" to FunkType.VarType(com.funk.language.ast.TypeVar("c")),
                "cont" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf(
                        "value" to FunkType.VarType(com.funk.language.ast.TypeVar("a"))
                    )),
                    FunkType.VarType(com.funk.language.ast.TypeVar("b"))
                ),
                "values" to FunkType.ArrayType(FunkType.VarType(com.funk.language.ast.TypeVar("a")))
            )),
            // Return type: ['b] (array of all results from resuming with each value)
            FunkType.ArrayType(FunkType.VarType(com.funk.language.ast.TypeVar("b")))
        )

        // __native_continuation_abort: { delim: 'c } -> 'b
        // Aborts the current computation without resuming
        context["__native_continuation_abort"] = FunkType.FunctionType(
            // Parameter type: { delim: 'c }
            FunkType.RecordType(mapOf(
                "delim" to FunkType.VarType(com.funk.language.ast.TypeVar("c"))
            )),
            // Return type: 'b (never actually returns, but type is polymorphic)
            FunkType.VarType(com.funk.language.ast.TypeVar("b"))
        )

        // __native_continuation_is_suspended: { value: 'a } -> Bool
        // Checks if a value is a suspended continuation
        context["__native_continuation_is_suspended"] = FunkType.FunctionType(
            // Parameter type: { value: 'a }
            FunkType.RecordType(mapOf(
                "value" to FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            )),
            // Return type: Bool
            FunkType.BooleanType
        )

        // __native_continuation_extract_suspended: { delim: 'c, suspended: 'a } -> Option { value: 'v, cont: 'c }
        // Extracts the suspended value and continuation from a suspended state
        context["__native_continuation_extract_suspended"] = FunkType.FunctionType(
            // Parameter type: { delim: 'c, suspended: 'a }
            FunkType.RecordType(mapOf(
                "delim" to FunkType.VarType(com.funk.language.ast.TypeVar("c")),
                "suspended" to FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            )),
            // Return type: Option { value: 'v, cont: 'c }
            FunkType.TypeApplication("Option", listOf(
                FunkType.RecordType(mapOf(
                    "value" to FunkType.VarType(com.funk.language.ast.TypeVar("v")),
                    "cont" to FunkType.FunctionType(
                        FunkType.RecordType(mapOf(
                            "value" to FunkType.VarType(com.funk.language.ast.TypeVar("x"))
                        )),
                        FunkType.VarType(com.funk.language.ast.TypeVar("c"))
                    )
                ))
            ))
        )
    }
}
