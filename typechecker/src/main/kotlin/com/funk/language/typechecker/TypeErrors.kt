package com.funk.language.typechecker

/**
 * Structured type errors for comprehensive error reporting
 */

sealed class TypeErrorKind {
    data class UnificationError(
        val expected: FunkType,
        val actual: FunkType,
        val context: String? = null
    ) : TypeErrorKind()
    
    data class UnboundVariable(
        val name: String,
        val availableNames: List<String> = emptyList()
    ) : TypeErrorKind()
    
    data class FieldNotFound(
        val fieldName: String,
        val recordType: FunkType,
        val availableFields: List<String> = emptyList()
    ) : TypeErrorKind()
    
    data class ConstructorNotFound(
        val constructorName: String,
        val variantType: FunkType,
        val availableConstructors: List<String> = emptyList()
    ) : TypeErrorKind()
    
    data class ArityMismatch(
        val expected: Int,
        val actual: Int,
        val context: String? = null
    ) : TypeErrorKind()
    
    data class TypeMismatch(
        val expected: FunkType,
        val actual: FunkType,
        val context: String? = null
    ) : TypeErrorKind()
    
    data class InvalidOperation(
        val operation: String,
        val operandType: FunkType,
        val context: String? = null
    ) : TypeErrorKind()
    
    data class CircularType(
        val typeVar: String,
        val type: FunkType
    ) : TypeErrorKind()
    
    data class ModuleNotFound(
        val moduleName: String,
        val availableModules: List<String> = emptyList()
    ) : TypeErrorKind()
    
    data class ExportNotFound(
        val exportName: String,
        val moduleName: String,
        val availableExports: List<String> = emptyList()
    ) : TypeErrorKind()
    
    data class DuplicateDefinition(
        val name: String,
        val context: String? = null
    ) : TypeErrorKind()
    
    data class InvalidPattern(
        val pattern: String,
        val expectedType: FunkType,
        val context: String? = null
    ) : TypeErrorKind()
    
    data class InexhaustivePattern(
        val missingCases: List<String>,
        val context: String? = null
    ) : TypeErrorKind()
    
    data class UnreachablePattern(
        val pattern: String,
        val context: String? = null
    ) : TypeErrorKind()
}

/**
 * Represents a type error with location information
 */
data class StructuredTypeError(
    val kind: TypeErrorKind,
    val location: SourceLocation? = null,
    override val message: String? = null
) : Exception(message ?: kind.toString())

/**
 * Source location for error reporting
 */
data class SourceLocation(
    val line: Int,
    val column: Int,
    val file: String? = null
)