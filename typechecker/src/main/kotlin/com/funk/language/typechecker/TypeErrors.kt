package com.funk.language.typechecker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Structured, serializable type errors for comprehensive error reporting
 */

@Serializable
sealed class TypeErrorKind {
    @Serializable
    data class UnificationError(
        val expected: SerializableType,
        val actual: SerializableType,
        val context: String? = null
    ) : TypeErrorKind()
    
    @Serializable
    data class UnboundVariable(
        val name: String,
        val availableNames: List<String> = emptyList()
    ) : TypeErrorKind()
    
    @Serializable
    data class FieldNotFound(
        val recordType: SerializableType,
        val field: String,
        val availableFields: List<String> = emptyList()
    ) : TypeErrorKind()
    
    @Serializable
    data class ConstructorNotFound(
        val variantType: SerializableType,
        val constructor: String,
        val availableConstructors: List<String> = emptyList()
    ) : TypeErrorKind()
    
    @Serializable
    data class EffectNotFound(
        val effect: String,
        val availableEffects: List<String> = emptyList()
    ) : TypeErrorKind()
    
    @Serializable
    data class OccursCheck(
        val variable: String,
        val type: SerializableType
    ) : TypeErrorKind()
    
    @Serializable
    data class RowConstraintViolation(
        val constraint: String,
        val type: SerializableType,
        val reason: String
    ) : TypeErrorKind()
    
    @Serializable
    data class PartialApplicationError(
        val functionType: SerializableType,
        val providedArgs: SerializableType,
        val missingFields: List<String> = emptyList(),
        val extraFields: List<String> = emptyList()
    ) : TypeErrorKind()
    
    @Serializable
    data class EffectMismatch(
        val expected: List<String>,
        val actual: List<String>,
        val missing: List<String> = emptyList(),
        val extra: List<String> = emptyList()
    ) : TypeErrorKind()
    
    @Serializable
    data class ModuleError(
        val module: String,
        val kind: ModuleErrorKind
    ) : TypeErrorKind()
    
    @Serializable
    data class ArityMismatch(
        val expected: Int,
        val actual: Int,
        val context: String
    ) : TypeErrorKind()
    
    @Serializable
    data class KindError(
        val expected: String,
        val actual: String,
        val type: SerializableType
    ) : TypeErrorKind()
    
    @Serializable
    data class ExhaustivenessError(
        val variantType: SerializableType,
        val missingConstructors: List<String>,
        val matchedConstructors: List<String> = emptyList()
    ) : TypeErrorKind()
    
    @Serializable
    data class UnreachablePatternError(
        val pattern: String,
        val reason: String
    ) : TypeErrorKind()
}

@Serializable
enum class ModuleErrorKind {
    NOT_FOUND,
    CIRCULAR_DEPENDENCY,
    PRIVATE_ACCESS,
    DUPLICATE_EXPORT,
    MISSING_IMPORT
}

@Serializable
data class SourceLocation(
    val file: String,
    val line: Int,
    val column: Int,
    val length: Int = 1
)

@Serializable
data class ErrorContext(
    val file: String? = null,
    val module: String? = null,
    val function: String? = null,
    val expression: String? = null,
    val expressionType: String? = null, // e.g., "match expression", "lambda", "binary operation"
    val variableName: String? = null,
    val additionalInfo: Map<String, String> = emptyMap()
)

@Serializable
data class TypeErrorInfo(
    val kind: TypeErrorKind,
    val location: SourceLocation? = null,
    val message: String,
    val hint: String? = null,
    val relatedLocations: List<Pair<SourceLocation, String>> = emptyList(),
    val context: ErrorContext? = null // Added as optional parameter for backward compatibility
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)
    
    companion object {
        fun fromJson(json: String): TypeErrorInfo = Json.decodeFromString(serializer(), json)
    }
}

/**
 * Serializable representation of types for error reporting
 */
@Serializable
sealed class SerializableType {
    @Serializable
    object IntType : SerializableType()
    
    @Serializable
    object FloatType : SerializableType()
    
    @Serializable
    object StringType : SerializableType()
    
    @Serializable
    object BooleanType : SerializableType()
    
    @Serializable
    data class VarType(val name: String) : SerializableType()
    
    @Serializable
    data class RecordType(
        val fields: Map<String, SerializableType>,
        val isOpen: Boolean = false
    ) : SerializableType()
    
