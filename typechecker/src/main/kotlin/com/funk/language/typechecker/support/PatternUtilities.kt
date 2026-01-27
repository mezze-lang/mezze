package com.funk.language.typechecker.support

import com.funk.language.ast.*
import com.funk.language.typechecker.TypeCheckError
import com.funk.language.typechecker.ErrorType
import com.funk.language.typechecker.engines.IInferenceEngine
import com.funk.language.typechecker.engines.IResolutionEngine

/**
 * Utilities for pattern matching and pattern binding extraction.
 *
 * This module contains pure functions for working with patterns in match expressions,
 * let bindings, and function parameters.
 */
object PatternUtilities {

    /**
     * Extract variable bindings from a pattern.
     *
     * Given a pattern and the type of value being matched, returns a map of
     * variable names to their types.
     *
     * @param pattern The pattern to extract bindings from
     * @param valueType The type of the value being matched
     * @param inferenceEngine Engine for type inference (for field types)
     * @param resolutionEngine Engine for resolution (for variant types)
     * @return Map of variable names to their types
     */
    fun extractPatternBindings(
        pattern: Pattern,
        valueType: FunkType,
        inferenceEngine: IInferenceEngine,
        resolutionEngine: IResolutionEngine
    ): Map<String, FunkType> {
        return when (pattern) {
            is VariablePattern -> {
                mapOf(pattern.name to valueType)
            }
            is ConstructorPattern -> {
                val bindings = mutableMapOf<String, FunkType>()

                // If there's a record pattern, extract field bindings
                pattern.recordPattern?.let { recordPattern ->
                    // PRIORITY 1: Try to get the data type from the provided valueType first
                    // This ensures we use concrete types (e.g., Option Int) when available
                    // Handle both VariantType and TypeApplication
                    val underlyingVariant = getUnderlyingVariantType(valueType, resolutionEngine)
                    val constructorDataType = underlyingVariant?.constructors?.get(pattern.constructor)

                    // BUG FIX: Check if this is a unit variant (no payload) but pattern tries to destructure it
                    if (underlyingVariant != null &&
                        underlyingVariant.constructors.containsKey(pattern.constructor) &&
                        constructorDataType == null) {
                        // Constructor exists but has no payload - error!
                        throw TypeCheckError.unitVariantWithPayload(pattern.constructor, null)
                    }

                    if (constructorDataType is FunkType.RecordType) {
                        // Found the exact type from valueType! Use it for precise field types
                        bindings.putAll(extractRecordPatternBindings(recordPattern, constructorDataType, inferenceEngine, resolutionEngine))
                    } else {
                        // PRIORITY 2: Fallback - try to find the variant type by constructor name
                        // This handles cases where valueType is a fresh type variable (e.g., from array patterns)
                        val foundType = resolutionEngine.findVariantConstructorType(pattern.constructor)
                        val fallbackVariantType = if (foundType != null) {
                            getUnderlyingVariantType(foundType, resolutionEngine)
                        } else {
                            null
                        }
                        val fallbackDataType = fallbackVariantType?.constructors?.get(pattern.constructor)

                        // BUG FIX: Also check fallback for unit variant
                        if (fallbackVariantType != null &&
                            fallbackVariantType.constructors.containsKey(pattern.constructor) &&
                            fallbackDataType == null) {
                            // Constructor exists but has no payload - error!
                            throw TypeCheckError.unitVariantWithPayload(pattern.constructor, null)
                        }

                        if (fallbackDataType is FunkType.RecordType) {
                            bindings.putAll(extractRecordPatternBindings(recordPattern, fallbackDataType, inferenceEngine, resolutionEngine))
                        } else if (fallbackDataType != null) {
                            // Has a payload but not a record type - still try to process
                            for (entry in recordPattern.entries) {
                                if (entry.pattern == null) {
                                    bindings[entry.name] = inferenceEngine.inferFieldTypeFromName(entry.name)
                                } else {
                                    val fieldType = inferenceEngine.inferFieldTypeFromName(entry.name)
                                    bindings.putAll(
                                        extractPatternBindings(entry.pattern!!, fieldType, inferenceEngine, resolutionEngine)
                                    )
                                }
                            }
                        }
                        // If fallbackDataType is null and we didn't find the variant,
                        // skip inference - the type system doesn't have enough info
                    }
                }

                bindings
            }
            is RecordPattern -> {
                when (valueType) {
                    is FunkType.RecordType -> {
                        extractRecordPatternBindings(pattern, valueType, inferenceEngine, resolutionEngine)
                    }
                    else -> {
                        // If we don't know the exact type, infer from field names
                        val bindings = mutableMapOf<String, FunkType>()
                        for (entry in pattern.entries) {
                            if (entry.pattern == null) {
                                // Shorthand {x} - bind x to its field type
                                bindings[entry.name] = inferenceEngine.inferFieldTypeFromName(entry.name)
                            } else {
                                // Pattern {field = pattern} - extract bindings from the pattern
                                val fieldType = inferenceEngine.inferFieldTypeFromName(entry.name)
                                bindings.putAll(
                                    extractPatternBindings(entry.pattern!!, fieldType, inferenceEngine, resolutionEngine)
                                )
                            }
                        }
                        bindings
                    }
                }
            }
            is WildcardPattern -> {
                emptyMap() // Wildcard doesn't bind any variables
            }
            is LiteralPattern -> {
                emptyMap() // Literal patterns don't bind any variables
            }
            is ArrayPattern -> {
                // Array pattern: [el1, el2, ...rest]
                // Extract element type from array type
                val elementType = when (valueType) {
                    is FunkType.ArrayType -> valueType.elementType
                    is FunkType.ListType -> valueType.elementType
                    else -> {
                        // Unknown type - create a fresh type variable
                        FunkType.VarType(TypeVar.fresh())
                    }
                }

                val bindings = mutableMapOf<String, FunkType>()

                // Extract bindings from element patterns
                for (elementPattern in pattern.elements) {
                    bindings.putAll(
                        extractPatternBindings(elementPattern, elementType, inferenceEngine, resolutionEngine)
                    )
                }

                // Extract binding from rest pattern
                // Note: Anonymous rest patterns like [x, ...] have restPattern = null
                pattern.restPattern?.let { restName ->
                    // Rest pattern binds to the same array type
                    bindings[restName] = valueType
                }

                bindings
            }
        }
    }

