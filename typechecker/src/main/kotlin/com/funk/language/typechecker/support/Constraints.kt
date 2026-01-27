package com.funk.language.typechecker.support

import com.funk.language.ast.FunkType
import com.funk.language.ast.TypeVar
import com.funk.language.ast.SourceLocation
import com.funk.language.ast.Expression

/**
 * Represents a type constraint in the constraint-based type inference system.
 *
 * Constraints are generated during type inference and solved by the ConstraintSolver
 * to produce type substitutions (unification). This sealed interface ensures exhaustive
 * pattern matching when processing constraints.
 *
 * # Constraint Types
 *
 * - **Equality**: Two types must be unified (T1 = T2)
 * - **Instance**: A type must be an instance of a type scheme (for polymorphism)
 * - **AbilityConstraint**: A type variable must implement an ability (type class)
 * - **AnnotationConstraint**: An inferred type must match a declared type annotation
 *
 * # Usage
 *
 * Constraints are collected during type inference and passed to the ConstraintSolver:
 *
 * ```kotlin
 * val constraints = mutableListOf<Constraint>()
 * constraints.add(Constraint.Equality(typeA, typeB, expr.sourceLocation))
 * val substitution = solver.solve(constraints)
 * ```
 *
 * # Source Location Tracking
 *
 * All constraint types now include an optional sourceLocation field for better
 * error reporting. This allows the solver to point to the exact code location
 * where a constraint was generated when reporting type errors.
 */
sealed class Constraint {
    /**
     * The source location where this constraint was generated (if available).
     * Used for error reporting to show the user where a type error occurred.
     */
    abstract val sourceLocation: SourceLocation?

    /**
     * Equality constraint: T1 = T2
     *
     * Represents that two types must be unified to the same type.
     * This is the most common constraint type, generated whenever we need
     * two types to match.
     *
     * # Examples
     *
     * - Function application: `f x` generates `paramType(f) = typeOf(x)`
     * - Let binding: `let x = 5` generates `typeOf(x) = Int`
     * - Binary operators: `a + b` generates `typeOf(a) = typeOf(b)`
     *
     * @property left The first type to unify
     * @property right The second type to unify
     * @property sourceLocation Location in source code where this constraint was generated
     */
    data class Equality(
        val left: FunkType,
        val right: FunkType,
        override val sourceLocation: SourceLocation? = null
    ) : Constraint() {
        override fun toString(): String = "${prettyPrint(left)} = ${prettyPrint(right)}"
    }

    /**
     * Instance constraint: T is an instance of TypeScheme
     *
     * Represents that a type must be an instance of a polymorphic type scheme.
     * This is used for let-polymorphism (Hindley-Milner type inference).
     *
     * # Examples
     *
     * - Using a polymorphic function: `id 5` where `id : ∀'a. 'a -> 'a`
     *   generates `Int instance of ∀'a. 'a -> 'a`
     * - Instantiating a polymorphic value: `let f = identity in f 5`
     *
     * @property type The type to check
     * @property scheme The type scheme to instantiate
     * @property sourceLocation Location in source code where this constraint was generated
     */
    data class Instance(
        val type: FunkType,
        val scheme: FunkType,
        override val sourceLocation: SourceLocation? = null
    ) : Constraint() {
        override fun toString(): String = "${prettyPrint(type)} instance of ${prettyPrint(scheme)}"
    }

    /**
     * Ability constraint: TypeVar must implement Ability
     *
     * Represents that a type variable must have a specific ability (type class).
     * Abilities enable ad-hoc polymorphism (operator overloading) by constraining
     * type variables to types that implement certain operations.
     *
     * # Examples
     *
     * - Arithmetic: `x + y` generates `typeOf(x) : NumAdd` and `typeOf(y) : NumAdd`
     * - Comparison: `x == y` generates `typeOf(x) : Eq` and `typeOf(y) : Eq`
     * - String conversion: `Inspect.to_str x` generates `typeOf(x) : Inspect`
     *
     * # Built-in Abilities
     *
     * - NumAdd, NumSub, NumMul, NumDiv, NumMod: Arithmetic operations
     * - Eq: Equality comparison (==, !=)
     * - Ord: Ordering comparison (<, >, <=, >=)
     * - LogicalAnd, LogicalOr, LogicalNot: Boolean operations
     * - Inspect: String conversion (universal - all types have this)
     *
     * @property typeVar The type variable that must have this ability
     * @property ability The name of the ability (e.g., "NumAdd", "Eq")
     * @property sourceLocation Location in source code where this constraint was generated
     */
    data class AbilityConstraint(
        val typeVar: TypeVar,
        val ability: String,
        override val sourceLocation: SourceLocation? = null
    ) : Constraint() {
        override fun toString(): String = "${typeVar.name} : $ability"
    }

    /**
     * Annotation constraint: Inferred type must match declared type annotation
     *
     * Represents that the inferred type of an expression must be compatible with
     * a user-provided type annotation. This is used for explicit type annotations
     * on let bindings, function parameters, and other declarations.
     *
     * # Examples
     *
     * - Annotated let: `let x : Int = 5` generates annotation constraint
     * - Annotated function: `let f : Int -> Int = {x} -> x + 1`
     * - Annotated parameter: `{x : String} -> String.length x`
     *
     * # Validation
     *
     * Unlike Equality constraints (which unify types), AnnotationConstraints are
     * validated after unification. The inferred type must be compatible with the
     * declared type, but we don't modify the inferred type based on the annotation.
     *
     * @property inferredType The type inferred by the type checker
     * @property declaredType The type declared in the annotation
     * @property expression The expression being type-checked (for error reporting)
     * @property sourceLocation Location in source code where this constraint was generated
     */
    data class AnnotationConstraint(
        val inferredType: FunkType,
        val declaredType: FunkType,
        val expression: Expression,
        override val sourceLocation: SourceLocation? = null
    ) : Constraint() {
        override fun toString(): String =
            "Annotation: ${expression::class.simpleName} must have type ${prettyPrint(declaredType)}"
    }

    companion object {
        /**
         * Pretty-print a type for constraint display.
         * This is a simple version - the full pretty printer is in ErrorPrettyPrinter.
         */
        private fun prettyPrint(type: FunkType): String = when (type) {
            is FunkType.IntType -> "Int"
            is FunkType.FloatType -> "Float"
            is FunkType.StringType -> "String"
            is FunkType.BooleanType -> "Bool"
            is FunkType.NatType -> "Nat"
            is FunkType.FracType -> "Frac"
            is FunkType.VarType -> type.variable.name
            is FunkType.FunctionType -> {
                val paramStr = if (type.paramType is FunkType.FunctionType) {
                    "(${prettyPrint(type.paramType)})"
                } else {
                    prettyPrint(type.paramType)
                }
                "$paramStr -> ${prettyPrint(type.returnType)}"
            }
            is FunkType.RecordType -> {
                val fields = type.fields.entries.joinToString(", ") { (name, fieldType) ->
                    "$name: ${prettyPrint(fieldType)}"
                }
                "{$fields}"
            }
            is FunkType.VariantType -> {
                val constructors = type.constructors.entries.joinToString(", ") { (name, dataType) ->
                    if (dataType != null) "$name ${prettyPrint(dataType)}" else name
                }
                "| $constructors |"
            }
            is FunkType.ListType -> "[${prettyPrint(type.elementType)}]"
            is FunkType.ArrayType -> "Array ${prettyPrint(type.elementType)}"
            else -> type.toString()
        }
    }
}
