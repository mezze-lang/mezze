package com.funk.language.interpreter.stdlib.system

import com.funk.language.interpreter.*
import com.funk.language.interpreter.stdlib.effects.TimeImplementations
import com.funk.language.interpreter.stdlib.effects.EnvironmentImplementations

/**
 * Truffle runtime implementations for system effects (Time, Environment, Network)
 *
 * This file now delegates to the organized effects implementations:
 * - TimeImplementations: All time operations
 * - EnvironmentImplementations: All environment operations
 *
 * JSON operations remain here for now.
 */
object TruffleSystemImplementations {

    /**
     * Register all system effect implementations
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        // Delegate to effects implementations
        TimeImplementations.registerAll(context)
        EnvironmentImplementations.registerAll(context)

        // Keep JSON operations here for now
        registerJsonOperations(context)
    }

    /**
     * JSON operations (simplified implementations)
     */
    private fun registerJsonOperations(context: MutableMap<String, FunkValue>) {
        context["__native_json_parse"] = NativeFunkFunction("__native_json_parse") { args ->
            val record = args[0] as FunkRecord
            val jsonString = record.fields["json_string"] as? FunkString
                ?: throw NativeFunctionException("__native_json_parse", "expects 'json_string' as String")

            try {
                // Simplified JSON parsing - would need a proper JSON parser in production
                val trimmed = jsonString.value.trim()
                when {
                    trimmed == "null" -> FunkVariant("Ok", FunkRecord(mapOf("val" to FunkVariant("JsonNull", null))))
                    trimmed == "true" -> FunkVariant("Ok", FunkRecord(mapOf("val" to FunkVariant("JsonBool", FunkRecord(mapOf("value" to FunkBool(true)))))))
                    trimmed == "false" -> FunkVariant("Ok", FunkRecord(mapOf("val" to FunkVariant("JsonBool", FunkRecord(mapOf("value" to FunkBool(false)))))))
                    trimmed.startsWith("\"") && trimmed.endsWith("\"") -> {
                        val stringValue = trimmed.substring(1, trimmed.length - 1)
                        FunkVariant("Ok", FunkRecord(mapOf("val" to FunkVariant("JsonString", FunkRecord(mapOf("value" to FunkString(stringValue)))))))
                    }
                    trimmed.matches(Regex("-?\\d+(\\.\\d+)?")) -> {
                        val numberValue = trimmed.toDouble()
                        FunkVariant("Ok", FunkRecord(mapOf("val" to FunkVariant("JsonNumber", FunkRecord(mapOf("value" to FunkFloat(numberValue)))))))
                    }
                    else -> FunkVariant("Err", FunkRecord(mapOf("error" to FunkString("Unsupported JSON format"))))
                }
            } catch (e: Exception) {
                FunkVariant("Err", FunkRecord(mapOf("error" to FunkString("JSON parsing failed: ${e.message}"))))
            }
        }

        context["__native_json_stringify"] = NativeFunkFunction("__native_json_stringify") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["value"] as? FunkVariant
                ?: throw NativeFunctionException("__native_json_stringify", "expects 'value' as JsonValue")

            when (value.constructor) {
                "JsonNull" -> FunkString("null")
                "JsonBool" -> {
                    val boolRecord = value.value as FunkRecord
                    val boolValue = boolRecord.fields["value"] as FunkBool
                    FunkString(boolValue.value.toString())
                }
                "JsonNumber" -> {
                    val numberRecord = value.value as FunkRecord
                    val numberValue = numberRecord.fields["value"] as FunkFloat
                    FunkString(numberValue.value.toString())
                }
                "JsonString" -> {
                    val stringRecord = value.value as FunkRecord
                    val stringValue = stringRecord.fields["value"] as FunkString
                    FunkString("\"${stringValue.value}\"")
                }
                else -> FunkString("\"unsupported\"")
            }
        }

        context["__native_json_pretty_print"] = NativeFunkFunction("__native_json_pretty_print") { args ->
            val record = args[0] as FunkRecord
            val value = record.fields["value"] as? FunkVariant
                ?: throw NativeFunctionException("__native_json_pretty_print", "expects 'value' as JsonValue")
            val indent = record.fields["indent"] as? FunkInt
                ?: throw NativeFunctionException("__native_json_pretty_print", "expects 'indent' as Int")

            // Simplified pretty printing - just add some spacing
            val jsonString = context["__native_json_stringify"]!!.let { stringify ->
                (stringify as NativeFunkFunction).call(listOf(FunkRecord(mapOf("value" to value)))) as FunkString
            }

            FunkString(jsonString.value) // Would need proper indentation logic
        }

        context["__native_json_get_field"] = NativeFunkFunction("__native_json_get_field") { args ->
            val record = args[0] as FunkRecord
            val fields = record.fields["fields"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_json_get_field", "expects 'fields' as ArrayList")
            val field = record.fields["field"] as? FunkString
                ?: throw NativeFunctionException("__native_json_get_field", "expects 'field' as String")

            for (pair in fields.elements) {
                val tuple = pair as FunkTuple
                val key = tuple.elements[0] as FunkString
                val value = tuple.elements[1]
                if (key.value == field.value) {
                    return@NativeFunkFunction FunkVariant("Some", FunkRecord(mapOf("value" to value)))
                }
            }
            FunkVariant("None", null)
        }

        context["__native_json_get_index"] = NativeFunkFunction("__native_json_get_index") { args ->
            val record = args[0] as FunkRecord
            val elements = record.fields["elements"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_json_get_index", "expects 'elements' as ArrayList")
            val index = record.fields["index"] as? FunkInt
                ?: throw NativeFunctionException("__native_json_get_index", "expects 'index' as Int")

            if (index.value >= 0 && index.value < elements.size()) {
                val value = elements.get(index.value.toInt())
                FunkVariant("Some", FunkRecord(mapOf("value" to value)))
            } else {
                FunkVariant("None", null)
            }
        }
    }
}
