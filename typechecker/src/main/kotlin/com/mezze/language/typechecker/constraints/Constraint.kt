package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.ModulePath
import com.mezze.language.parsedast.Loc
import com.mezze.language.ast.TypeVar
import com.mezze.language.parsedast.ParsedPerform
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.dependencies.DefinitionRef
import com.mezze.language.typedast.Hash

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
 * Structured error context for generating user-friendly type error messages.
 *
 * Instead of parsing unstructured context strings, this sealed class provides
 * typed context information that can be used to generate clear error messages.
 */
sealed class ErrorContext {
    /** Fallback for simple string context (backwards compatibility) */
    data class Simple(val message: String) : ErrorContext()

    /** Function application: argument type must match parameter type */
    data class FunctionArgument(
        val functionName: String?,
        val argumentName: String?,      // Name of the argument (from record field or param name)
        val parameterIndex: Int = 0     // 0-indexed position
    ) : ErrorContext()

    /** Type annotation: expression type must match annotation */
    data class TypeAnnotation(
        val bindingName: String,
        val isReturnType: Boolean = false
    ) : ErrorContext()

    /** Field access: record must have field with matching type */
    data class FieldAccess(
        val fieldName: String,
        val recordExpression: String? = null
    ) : ErrorContext()

    /** Pattern matching: scrutinee type must match pattern */
    data class PatternMatch(
        val patternDescription: String,
        val constructorName: String? = null
    ) : ErrorContext()

    /** Binary operation: operand types must be compatible */
    data class BinaryOperation(
        val operator: String,
        val isLeftOperand: Boolean
    ) : ErrorContext()

    /** Seq literal: all elements must have same type */
    data class SeqElement(val elementIndex: Int) : ErrorContext()

    /** Return type: expression type must match function return type */
    data class ReturnType(val functionName: String?) : ErrorContext()

    /** String interpolation: expression must be convertible to String */
    data class StringInterpolation(val expressionIndex: Int) : ErrorContext()
}

/**
 * Type constraint - represents a requirement on types.
 *
 * Sealed interface ensures exhaustive pattern matching.
 * No catch-all else clauses!
 *
 * All constraints have a loc for error reporting.
 */
