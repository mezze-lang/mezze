package com.funk.language.typechecker.constraints

import com.funk.language.ast.*
import com.funk.language.typechecker.context.TypingContext

/**
 * Constraint-based type inference - Constraint types.
 *
 * Separates constraint GENERATION from constraint SOLVING.
 * This is the core of the new architecture.
 *
 * Design principles:
 * - Constraints are immutable data
 * - Generation phase: collect constraints
 * - Solving phase: unify constraints
 * - Validation phase: check results
 *
 * No more mixed concerns!
 */

/**
 * Type constraint - represents a requirement on types.
 *
 * Sealed interface ensures exhaustive pattern matching.
 * No catch-all else clauses!
 *
 * All constraints have a sourceLocation for error reporting.
 */
sealed interface Constraint {
    /**
     * The source location where this constraint was generated (if available).
     * Used for error reporting to show the user where a type error occurred.
     */
    val sourceLocation: SourceLocation?

    /**
     * Equality constraint: t1 must equal t2
     *
     * Generated from:
     * - Function application: arg type = param type
     * - Return statements: expr type = return type
     * - Assignment: rhs type = lhs type
     * - Binary operations: operand types must match
     */
    data class Equality(
        val left: FunkType,
        val right: FunkType,
        val context: String? = null,  // For error messages
        override val sourceLocation: SourceLocation? = null
    ) : Constraint

    /**
     * Ability constraint: type must have an ability
     *
     * Generated from:
     * - Ability method calls: Show.display requires Show 'a
     * - Operator usage: + requires NumAdd
     * - Pattern matching with Eq: requires Eq
     */
    data class AbilityRequired(
        val typeVar: TypeVar,
        val abilityName: String,
        val typeArgs: List<FunkType> = emptyList(),
        val context: String? = null,  // For error messages
        override val sourceLocation: SourceLocation? = null
    ) : Constraint

    /**
     * Effect constraint: expression uses an effect
     *
     * Generated from:
     * - Effect operations: Log.write uses Log effect
     * - Function calls: calling effectful function
     * - Handle expressions: handler must cover effects
     */
    data class EffectUsed(
        val effect: String,
        val expression: Expression? = null,  // For error messages
        val context: String? = null,
        override val sourceLocation: SourceLocation? = null
    ) : Constraint

    /**
     * Subsumption constraint: t1 is more general than t2
     *
     * Generated from:
     * - Polymorphic instantiation
     * - Subtyping (if we add it)
     */
    data class Subsumes(
        val general: FunkType,
        val specific: FunkType,
        val context: String? = null,
        override val sourceLocation: SourceLocation? = null
    ) : Constraint

    /**
     * Row constraint: record/variant must have field/constructor
     *
     * Generated from:
     * - Field access: record.field
     * - Variant construction: Some {val = x}
     * - Pattern matching: match on constructor
     */
    data class HasField(
        val recordType: FunkType,
        val fieldName: String,
        val fieldType: FunkType,
        val context: String? = null,
        override val sourceLocation: SourceLocation? = null
    ) : Constraint

    data class HasConstructor(
        val variantType: FunkType,
        val constructorName: String,
        val payloadType: FunkType?,
        val context: String? = null,
        override val sourceLocation: SourceLocation? = null
    ) : Constraint
}

/**
 * Result of constraint generation.
 *
 * Contains:
 * - The inferred type (may contain fresh type variables)
 * - List of constraints that must be satisfied
 * - Optional substitution hints
 */
