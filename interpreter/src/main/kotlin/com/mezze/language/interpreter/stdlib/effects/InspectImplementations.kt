package com.mezze.language.interpreter.stdlib.effects

import com.mezze.language.interpreter.*

/**
 * Truffle-optimized implementations for Inspect ability operations.
 *
 * The Inspect ability provides a way to convert any Mezze value to a human-readable string representation.
 * This is similar to toString() in other languages, but implemented as a universal ability that works
 * with all types in the Mezze type system.
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
     *
     * CRITICAL: Inspect is an ABILITY, not an effect!
     * Abilities are automatically available for all types without requiring imports.
     * This is different from effects which require handlers via `perform`.
     */
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        // Register the native implementation
        context["__native_inspect_to_str"] = NativeMezzeFunction("__native_inspect_to_str") { args ->
            val record = args[0] as MezzeRecord
            val value = record.fields["val"] ?: throw MezzeException("__native_inspect_to_str expects 'val' field")
            MezzeString(valueToString(value))
        }

        // Register Inspect as a globally available ability (like a built-in effect)
        // This makes Inspect.to_str available without needing to import or use performers
        val inspectAbility = MezzeRecord(mapOf(
            "to_str" to NativeMezzeFunction("Inspect.to_str") { args ->
                val record = args[0] as MezzeRecord
                val value = record.fields["val"] ?: throw MezzeException("Inspect.to_str expects 'val' field")
                MezzeString(valueToString(value))
            }
        ))
        context["Inspect"] = inspectAbility
    }

    /**
     * Convert a MezzeValue to a human-readable string representation
     */
    private fun valueToString(value: MezzeValue): String {
        return when (value) {
            // Primitive types
            is MezzeInt -> value.value.toString()
            is MezzeFloat -> value.value.toString()
            is MezzeString -> "\"${value.value}\""  // Include quotes for strings
            is MezzeBool -> value.value.toString()
            is MezzeNat -> "${value.value}n"  // Add 'n' suffix for natural numbers
            is MezzeFrac -> "${value.numerator}/${value.denominator}"
            is MezzeBytes -> value.getDisplayString()  // Use the built-in display representation
            is MezzeChar -> "'${value.value}'"

            // Unit/Null
            is MezzeUnit -> "()"
            MezzeNull -> "null"

            // Records - show field names and values
            is MezzeRecord -> {
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
            is MezzeVariant -> {
                if (value.value == null) {
                    value.constructor
                } else {
                    "${value.constructor} ${valueToString(value.value)}"
                }
            }

            // Lists
            is MezzeList -> {
                val elementStrings = value.toKotlinList().map { valueToString(it) }
                "[${elementStrings.joinToString(", ")}]"
            }

            // Non-empty lists
            is MezzeNonEmptyList -> {
                val elementStrings = value.elements.map { valueToString(it) }
                "NonEmptyList(${elementStrings.joinToString(", ")})"
            }

            // Maps
            is MezzeMap -> {
                if (value.isEmpty()) {
                    "{}"
                } else {
                    val entryStrings = value.toKotlinMap().map { (k, v) ->
                        "${valueToString(k)} = ${valueToString(v)}"
                    }
                    "{ ${entryStrings.joinToString(", ")} }"
                }
            }

            // Sets
            is MezzeSet -> {
                if (value.isEmpty()) {
                    "{}"
                } else {
                    val elementStrings = value.toList().map { valueToString(it) }
                    "{${elementStrings.joinToString(", ")}}"
                }
            }

            // Arrays (ArrayList)
            is MezzeArrayList -> {
                val elementStrings = value.toList().map { valueToString(it) }
                "[|${elementStrings.joinToString(", ")}|]"
            }

            // Sorted Sets
            is MezzeSortedSet -> {
                if (value.isEmpty()) {
                    "SortedSet{}"
                } else {
                    val elementStrings = value.toList().map { valueToString(it) }
                    "SortedSet{${elementStrings.joinToString(", ")}}"
                }
            }

            // Sorted Maps
            is MezzeSortedMap -> {
                if (value.isEmpty()) {
                    "SortedMap{}"
                } else {
                    val entryStrings = value.toKotlinMap().map { (k, v) ->
                        "${valueToString(k)} = ${valueToString(v)}"
                    }
                    "SortedMap{ ${entryStrings.joinToString(", ")} }"
                }
            }

            // Deques
            is MezzeDeque -> {
                val elementStrings = value.toList().map { valueToString(it) }
                "Deque[${elementStrings.joinToString(", ")}]"
            }

            // Tuples
            is MezzeTuple -> {
                val elementStrings = value.elements.map { valueToString(it) }
                "(${elementStrings.joinToString(", ")})"
            }

            // Functions - just show that it's a function
            is MezzeTruffleFunction -> value.name?.let { "<function:$it>" } ?: "<function>"
            is NativeMezzeFunction -> "<native-function:${value.name}>"

            // Effect-related types
            is MezzeSuspended -> {
                val effectList = value.unhandledEffects.sorted().joinToString(", ")
                "<suspended <$effectList>>"
            }
            is MezzeThunk -> "<thunk>"
            is MezzeContinuation -> "<continuation@${value.capturePoint}>"
            is MezzeContinuationSuspended -> "<suspended-continuation>"

            // Concurrency types
            is MezzePromise -> {
                if (value.isDone()) {
                    try {
                        val result = value.future.getNow(MezzeNull)
                        "<promise:resolved(${valueToString(result)})>"
                    } catch (e: Exception) {
                        "<promise:rejected(${e.message})>"
                    }
                } else {
                    "<promise:pending>"
                }
            }
            is MezzeThread -> "<thread:${value.threadId}>"
            is MezzeCont -> "<cont:${value.delimiterId}>"

            // Fallback for any other types
            else -> value.getDisplayString()
        }
    }
}
