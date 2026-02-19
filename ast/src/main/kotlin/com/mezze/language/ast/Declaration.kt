@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.mezze.language.ast

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * AST nodes for module-level declarations and program structure.
 *
 * This file contains:
 * - Program: Top-level program structure
 * - TypeCheckResult: Result of type checking
 * - ProgramItem: All module-level items (LetBinding, TypeDeclaration, etc.)
 * - LetBinding: Top-level and local variable bindings
 * - TypeDeclaration: Type alias and newtype definitions
 * - TypeDefinition: Record, Variant, and Newtype definitions
 * - Effect and Ability declarations: EffectDeclaration, ImplDeclaration, etc.
 * - TopLevelExpression: Expressions at module scope
 * - Module system: UseDeclaration, PubUseDeclaration
 *
 * Part of the refactored AST module - previously in MezzeAST.kt.
 */

// ============================================================================
// PROGRAM STRUCTURE
// ============================================================================

@Serializable
data class Program(
    val items: List<ProgramItem>
)

/**
 * Result of type checking a program
 * Separates user code (Program) from compiler artifacts (monomorphic instances)
 *
 * This follows the Rust approach:
 * - User AST contains only user-written code
 * - Monomorphic instances are compiler-internal, not part of the AST
 * - Users never see or write mangled names like "add$Int"
 */
@Serializable
data class TypeCheckResult(
    val program: Program,  // User's original AST with type information
    val monomorphicInstances: Map<String, LetBinding> = emptyMap()  // Compiler-generated specialized functions (e.g., "add$Int" -> specialized binding)
)

@Serializable
@JsonClassDiscriminator("itemKind")
sealed interface ProgramItem

// ============================================================================
// LET BINDINGS
// ============================================================================

@Serializable
data class LetBinding(
    val pattern: Pattern,                         // Pattern for destructuring (VariablePattern for simple bindings)
    val visibility: Visibility = Visibility.PRIVATE,
    val typeAnnotation: TypeAnnotation? = null,  // From parser
    val inferredType: MezzeType? = null,          // From typechecker (monomorphic, instantiated type)
    val typeScheme: TypeScheme? = null,          // From typechecker (polymorphic type with constraints)
    val expression: Expression,

    // Monomorphization tracking
    // If this is a monomorphic instance of a generic function, these fields track it
    val isMonomorphicInstance: Boolean = false,  // true if this is a specialized version
    val originalFunctionName: String? = null,    // name of the original generic function
    val monomorphicTypeArgs: Map<String, MezzeType> = emptyMap(),  // type arguments for this instance

    // Content-addressable hash (populated by TypeChecker)
    // This is a cryptographic hash of the definition's structure, used for:
    // - Caching: same hash = same code, skip re-processing
    // - Referencing: identify definitions independently of their names
    val contentHash: String? = null
) : ProgramItem {
    /**
     * For backward compatibility - returns the variable name if pattern is a simple VariablePattern.
     * For complex patterns (RecordPattern), this throws. Use pattern directly for full support.
     */
    val variableName: String
        get() = (pattern as? VariablePattern)?.name
            ?: throw IllegalStateException(
                "LetBinding has a complex pattern (${pattern.kindDescription()}). " +
                "Use pattern directly for destructuring support."
            )

    /**
     * Check if this is a simple variable binding (not a destructuring pattern)
     */
    val isSimpleBinding: Boolean
        get() = pattern is VariablePattern

    companion object {
        /**
         * Create a simple let binding with just a variable name (backward compatibility)
         */
        fun simple(
            name: String,
            expression: Expression,
            visibility: Visibility = Visibility.PRIVATE,
            typeAnnotation: TypeAnnotation? = null,
            inferredType: MezzeType? = null,
            typeScheme: TypeScheme? = null
        ) = LetBinding(
            VariablePattern(name),
            visibility,
            typeAnnotation,
            inferredType,
            typeScheme,
            expression
        )
    }
}

// ============================================================================
// TYPE DECLARATIONS
// ============================================================================