data class ConstraintResult(
    val type: FunkType,
    val constraints: List<Constraint>,
    val effects: Set<String> = emptySet(),  // Effects used in this expression
    val abilityConstraints: List<Constraint.AbilityRequired> = emptyList(),  // Ability requirements
    val undefinedVariables: List<UndefinedVariable> = emptyList(),  // Undefined variables found during constraint generation
    val duplicateBindings: List<DuplicateBinding> = emptyList()  // Duplicate bindings found during constraint generation
) {
    /**
     * Information about an undefined variable.
     */
    data class UndefinedVariable(
        val name: String,
        val sourceLocation: SourceLocation?
    )

    /**
     * Information about a duplicate binding.
     */
    data class DuplicateBinding(
        val name: String,
        val sourceLocation: SourceLocation?
    )
    companion object {
        /**
         * Simple result with just a type (no constraints)
         */
        fun simple(type: FunkType): ConstraintResult {
            return ConstraintResult(type, emptyList())
        }

        /**
         * Combine multiple constraint results
         */
        fun combine(
            type: FunkType,
            results: List<ConstraintResult>
        ): ConstraintResult {
            return ConstraintResult(
                type = type,
                constraints = results.flatMap { it.constraints },
                effects = results.flatMap { it.effects }.toSet(),
                abilityConstraints = results.flatMap { it.abilityConstraints },
                undefinedVariables = results.flatMap { it.undefinedVariables },
                duplicateBindings = results.flatMap { it.duplicateBindings }
            )
        }

        /**
         * Add constraints to a result
         */
        fun ConstraintResult.withConstraints(
            newConstraints: List<Constraint>
        ): ConstraintResult {
            return copy(constraints = constraints + newConstraints)
        }

        /**
         * Add effects to a result
         */
        fun ConstraintResult.withEffects(
            newEffects: Set<String>
        ): ConstraintResult {
            return copy(effects = effects + newEffects)
        }

        /**
         * Add ability constraints to a result
         */
        fun ConstraintResult.withAbilities(
            newAbilities: List<Constraint.AbilityRequired>
        ): ConstraintResult {
            return copy(abilityConstraints = abilityConstraints + newAbilities)
        }
    }
}

/**
 * Constraint generation context.
 *
 * Passed down during constraint generation to track:
 * - Current typing context (variable bindings)
 * - Expected type (for bidirectional inference)
 * - Effect context (allowed effects)
 */
data class ConstraintContext(
    val typingContext: TypingContext,
    val expectedType: FunkType? = null,  // For bidirectional inference
    val allowedEffects: Set<String> = emptySet(),
    val currentFunction: String? = null,  // For better error messages
    val performerToEffect: Map<String, String> = emptyMap(),  // Maps handler names to effect names (e.g., Console -> Log)
    val abilityNames: Set<String> = emptySet(),  // Set of all ability names (e.g., Show, Eq)
    val effectNames: Set<String> = emptySet(),  // Set of all effect names (e.g., Log, FileSystem)
    val performContext: PerformExpression? = null,  // Current perform context for effect associated type substitution
    val abilityDeclarations: Map<String, AbilityDeclaration> = emptyMap(),  // All ability declarations for method type lookup
    val recordLiteralBindings: Set<String> = emptySet(),  // Variables bound to explicit record literals (for field access validation)
    val typeDeclarations: Map<String, TypeDeclaration> = emptyMap()  // Type declarations for validation only (NOT for general type resolution)
) {
    /**
     * Extend context with new variable binding
     */
    fun extend(name: String, type: FunkType): ConstraintContext {
        return copy(typingContext = typingContext.extendMono(name, type))
    }

    /**
     * Enter a new scope
     */
    fun enterScope(): ConstraintContext {
        return copy(typingContext = typingContext.enterScope())
    }

    /**
     * Set expected type (for bidirectional inference)
     */
    fun withExpectedType(type: FunkType): ConstraintContext {
        return copy(expectedType = type)
    }

    /**
     * Add allowed effects (inside handle block)
     */
    fun withAllowedEffects(effects: Set<String>): ConstraintContext {
        return copy(allowedEffects = allowedEffects + effects)
    }

    /**
     * Mark a variable as bound to a record literal
     * (for field access validation - matches OldTypeChecker.kt:1428-1429)
     */
    fun withRecordLiteralBinding(name: String): ConstraintContext {
        return copy(recordLiteralBindings = recordLiteralBindings + name)
    }
}