    @Serializable
    data class VariantType(
        val constructors: Map<String, SerializableType?>,
        val isOpen: Boolean = false
    ) : SerializableType()
    
    @Serializable
    data class FunctionType(
        val paramType: SerializableType,
        val returnType: SerializableType
    ) : SerializableType()
    
    @Serializable
    data class TypeConstructor(
        val name: String,
        val args: List<SerializableType>
    ) : SerializableType()
    
    @Serializable
    data class TypeAlias(
        val name: String,
        val type: SerializableType
    ) : SerializableType()
}

/**
 * Convert FunkType to SerializableType for error reporting
 */
fun FunkType.toSerializable(): SerializableType = when (this) {
    is FunkType.IntType -> SerializableType.IntType
    is FunkType.FloatType -> SerializableType.FloatType
    is FunkType.StringType -> SerializableType.StringType
    is FunkType.BooleanType -> SerializableType.BooleanType
    is FunkType.VarType -> SerializableType.VarType(this.variable.name)
    is FunkType.RecordType -> SerializableType.RecordType(
        this.fields.mapValues { it.value.toSerializable() },
        this.isOpen
    )
    is FunkType.VariantType -> SerializableType.VariantType(
        this.constructors.mapValues { it.value?.toSerializable() },
        this.isOpen
    )
    is FunkType.FunctionType -> SerializableType.FunctionType(
        this.paramType.toSerializable(),
        this.returnType.toSerializable()
    )
    is FunkType.TypeConstructor -> SerializableType.TypeConstructor(
        this.name,
        this.args.map { it.toSerializable() }
    )
    is FunkType.TypeAlias -> SerializableType.TypeAlias(
        this.name,
        this.type.toSerializable()
    )
}

/**
 * Enhanced TypeError with structured error information
 */
