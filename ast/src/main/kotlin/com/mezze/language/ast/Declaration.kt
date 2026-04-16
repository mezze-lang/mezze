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

/**
 * A comment in the source code, preserved for the formatter.
 *
 * @param text The full comment text including the '#' character
 * @param line The line number where the comment appears (1-indexed)
 * @param column The column number where the comment starts (1-indexed)
 * @param isTrailing True if the comment is on the same line as code (trailing comment),
 *                   false if it's on its own line (leading comment)
 */
@Serializable
data class Comment(
    val text: String,
    val line: Int,
    val column: Int,
    val isTrailing: Boolean = false
)

@Serializable
data class Program(
    val items: List<ProgramItem>,
    val comments: List<Comment> = emptyList()
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
/**
 * A resolved import with type information, for IDE support.
 */
@Serializable
data class ResolvedImport(
    val localName: String,           // Name as used in the importing file
    val originalName: String,        // Name in the source module
    val modulePath: String,          // Source module path (e.g., "std::effects::log")
    val type: MezzeType,             // Resolved type of the import
    val kind: ImportKind             // What kind of thing was imported
)

@Serializable
enum class ImportKind {
    FUNCTION,   // A let binding / function
    TYPE,       // A type declaration
    EFFECT,     // An effect declaration
    ABILITY,    // An ability declaration
    PERFORMER   // An effect performer (impl)
}

@Serializable
data class TypeCheckResult(
    @Deprecated("v1 Program is always empty — use typedModule instead")
    val program: Program = Program(emptyList()),
    val resolvedImports: List<ResolvedImport> = emptyList(),
    val warnings: List<TypeCheckWarning> = emptyList(),
    val importedAbilities: Map<String, AbilityDeclaration> = emptyMap(),
    val importedEffects: Map<String, EffectDeclaration> = emptyMap(),
    val loweredFunctions: List<com.mezze.language.truffleir.FunctionIR> = emptyList(),
    val typedModule: com.mezze.language.typedast.TypedModule? = null,  // The real typechecker output
    /** Performer record info for effect handler registration: List of [performerHash, effectHash, op1Hash, op1Name, ...] */
    val performerRecords: List<Array<String>> = emptyList(),
    /** Loaded modules (from import resolution) — includes their v2 TypedModules for compilation */
    val loadedModules: Map<String, com.mezze.language.typedast.TypedModule> = emptyMap()
) {
    /** Get inferred type of a top-level binding by name */
    fun getVariableType(name: String): MezzeType? {
        // Primary: TypedModule (v2 pipeline)
        val v2Type = typedModule?.decls
            ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            ?.find { it.name == name }
            ?.scheme?.type
        if (v2Type != null && v2Type !is MezzeType.VarType) return v2Type
        return null
    }
}

/**
 * A warning from type checking (not a fatal error).
 */
@Serializable
data class TypeCheckWarning(
    val message: String,
    val sourceLocation: SourceLocation? = null
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
 *   newtype UserId = UserId Int deriving Eq
 *   newtype Point = Point { x: Float, y: Float } deriving Eq, Ord
 */
@Serializable
data class NewtypeDefinition(
    val constructor: String,        // Constructor name (e.g., "Email")
    val wrappedType: TypeAnnotation  // Type being wrapped (e.g., String, { x: Float, y: Float })
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
    val operations: List<EffectOperation>,
    val associatedTypes: List<String> = emptyList(),  // Associated types from 'having 'state, 'error'
    val inferredType: MezzeType? = null,           // From typechecker
    val sourceLocation: SourceLocation? = null,   // For error reporting
    val contentHash: String? = null               // Content-addressable hash
) : ProgramItem {
    // Effects are always public - no visibility field needed
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
    val effect: QualifiedRef,
    val performerName: String,
    val performerParams: List<PerformerParameter> = emptyList(), // For parameterized performers like FileLogger {path: String}
    val associatedTypeBindings: Map<String, TypeAnnotation> = emptyMap(), // Bindings from 'having 'state = Int, 'error = String'
    val operations: List<PerformerOperation>,
    val inferredType: MezzeType? = null,           // From typechecker
    val sourceLocation: SourceLocation? = null,   // For error reporting
    val contentHash: String? = null               // Content-addressable hash
) : ProgramItem

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
    val typeParams: List<String>,                // ALL type parameters including receiver, e.g. ['b, 'a] for "ability Convert 'b for 'a"
    val receiverTypeParam: String? = null,       // The 'for' type param that becomes `it` in impl bodies. e.g. "'a" in "ability Show for 'a"
    val associatedTypes: List<String> = emptyList(),  // Associated types from 'having 'item, 'iter'
    val constraints: List<AbilityConstraint> = emptyList(),
    val dependencies: List<AbilityDependency> = emptyList(),  // Ability dependencies (e.g., Ord depends on Eq)
    val operations: List<AbilityOperation>,
    val inferredType: MezzeType? = null,           // From typechecker
    val sourceLocation: SourceLocation? = null,   // For error reporting
    val contentHash: String? = null               // Content-addressable hash
) : ProgramItem {
    // Abilities are always public - no visibility field needed
    // Backward compatibility
    val associatedType: String? get() = associatedTypes.firstOrNull()
}

@Serializable
data class AbilityConstraint(
    val abilityName: String,
    val typeParam: String,
    val typeArgs: List<MezzeType> = emptyList()  // Additional type arguments for multi-parameter abilities (e.g., Json in Encode Json 'a)
)

/**
 * Ability constraint in function type signatures.
 *
 * Examples:
 * - Simple: `with Eq 'a` → FunctionAbilityConstraint("Eq", [IdentifierTypeAnnotation("'a")])
 * - With associated types: `with Container 'c having 'item = Int`
 *   → FunctionAbilityConstraint("Container", [...], {'item -> IdentifierTypeAnnotation("Int")})
 */
@Serializable
data class FunctionAbilityConstraint(
    val abilityName: String,
    val typeArgs: List<TypeAnnotation> = emptyList(),  // Type arguments for the ability
    val associatedTypeBindings: Map<String, TypeAnnotation> = emptyMap()  // Associated type bindings from 'having' clause
) {
    /** True if this constraint has any associated type bindings specified */
    val hasAssociatedTypes: Boolean get() = associatedTypeBindings.isNotEmpty()
}

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

/**
 * An operation declared in an ability.
 *
 * Operations can optionally declare effects they perform.
 * If no effects are specified, the operation is pure.
 *
 * Examples:
 * - Pure: `len: {c: 'c} -> Int`
 * - With effect: `get: {c: 'c, i: Int} -> Option 'elem <Mutation>`
 * - With multiple effects: `read: {path: String} -> Bytes <IO, Exception>`
 */
@Serializable
data class AbilityOperation(
    val name: String,
    val paramType: TypeAnnotation,
    val returnType: TypeAnnotation,
    val effects: EffectRowAnnotation? = null,  // Optional effects the operation performs
    val abilityConstraints: List<FunctionAbilityConstraint> = emptyList()  // Method-level ability constraints
) {
    /** Returns true if this operation declares any effects */
    val hasEffects: Boolean get() = effects != null && effects.allEffectNames.isNotEmpty()

    /** Returns true if this operation is pure (no effects) */
    val isPure: Boolean get() = !hasEffects
}

/**
 * Distinguishes named ability impls from inherent impls.
 * - Named: `impl Show for Int where { ... }` — implements an ability
 * - Inherent: `impl Int where { ... }` — attaches methods directly to a type
 */
@Serializable
sealed class ImplTarget {
    @Serializable
    data class Named(val ability: QualifiedRef) : ImplTarget()
    @Serializable
    data object Inherent : ImplTarget()
}

@Serializable
data class ImplAbilityDeclaration(
    val target: ImplTarget,
    val targetTypes: List<TypeAnnotation>,       // Multiple concrete types like [String, Int]
    val abilityTypeArgs: List<TypeAnnotation> = emptyList(), // Type args on the ability: impl Eq 'a for ['a] — the 'a
    val forallVars: List<String> = emptyList(),  // Type variables from 'forall 'a 'b.' quantification
    val associatedTypeBindings: Map<String, TypeAnnotation> = emptyMap(), // Bindings from 'having 'item = Int, 'iter = MyIter'
    val operations: List<ImplAbilityOperation>,
    val inferredType: MezzeType? = null,           // From typechecker
    val sourceLocation: SourceLocation? = null,   // For error reporting
    val contentHash: String? = null               // Content-addressable hash
) : ProgramItem {
    /** Convenience: get the ability name for named impls, or null for inherent impls */
    val abilityName: String? get() = (target as? ImplTarget.Named)?.ability?.localName

    /** Convenience: get the ability QualifiedRef for named impls */
    val abilityRef: QualifiedRef? get() = (target as? ImplTarget.Named)?.ability

    /** Whether this is an inherent impl (no ability) */
    val isInherent: Boolean get() = target is ImplTarget.Inherent

    /**
     * All type arguments combined: concrete abilityTypeArgs (before 'for') + targetTypes (after 'for').
     * Maps positionally to the ability's type params (extra params first, self last).
     *
     * For `impl Into Str for Int`: abilityTypeArgs=[Str], targetTypes=[Int] → allTypeArgs=[Str, Int]
     * For `impl Into for Str Int`: abilityTypeArgs=[], targetTypes=[Str, Int] → allTypeArgs=[Str, Int]
     * For `impl Eq 'a for ['a]`: abilityTypeArgs=['a](var), targetTypes=[['a]] → allTypeArgs=[['a]]
     *
     * Type variable abilityTypeArgs are quantifiers (not type arguments), so they are excluded.
     */
    val allTypeArgs: List<TypeAnnotation> get() {
        val concreteArgs = abilityTypeArgs.filter {
            it !is IdentifierTypeAnnotation || !it.name.startsWith("'")
        }
        return concreteArgs + targetTypes
    }
}

@Serializable
data class ImplAbilityOperation(
    val name: String,
    val paramType: TypeAnnotation?,  // Optional - can be inferred from ability declaration
    val returnType: TypeAnnotation?, // Optional - can be inferred from ability declaration
    val implementation: Expression,
    val abilityConstraints: List<FunctionAbilityConstraint> = emptyList()  // Method-level ability constraints
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