@Serializable
data class TypeDeclaration(
    val name: String,
    val visibility: Visibility = Visibility.PRIVATE,
    val typeParams: List<String> = emptyList(),
    val definition: TypeDefinition,
    val inferredType: MezzeType? = null,           // From typechecker
    val sourceLocation: SourceLocation? = null,   // For error reporting
    val contentHash: String? = null               // Content-addressable hash
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

/**
 * Newtype definition - zero-cost wrapper with a single constructor.
 *
 * Newtypes create distinct types at compile time but are erased at runtime.
 * The constructor is private to the defining module (not exported even if pub).
 *
 * Examples:
 *   newtype Email = Email String
 *   newtype UserId = UserId Int
 *   newtype Point = Point { x: Float, y: Float }
 */
@Serializable
data class NewtypeDefinition(
    val constructor: String,        // Constructor name (e.g., "Email")
    val wrappedType: TypeAnnotation // Type being wrapped (e.g., String)
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
// EFFECT DECLARATIONS
// ============================================================================

@Serializable
data class EffectDeclaration(
    val name: String,
    val visibility: Visibility = Visibility.PRIVATE,
    val operations: List<EffectOperation>,
    val associatedTypes: List<String> = emptyList(),  // Associated types from 'having 'state, 'error'
    val inferredType: MezzeType? = null,           // From typechecker
    val sourceLocation: SourceLocation? = null,   // For error reporting
    val contentHash: String? = null               // Content-addressable hash
) : ProgramItem {
    // Backward compatibility
    val associatedType: String? get() = associatedTypes.firstOrNull()
}

@Serializable
data class EffectOperation(
    val name: String,
    val paramType: TypeAnnotation,
    val returnType: TypeAnnotation,
    val effects: List<String> = emptyList()
)

@Serializable
data class ImplDeclaration(
    val effectName: String,
    val performerName: String,
    val performerParams: List<PerformerParameter> = emptyList(), // For parameterized performers like FileLogger {path: String}
    val associatedTypeBindings: Map<String, TypeAnnotation> = emptyMap(), // Bindings from 'having 'state = Int, 'error = String'
    val operations: List<PerformerOperation>,
    val inferredType: MezzeType? = null,           // From typechecker
    val sourceLocation: SourceLocation? = null,   // For error reporting
    val contentHash: String? = null               // Content-addressable hash
) : ProgramItem {
    // Backward compatibility
    val associatedTypeBinding: Pair<String, TypeAnnotation>?
        get() = associatedTypeBindings.entries.firstOrNull()?.let { it.key to it.value }
}

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

// ============================================================================
// ABILITY DECLARATIONS
// ============================================================================

@Serializable
data class AbilityDeclaration(
    val name: String,
    val visibility: Visibility = Visibility.PRIVATE,
    val typeParams: List<String>,                // Multiple type parameters like ['a, 'b]
    val associatedTypes: List<String> = emptyList(),  // Associated types from 'having 'item, 'iter'
    val constraints: List<AbilityConstraint> = emptyList(),
    val dependencies: List<AbilityDependency> = emptyList(),  // Ability dependencies (e.g., Ord depends on Eq)
    val operations: List<AbilityOperation>,
    val inferredType: MezzeType? = null,           // From typechecker
    val sourceLocation: SourceLocation? = null,   // For error reporting
    val contentHash: String? = null               // Content-addressable hash
) : ProgramItem {
    // Backward compatibility
    val associatedType: String? get() = associatedTypes.firstOrNull()
}

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
data class ImplAbilityDeclaration(
    val abilityName: String,
    val abilityModulePath: List<String> = emptyList(), // Module path for qualified ability names like codec::EncodeFormat
    val targetTypes: List<TypeAnnotation>,       // Multiple concrete types like [String, Int]
    val forallVars: List<String> = emptyList(),  // Type variables from 'forall 'a 'b.' quantification
    val associatedTypeBindings: Map<String, TypeAnnotation> = emptyMap(), // Bindings from 'having 'item = Int, 'iter = MyIter'
    val operations: List<ImplAbilityOperation>,
    val inferredType: MezzeType? = null,           // From typechecker
    val sourceLocation: SourceLocation? = null,   // For error reporting
    val contentHash: String? = null               // Content-addressable hash
) : ProgramItem {
    // Full qualified ability name
    val qualifiedAbilityName: String
        get() = if (abilityModulePath.isEmpty()) abilityName else "${abilityModulePath.joinToString("::")}::$abilityName"
    // Backward compatibility
    val associatedTypeBinding: Pair<String, TypeAnnotation>?
        get() = associatedTypeBindings.entries.firstOrNull()?.let { it.key to it.value }
}

@Serializable
data class ImplAbilityOperation(
    val name: String,
    val paramType: TypeAnnotation?,  // Optional - can be inferred from ability declaration
    val returnType: TypeAnnotation?, // Optional - can be inferred from ability declaration
    val implementation: Expression
)

// ============================================================================
// TOP-LEVEL EXPRESSIONS
// ============================================================================

@Serializable
data class TopLevelExpression(
    val expression: Expression
) : ProgramItem

// ============================================================================
// MODULE SYSTEM
// ============================================================================

@Serializable
data class UseDeclaration(
    val path: UsePath,
    val alias: String? = null,
    val sourceLocation: SourceLocation? = null
) : ProgramItem

@Serializable
data class PubUseDeclaration(
    val path: UsePath,
    val alias: String? = null,
    val sourceLocation: SourceLocation? = null
) : ProgramItem