class StructuredTypeError(
    val errorInfo: TypeErrorInfo
) : Exception(errorInfo.message) {
    
    companion object {
        fun unificationError(
            expected: FunkType,
            actual: FunkType,
            location: SourceLocation? = null,
            context: ErrorContext? = null
        ): StructuredTypeError {
            val message = buildString {
                append("Cannot unify types: expected ${expected.prettyPrint()}, but got ${actual.prettyPrint()}")
                context?.let { ctx ->
                    when {
                        ctx.function != null && ctx.expressionType != null -> 
                            append(" in ${ctx.expressionType} within function '${ctx.function}'")
                        ctx.function != null -> 
                            append(" in function '${ctx.function}'")
                        ctx.expressionType != null -> 
                            append(" in ${ctx.expressionType}")
                    }
                }
            }
            
            val hint = when {
                expected is FunkType.RecordType && actual is FunkType.RecordType -> {
                    val missingFields = expected.fields.keys - actual.fields.keys
                    val extraFields = actual.fields.keys - expected.fields.keys
                    when {
                        missingFields.isNotEmpty() -> "Missing fields: ${missingFields.joinToString()}"
                        extraFields.isNotEmpty() -> "Extra fields: ${extraFields.joinToString()}"
                        else -> "Field types don't match"
                    }
                }
                expected is FunkType.FunctionType && actual is FunkType.FunctionType -> {
                    "Function signatures don't match - check parameter and return types"
                }
                else -> null
            }
            
            return StructuredTypeError(
                TypeErrorInfo(
                    TypeErrorKind.UnificationError(
                        expected.toSerializable(),
                        actual.toSerializable(),
                        context?.expression
                    ),
                    location,
                    message,
                    hint,
                    emptyList(),
                    context
                )
            )
        }
        
        // Backward compatible method with old signature
        fun unboundVariable(
            name: String,
            availableNames: List<String> = emptyList(),
            location: SourceLocation? = null
        ): StructuredTypeError = unboundVariable(name, availableNames, location, null)
        
        fun unboundVariable(
            name: String,
            availableNames: List<String> = emptyList(),
            location: SourceLocation? = null,
            context: ErrorContext? = null
        ): StructuredTypeError {
            val message = buildString {
                append("Unbound variable: $name")
                context?.let { ctx ->
                    when {
                        ctx.function != null && ctx.expressionType != null -> 
                            append(" in ${ctx.expressionType} within function '${ctx.function}'")
                        ctx.function != null -> 
                            append(" in function '${ctx.function}'")
                        ctx.expressionType != null -> 
                            append(" in ${ctx.expressionType}")
                    }
                }
            }
            
            val hint = if (availableNames.isNotEmpty()) {
                val suggestions = availableNames.filter { 
                    it.lowercase().contains(name.lowercase()) || 
                    levenshteinDistance(it, name) <= 2 
                }
                if (suggestions.isNotEmpty()) {
                    "Did you mean: ${suggestions.joinToString(" or ")}?"
                } else {
                    "Available variables: ${availableNames.take(5).joinToString()}"
                }
            } else null
            
            return StructuredTypeError(
                TypeErrorInfo(
                    TypeErrorKind.UnboundVariable(name, availableNames),
                    location,
                    message,
                    hint,
                    emptyList(),
                    context
                )
            )
        }
        
        fun fieldNotFound(
            recordType: FunkType,
            field: String,
            location: SourceLocation? = null,
            context: ErrorContext? = null
        ): StructuredTypeError {
            val availableFields = when (recordType) {
                is FunkType.RecordType -> recordType.fields.keys.toList()
                else -> emptyList()
            }
            
            val message = buildString {
                append("Field '$field' not found in record type")
                context?.let { ctx ->
                    when {
                        ctx.function != null && ctx.expressionType != null -> 
                            append(" in ${ctx.expressionType} within function '${ctx.function}'")
                        ctx.function != null -> 
                            append(" in function '${ctx.function}'")
                        ctx.expressionType != null -> 
                            append(" in ${ctx.expressionType}")
                    }
                }
            }
            
            val hint = if (availableFields.isNotEmpty()) {
                val suggestions = availableFields.filter { 
                    it.lowercase().contains(field.lowercase()) || 
                    levenshteinDistance(it, field) <= 2 
                }
                if (suggestions.isNotEmpty()) {
                    "Did you mean: ${suggestions.joinToString(" or ")}?"
                } else {
                    "Available fields: ${availableFields.joinToString()}"
                }
            } else null
            
            return StructuredTypeError(
                TypeErrorInfo(
                    TypeErrorKind.FieldNotFound(
                        recordType.toSerializable(),
                        field,
                        availableFields
                    ),
                    location,
                    message,
                    hint,
                    emptyList(),
                    context
                )
            )
        }
        
        fun partialApplicationError(
            functionType: FunkType.FunctionType,
            providedArgs: FunkType,
            location: SourceLocation? = null,
            context: ErrorContext? = null
        ): StructuredTypeError {
            val (missingFields, extraFields) = when {
                functionType.paramType is FunkType.RecordType && providedArgs is FunkType.RecordType -> {
                    val missing = functionType.paramType.fields.keys - providedArgs.fields.keys
                    val extra = providedArgs.fields.keys - functionType.paramType.fields.keys
                    missing.toList() to extra.toList()
                }
                else -> emptyList<String>() to emptyList<String>()
            }
            
            val message = buildString {
                append("Partial application error")
                context?.let { ctx ->
                    when {
                        ctx.function != null && ctx.expressionType != null -> 
                            append(" in ${ctx.expressionType} within function '${ctx.function}'")
                        ctx.function != null -> 
                            append(" in function '${ctx.function}'")
                        ctx.expressionType != null -> 
                            append(" in ${ctx.expressionType}")
                    }
                }
            }
            
            val hint = when {
                missingFields.isNotEmpty() && extraFields.isEmpty() -> 
                    "Missing required fields: ${missingFields.joinToString()}"
                missingFields.isEmpty() && extraFields.isNotEmpty() -> 
                    "Unexpected fields: ${extraFields.joinToString()}"
                missingFields.isNotEmpty() && extraFields.isNotEmpty() -> 
                    "Missing: ${missingFields.joinToString()}, Extra: ${extraFields.joinToString()}"
                else -> "Argument structure doesn't match function parameters"
            }
            
            return StructuredTypeError(
                TypeErrorInfo(
                    TypeErrorKind.PartialApplicationError(
                        functionType.toSerializable(),
                        providedArgs.toSerializable(),
                        missingFields,
                        extraFields
                    ),
                    location,
                    message,
                    hint,
                    emptyList(),
                    context
                )
            )
        }
        
        fun exhaustivenessError(
            variantType: FunkType,
            missingConstructors: List<String>,
            matchedConstructors: List<String> = emptyList(),
            location: SourceLocation? = null,
            context: ErrorContext? = null
        ): StructuredTypeError {
            val message = buildString {
                append("Match expression is not exhaustive. Missing cases: ${missingConstructors.joinToString()}")
                context?.let { ctx ->
                    when {
                        ctx.function != null && ctx.expressionType != null -> 
                            append(" in ${ctx.expressionType} within function '${ctx.function}'")
                        ctx.function != null -> 
                            append(" in function '${ctx.function}'")
                        ctx.expressionType != null -> 
                            append(" in ${ctx.expressionType}")
                    }
                }
            }
            
            val hint = "Add cases for the missing constructors or use a wildcard pattern (_)"
            
            return StructuredTypeError(
                TypeErrorInfo(
                    TypeErrorKind.ExhaustivenessError(
                        variantType.toSerializable(),
                        missingConstructors,
                        matchedConstructors
                    ),
                    location,
                    message,
                    hint,
                    emptyList(),
                    context
                )
            )
        }
        
        fun unreachablePatternError(
            pattern: String,
            reason: String = "All constructors are already covered",
            location: SourceLocation? = null,
            context: ErrorContext? = null
        ): StructuredTypeError {
            val message = buildString {
                append("Unreachable pattern: $pattern. $reason")
                context?.let { ctx ->
                    when {
                        ctx.function != null && ctx.expressionType != null -> 
                            append(" in ${ctx.expressionType} within function '${ctx.function}'")
                        ctx.function != null -> 
                            append(" in function '${ctx.function}'")
                        ctx.expressionType != null -> 
                            append(" in ${ctx.expressionType}")
                    }
                }
            }
            
            val hint = "Remove the unreachable pattern"
            
            return StructuredTypeError(
                TypeErrorInfo(
                    TypeErrorKind.UnreachablePatternError(
                        pattern,
                        reason
                    ),
                    location,
                    message,
                    hint,
                    emptyList(),
                    context
                )
            )
        }
    }
}

