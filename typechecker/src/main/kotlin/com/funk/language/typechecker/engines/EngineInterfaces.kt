package com.funk.language.typechecker.engines

import com.funk.language.ast.*
import com.funk.language.typechecker.support.TypeContext

/**
 * Interface contracts for the four core type checking engines.
 *
 * These interfaces enable dependency injection and break circular dependencies
 * between InferenceEngine, ValidationEngine, and ResolutionEngine.
 *
 * # Architecture
 *
 * The four engines are:
 * - **IUnificationEngine**: Type unification and constraint solving
 * - **IInferenceEngine**: Type inference for expressions
 * - **IValidationEngine**: Type validation and checking
 * - **IResolutionEngine**: Type alias and ability resolution
 *
 * # Dependency Graph
 *
 * ```
 * TypeChecker (facade)
 *     ├── IUnificationEngine (no dependencies on other engines)
 *     ├── IInferenceEngine (depends on: IUnificationEngine, IValidationEngine, IResolutionEngine)
 *     ├── IValidationEngine (depends on: IUnificationEngine, IInferenceEngine, IResolutionEngine)
 *     └── IResolutionEngine (depends on: IUnificationEngine, IInferenceEngine, IValidationEngine)
 * ```
 *
 * # Usage Pattern
 *
 * Engines accept interface dependencies in their constructors:
 *
 * ```kotlin
 * class InferenceEngine(
 *     private val typeContext: TypeContext,
 *     private val unificationEngine: IUnificationEngine,
 *     private val validationEngine: IValidationEngine?,  // Nullable during construction
 *     private val resolutionEngine: IResolutionEngine?   // Nullable during construction
 * ) : IInferenceEngine {
 *     // Implementation
 * }
 * ```
 *
 * Then wire them in TypeChecker:
 *
 * ```kotlin
 * val unificationEngine: IUnificationEngine = UnificationEngine(typeContext, constraintSolver)
 * val inferenceEngine = InferenceEngine(typeContext, unificationEngine, null, null)
 * val validationEngine = ValidationEngine(typeContext, unificationEngine, inferenceEngine, null)
 * val resolutionEngine = ResolutionEngine(typeContext, unificationEngine, inferenceEngine, validationEngine)
 *
 * // Wire back references
 * inferenceEngine.validationEngine = validationEngine
 * inferenceEngine.resolutionEngine = resolutionEngine
 * validationEngine.resolutionEngine = resolutionEngine
 * ```
 *
 * @see UnificationEngine
 * @see InferenceEngine
 * @see ValidationEngine
 * @see ResolutionEngine
 */

// ============================================================================
// Unification Engine Interface
// ============================================================================

/**
 * Interface for type unification operations.
 *
 * The unification engine handles:
 * - Type unification using Hindley-Milner algorithm
 * - Constraint solving
 * - Type variable substitution
 * - Type scheme generalization and instantiation
 * - Type equality checking
 *
 * This engine has no dependencies on other engines.
 */
interface IUnificationEngine {
    /**
     * Unify two types, updating the type context with constraints.
     *
     * @param actualType The actual inferred type
     * @param expectedType The expected type
     * @return true if unification succeeded, false otherwise
     */
    fun unifyTypes(actualType: FunkType, expectedType: FunkType): Boolean

    /**
     * Unify two types with explicit substitution tracking.
     *
     * @param type1 First type
     * @param type2 Second type
     * @param substitutions Map to track type variable substitutions
     * @throws UnificationException if unification fails
     */
    fun unifyTypesWithSubstitution(
        type1: FunkType,
        type2: FunkType,
        substitutions: MutableMap<TypeVar, FunkType>
    )

    /**
     * Apply a substitution to a type.
     *
     * @param type The type to apply substitution to
     * @param substitutions The substitution map
     * @return The type with substitutions applied
     */
    fun applySubstitution(type: FunkType, substitutions: Map<TypeVar, FunkType>): FunkType

    /**
     * Generalize a type into a type scheme by quantifying free type variables.
     *
     * @param type The type to generalize
     * @return A type scheme with quantified variables
     */
    fun generalize(type: FunkType): TypeScheme

    /**
     * Instantiate a type scheme with fresh type variables.
     *
     * @param scheme The type scheme to instantiate
     * @return The instantiated type
     */
    fun instantiateScheme(scheme: TypeScheme): FunkType

