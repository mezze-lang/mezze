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
            com.mezze.language.typechecker.util.WellKnownTypeApp.of("Option", listOf(
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

        // __native_continuation_suspend_and_resume: { delim: 'c, value: 'a } -> {}
        // Collects a value and immediately resumes execution (for Generator yield).
        // Unlike suspend_with which escapes, this adds to internal collector and continues.
        context["__native_continuation_suspend_and_resume"] = MezzeType.FunctionType(
            // Parameter type: { delim: 'c, value: 'a }
            MezzeType.RecordType(mapOf(
                "delim" to MezzeType.VarType(com.mezze.language.ast.TypeVar("c")),
                "value" to MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            )),
            // Return type: {} (unit - execution continues)
            MezzeType.RecordType(emptyMap())
        )

        // __native_continuation_get_collected: { delim: 'c } -> ['a]
        // Gets all collected values without clearing the collector.
        context["__native_continuation_get_collected"] = MezzeType.FunctionType(
            // Parameter type: { delim: 'c }
            MezzeType.RecordType(mapOf(
                "delim" to MezzeType.VarType(com.mezze.language.ast.TypeVar("c"))
            )),
            // Return type: ['a] (seq of collected values)
            MezzeType.SeqType(MezzeType.VarType(com.mezze.language.ast.TypeVar("a")))
        )

        // __native_continuation_take_collected: { delim: 'c } -> ['a]
        // Gets all collected values and clears the collector.
        context["__native_continuation_take_collected"] = MezzeType.FunctionType(
            // Parameter type: { delim: 'c }
            MezzeType.RecordType(mapOf(
                "delim" to MezzeType.VarType(com.mezze.language.ast.TypeVar("c"))
            )),
            // Return type: ['a] (seq of collected values)
            MezzeType.SeqType(MezzeType.VarType(com.mezze.language.ast.TypeVar("a")))
        )

        // __native_continuation_collected_count: { delim: 'c } -> Nat
        // Gets the count of collected values.
        context["__native_continuation_collected_count"] = MezzeType.FunctionType(
            // Parameter type: { delim: 'c }
            MezzeType.RecordType(mapOf(
                "delim" to MezzeType.VarType(com.mezze.language.ast.TypeVar("c"))
            )),
            // Return type: Nat
            MezzeType.NatType
        )

        // __native_continuation_run_suspendable: { thunk: {} -> 'a } -> | Completed {value: 'a}, Suspended {value: 'v, cont: {value: 'r} -> 'a} |
        // Runs a thunk and catches any suspension, returning either Completed or Suspended.
        // This is THE KEY PRIMITIVE for generators - it catches ContinuationSuspendedException
        // and returns the suspended value + continuation in a usable form.
        context["__native_continuation_run_suspendable"] = MezzeType.FunctionType(
            // Parameter type: { thunk: {} -> 'a }
            MezzeType.RecordType(mapOf(
                "thunk" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
                )
            )),
            // Return type: | Completed {value: 'a}, Suspended {value: 'v, cont: {value: 'r} -> 'a} |
            MezzeType.VariantType(mapOf(
                "Completed" to MezzeType.RecordType(mapOf(
                    "value" to MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
                )),
                "Suspended" to MezzeType.RecordType(mapOf(
                    "value" to MezzeType.VarType(com.mezze.language.ast.TypeVar("v")),
                    "cont" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf(
                            "value" to MezzeType.VarType(com.mezze.language.ast.TypeVar("r"))
                        )),
                        MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
                    )
                ))
            ))
        )

        // =====================================================================
        // Snapshot/Serialization primitives for workflow support
        // =====================================================================

        // __native_continuation_snapshot: { cont: {value: 'a} -> 'b } -> Bytes
        // Serializes a continuation to bytes for persistence.
        // The continuation must be a state machine with serializable captured values.
        // Returns the serialized bytes or throws if not serializable.
        context["__native_continuation_snapshot"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "cont" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf(
                        "value" to MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
                    )),
                    MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
                )
            )),
            MezzeType.BytesType
        )

        // __native_continuation_restore: { data: Bytes, functions: {} } -> {value: 'a} -> 'b
        // Restores a continuation from serialized bytes.
        // The functions parameter provides the registry of available functions.
        // Returns the restored continuation function.
        context["__native_continuation_restore"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "data" to MezzeType.BytesType,
                "functions" to MezzeType.RecordType(emptyMap())
            )),
            MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "value" to MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
                )),
                MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            )
        )

        // __native_continuation_is_serializable: { cont: {value: 'a} -> 'b } -> Bool
        // Checks if a continuation can be serialized.
        // Returns true if all captured values are serializable primitive types,
        // records with serializable fields, or sequences of serializable values.
        context["__native_continuation_is_serializable"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "cont" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf(
                        "value" to MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
                    )),
                    MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
                )
            )),
            MezzeType.BoolType
        )

        // =====================================================================
        // Internal state machine support (used by ContinuationTransformer)
        // =====================================================================

        // __state_machine_input__: 'a
        // Internal placeholder for state machine input value during transformation.
        // This is replaced by the actual runtime value when the state machine executes.
        // The polymorphic type allows it to match any pattern binding.
        context["__state_machine_input__"] = MezzeType.VarType(com.mezze.language.ast.TypeVar("input"))
    }
}