/**
 * Utility functions
 */
private fun levenshteinDistance(s1: String, s2: String): Int {
    val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
    
    for (i in 0..s1.length) dp[i][0] = i
    for (j in 0..s2.length) dp[0][j] = j
    
    for (i in 1..s1.length) {
        for (j in 1..s2.length) {
            dp[i][j] = minOf(
                dp[i-1][j] + 1,
                dp[i][j-1] + 1,
                dp[i-1][j-1] + if (s1[i-1] == s2[j-1]) 0 else 1
            )
        }
    }
    
    return dp[s1.length][s2.length]
}

/**
 * Pretty printing for types
 */
fun FunkType.prettyPrint(): String = when (this) {
    is FunkType.IntType -> "Int"
    is FunkType.FloatType -> "Float"
    is FunkType.StringType -> "String"
    is FunkType.BooleanType -> "Boolean"
    is FunkType.VarType -> this.variable.name
    is FunkType.RecordType -> {
        val fields = this.fields.entries.joinToString(", ") { "${it.key}: ${it.value.prettyPrint()}" }
        val suffix = if (this.isOpen) ", ..." else ""
        "{ $fields$suffix }"
    }
    is FunkType.VariantType -> {
        val constructors = this.constructors.entries.joinToString(", ") { (name, type) ->
            when (type) {
                null -> name
                is FunkType.RecordType -> {
                    // For record types, format as "Red { val: Int }" without extra braces
                    val fields = type.fields.entries.joinToString(", ") { "${it.key}: ${it.value.prettyPrint()}" }
                    val suffix = if (type.isOpen) ", ..." else ""
                    "$name { $fields$suffix }"
                }
                else -> "$name(${type.prettyPrint()})" // Non-record types use parentheses
            }
        }
        val suffix = if (this.isOpen) ", ..." else ""
        "| $constructors$suffix |"
    }
    is FunkType.FunctionType -> {
        "${this.paramType.prettyPrint()} -> ${this.returnType.prettyPrint()}"
    }
    is FunkType.TypeConstructor -> {
        if (this.args.isEmpty()) this.name
        else "${this.name}<${this.args.joinToString { it.prettyPrint() }}>"
    }
    is FunkType.TypeAlias -> this.name
}
