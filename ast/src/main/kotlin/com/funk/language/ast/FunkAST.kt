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
    
    // Enhanced numeric types
    @Serializable
    object NatType : FunkType
    
    @Serializable
    object FracType : FunkType
    
    // Generic numeric type
    @Serializable
    data class NumType(val constraint: TypeVar) : FunkType
    
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
        val effects: EffectRow? = null,  // null = pure function, non-null = effectful function
        val abilityConstraints: List<FunctionAbilityConstraint> = emptyList()  // Ability constraints for this function
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
    
    // Function-level ability constraints
    @Serializable
    data class FunctionAbilityConstraint(
        val abilityName: String,
        val typeArgs: List<FunkType> = emptyList()  // Type arguments for the ability
    )
    
}

/**
 * Type schemes for let-polymorphism
 * Represents ∀α₁,α₂,...,αₙ. τ (forall type variables, type τ)
 */
@Serializable
data class TypeScheme(
    val quantifiedVars: Set<TypeVar>,
    val type: FunkType
) {
    /**
     * Check if this type scheme is monomorphic (no quantified variables)
     */
    val isMonomorphic: Boolean get() = quantifiedVars.isEmpty()
    
    /**
     * Instantiate this type scheme with fresh type variables
     */
    fun instantiate(): FunkType {
        if (isMonomorphic) return type
        
        val substitution = quantifiedVars.associateWith { 
            FunkType.VarType(TypeVar.fresh()) 
        }
        return applySubstitutionToType(type, substitution)
    }
    
    /**
     * Apply a substitution to a type
     */
    private fun applySubstitutionToType(type: FunkType, substitution: Map<TypeVar, FunkType>): FunkType {
        return when (type) {
            is FunkType.VarType -> substitution[type.variable] ?: type
            is FunkType.FunctionType -> FunkType.FunctionType(
                applySubstitutionToType(type.paramType, substitution),
                applySubstitutionToType(type.returnType, substitution),
                type.effects,
                type.abilityConstraints
            )
            is FunkType.RecordType -> FunkType.RecordType(
                type.fields.mapValues { (_, fieldType) -> 
                    applySubstitutionToType(fieldType, substitution) 
                },
                type.rowVar
            )
            is FunkType.VariantType -> FunkType.VariantType(
                type.constructors.mapValues { (_, dataType) -> 
                    dataType?.let { applySubstitutionToType(it, substitution) }
                },
                type.rowVar
            )
            is FunkType.NumType -> FunkType.NumType(
                substitution[type.constraint]?.let { 
                    if (it is FunkType.VarType) it.variable else type.constraint 
                } ?: type.constraint
            )
            // Concrete types remain unchanged
            is FunkType.IntType, is FunkType.FloatType, is FunkType.StringType, 
            is FunkType.BooleanType, is FunkType.NatType, is FunkType.FracType -> type
            is FunkType.EffectRow -> type // Effects don't contain type variables for now
        }
    }
}

