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
        val returnType: FunkType,
        val effects: EffectRow? = null  // null = pure function, non-null = effectful function
    ) : FunkType
    
    // Effect rows
    @Serializable
    data class EffectRow(
        val effects: Set<String>,
        val rowVar: RowVar? = null // null = closed, non-null = open
    ) : FunkType {
        val isOpen: Boolean get() = rowVar != null
        val isClosed: Boolean get() = rowVar == null
    }
    
}

@Serializable
data class TypeVar(val name: String, val level: Int = 0) {
    companion object {
        private var globalCounter = 0
        private var currentLevel = 0
        private val scopeCounters = mutableMapOf<Int, Int>()
        
        fun fresh(level: Int = currentLevel): TypeVar {
            // For nested scopes, use a shared counter across all scopes in the same function
            val scopeCounter = if (level == 0) {
                // Top-level scope uses its own counter
                scopeCounters.getOrPut(level) { 0 }
            } else {
                // All nested scopes share a counter that continues from where we left off
                val sharedCounter = scopeCounters.values.maxOrNull() ?: 0
                sharedCounter
            }
            val name = "'${('a' + scopeCounter)}"
            
            // Update all scope counters to the next value
            if (level == 0) {
                scopeCounters[level] = scopeCounter + 1
            } else {
                // Update all nested scope counters
                for (i in 1..currentLevel) {
                    scopeCounters[i] = scopeCounter + 1
                }
            }
            return TypeVar(name, level)
        }
        
        fun freshNamed(baseName: String, level: Int = currentLevel): TypeVar {
            val scopeCounter = scopeCounters.getOrPut(level) { 0 }
            val name = "'${baseName}${scopeCounter}"
            scopeCounters[level] = scopeCounter + 1
            return TypeVar(name, level)
        }
        
        fun resetCounter() {
            globalCounter = 0
            currentLevel = 0
            scopeCounters.clear()
        }
        
        fun enterScope(): Int {
            currentLevel++
            // Nested scopes inherit the parent's counter, top-level scopes start at 0
            if (currentLevel == 1) {
                // Top-level scope (inside a function) starts at 0
                scopeCounters[currentLevel] = 0
            } else {
                // Nested scopes continue from parent's counter
                val parentCounter = scopeCounters[currentLevel - 1] ?: 0
                scopeCounters[currentLevel] = parentCounter
            }
            return currentLevel
        }
        
        fun exitScope() {
            if (currentLevel > 0) {
                // Clean up the scope counter when exiting
                scopeCounters.remove(currentLevel)
                currentLevel--
            }
        }
        
        fun getCurrentLevel(): Int = currentLevel
        
        fun resetScopeCounter(level: Int = currentLevel) {
            scopeCounters[level] = 0
        }
    }
}

@Serializable
data class RowVar(val name: String) {
    companion object {
        private var counter = 0
        
        fun fresh(): RowVar {
            val name = "row${counter}"
            counter++
            return RowVar(name)
        }
    }
}

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
data class EffectDeclaration(
    val name: String,
    val operations: List<EffectOperation>,
    val inferredType: FunkType? = null           // From typechecker
) : ProgramItem

@Serializable
data class ImplDeclaration(
    val effectName: String,
    val handlerName: String,
    val handlerParams: RecordTypeAnnotation? = null, // For parameterized handlers like FileLogger {path: String}
    val operations: List<ImplOperation>,
    val inferredType: FunkType? = null           // From typechecker
) : ProgramItem

@Serializable
data class TopLevelExpression(
    val expression: Expression
) : ProgramItem



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
data class RecordFieldDef(
    val name: String,
    val type: TypeAnnotation
)

@Serializable
data class VariantConstructorDef(
    val name: String,
    val dataType: TypeAnnotation? = null
)

@Serializable
data class EffectOperation(
    val name: String,
    val paramType: TypeAnnotation,
    val returnType: TypeAnnotation
)

@Serializable
data class ImplOperation(
    val name: String,
    val paramType: TypeAnnotation,
    val returnType: TypeAnnotation,
    val implementation: Expression
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

@Serializable
data class UnaryOperation(
    val operator: String,
    val operand: Expression,
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

@Serializable
data class RecordUpdateExpression(
    val baseRecord: Expression,
    val updates: List<RecordUpdateEntry>,
    override val inferredType: FunkType? = null
) : Expression

@Serializable
data class RecordUpdateEntry(
    val fieldPath: List<String>, // ["x"] for x = v, ["y", "z"] for y.z = v
    val value: Expression
)

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
data class DoExpression(
    val statements: List<Statement>,
    val result: Expression,
    override val inferredType: FunkType? = null
) : Expression

// Effect handling
@Serializable
data class PerformExpression(
    val expression: Expression,
    val handlers: List<PerformHandler>,
    override val inferredType: FunkType? = null
) : Expression

@Serializable
data class PerformHandler(
    val name: String,
    val parameters: RecordLiteral? = null
)


@Serializable
data class EffectCallExpression(
    val effectName: String,
    val operationName: String,
    val arguments: Expression,
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
data class TypeApplicationAnnotation(
    val constructor: String,
    val args: List<TypeAnnotation>
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
    val returnType: TypeAnnotation,
    val effects: EffectRowAnnotation? = null
) : TypeAnnotation

@Serializable
data class EffectRowAnnotation(
    val effects: List<String>
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