sealed interface Constraint {
    /**
     * The source location where this constraint was generated (if available).
     * Used for error reporting to show the user where a type error occurred.
     */
    val loc: Loc?

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
        val left: MezzeType,
        val right: MezzeType,
        val context: String? = null,  // For error messages (backwards compat)
        override val loc: Loc? = null,
        /** True if this constraint was generated from a match case (allows variant merging) */
        val isMatchCaseContext: Boolean = false,
        /** Structured error context for better error messages */
        val errorContext: ErrorContext? = null
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
        val abilityName: String,  // For error messages and fallback lookup
        val abilityHash: String? = null,  // Content hash — preferred for impl resolution
        val typeArgs: List<MezzeType> = emptyList(),
        val context: String? = null,  // For error messages
        override val loc: Loc? = null
    ) : Constraint

    /**
     * Associated type resolution constraint for abilities.
     *
     * When an ability has associated types (e.g., `Container 'c having 'item`),
     * this constraint ensures that the associated type variables are unified
     * with their concrete bindings from the matching impl.
     *
     * Example: For `Container.get {container = intArray}` where `intArray: [Int]`
     * and `impl Container for [Int] having 'item = Int`:
     * - primaryTypeVar is resolved to [Int]
     * - associatedTypeVars = {'item -> TypeVar}
     * - Once [Int] is known, we look up the impl and unify 'item with Int
     */
    data class AssociatedTypeResolution(
        val abilityName: String,
        val primaryTypeVar: TypeVar,
        val associatedTypeVars: Map<String, TypeVar>,  // Associated type name -> type variable to unify
        val context: String? = null,
        override val loc: Loc? = null
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
        val context: String? = null,
        override val loc: Loc? = null
    ) : Constraint

    /**
     * Subsumption constraint: t1 is more general than t2
     *
     * Generated from:
     * - Polymorphic instantiation
     * - Subtyping (if we add it)
     */
    data class Subsumes(
        val general: MezzeType,
        val specific: MezzeType,
        val context: String? = null,
        override val loc: Loc? = null
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
        val recordType: MezzeType,
        val fieldName: String,
        val fieldType: MezzeType,
        val context: String? = null,
        override val loc: Loc? = null,
        /** If true, disallow field accumulation - field must exist exactly. Used for record literal field access. */
        val strictFieldCheck: Boolean = false
    ) : Constraint

    data class HasConstructor(
        val variantType: MezzeType,
        val constructorName: String,
        val payloadType: MezzeType?,
        val context: String? = null,
        override val loc: Loc? = null,
        /** True if this constraint came from pattern matching (should create closed variant) */
        val isPatternContext: Boolean = false
    ) : Constraint

    /**
     * Constraint that marks a type as "must be open" (have a row variable).
     *
     * Generated when a match expression has a wildcard pattern, indicating
     * the scrutinee type could have additional constructors beyond those
     * explicitly matched.
     *
     * @param type The type that should be open
     * @param bindingName The name of the binding containing this match (used for per-binding open tracking)
     * @param context Human-readable context
     */
    data class MustBeOpen(
        val type: MezzeType,
        val bindingName: String? = null,
        val context: String? = null,
        override val loc: Loc? = null
    ) : Constraint

    /**
     * Constraint for exhaustive record pattern matching.
     *
     * Generated when a closed record pattern (no ...) is used, requiring
     * that all fields of the record type are covered by the pattern.
     *
     * @param recordType The record type being matched
     * @param patternFields The field names that appear in the pattern
     * @param context Human-readable context
     */
    data class RecordPatternExhaustive(
        val recordType: MezzeType,
        val patternFields: Set<String>,
        val context: String? = null,
        override val loc: Loc? = null
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
    val type: MezzeType,
    val constraints: List<Constraint>,
    val effects: Set<String> = emptySet(),  // Effects used in this expression (simple names without associated types)
    val effectInstances: Set<MezzeType.EffectInstance> = emptySet(),  // Effects with associated type bindings
    val performed: Boolean = false,  // True if effects were performed via 'perform' expression
    val abilityConstraints: List<Constraint.AbilityRequired> = emptyList(),  // Ability requirements
    val associatedTypeConstraints: List<Constraint.AssociatedTypeResolution> = emptyList(),  // Associated type resolution constraints
    val undefinedVariables: List<UndefinedVariable> = emptyList(),  // Undefined variables found during constraint generation
    val duplicateBindings: List<DuplicateBinding> = emptyList(),  // Duplicate bindings found during constraint generation
    val tailCalls: Set<Loc> = emptySet(),  // TCO: Source locations of self-recursive tail calls
    val usedVariables: Set<String> = emptySet(),  // Variables that were referenced in this expression
    val unusedVariables: List<UnusedVariable> = emptyList(),  // Unused pattern bindings (warnings)
    val patternWarnings: List<String> = emptyList(),  // Warnings from pattern analysis (e.g., float literal patterns)
    val referencedBindings: Set<DefinitionRef.ByName> = emptySet(),  // Module-qualified references for dependency tracking
    val methodCallResolutions: Map<Pair<Loc, String>, MethodCallInfo> = emptyMap(),  // Method call resolutions keyed by (ParsedFunctionCall loc, methodName)
    // Per-call-site return type — keyed by the ParsedFunctionCall's loc.
    // Set by the constraint generator to the fresh type var that gets unified
    // with the call's actual return type. After the substitution is applied
    // this carries the monomorphized return type that the TypedAstBuilder can
    // stamp on `TypedCall.type`. Without this, qualified-import calls (like
    // `seq::filter { ... }`) only know the generic scheme of their function
    // reference (`{xs:[a], p: a->Bool} -> [a]`) and the call's `.type` falls
    // back to the surrounding contextType (`unknown`) — which then breaks LSP
    // per-segment chain inlay hints in pipe / dot-chain expressions.
    val callReturnTypes: Map<Loc, MezzeType> = emptyMap()
) {
    /**
     * Information about a resolved method call (ability method or inherent method).
     *
     * Stored during constraint generation so the TypedAstBuilder can emit proper
     * TypedCall nodes instead of TypedFieldAccess for `value.method { args }` syntax.
     */
    data class MethodCallInfo(
        val abilityName: String?,        // null for inherent methods
        val methodName: String,
        val methodHash: String?,         // Content hash of the resolved method (from impl)
        val receiverFieldName: String,   // The field name the receiver maps to (e.g., "it" for ability methods)
        val isInherentMethod: Boolean = false,
        val implContentHash: Hash? = null,  // Content hash of the impl declaration (for inherent methods)
        val implStartLine: Int = 0,      // Loc of impl declaration — for computing TypedAbilityImpl hash
        val implStartCol: Int = 0,
        val implSourceFile: String = "",  // Source file of impl declaration — disambiguates same line/col in different files
        // The function type at the call site so TypedAstBuilder can stamp it on
        // the TypedCall (otherwise the call's `.type` falls back to the
        // surrounding contextType — `unknown` for chained method calls). LSP
        // chain-segment inlay hints rely on this being set.
        val methodFunctionType: com.mezze.language.ast.MezzeType? = null
    )
    /**
     * Information about an undefined variable.
     */
    data class UndefinedVariable(
        val name: String,
        val loc: Loc?,
        val qualifier: ModulePath? = null,  // Structured module qualifier (if any)
        val isQualified: Boolean = false    // True if this was a qualified reference like module::name
    )

    /**
     * Information about a duplicate binding.
     */
    data class DuplicateBinding(
        val name: String,
        val loc: Loc?
    )

    /**
     * Information about an unused variable binding (warning).
     */
    data class UnusedVariable(
        val name: String,
        val loc: Loc?
    )
    companion object {
        /**
         * Simple result with just a type (no constraints)
         */
        fun simple(type: MezzeType): ConstraintResult {
            return ConstraintResult(type, emptyList())
        }

        /**
         * Combine multiple constraint results.
         * OPTIMIZATION: Short-circuit empty collections to avoid unnecessary allocations.
         */
        fun combine(
            type: MezzeType,
            results: List<ConstraintResult>
        ): ConstraintResult {
            // OPTIMIZATION: Check if any result has effectInstances before collecting
            val hasEffectInstances = results.any { it.effectInstances.isNotEmpty() }

            return ConstraintResult(
                type = type,
                constraints = results.flatMap { it.constraints },
                effects = results.flatMap { it.effects }.toSet(),
                effectInstances = if (hasEffectInstances) results.flatMap { it.effectInstances }.toSet() else emptySet(),
                abilityConstraints = results.flatMap { it.abilityConstraints },
                associatedTypeConstraints = results.flatMap { it.associatedTypeConstraints },
                undefinedVariables = results.flatMap { it.undefinedVariables },
                duplicateBindings = results.flatMap { it.duplicateBindings },
                tailCalls = results.flatMap { it.tailCalls }.toSet(),
                usedVariables = results.flatMap { it.usedVariables }.toSet(),
                unusedVariables = results.flatMap { it.unusedVariables },
                patternWarnings = results.flatMap { it.patternWarnings },
                referencedBindings = results.flatMap { it.referencedBindings }.toSet(),
                methodCallResolutions = results.fold(emptyMap()) { acc, r -> acc + r.methodCallResolutions },
                callReturnTypes = results.fold(emptyMap()) { acc, r -> acc + r.callReturnTypes }
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
         * Add effect instances to a result.
         * OPTIMIZATION: Short-circuit if new instances are empty.
         */
        fun ConstraintResult.withEffectInstances(
            newEffectInstances: Set<MezzeType.EffectInstance>
        ): ConstraintResult {
            return if (newEffectInstances.isEmpty()) this
                   else copy(effectInstances = effectInstances + newEffectInstances)
        }

        /**
         * Add ability constraints to a result
         */
        fun ConstraintResult.withAbilities(
            newAbilities: List<Constraint.AbilityRequired>
        ): ConstraintResult {
            return copy(abilityConstraints = abilityConstraints + newAbilities)
        }

        /**
         * TCO: Add a tail call location to a result
         */
        fun ConstraintResult.withTailCall(
            location: Loc
        ): ConstraintResult {
            return copy(tailCalls = tailCalls + location)
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
    val expectedType: MezzeType? = null,  // For bidirectional inference
    val allowedEffects: Set<String> = emptySet(),
    val currentFunction: String? = null,  // For better error messages
    val performerToEffect: Map<String, String> = emptyMap(),  // Maps handler names to effect names (e.g., Console -> Log)
    val performerAddedEffects: Map<String, Set<String>> = emptyMap(),  // Maps handler names to effects they ADD (e.g., FileLogger -> {FileSystem})
    val abilityNames: Set<String> = emptySet(),  // Set of all ability names (e.g., Show, Eq)
    val effectNames: Set<String> = emptySet(),  // Set of all effect names (e.g., Log, FileSystem)
    val effectDeclarations: Map<String, com.mezze.language.parsedast.ParsedEffectDecl> = emptyMap(),  // Effect declarations (for associated type lookup)
    val performContext: ParsedPerform? = null,  // Current perform context for effect associated type substitution
    val abilityDeclarations: Map<String, com.mezze.language.parsedast.ParsedAbilityDecl> = emptyMap(),  // All ability declarations for method type lookup
    val recordLiteralBindings: Set<String> = emptySet(),  // Variables bound to explicit record literals (for field access validation)
    val typeDeclarations: Map<String, com.mezze.language.parsedast.ParsedTypeDecl> = emptyMap(),  // Type declarations for validation only (NOT for general type resolution)
    val currentBindingName: String? = null,  // The name of the binding being generated (for per-binding open variant tracking)

    // TCO: Name of the enclosing function for self-recursion detection
    val enclosingFunctionName: String? = null,
    // TCO: True when current expression is in tail position
    val inTailPosition: Boolean = false
) {
    /**
     * Extend context with new variable binding
     */
    fun extend(name: String, type: MezzeType): ConstraintContext {
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
    fun withExpectedType(type: MezzeType): ConstraintContext {
        return copy(expectedType = type)
    }

    /**
     * Clear expected type (for intermediate expressions in do blocks)
     */
    fun clearExpectedType(): ConstraintContext {
        return copy(expectedType = null)
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

    /**
     * TCO: Set the enclosing function name for self-recursion detection
     */
    fun withEnclosingFunction(name: String): ConstraintContext {
        return copy(enclosingFunctionName = name)
    }

    /**
     * TCO: Mark current position as tail position
     */
    fun inTailPosition(): ConstraintContext {
        return copy(inTailPosition = true)
    }

    /**
     * TCO: Mark current position as NOT tail position
     */
    fun notInTailPosition(): ConstraintContext {
        return copy(inTailPosition = false)
    }
}