    /**
     * Extract bindings from a record pattern.
     */
    private fun extractRecordPatternBindings(
        recordPattern: RecordPattern,
        recordType: FunkType.RecordType,
        inferenceEngine: IInferenceEngine,
        resolutionEngine: IResolutionEngine
    ): Map<String, FunkType> {
        val bindings = mutableMapOf<String, FunkType>()

        // Collect the field names mentioned in the pattern
        val patternFieldNames = recordPattern.entries.map { it.name }.toSet()

        // STRICT RECORD DESTRUCTURING: If pattern is not open (no ...), all fields must be covered
        if (!recordPattern.isOpen) {
            val recordFieldNames = recordType.fields.keys
            val uncoveredFields = recordFieldNames - patternFieldNames

            if (uncoveredFields.isNotEmpty()) {
                val fieldList = uncoveredFields.sorted().joinToString(", ")
                throw TypeCheckError(
                    "Non-exhaustive record pattern: missing field${if (uncoveredFields.size > 1) "s" else ""} $fieldList. " +
                    "Use { ${patternFieldNames.joinToString(", ")}, ... } to ignore extra fields.",
                    null as Expression?,
                    ErrorType.GENERAL,
                    mapOf("missingFields" to uncoveredFields.toList(), "patternFields" to patternFieldNames.toList())
                )
            }
        }

        for (entry in recordPattern.entries) {
            val fieldType = recordType.fields[entry.name]
                ?: throw TypeCheckError.missingField(entry.name, recordType, null)

            if (entry.pattern == null) {
                // Shorthand {x} - bind x to its field type
                bindings[entry.name] = fieldType
            } else {
                // Pattern {field = pattern} - extract bindings from the pattern
                // Pass the real resolutionEngine for nested constructor patterns that may need
                // TypeApplication expansion (e.g., Ok { val = Pair { first, second } })
                bindings.putAll(
                    extractPatternBindings(
                        entry.pattern!!,
                        fieldType,
                        inferenceEngine,
                        resolutionEngine
                    )
                )
            }
        }

        return bindings
    }

    /**
     * Get the underlying variant type from a type that might be a TypeApplication or SuspendedType.
     *
     * For example: Option Int -> VariantType with constructors {Some, None}
     *
     * This delegates to the ResolutionEngine which has access to the type declaration map.
     */
    private fun getUnderlyingVariantType(
        type: FunkType,
        resolutionEngine: IResolutionEngine
    ): FunkType.VariantType? {
        return resolutionEngine.getUnderlyingVariantType(type)
    }
}
