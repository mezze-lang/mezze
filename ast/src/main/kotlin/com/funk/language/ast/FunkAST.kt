package com.funk.language.ast

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Unified AST for Funk language that supports both untyped and typed phases
 * 
 * Flow:
 * 1. Parser produces untyped AST (inferredType = null)
 * 2. Typechecker enhances the same AST with type information (inferredType filled)
 * 3. Interpreter/Compiler uses the typed AST
 * 
 * This eliminates the need for separate parser AST and typechecker AST conversions.
 */

// ============================================================================
// TYPES
// ============================================================================

@Serializable
@JsonClassDiscriminator("typeKind")
sealed interface FunkType {
    // Primitive types
    @Serializable
    object IntType : FunkType
    
    @Serializable
    object FloatType : FunkType 
    
    @Serializable
    object StringType : FunkType
    
    @Serializable
    object BooleanType : FunkType
    
    // Type variables (for inference)
    @Serializable
    data class VarType(val variable: TypeVar) : FunkType
    
    // Record types
    @Serializable
    data class RecordType(
        val fields: Map<String, FunkType>,
        val rowVar: RowVar? = null // null = closed, non-null = open
    ) : FunkType {
        val isOpen: Boolean get() = rowVar != null
        val isClosed: Boolean get() = rowVar == null
    }
    
    // Variant types  
    @Serializable
    data class VariantType(
        val constructors: Map<String, FunkType?>, // null = no data, FunkType = constructor data
        val rowVar: RowVar? = null // null = closed, non-null = open
    ) : FunkType {
        val isOpen: Boolean get() = rowVar != null
        val isClosed: Boolean get() = rowVar == null
    }
    
    // Function types
    @Serializable
    data class FunctionType(
        val paramType: FunkType,
        val returnType: FunkType
    ) : FunkType
    
    // Higher-kinded types
    @Serializable
    data class TypeConstructor(
        val name: String,
        val args: List<FunkType>
    ) : FunkType
}

@Serializable
data class TypeVar(val name: String)

@Serializable
data class RowVar(val name: String)

// ============================================================================
// PROGRAM STRUCTURE
// ============================================================================

@Serializable
data class Program(
    val items: List<ProgramItem>
)

@Serializable
@JsonClassDiscriminator("itemKind")
sealed interface ProgramItem

// ============================================================================
// DECLARATIONS
// ============================================================================

@Serializable
data class LetBinding(
    val variableName: String,
    val typeAnnotation: TypeAnnotation? = null,  // From parser
    val inferredType: FunkType? = null,          // From typechecker
    val expression: Expression
) : ProgramItem

@Serializable
data class TypeDeclaration(
    val name: String,
    val typeParams: List<String> = emptyList(),
    val definition: TypeDefinition,
    val inferredType: FunkType? = null           // From typechecker
) : ProgramItem

@Serializable
data class TopLevelExpression(
    val expression: Expression
) : ProgramItem


// Module System
@Serializable
data class ModuleDeclaration(
    val name: String,
    val statements: List<ModuleStatement>
) : ProgramItem

@Serializable
@JsonClassDiscriminator("moduleStmtKind")
sealed interface ModuleStatement

@Serializable
data class ExposeDeclaration(
    val items: List<ExposeItem>
) : ModuleStatement

@Serializable
data class ExposeItem(
    val kind: ExposeKind?,
    val name: String
)

@Serializable
enum class ExposeKind { TYPE }

@Serializable
data class AccessDeclaration(
    val moduleName: String,
    val items: List<AccessItem>
) : ModuleStatement

@Serializable
data class AccessItem(
    val name: String,
    val alias: String?
)

@Serializable
data class ModDeclaration(
    val moduleName: String
) : ProgramItem, ModuleStatement

@Serializable
data class AccessModuleDeclaration(
    val modulePath: List<String>,
    val alias: String?,
    val isExposed: Boolean = false
) : ProgramItem, ModuleStatement

@Serializable
@JsonClassDiscriminator("typeDefKind")
sealed interface TypeDefinition

@Serializable
data class RecordDefinition(
    val fields: List<RecordFieldDef>
) : TypeDefinition

@Serializable
data class VariantDefinition(
    val constructors: List<VariantConstructorDef>
) : TypeDefinition

@Serializable
data class AliasDefinition(
    val aliasedType: TypeAnnotation
) : TypeDefinition

@Serializable
data class RecordFieldDef(
    val name: String,
    val type: TypeAnnotation
)

@Serializable
data class VariantConstructorDef(
    val name: String,
    val dataType: TypeAnnotation? = null
)

// ============================================================================
// EXPRESSIONS (Unified - can be untyped or typed)
// ============================================================================

@Serializable
@JsonClassDiscriminator("exprKind")
sealed interface Expression {
    val inferredType: FunkType? // null during parsing, filled by typechecker
}

// Literals
@Serializable
data class IntLiteral(
    val value: Long,
    override val inferredType: FunkType? = null
) : Expression

@Serializable
data class FloatLiteral(
    val value: Double,
    override val inferredType: FunkType? = null
) : Expression

@Serializable
data class StringLiteral(
    val value: String,
    override val inferredType: FunkType? = null
) : Expression