@Serializable
data class TypeVar(val name: String, val level: Int = 0) {
    companion object {
        private var globalCounter = 0
        private var currentLevel = 0
        private val scopeCounters = mutableMapOf<Int, Int>()
        
        fun fresh(level: Int = currentLevel): TypeVar {
            // Get or initialize counter for this level, starting from 0
            val scopeCounter = scopeCounters.getOrPut(level) { 0 }
            
            // Generate name: 'a', 'b', 'c', etc.
            val name = "'${('a' + scopeCounter)}"
            
            // Increment counter for this level
            scopeCounters[level] = scopeCounter + 1
            
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
        
        fun resetScope(level: Int = 0) {
            // Reset counter for a specific scope level (useful for per-function reset)
            scopeCounters[level] = 0
        }
        
        fun enterScope(): Int {
            currentLevel++
            // Each new scope starts fresh with 'a
            scopeCounters[currentLevel] = 0
            return currentLevel
        }
        
        fun exitScope() {
            if (currentLevel > 0) {
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
    val performerName: String,
    val performerParams: List<PerformerParameter> = emptyList(), // For parameterized performers like FileLogger {path: String}
    val operations: List<PerformerOperation>,
    val inferredType: FunkType? = null           // From typechecker
) : ProgramItem

@Serializable
data class AbilityDeclaration(
    val name: String,
    val typeParams: List<String>,                // Multiple type parameters like ['a, 'b]
    val constraints: List<AbilityConstraint> = emptyList(),
    val dependencies: List<AbilityDependency> = emptyList(),  // Ability dependencies (e.g., Ord depends on Eq)
    val operations: List<AbilityOperation>,
    val inferredType: FunkType? = null           // From typechecker
) : ProgramItem

@Serializable
data class ImplAbilityDeclaration(
    val abilityName: String,
    val targetTypes: List<TypeAnnotation>,       // Multiple concrete types like [String, Int]
    val operations: List<ImplAbilityOperation>,
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
    val returnType: TypeAnnotation,
    val effects: List<String> = emptyList()
)

@Serializable
data class PerformerParameter(
    val name: String,
    val type: TypeAnnotation
)

@Serializable
data class PerformerOperation(
    val name: String,
    val paramType: TypeAnnotation,
    val returnType: TypeAnnotation,
    val implementation: Expression,
    val effects: EffectRowAnnotation? = null
)

// Keep ImplOperation for backward compatibility during transition
@Serializable
data class ImplOperation(
    val name: String,
    val paramType: TypeAnnotation,
    val returnType: TypeAnnotation,
    val implementation: Expression
)

@Serializable
data class AbilityConstraint(
    val abilityName: String,
    val typeParam: String
)

@Serializable
data class FunctionAbilityConstraint(
    val abilityName: String,
    val typeArgs: List<TypeAnnotation> = emptyList()  // Type arguments for the ability
)

@Serializable
data class AbilityDependency(
    val abilityName: String,
    val typeArgs: List<TypeAnnotation> = emptyList()  // Type arguments for the dependency
)

@Serializable
data class AbilityOperation(
    val name: String,
    val paramType: TypeAnnotation,
    val returnType: TypeAnnotation
)

@Serializable
data class ImplAbilityOperation(
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

// Temporary placeholder for pipe operator desugaring - should never appear in final AST
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
    val returnType: TypeAnnotation? = null,  // Explicit return type annotation
    val abilityConstraints: List<FunctionAbilityConstraint> = emptyList(),  // Ability constraints
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
    val patterns: List<Pattern>,        // Multiple patterns for | syntax
    val guard: Expression? = null,      // Optional guard condition
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
    val effects: EffectRowAnnotation? = null,
    val abilityConstraints: List<FunctionAbilityConstraint> = emptyList()
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

/**
 * Convert TypeAnnotation to internal FunkType representation
 */
fun convertTypeAnnotation(annotation: TypeAnnotation): FunkType {
    return when (annotation) {
        is IdentifierTypeAnnotation -> {
            when (annotation.name) {
                "Int" -> FunkType.IntType
                "Float" -> FunkType.FloatType
                "String" -> FunkType.StringType
                "Boolean" -> FunkType.BooleanType
                else -> FunkType.VarType(TypeVar(annotation.name))
            }
        }
        is FunctionTypeAnnotation -> {
            val paramType = convertTypeAnnotation(annotation.paramType)
            val returnType = convertTypeAnnotation(annotation.returnType)
            
            // Convert effect annotations
            val effects = annotation.effects?.let { effectRow ->
                FunkType.EffectRow(effectRow.effects.toSet())
            }
            
            // Convert ability constraints  
            val abilityConstraints = annotation.abilityConstraints.map { constraint ->
                FunkType.FunctionAbilityConstraint(
                    constraint.abilityName,
                    constraint.typeArgs.map { convertTypeAnnotation(it) }
                )
            }
            
            FunkType.FunctionType(
                paramType, 
                returnType, 
                effects, 
                abilityConstraints
            )
        }
        is RecordTypeAnnotation -> {
            val fieldTypes = annotation.entries.associate { entry ->
                entry.name to convertTypeAnnotation(entry.type)
            }
            FunkType.RecordType(fieldTypes)
        }
        is VariantTypeAnnotation -> {
            val constructorTypes = annotation.constructors.associate { constructor ->
                constructor.name to constructor.dataType?.let { convertTypeAnnotation(it) }
            }
            FunkType.VariantType(constructorTypes)
        }
        is TypeApplicationAnnotation -> {
            // For now, just return a simple identifier type - proper generic support would be more complex
            FunkType.VarType(TypeVar(annotation.constructor))
        }
        else -> {
            throw RuntimeException("Unsupported type annotation: ${annotation::class.simpleName}")
        }
    }
}

/**
 * Normalize type variables with constraint awareness to preserve unification relationships
 */
fun normalizeTypeVariablesWithConstraints(type: FunkType, constraints: List<Pair<FunkType, FunkType>>): FunkType {
    val typeVarsInOrder = mutableListOf<TypeVar>()
    collectTypeVariablesInOrder(type, typeVarsInOrder)
    
    // Create equivalence classes based on constraints
    val equivalenceClasses = mutableListOf<MutableSet<TypeVar>>()
    val processed = mutableSetOf<TypeVar>()
    
    for (typeVar in typeVarsInOrder) {
        if (typeVar in processed) continue
        
        val equivalenceClass = mutableSetOf<TypeVar>()
        equivalenceClass.add(typeVar)
        processed.add(typeVar)
        
        // Find all type variables that should be unified with this one
        for ((type1, type2) in constraints) {
            val vars1 = extractTypeVariables(type1)
            val vars2 = extractTypeVariables(type2)
            
            if (typeVar in vars1) {
                vars2.forEach { otherVar ->
                    if (otherVar !in processed) {
                        equivalenceClass.add(otherVar)
                        processed.add(otherVar)
                    }
                }
            } else if (typeVar in vars2) {
                vars1.forEach { otherVar ->
                    if (otherVar !in processed) {
                        equivalenceClass.add(otherVar)
                        processed.add(otherVar)
                    }
                }
            }
        }
        
        equivalenceClasses.add(equivalenceClass)
    }
    
    // Create mapping from old names to new normalized names
    val mapping = mutableMapOf<TypeVar, TypeVar>()
    equivalenceClasses.forEachIndexed { index, equivalenceClass ->
        val normalizedName = "'${('a' + index)}"
        for (typeVar in equivalenceClass) {
            mapping[typeVar] = TypeVar(normalizedName, typeVar.level)
        }
    }
    
    return substituteTypeVariables(type, mapping)
}

/**
 * Extract type variables from a type
 */
private fun extractTypeVariables(type: FunkType): Set<TypeVar> {
    val result = mutableSetOf<TypeVar>()
    when (type) {
        is FunkType.VarType -> result.add(type.variable)
        is FunkType.NumType -> result.add(type.constraint)
        is FunkType.FunctionType -> {
            result.addAll(extractTypeVariables(type.paramType))
            result.addAll(extractTypeVariables(type.returnType))
        }
        is FunkType.RecordType -> {
            type.fields.values.forEach { fieldType ->
                fieldType?.let { result.addAll(extractTypeVariables(it)) }
            }
        }
        is FunkType.VariantType -> {
            type.constructors.values.forEach { constructorType ->
                constructorType?.let { result.addAll(extractTypeVariables(it)) }
            }
        }
        else -> { /* Base types don't contain type variables */ }
    }
    return result
}

/**
 * Normalize type variables in a type to start from 'a, 'b, 'c, etc.
 * This provides clean, readable output by renaming type variables in order of appearance.
 */
fun normalizeTypeVariables(type: FunkType): FunkType {
    val typeVarsInOrder = mutableListOf<TypeVar>()
    collectTypeVariablesInOrder(type, typeVarsInOrder)
    
    // CRITICAL FIX: Group type variables by name to preserve constraint relationships
    // Type variables with the same name should get the same normalized name
    val groupedByName = typeVarsInOrder.groupBy { it.name }
    val uniqueNames = groupedByName.keys.toList()
    
    // Create mapping from old names to new normalized names
    val mapping = mutableMapOf<TypeVar, TypeVar>()
    uniqueNames.forEachIndexed { index, name ->
        val normalizedName = "'${('a' + index)}"
        // All type variables with the same original name get the same normalized name
        groupedByName[name]?.forEach { typeVar ->
            mapping[typeVar] = TypeVar(normalizedName, typeVar.level)
        }
    }
    
    return substituteTypeVariables(type, mapping)
}

/**
 * Collect all type variables in a type in order of appearance (left-to-right, depth-first)
 */
private fun collectTypeVariablesInOrder(type: FunkType, collector: MutableList<TypeVar>) {
    when (type) {
        is FunkType.VarType -> collector.add(type.variable)
        is FunkType.NumType -> collector.add(type.constraint)
        is FunkType.FunctionType -> {
            // Collect parameters first (left side), then return type (right side)
            collectTypeVariablesInOrder(type.paramType, collector)
            collectTypeVariablesInOrder(type.returnType, collector)
            type.effects?.let { /* Effect collection if needed */ }
        }
        is FunkType.RecordType -> {
            // Collect field types in alphabetical order for consistency
            type.fields.keys.sorted().forEach { fieldName ->
                type.fields[fieldName]?.let { collectTypeVariablesInOrder(it, collector) }
            }
            type.rowVar?.let { /* RowVar collection if needed */ }
        }
        is FunkType.VariantType -> {
            // Collect constructor types in alphabetical order for consistency
            type.constructors.keys.sorted().forEach { constructorName ->
                type.constructors[constructorName]?.let { collectTypeVariablesInOrder(it, collector) }
            }
        }
        // Base types don't contain type variables
        is FunkType.IntType, is FunkType.FloatType, is FunkType.StringType, 
        is FunkType.BooleanType, is FunkType.NatType, is FunkType.FracType -> {
            // No type variables to collect
        }
        is FunkType.EffectRow -> {
            // Effect rows might contain type variables in the future
        }
    }
}

/**
 * Collect all type variables in a type (old version for compatibility)
 */
private fun collectTypeVariables(type: FunkType, collector: MutableSet<TypeVar>) {
    when (type) {
        is FunkType.VarType -> collector.add(type.variable)
        is FunkType.NumType -> collector.add(type.constraint)
        is FunkType.FunctionType -> {
            collectTypeVariables(type.paramType, collector)
            collectTypeVariables(type.returnType, collector)
            type.effects?.let { /* Effect collection if needed */ }
        }
        is FunkType.RecordType -> {
            type.fields.values.forEach { collectTypeVariables(it, collector) }
            type.rowVar?.let { /* RowVar collection if needed */ }
        }
        is FunkType.VariantType -> {
            type.constructors.values.forEach { caseType ->
                caseType?.let { collectTypeVariables(it, collector) }
            }
        }
        // Base types don't contain type variables
        is FunkType.IntType, is FunkType.FloatType, is FunkType.StringType, 
        is FunkType.BooleanType, is FunkType.NatType, is FunkType.FracType -> {
            // No type variables to collect
        }
        is FunkType.EffectRow -> {
            // Effect rows might contain type variables in the future
        }
    }
}

/**
 * Substitute type variables in a type according to the mapping
 */
private fun substituteTypeVariables(type: FunkType, mapping: Map<TypeVar, TypeVar>): FunkType {
    return when (type) {
        is FunkType.VarType -> {
            val newVar = mapping[type.variable] ?: type.variable
            FunkType.VarType(newVar)
        }
        is FunkType.NumType -> {
            val newConstraint = mapping[type.constraint] ?: type.constraint
            FunkType.NumType(newConstraint)
        }
        is FunkType.FunctionType -> {
            val newParamType = substituteTypeVariables(type.paramType, mapping)
            val newReturnType = substituteTypeVariables(type.returnType, mapping)
            FunkType.FunctionType(newParamType, newReturnType, type.effects, type.abilityConstraints)
        }
        is FunkType.RecordType -> {
            val newFields = type.fields.mapValues { (_, fieldType) ->
                substituteTypeVariables(fieldType, mapping)
            }
            FunkType.RecordType(newFields, type.rowVar) // RowVar substitution if needed
        }
        is FunkType.VariantType -> {
            val newConstructors = type.constructors.mapValues { (_, caseType) ->
                caseType?.let { substituteTypeVariables(it, mapping) }
            }
            // CRITICAL FIX: Preserve row variable during type variable normalization
            FunkType.VariantType(newConstructors, type.rowVar)
        }
        // Base types and effect rows remain unchanged
        else -> type
    }
}


