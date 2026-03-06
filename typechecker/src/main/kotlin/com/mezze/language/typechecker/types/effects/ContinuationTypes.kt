package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType

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
    fun registerAll(context: MutableMap<String, MezzeType>) {
        // __native_continuation_delimit: {} -> 'c
        // Creates an opaque delimiter handle for scoped delimited continuations
        // The delimiter establishes a boundary for continuation capture and resume operations
        context["__native_continuation_delimit"] = MezzeType.FunctionType(
            // Parameter type: {}
            MezzeType.RecordType(emptyMap()),
            // Return type: 'c (opaque delimiter handle - MezzeCont)
            MezzeType.VarType(com.mezze.language.ast.TypeVar("c"))
        )

        // __native_continuation_activate: { delim: 'c } -> {}
        // Activates a delimiter in thread-local state, making it the current active delimiter
        // This pushes the delimiter onto the thread-local stack for use by continuation operations
        context["__native_continuation_activate"] = MezzeType.FunctionType(
            // Parameter type: { delim: 'c }
            MezzeType.RecordType(mapOf(
                "delim" to MezzeType.VarType(com.mezze.language.ast.TypeVar("c"))
            )),
            // Return type: {} (unit value)
            MezzeType.RecordType(emptyMap())
        )

        // __native_continuation_capture: { delim: 'c } -> { value: 'a } -> 'b
        // Captures the current continuation as a function
        context["__native_continuation_capture"] = MezzeType.FunctionType(
            // Parameter type: { delim: 'c }
            MezzeType.RecordType(mapOf(
                "delim" to MezzeType.VarType(com.mezze.language.ast.TypeVar("c"))
            )),
            // Return type: { value: 'a } -> 'b (a function that takes value and returns result)
            MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "value" to MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
                )),
                MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            )
        )

        // __native_continuation_resume: { delim: 'c, cont: { value: 'a } -> 'b, value: 'a } -> 'b
        // Resumes a continuation with a value
        context["__native_continuation_resume"] = MezzeType.FunctionType(
            // Parameter type: { delim: 'c, cont: function, value: 'a }
            MezzeType.RecordType(mapOf(
                "delim" to MezzeType.VarType(com.mezze.language.ast.TypeVar("c")),
                "cont" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf(
                        "value" to MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
                    )),
                    MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
                ),
                "value" to MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            )),
            // Return type: 'b
            MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
        )

        // __native_continuation_suspend: {} -> 'a
        // Suspends the current computation, returning a suspended value
        context["__native_continuation_suspend"] = MezzeType.FunctionType(
            // Parameter type: {}
            MezzeType.RecordType(emptyMap()),
            // Return type: 'a (can be any type, signaling suspension)
            MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
        )

        // __native_continuation_suspend_with: { delim: 'c, value: 'a } -> 'b
        // Suspends with a value attached - packages value with the suspended continuation
        // This is the key primitive for implementing generators in userland
        context["__native_continuation_suspend_with"] = MezzeType.FunctionType(
            // Parameter type: { delim: 'c, value: 'a }
            MezzeType.RecordType(mapOf(
                "delim" to MezzeType.VarType(com.mezze.language.ast.TypeVar("c")),
                "value" to MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            )),
            // Return type: 'b (suspended state containing value + continuation)
            MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
        )

        // __native_continuation_fork: { delim: 'c, cont: { value: 'a } -> 'b, values: ['a] } -> ['b]
        // Multi-shot: resumes continuation once for each value, collects all results
        // This is the key primitive for implementing nondeterministic choice (amb/backtracking)
        context["__native_continuation_fork"] = MezzeType.FunctionType(
            // Parameter type: { delim: 'c, cont: { value: 'a } -> 'b, values: ['a] }
            MezzeType.RecordType(mapOf(
                "delim" to MezzeType.VarType(com.mezze.language.ast.TypeVar("c")),
                "cont" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf(
                        "value" to MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
                    )),
                    MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
                ),
                "values" to MezzeType.ArrayType(MezzeType.VarType(com.mezze.language.ast.TypeVar("a")))
            )),
            // Return type: ['b] (array of all results from resuming with each value)
            MezzeType.ArrayType(MezzeType.VarType(com.mezze.language.ast.TypeVar("b")))
        )

        // __native_continuation_abort: { delim: 'c } -> 'b
        // Aborts the current computation without resuming
        context["__native_continuation_abort"] = MezzeType.FunctionType(
            // Parameter type: { delim: 'c }
            MezzeType.RecordType(mapOf(
                "delim" to MezzeType.VarType(com.mezze.language.ast.TypeVar("c"))
            )),
            // Return type: 'b (never actually returns, but type is polymorphic)
            MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
        )

        // __native_continuation_is_suspended: { value: 'a } -> Bool
        // Checks if a value is a suspended continuation
        context["__native_continuation_is_suspended"] = MezzeType.FunctionType(
            // Parameter type: { value: 'a }
            MezzeType.RecordType(mapOf(
                "value" to MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            )),
            // Return type: Bool
            MezzeType.BoolType
        )

        // __native_continuation_extract_suspended: { delim: 'c, suspended: 'a } -> Option { value: 'v, cont: 'c }
        // Extracts the suspended value and continuation from a suspended state
        context["__native_continuation_extract_suspended"] = MezzeType.FunctionType(
            // Parameter type: { delim: 'c, suspended: 'a }
            MezzeType.RecordType(mapOf(
                "delim" to MezzeType.VarType(com.mezze.language.ast.TypeVar("c")),
                "suspended" to MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            )),
            // Return type: Option { value: 'v, cont: 'c }
            MezzeType.TypeApplication("Option", listOf(
                MezzeType.RecordType(mapOf(
                    "value" to MezzeType.VarType(com.mezze.language.ast.TypeVar("v")),
                    "cont" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf(
                            "value" to MezzeType.VarType(com.mezze.language.ast.TypeVar("x"))
                        )),
                        MezzeType.VarType(com.mezze.language.ast.TypeVar("c"))
                    )
                ))
            ))
        )

        // __native_continuation_deactivate: { delim: 'c } -> {}
        // Pops the delimiter from the thread-local stack, ending its scope.
        // Should be called when done using a continuation to prevent stack leaks.
        context["__native_continuation_deactivate"] = MezzeType.FunctionType(
            // Parameter type: { delim: 'c }
            MezzeType.RecordType(mapOf(
                "delim" to MezzeType.VarType(com.mezze.language.ast.TypeVar("c"))
            )),
            // Return type: {} (unit)
            MezzeType.RecordType(emptyMap())
        )
    }
}