@Serializable
data class BooleanLiteral(
    val value: Boolean,
    override val inferredType: FunkType? = null
) : Expression

// Variables
@Serializable
data class Identifier(
    val name: String,
    override val inferredType: FunkType? = null
) : Expression

@Serializable
data class PlaceholderExpression(
    override val inferredType: FunkType? = null
) : Expression

// Operations
@Serializable
data class BinaryOperation(
    val left: Expression,
    val operator: String,
    val right: Expression,
    override val inferredType: FunkType? = null
) : Expression

// PipeExpression removed - pipe operator is now desugared during parsing

// Records
@Serializable
data class RecordLiteral(
    val entries: List<RecordEntry>,
    val restField: String? = null,
    override val inferredType: FunkType? = null
) : Expression

@Serializable
data class RecordEntry(
    val name: String,
    val value: Expression? = null  // null for shorthand {x} instead of {x = x}
)

@Serializable
data class MemberAccess(
    val receiver: Expression,
    val member: String,
    override val inferredType: FunkType? = null
) : Expression

@Serializable
data class RecordUpdate(
    val record: Expression,
    val updates: List<RecordEntry>,
    override val inferredType: FunkType? = null
) : Expression

// Variants
@Serializable
data class VariantConstruction(
    val constructor: String,
    val value: Expression? = null,
    override val inferredType: FunkType? = null
) : Expression

// Functions
@Serializable
data class LambdaExpression(
    val parameters: LambdaParameters,
    val body: Expression,
    override val inferredType: FunkType? = null
) : Expression

@Serializable
data class PartiallyAppliedFunction(
    val originalFunction: Expression,
    val providedArguments: RecordLiteral,
    val remainingParameters: Map<String, FunkType>,
    override val inferredType: FunkType? = null
) : Expression

@Serializable
@JsonClassDiscriminator("paramKind")
sealed interface LambdaParameters

@Serializable
data class TypedParameters(
    val recordType: RecordTypeAnnotation
) : LambdaParameters

@Serializable
data class UntypedParameters(
    val names: List<String>
) : LambdaParameters

@Serializable
data class FunctionCall(
    val function: Expression,
    val arguments: Expression,
    override val inferredType: FunkType? = null
) : Expression

// Control flow
@Serializable
data class IfExpression(
    val condition: Expression,
    val thenBranch: Expression,
    val elseBranch: Expression,
    override val inferredType: FunkType? = null
) : Expression

@Serializable
data class MatchExpression(
    val expression: Expression,
    val cases: List<MatchCase>,
    override val inferredType: FunkType? = null
) : Expression

@Serializable
data class MatchCase(
    val pattern: Pattern,
    val expression: Expression
)

// Blocks
@Serializable
data class BlockExpression(
    val statements: List<Statement>,
    val result: Expression,
    override val inferredType: FunkType? = null
) : Expression

@Serializable
data class DoExpression(
    val statements: List<Statement>,
    val result: Expression,
    override val inferredType: FunkType? = null
) : Expression

// ============================================================================
// PATTERNS
// ============================================================================

@Serializable
@JsonClassDiscriminator("patternKind")
sealed interface Pattern

@Serializable
data class WildcardPattern(
    val dummy: Unit = Unit
) : Pattern

@Serializable
data class VariablePattern(
    val name: String
) : Pattern

@Serializable
data class ConstructorPattern(
    val constructor: String,
    val recordPattern: RecordPattern? = null
) : Pattern

@Serializable
data class RecordPattern(
    val entries: List<RecordPatternEntry>
) : Pattern

@Serializable
data class RecordPatternEntry(
    val name: String,
    val pattern: Pattern? = null  // null for shorthand {x} instead of {x = x}
)

// ============================================================================
// STATEMENTS
// ============================================================================

@Serializable
@JsonClassDiscriminator("stmtKind")
sealed interface Statement

@Serializable
data class LetStatement(
    val variableName: String,
    val typeAnnotation: TypeAnnotation? = null,
    val expression: Expression
) : Statement

@Serializable
data class ExpressionStatement(
    val expression: Expression
) : Statement

// ============================================================================
// TYPE ANNOTATIONS (Used by parser, converted to FunkType by typechecker)
// ============================================================================

@Serializable
@JsonClassDiscriminator("typeAnnotationKind")
sealed interface TypeAnnotation

@Serializable
data class IdentifierTypeAnnotation(
    val name: String
) : TypeAnnotation

@Serializable
data class RecordTypeAnnotation(
    val entries: List<RecordTypeEntry>,
    val restField: String? = null
) : TypeAnnotation

@Serializable
data class VariantTypeAnnotation(
    val constructors: List<VariantTypeConstructor>
) : TypeAnnotation

@Serializable
data class FunctionTypeAnnotation(
    val paramType: TypeAnnotation,
    val returnType: TypeAnnotation
) : TypeAnnotation

@Serializable
data class RecordTypeEntry(
    val name: String,
    val type: TypeAnnotation
)

@Serializable
data class VariantTypeConstructor(
    val name: String,
    val dataType: TypeAnnotation? = null
)

// ============================================================================
// UTILITY TYPES
// ============================================================================

@Serializable
data class ContentHash(val hash: String)

@Serializable
data class SourceLocation(
    val line: Int,
    val column: Int,
    val file: String? = null
)