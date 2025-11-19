@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.funk.language.ast

import kotlinx.serialization.ExperimentalSerializationApi
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
// BUILT-IN ABILITIES
// ============================================================================

/**
 * Built-in abilities that are always available in the language
 */
object BuiltinAbilities {
    // Comparison abilities
    const val EQ = "Eq"
    const val ORD = "Ord"
    
    // Numeric abilities
    const val NUM_ADD = "NumAdd"
    const val NUM_SUB = "NumSub"
    const val NUM_MUL = "NumMul"
    const val NUM_DIV = "NumDiv"
    const val NUM_MOD = "NumMod"

    // Inspect ability
    const val INSPECT = "Inspect"

    // All built-in abilities
    val ALL = setOf(EQ, ORD, NUM_ADD, NUM_SUB, NUM_MUL, NUM_DIV, NUM_MOD, INSPECT)
    
    /**
     * Check if an ability name is a built-in ability
     */
    fun isBuiltin(abilityName: String): Boolean = abilityName in ALL
    
    /**
     * Automatic ability implementations for built-in types
     * Maps type to the set of abilities it automatically implements
     */
    private val automaticImplementations = mapOf(
        // Primitive types
        "Int" to setOf(EQ, ORD, INSPECT, NUM_ADD, NUM_SUB, NUM_MUL, NUM_DIV, NUM_MOD),
        "Float" to setOf(EQ, ORD, INSPECT, NUM_ADD, NUM_SUB, NUM_MUL, NUM_DIV),
        "String" to setOf(EQ, ORD, INSPECT),
        "Boolean" to setOf(EQ, ORD, INSPECT),
        "Nat" to setOf(EQ, ORD, INSPECT, NUM_ADD, NUM_MUL, NUM_DIV, NUM_MOD),
        "Frac" to setOf(EQ, ORD, INSPECT, NUM_ADD, NUM_SUB, NUM_MUL, NUM_DIV),

        // Built-in core types (conditional implementations based on element types)
        "Option" to setOf(EQ, ORD, INSPECT), // if element type has these abilities
        "Result" to setOf(EQ, ORD, INSPECT), // if both error and value types have these abilities
        "List" to setOf(EQ, ORD, INSPECT),   // if element type has these abilities
        "Map" to setOf(EQ, INSPECT),         // if key and value types have these abilities
        "Set" to setOf(EQ, INSPECT),         // if element type has these abilities
        "Ordering" to setOf(EQ, ORD, INSPECT)
    )
    
    /**
     * Check if a built-in type automatically implements an ability
     */
    fun hasAutomaticImplementation(typeName: String, abilityName: String): Boolean {
        return automaticImplementations[typeName]?.contains(abilityName) == true
    }
    
    /**
     * Get all abilities that a built-in type automatically implements
     */
    fun getAutomaticImplementations(typeName: String): Set<String> {
        return automaticImplementations[typeName] ?: emptySet()
    }
    
    /**
     * Check if a FunkType automatically implements an ability
     * This handles both primitive types and parameterized built-in types
     */
    fun typeHasAutomaticImplementation(type: FunkType, abilityName: String): Boolean {
        return when (type) {
            is FunkType.IntType -> hasAutomaticImplementation("Int", abilityName)
            is FunkType.FloatType -> hasAutomaticImplementation("Float", abilityName)
            is FunkType.StringType -> hasAutomaticImplementation("String", abilityName)
            is FunkType.BooleanType -> hasAutomaticImplementation("Boolean", abilityName)
            is FunkType.NatType -> hasAutomaticImplementation("Nat", abilityName)
            is FunkType.FracType -> hasAutomaticImplementation("Frac", abilityName)
            is FunkType.OrderingType -> hasAutomaticImplementation("Ordering", abilityName)
            
            // Parameterized types - check if element types support the ability
            is FunkType.TypeApplication -> {
                val typeName = type.constructor
                hasAutomaticImplementation(typeName, abilityName) &&
                type.args.all { typeHasAutomaticImplementation(it, abilityName) }
            }
            is FunkType.ListType -> {
                hasAutomaticImplementation("List", abilityName) && 
                typeHasAutomaticImplementation(type.elementType, abilityName)
            }
            is FunkType.MapType -> {
                hasAutomaticImplementation("Map", abilityName) && 
                typeHasAutomaticImplementation(type.keyType, abilityName) &&
                typeHasAutomaticImplementation(type.valueType, abilityName)
            }
            is FunkType.SetType -> {
                hasAutomaticImplementation("Set", abilityName) && 
                typeHasAutomaticImplementation(type.elementType, abilityName)
            }
            
            else -> false
        }
    }
}