    /**
     * Check if two types are structurally equal.
     *
     * @param type1 First type
     * @param type2 Second type
     * @return true if types are structurally equal
     */
    fun areTypesStructurallyEqual(type1: FunkType, type2: FunkType): Boolean
}

// ============================================================================
// Inference Engine Interface
// ============================================================================

/**
 * Interface for type inference operations.
 *
 * The inference engine handles:
 * - Expression type inference
 * - Lambda parameter type inference
 * - Pattern type inference
 * - Effect and ability inference
 *
 * Dependencies: IUnificationEngine, IValidationEngine, IResolutionEngine
 */
interface IInferenceEngine {
    /**
     * Infer the type of an expression.
     *
     * @param expr The expression to infer
     * @param expectedType Optional expected type for bidirectional inference
     * @return The inferred type
     */
    fun inferType(expr: Expression, expectedType: FunkType? = null): FunkType

    /**
     * Infer field type from field name (simple heuristic).
     *
     * @param fieldName The field name
     * @return A fresh type variable for the field
     */
    fun inferFieldTypeFromName(fieldName: String): FunkType
}

// ============================================================================
// Validation Engine Interface
// ============================================================================

/**
 * Interface for type validation operations.
 *
 * The validation engine handles:
 * - Pattern matching exhaustiveness and reachability
 * - Ability constraint satisfaction
 * - Type annotation validation
 * - Effect and performer validation
 * - Arithmetic operation validation
 *
 * Dependencies: IUnificationEngine, IInferenceEngine, IResolutionEngine
 */
interface IValidationEngine {
    /**
     * Validate that match patterns are exhaustive and don't have unreachable cases.
     *
     * @param matchedType The type being matched
     * @param cases The match cases
     */
    fun validateMatchExhaustiveness(matchedType: FunkType, cases: List<MatchCase>)

    /**
     * Validate that a type satisfies ability constraints.
     *
     * @param type The type to validate
     * @param constraints The ability constraints
     * @param typeVar The type variable being constrained
     * @param location Source location for error reporting
     */
    fun validateAbilityConstraints(
        type: FunkType,
        constraints: Set<String>,
        typeVar: TypeVar,
        location: SourceLocation?
    )

    /**
     * Validate that an inferred type matches a user-provided annotation.
     *
     * @param inferredType The inferred type
     * @param annotatedType The annotated type
     * @param expr The expression being validated
     */
    fun validateTypeAgainstAnnotation(
        inferredType: FunkType,
        annotatedType: FunkType,
        expr: Expression
    )
}

// ============================================================================
// Resolution Engine Interface
// ============================================================================

/**
 * Interface for type resolution operations.
 *
 * The resolution engine handles:
 * - Type alias resolution and expansion
 * - Ability operation and implementation resolution
 * - Module value and declaration resolution
 * - Variant constructor lookup
 * - Type matching and supertype finding
 *
 * Dependencies: IUnificationEngine, IInferenceEngine, IValidationEngine
 */
interface IResolutionEngine {
    /**
     * Resolve a type alias to its definition.
     *
     * @param type The type to resolve
     * @return The resolved type with aliases expanded
     */
    fun resolveTypeAlias(type: FunkType): FunkType

    /**
     * Look up a variable's type in the context.
     *
     * @param name The variable name
     * @return The type if found, null otherwise
     */
    fun lookupVariable(name: String): FunkType?

    /**
     * Find the variant type for a constructor name.
     *
     * @param constructorName The constructor name
     * @return The variant type if found, null otherwise
     */
    fun findVariantConstructorType(constructorName: String): FunkType?

    /**
     * Find the closest matching name for error suggestions.
     *
     * @param input The input string
     * @param candidates Collection of candidates
     * @return The closest match, or null if none
     */
    fun findClosestName(input: String, candidates: Collection<String>): String?

    /**
     * Find common supertype for two types.
     *
     * @param type1 First type
     * @param type2 Second type
     * @return Common supertype if one exists, null otherwise
     */
    fun findCommonSupertype(type1: FunkType, type2: FunkType): FunkType?

    /**
     * Get the underlying variant type from a type that might be a TypeApplication or SuspendedType.
     *
     * For example: Option Int -> VariantType with constructors {Some, None}
     *
     * @param type The type to expand
     * @return The underlying VariantType, or null if not a variant
     */
    fun getUnderlyingVariantType(type: FunkType): FunkType.VariantType?
}
