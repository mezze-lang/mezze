package com.funk.language.interpreter.stdlib.effects

import com.funk.language.interpreter.*

/**
 * Truffle-optimized implementations for Inspect ability operations.
 *
 * The Inspect ability provides a way to convert any Funk value to a human-readable string representation.
 * This is similar to toString() in other languages, but implemented as a universal ability that works
 * with all types in the Funk type system.
 *
 * Operations:
 * - to_str: Convert any value to its string representation
 *
 * The Inspect ability is marked as universally available in the type system (see ConstraintSolver.kt),
 * meaning it can be called on any type without explicit constraint requirements.
 */
object InspectImplementations {

    /**
     * Register all Inspect ability implementations
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        // Register the native implementation
        context["__native_inspect_to_str"] = NativeFunkFunction("__native_inspect_to_str") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["val"] ?: throw FunkException("__native_inspect_to_str expects 'val' field")
            FunkString(valueToString(value))
        }

        // Register Inspect as a globally available ability (like a built-in effect)
        // This makes Inspect.to_str available without needing to import or use performers
        val inspectAbility = FunkRecord(mapOf(
            "to_str" to NativeFunkFunction("Inspect.to_str") { args ->
                val record = args[0] as FunkRecord
                val value = record.fields["val"] ?: throw FunkException("Inspect.to_str expects 'val' field")
                FunkString(valueToString(value))
            }
        ))
        context["Inspect"] = inspectAbility
    }

    /**
     * Convert a FunkValue to a human-readable string representation
     */
    private fun valueToString(value: FunkValue): String {
        return when (value) {
            // Primitive types
            is FunkInt -> value.value.toString()
            is FunkFloat -> value.value.toString()
            is FunkString -> "\"${value.value}\""  // Include quotes for strings
            is FunkBool -> value.value.toString()
            is FunkNat -> "${value.value}n"  // Add 'n' suffix for natural numbers
            is FunkFrac -> "${value.numerator}/${value.denominator}"

            // Unit/Null
            is FunkUnit -> "()"
            FunkNull -> "null"

            // Records - show field names and values
            is FunkRecord -> {
                if (value.fields.isEmpty()) {
                    "{}"
                } else {
                    val fieldStrings = value.fields.map { (key, fieldValue) ->
                        "$key = ${valueToString(fieldValue)}"
                    }
                    "{ ${fieldStrings.joinToString(", ")} }"
                }
            }

            // Variants - show constructor and value
            is FunkVariant -> {
                if (value.value == null) {
                    value.constructor
                } else {
                    "${value.constructor} ${valueToString(value.value)}"
                }
            }

            // Lists
            is FunkList -> {
                val elementStrings = value.elements.map { valueToString(it) }
                "[${elementStrings.joinToString(", ")}]"
            }

            // Non-empty lists
            is FunkNonEmptyList -> {
                val elementStrings = value.elements.map { valueToString(it) }
                "NonEmptyList(${elementStrings.joinToString(", ")})"
            }

            // Maps
            is FunkMap -> {
                if (value.entries.isEmpty()) {
                    "{}"
                } else {
                    val entryStrings = value.entries.map { (k, v) ->
                        "${valueToString(k)} = ${valueToString(v)}"
                    }
                    "{ ${entryStrings.joinToString(", ")} }"
                }
            }

            // Sets
            is FunkSet -> {
                if (value.elements.isEmpty()) {
                    "{}"
                } else {
                    val elementStrings = value.elements.map { valueToString(it) }
                    "{${elementStrings.joinToString(", ")}}"
                }
            }

            // Tuples
            is FunkTuple -> {
                val elementStrings = value.elements.map { valueToString(it) }
                "(${elementStrings.joinToString(", ")})"
            }

            // Functions - just show that it's a function
            is FunkTruffleFunction -> value.name?.let { "<function:$it>" } ?: "<function>"
            is NativeFunkFunction -> "<native-function:${value.name}>"

            // Effect-related types
            is FunkSuspended -> {
                val effectList = value.unhandledEffects.sorted().joinToString(", ")
                "<suspended <$effectList>>"
            }
            is FunkThunk -> "<thunk>"
            is FunkContinuation -> "<continuation@${value.capturePoint}>"
            is FunkContinuationSuspended -> "<suspended-continuation>"

            // Concurrency types
            is FunkPromise -> {
                if (value.isDone()) {
                    try {
                        val result = value.future.getNow(FunkNull)
                        "<promise:resolved(${valueToString(result)})>"
                    } catch (e: Exception) {
                        "<promise:rejected(${e.message})>"
                    }
                } else {
                    "<promise:pending>"
                }
            }
            is FunkThread -> "<thread:${value.threadId}>"
            is FunkCont -> "<cont:${value.delimiterId}>"

            // Fallback for any other types
            else -> value.getDisplayString()
        }
    }
}