/**
 * Built-in operator mappings to abilities
 * Maps operators to their corresponding ability operations
 */
object BuiltinOperators {
    
    /**
     * Arithmetic operators mapped to abilities
     */
    val arithmeticOperators = mapOf(
        "+" to BuiltinAbilities.NUM_ADD,
        "-" to BuiltinAbilities.NUM_SUB,
        "*" to BuiltinAbilities.NUM_MUL,
        "/" to BuiltinAbilities.NUM_DIV,
        "%" to BuiltinAbilities.NUM_MOD
    )
    
    /**
     * Comparison operators mapped to abilities
     */
    val comparisonOperators = mapOf(
        "==" to BuiltinAbilities.EQ,
        "!=" to BuiltinAbilities.EQ, // negated
        "<" to BuiltinAbilities.ORD,
        ">" to BuiltinAbilities.ORD,
        "<=" to BuiltinAbilities.ORD,
        ">=" to BuiltinAbilities.ORD
    )
    
    /**
     * Boolean operators (built-in language constructs)
     */
    val booleanOperators = setOf("&&", "||", "!")
    
    /**
     * All built-in operators
     */
    val allOperators = arithmeticOperators.keys + comparisonOperators.keys + booleanOperators
    
    /**
     * Check if an operator is a built-in operator
     */
    fun isBuiltinOperator(operator: String): Boolean {
        return operator in allOperators
    }
    
    /**
     * Get the ability required for an operator
     */
    fun getRequiredAbility(operator: String): String? {
        return arithmeticOperators[operator] ?: comparisonOperators[operator]
    }
    
    /**
     * Check if an operator is a boolean operator (no ability constraint needed)
     */
    fun isBooleanOperator(operator: String): Boolean {
        return operator in booleanOperators
    }
    
    /**
     * Get the ability operation name for an operator
     */
    fun getAbilityOperation(operator: String): String? {
        return when (operator) {
            "+" -> "add"
            "-" -> "subtract"
            "*" -> "multiply"
            "/" -> "divide"
            "%" -> "modulo"
            "==", "!=" -> "equals"
            "<" -> "less_than"
            ">" -> "greater_than"
            "<=", ">=" -> null // derived from other operations
            else -> null
        }
    }
}

/**
 * Built-in ability interface definitions
 * These define the structure and operations for each built-in ability
 */
object BuiltinAbilityDefinitions {
    
    /**
     * Equality ability - provides equals operation
     */
    data class EqAbility(
        val equals: String = "equals" // { l: 'a, r: 'a } -> Boolean
    )
    
    /**
     * Ordering ability - provides comparison operations
     */
    data class OrdAbility(
        val compare: String = "compare",        // { l: 'a, r: 'a } -> Ordering
        val lessThan: String = "less_than",     // { l: 'a, r: 'a } -> Boolean
        val greaterThan: String = "greater_than" // { l: 'a, r: 'a } -> Boolean
    )
    
    /**
     * Numeric addition ability
     */
    data class NumAddAbility(
        val add: String = "add" // { l: 'a, r: 'a } -> 'a
    )
    
    /**
     * Numeric subtraction ability
     */
    data class NumSubAbility(
        val subtract: String = "subtract" // { l: 'a, r: 'a } -> 'a
    )
    
    /**
     * Numeric multiplication ability
     */
    data class NumMulAbility(
        val multiply: String = "multiply" // { l: 'a, r: 'a } -> 'a
    )
    
    /**
     * Numeric division ability
     */
    data class NumDivAbility(
        val divide: String = "divide" // { l: 'a, r: 'a } -> 'a
    )
    
    /**
     * Numeric modulo ability
     */
    data class NumModAbility(
        val modulo: String = "modulo" // { l: 'a, r: 'a } -> 'a
    )
    
    /**
     * Inspect ability - provides string representation
     */
    data class InspectAbility(
        val to_str: String = "to_str" // { val: 'a } -> String
    )
    
    /**
     * Get the operation names for a built-in ability
     */
    fun getAbilityOperations(abilityName: String): List<String> {
        return when (abilityName) {
            BuiltinAbilities.EQ -> listOf(EqAbility().equals)
            BuiltinAbilities.ORD -> listOf(
                OrdAbility().compare, 
                OrdAbility().lessThan, 
                OrdAbility().greaterThan
            )
            BuiltinAbilities.NUM_ADD -> listOf(NumAddAbility().add)
            BuiltinAbilities.NUM_SUB -> listOf(NumSubAbility().subtract)
            BuiltinAbilities.NUM_MUL -> listOf(NumMulAbility().multiply)
            BuiltinAbilities.NUM_DIV -> listOf(NumDivAbility().divide)
            BuiltinAbilities.NUM_MOD -> listOf(NumModAbility().modulo)
            BuiltinAbilities.INSPECT -> listOf(InspectAbility().to_str)
            else -> emptyList()
        }
    }
    
    /**
     * Check if an operation belongs to a built-in ability
     */
    fun isBuiltinAbilityOperation(abilityName: String, operationName: String): Boolean {
        return getAbilityOperations(abilityName).contains(operationName)
    }
}

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
    
    // Built-in core types (always available)
    // Note: Option and Result are now regular variant types, not special AST types
    // They are defined as built-in variants:
    //   Option 'a = Some { val: 'a } | None
    //   Result 'a 'e = Ok { val: 'a } | Err { error: 'e }

    @Serializable
    data class ListType(val elementType: FunkType) : FunkType
    
    @Serializable
    data class MapType(val keyType: FunkType, val valueType: FunkType) : FunkType
    
    @Serializable
    data class SetType(val elementType: FunkType) : FunkType

    // Generic type application (e.g., Option Int, Result String Error, MyType 'a 'b)
    // This represents applying type arguments to a type constructor
    @Serializable
    data class TypeApplication(val constructor: String, val args: List<FunkType>) : FunkType

    // Built-in ordering type for comparisons
    @Serializable
    object OrderingType : FunkType
    
    // ArrayList types (maps to java.util.ArrayList) - kept for backward compatibility
    @Serializable
    data class ArrayListType(val elementType: FunkType) : FunkType
    
    // Array types (for native arrays)
    @Serializable
    data class ArrayType(val elementType: FunkType) : FunkType
    
    // Function types
    @Serializable
    data class FunctionType(
        val paramType: FunkType,
        val returnType: FunkType,
        val effects: EffectRow? = null,  // null = pure function, non-null = effectful function
        val abilityConstraints: List<FunctionAbilityConstraint> = emptyList()  // Ability constraints for this function
    ) : FunkType

    // Suspended computation types (values with unhandled effects)
    // This represents a value of type valueType that has unhandled effects
    // Unlike FunctionType, this is not a function - it's an already-computed value
    // that cannot be used until its effects are handled
    @Serializable
    data class SuspendedType(
        val valueType: FunkType,
        val effects: EffectRow
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
    val type: FunkType,
    val constraints: List<AbilityConstraint> = emptyList()
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
    
    companion object {
        /**
         * Generalize a type into a type scheme by quantifying over free type variables
         */
        fun generalize(type: FunkType, constraints: List<AbilityConstraint> = emptyList()): TypeScheme {
            val typeVars = mutableSetOf<TypeVar>()
            collectTypeVariables(type, typeVars)
            
            // Filter constraints to only include those relevant to the type variables
            val relevantConstraints = constraints.filter { constraint ->
                typeVars.any { it.name == constraint.typeParam }
            }
            
            return TypeScheme(typeVars, type, relevantConstraints)
        }
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
            is FunkType.TypeApplication -> FunkType.TypeApplication(
                type.constructor,
                type.args.map { applySubstitutionToType(it, substitution) }
            )
            is FunkType.ListType -> FunkType.ListType(
                applySubstitutionToType(type.elementType, substitution)
            )
            is FunkType.MapType -> FunkType.MapType(
                applySubstitutionToType(type.keyType, substitution),
                applySubstitutionToType(type.valueType, substitution)
            )
            is FunkType.SetType -> FunkType.SetType(
                applySubstitutionToType(type.elementType, substitution)
            )
            is FunkType.ArrayListType -> FunkType.ArrayListType(
                applySubstitutionToType(type.elementType, substitution)
            )
            is FunkType.ArrayType -> FunkType.ArrayType(
                applySubstitutionToType(type.elementType, substitution)
            )
            is FunkType.SuspendedType -> FunkType.SuspendedType(
                applySubstitutionToType(type.valueType, substitution),
                type.effects
            )
            // Concrete types remain unchanged
            is FunkType.IntType, is FunkType.FloatType, is FunkType.StringType,
            is FunkType.BooleanType, is FunkType.NatType, is FunkType.FracType,
            is FunkType.OrderingType -> type
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
    val visibility: Visibility = Visibility.PRIVATE,
    val typeAnnotation: TypeAnnotation? = null,  // From parser
    val inferredType: FunkType? = null,          // From typechecker (monomorphic, instantiated type)
    val typeScheme: TypeScheme? = null,          // From typechecker (polymorphic type with constraints)
    val expression: Expression
) : ProgramItem

@Serializable
data class TypeDeclaration(
    val name: String,
    val visibility: Visibility = Visibility.PRIVATE,
    val typeParams: List<String> = emptyList(),
    val definition: TypeDefinition,
    val inferredType: FunkType? = null           // From typechecker
) : ProgramItem

@Serializable
data class EffectDeclaration(
    val name: String,
    val visibility: Visibility = Visibility.PRIVATE,
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
    val visibility: Visibility = Visibility.PRIVATE,
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

// Module system declarations
@Serializable
data class UseDeclaration(
    val path: UsePath,
    val alias: String? = null
) : ProgramItem

@Serializable
data class PubUseDeclaration(
    val path: UsePath,
    val alias: String? = null
) : ProgramItem



@Serializable
@JsonClassDiscriminator("typeDefKind")
sealed interface TypeDefinition

@Serializable
data class RecordDefinition(
    val fields: List<RecordFieldDef>,
    val isOpen: Boolean = false
) : TypeDefinition

@Serializable
data class VariantDefinition(
    val constructors: List<VariantConstructorDef>,
    val isOpen: Boolean = false
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
    val paramType: TypeAnnotation?,  // Optional - can be inferred from effect declaration
    val returnType: TypeAnnotation?, // Optional - can be inferred from effect declaration
    val implementation: Expression,
    val effects: EffectRowAnnotation? = null  // Optional - can be inferred if not specified
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

/**
 * Display constraint for debugging and testing purposes
 */
@Serializable
data class InspectAbilityConstraint(
    val typeParam: String,
    val abilityName: String
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
    val paramType: TypeAnnotation?,  // Optional - can be inferred from ability declaration
    val returnType: TypeAnnotation?, // Optional - can be inferred from ability declaration
    val implementation: Expression
)

// ============================================================================
// SOURCE LOCATION TRACKING
// ============================================================================

@Serializable
data class SourceLocation(
    val line: Int,
    val column: Int,
    val file: String? = null
) {
    override fun toString(): String = if (file != null) "$file:$line:$column" else "$line:$column"
}

// ============================================================================
// EXPRESSIONS (Unified - can be untyped or typed)
// ============================================================================

@Serializable
@JsonClassDiscriminator("exprKind")
sealed interface Expression {
    val inferredType: FunkType? // null during parsing, filled by typechecker
    val sourceLocation: SourceLocation? get() = null // source location for error reporting (default implementation)
}

// Literals
@Serializable
data class IntLiteral(
    val value: Long,
    override val inferredType: FunkType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression

@Serializable
data class FloatLiteral(
    val value: Double,
    override val inferredType: FunkType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression

@Serializable
data class StringLiteral(
    val value: String,
    override val inferredType: FunkType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression

@Serializable
data class BooleanLiteral(
    val value: Boolean,
    override val inferredType: FunkType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression

@Serializable
data class FractionLiteral(
    val numerator: Long,
    val denominator: Long,
    override val inferredType: FunkType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression

@Serializable
data class ArrayListLiteral(
    val elements: List<Expression>,
    override val inferredType: FunkType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression

@Serializable
data class ArrayLiteral(
    val elements: List<Expression>,
    override val inferredType: FunkType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression

@Serializable
data class ArrayAccess(
    val array: Expression,
    val index: Expression,
    override val inferredType: FunkType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression



// Variables
@Serializable
data class Identifier(
    val name: String,
    override val inferredType: FunkType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression

// Temporary placeholder for pipe operator desugaring - should never appear in final AST
@Serializable
data class PlaceholderExpression(
    override val inferredType: FunkType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression


// Operations
@Serializable
data class BinaryOperation(
    val left: Expression,
    val operator: String,
    val right: Expression,
    override val inferredType: FunkType? = null,
    override val sourceLocation: SourceLocation? = null
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
    val entries: List<RecordLiteralEntry>,
    val restField: String? = null,
    override val inferredType: FunkType? = null
) : Expression {
    // Thread-local to track nesting depth to prevent infinite recursion
    companion object {
        internal val toStringDepth = ThreadLocal.withInitial { 0 }
        internal const val MAX_DEPTH = 20
    }

    override fun toString(): String {
        val depth = toStringDepth.get()
        if (depth > MAX_DEPTH) {
            return "RecordLiteral{<max depth reached>}"
        }

        return try {
            toStringDepth.set(depth + 1)
            val entriesStr = if (depth > 5) "<truncated>" else "size=${entries.size}"
            "RecordLiteral($entriesStr)"
        } catch (e: StackOverflowError) {
            "RecordLiteral{<StackOverflowError during toString>}"
        } finally {
            toStringDepth.set(depth)
            if (depth == 0) {
                toStringDepth.remove() // Clean up thread-local when back to top level
            }
        }
    }
}

@Serializable
sealed class RecordLiteralEntry

@Serializable
data class RecordFieldEntry(
    val name: String,
    val value: Expression? = null  // null for shorthand {x} instead of {x = x}
) : RecordLiteralEntry() {
    override fun toString(): String {
        val depth = RecordLiteral.toStringDepth.get()
        if (depth > 5) {
            return "RecordFieldEntry{<truncated>}"
        }

        return try {
            RecordLiteral.toStringDepth.set(depth + 1)
            "RecordFieldEntry($name)"
        } catch (e: StackOverflowError) {
            "RecordFieldEntry{<StackOverflowError>}"
        } finally {
            RecordLiteral.toStringDepth.set(depth)
        }
    }
}

@Serializable
data class RecordSpreadEntry(
    val expression: Expression
) : RecordLiteralEntry() {
    override fun toString(): String {
        val depth = RecordLiteral.toStringDepth.get()
        if (depth > 5) {
            return "RecordSpreadEntry{<truncated>}"
        }

        return try {
            RecordLiteral.toStringDepth.set(depth + 1)
            "RecordSpreadEntry(...)"
        } catch (e: StackOverflowError) {
            "RecordSpreadEntry{<StackOverflowError>}"
        } finally {
            RecordLiteral.toStringDepth.set(depth)
        }
    }
}

// Helper extension functions for backward compatibility
fun RecordLiteral.getFieldEntries(): List<RecordFieldEntry> {
    return entries.filterIsInstance<RecordFieldEntry>()
}

fun RecordLiteral.getSpreadEntries(): List<RecordSpreadEntry> {
    return entries.filterIsInstance<RecordSpreadEntry>()
}

fun RecordLiteral.findField(name: String): RecordFieldEntry? {
    return entries.filterIsInstance<RecordFieldEntry>().find { it.name == name }
}

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
    override val inferredType: FunkType? = null,
    override val sourceLocation: SourceLocation? = null
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
    val names: List<String>,
    val isOpen: Boolean = false  // true if ... was used in parameter list
) : LambdaParameters

@Serializable
data class FunctionCall(
    val function: Expression,
    val arguments: Expression,
    override val inferredType: FunkType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression {
    override fun toString(): String {
        val depth = RecordLiteral.toStringDepth.get()
        if (depth > 5) {
            return "FunctionCall{<truncated>}"
        }

        return try {
            RecordLiteral.toStringDepth.set(depth + 1)
            "FunctionCall(...)"
        } catch (e: StackOverflowError) {
            "FunctionCall{<StackOverflowError>}"
        } finally {
            RecordLiteral.toStringDepth.set(depth)
        }
    }
}

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
    override val inferredType: FunkType? = null,
    override val sourceLocation: SourceLocation? = null
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
    val entries: List<RecordPatternEntry>,
    val isOpen: Boolean = false  // true if ... was used in the pattern
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
data class ArrayListTypeAnnotation(
    val elementType: TypeAnnotation
) : TypeAnnotation

@Serializable
data class ArrayTypeAnnotation(
    val elementType: TypeAnnotation
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

// ============================================================================
// MODULE SYSTEM
// ============================================================================

/**
 * Visibility modifier for declarations
 */
@Serializable
enum class Visibility {
    PUBLIC,    // pub - visible to other modules
    PRIVATE    // default - only visible within module
}

/**
 * Use path types for import declarations
 */
@Serializable
@JsonClassDiscriminator("usePathKind")
sealed interface UsePath

@Serializable
data class ModulePath(val segments: List<String>) : UsePath  // math::geometry

@Serializable
data class SelectivePath(
    val module: List<String>,
    val items: List<ImportItem>
) : UsePath  // math::geometry::{area, Shape as alias}

@Serializable
data class ImportItem(
    val name: String,
    val alias: String? = null
)

// ============================================================================
// TYPE CONVERSION
// ============================================================================

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
                "Nat" -> FunkType.NatType
                "Frac" -> FunkType.FracType
                "Ordering" -> FunkType.OrderingType
                else -> FunkType.VarType(TypeVar(annotation.name))
            }
        }
        is FunctionTypeAnnotation -> {
            // Validate that parameter type is a RecordTypeAnnotation
            if (annotation.paramType !is RecordTypeAnnotation) {
                throw IllegalArgumentException(
                    "Function parameter type must be a record type (e.g., {x: Int} -> Int), " +
                    "but got: ${annotation.paramType::class.simpleName}"
                )
            }

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
            when (annotation.constructor) {
                // Note: Option and Result are now regular variant types, not special built-in types
                // They will be handled by the variant type system
                "List" -> {
                    if (annotation.args.size == 1) {
                        FunkType.ListType(convertTypeAnnotation(annotation.args[0]))
                    } else {
                        throw RuntimeException("List type requires exactly 1 type argument")
                    }
                }
                "Map" -> {
                    if (annotation.args.size == 2) {
                        FunkType.MapType(
                            convertTypeAnnotation(annotation.args[0]),
                            convertTypeAnnotation(annotation.args[1])
                        )
                    } else {
                        throw RuntimeException("Map type requires exactly 2 type arguments")
                    }
                }
                "Set" -> {
                    if (annotation.args.size == 1) {
                        FunkType.SetType(convertTypeAnnotation(annotation.args[0]))
                    } else {
                        throw RuntimeException("Set type requires exactly 1 type argument")
                    }
                }
                else -> {
                    // For parameterized types like Option, Result, and user-defined generic types
                    // We need to look up the type definition and apply the type arguments
                    // For now, we'll create a TypeApplication that represents the applied type
                    // This will be resolved later during type checking
                    if (annotation.args.isNotEmpty()) {
                        // This is a parameterized type application (e.g., Option Int, Result String Error)
                        // We'll represent it as a type application for now
                        // The type checker will resolve this to the actual variant type
                        FunkType.TypeApplication(
                            annotation.constructor,
                            annotation.args.map { convertTypeAnnotation(it) }
                        )
                    } else {
                        // Simple type constructor without arguments (will be resolved as type variable)
                        FunkType.VarType(TypeVar(annotation.constructor))
                    }
                }
            }
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
        is FunkType.TypeApplication -> {
            type.args.forEach { arg ->
                result.addAll(extractTypeVariables(arg))
            }
        }
        is FunkType.ListType -> {
            result.addAll(extractTypeVariables(type.elementType))
        }
        is FunkType.MapType -> {
            result.addAll(extractTypeVariables(type.keyType))
            result.addAll(extractTypeVariables(type.valueType))
        }
        is FunkType.SetType -> {
            result.addAll(extractTypeVariables(type.elementType))
        }
        is FunkType.ArrayListType -> {
            result.addAll(extractTypeVariables(type.elementType))
        }
        is FunkType.ArrayType -> {
            result.addAll(extractTypeVariables(type.elementType))
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

    // CRITICAL FIX: Use identity-based distinct to preserve independence of type variables
    // TypeVar is a data class, so .distinct() uses structural equality (name + level)
    // This would incorrectly merge independent type variables with the same name!
    // Instead, we track by object identity using a map
    val seenIdentities = mutableMapOf<Int, TypeVar>()  // identity -> first occurrence
    val uniqueTypeVars = mutableListOf<TypeVar>()

    for (typeVar in typeVarsInOrder) {
        val identity = System.identityHashCode(typeVar)
        if (!seenIdentities.containsKey(identity)) {
            seenIdentities[identity] = typeVar
            uniqueTypeVars.add(typeVar)
        }
    }

    // Create mapping from old type variables to new normalized names
    // Use a custom map that compares by identity, not structural equality
    val mapping = object : MutableMap<TypeVar, TypeVar> by mutableMapOf() {
        private val identityMap = mutableMapOf<Int, TypeVar>()

        override fun get(key: TypeVar): TypeVar? {
            return identityMap[System.identityHashCode(key)]
        }

        override fun put(key: TypeVar, value: TypeVar): TypeVar? {
            val identity = System.identityHashCode(key)
            return identityMap.put(identity, value)
        }

        override fun containsKey(key: TypeVar): Boolean {
            return identityMap.containsKey(System.identityHashCode(key))
        }
    }

    uniqueTypeVars.forEachIndexed { index, typeVar ->
        val normalizedName = "'${('a' + index)}"
        val newTypeVar = TypeVar(normalizedName, typeVar.level)
        mapping[typeVar] = newTypeVar
    }

    return substituteTypeVariables(type, mapping)
}

/**
 * Collect all type variables in a type in order of appearance (left-to-right, depth-first)
 */
private fun collectTypeVariablesInOrder(type: FunkType, collector: MutableList<TypeVar>) {
    when (type) {
        is FunkType.VarType -> collector.add(type.variable)
        is FunkType.FunctionType -> {
            // Collect parameters first (left side), then return type (right side)
            collectTypeVariablesInOrder(type.paramType, collector)
            collectTypeVariablesInOrder(type.returnType, collector)
            // CRITICAL FIX: Also collect type variables from ability constraints
            // This ensures constraint type variables get the same normalized names as param/return types
            type.abilityConstraints.forEach { constraint ->
                constraint.typeArgs.forEach { arg ->
                    collectTypeVariablesInOrder(arg, collector)
                }
            }
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
        is FunkType.TypeApplication -> {
            type.args.forEach { arg ->
                collectTypeVariablesInOrder(arg, collector)
            }
        }
        is FunkType.ListType -> {
            collectTypeVariablesInOrder(type.elementType, collector)
        }
        is FunkType.MapType -> {
            collectTypeVariablesInOrder(type.keyType, collector)
            collectTypeVariablesInOrder(type.valueType, collector)
        }
        is FunkType.SetType -> {
            collectTypeVariablesInOrder(type.elementType, collector)
        }
        is FunkType.ArrayListType -> {
            collectTypeVariablesInOrder(type.elementType, collector)
        }
        is FunkType.ArrayType -> {
            collectTypeVariablesInOrder(type.elementType, collector)
        }
        is FunkType.SuspendedType -> {
            collectTypeVariablesInOrder(type.valueType, collector)
        }
        // Base types don't contain type variables
        is FunkType.IntType, is FunkType.FloatType, is FunkType.StringType,
        is FunkType.BooleanType, is FunkType.NatType, is FunkType.FracType,
        is FunkType.OrderingType -> {
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
        is FunkType.TypeApplication -> {
            type.args.forEach { arg ->
                collectTypeVariables(arg, collector)
            }
        }
        is FunkType.ListType -> {
            collectTypeVariables(type.elementType, collector)
        }
        is FunkType.MapType -> {
            collectTypeVariables(type.keyType, collector)
            collectTypeVariables(type.valueType, collector)
        }
        is FunkType.SetType -> {
            collectTypeVariables(type.elementType, collector)
        }
        is FunkType.ArrayListType -> {
            collectTypeVariables(type.elementType, collector)
        }
        is FunkType.ArrayType -> {
            collectTypeVariables(type.elementType, collector)
        }
        is FunkType.SuspendedType -> {
            collectTypeVariables(type.valueType, collector)
        }
        // Base types don't contain type variables
        is FunkType.IntType, is FunkType.FloatType, is FunkType.StringType,
        is FunkType.BooleanType, is FunkType.NatType, is FunkType.FracType,
        is FunkType.OrderingType -> {
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
        is FunkType.FunctionType -> {
            val newParamType = substituteTypeVariables(type.paramType, mapping)
            val newReturnType = substituteTypeVariables(type.returnType, mapping)
            // CRITICAL FIX: Also substitute type variables in ability constraints
            // This ensures constraint type variables are normalized along with param/return types
            val newAbilityConstraints = type.abilityConstraints.map { constraint ->
                FunkType.FunctionAbilityConstraint(
                    constraint.abilityName,
                    constraint.typeArgs.map { arg -> substituteTypeVariables(arg, mapping) }
                )
            }
            FunkType.FunctionType(newParamType, newReturnType, type.effects, newAbilityConstraints)
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
        is FunkType.TypeApplication -> {
            val newArgs = type.args.map { substituteTypeVariables(it, mapping) }
            FunkType.TypeApplication(type.constructor, newArgs)
        }
        is FunkType.ListType -> {
            val newElementType = substituteTypeVariables(type.elementType, mapping)
            FunkType.ListType(newElementType)
        }
        is FunkType.MapType -> {
            val newKeyType = substituteTypeVariables(type.keyType, mapping)
            val newValueType = substituteTypeVariables(type.valueType, mapping)
            FunkType.MapType(newKeyType, newValueType)
        }
        is FunkType.SetType -> {
            val newElementType = substituteTypeVariables(type.elementType, mapping)
            FunkType.SetType(newElementType)
        }
        is FunkType.ArrayListType -> {
            val newElementType = substituteTypeVariables(type.elementType, mapping)
            FunkType.ArrayListType(newElementType)
        }
        is FunkType.ArrayType -> {
            val newElementType = substituteTypeVariables(type.elementType, mapping)
            FunkType.ArrayType(newElementType)
        }
        // Base types and effect rows remain unchanged
        else -> type
    }
}



/**
 * Merge two lists of ability constraints, removing duplicates
 */
fun mergeConstraints(constraints1: List<AbilityConstraint>, constraints2: List<AbilityConstraint>): List<AbilityConstraint> {
    val merged = (constraints1 + constraints2).distinctBy { "${it.abilityName}:${it.typeParam}" }
    return merged
}

// ============================================================================
// BUILT-IN TYPE UTILITIES
// ============================================================================

/**
 * Built-in type constructors and utilities
 */
object BuiltinTypes {
    
    /**
     * Built-in type constructor names
     */
    val coreTypeNames = setOf("Option", "Result", "List", "Map", "Set")
    val primitiveTypeNames = setOf("Int", "Float", "String", "Boolean", "Nat", "Frac", "Ordering")
    val allBuiltinTypeNames = coreTypeNames + primitiveTypeNames
    
    /**
     * Built-in constructor names for Option
     */
    val optionConstructors = setOf("Some", "None")
    
    /**
     * Built-in constructor names for Result
     */
    val resultConstructors = setOf("Ok", "Err")
    
    /**
     * Built-in constructor names for List
     */
    val listConstructors = setOf("Nil", "Cons") // Internal representation
    
    /**
     * Built-in constructor names for Ordering
     */
    val orderingConstructors = setOf("LT", "EQ", "GT")
    
    /**
     * All built-in constructors
     */
    val allBuiltinConstructors = optionConstructors + resultConstructors + 
                                listConstructors + orderingConstructors
    
    /**
     * Check if a type name is a built-in type
     */
    fun isBuiltinTypeName(typeName: String): Boolean {
        return typeName in allBuiltinTypeNames
    }
    
    /**
     * Check if a constructor name is a built-in constructor
     */
    fun isBuiltinConstructor(constructorName: String): Boolean {
        return constructorName in allBuiltinConstructors
    }
    
    /**
     * Get the type name for a built-in constructor
     */
    fun getTypeForConstructor(constructorName: String): String? {
        return when (constructorName) {
            in optionConstructors -> "Option"
            in resultConstructors -> "Result"
            in listConstructors -> "List"
            in orderingConstructors -> "Ordering"
            else -> null
        }
    }
    
    /**
     * Create built-in type instances
     */
    fun createOptionType(elementType: FunkType): FunkType.TypeApplication {
        return FunkType.TypeApplication("Option", listOf(elementType))
    }

    fun createResultType(valueType: FunkType, errorType: FunkType): FunkType.TypeApplication {
        return FunkType.TypeApplication("Result", listOf(valueType, errorType))
    }
    
    fun createListType(elementType: FunkType): FunkType.ListType {
        return FunkType.ListType(elementType)
    }
    
    fun createMapType(keyType: FunkType, valueType: FunkType): FunkType.MapType {
        return FunkType.MapType(keyType, valueType)
    }
    
    fun createSetType(elementType: FunkType): FunkType.SetType {
        return FunkType.SetType(elementType)
    }
}