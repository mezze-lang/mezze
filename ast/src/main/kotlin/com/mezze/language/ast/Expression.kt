@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.mezze.language.ast

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * AST nodes for expressions and statements.
 *
 * This file contains:
 * - Expression: All expression types (Lambda, Application, Match, If, etc.)
 * - Literal expressions: IntLiteral, FloatLiteral, StringLiteral, etc.
 * - String interpolation: StringInterpolationPart hierarchy
 * - Variables and access: Identifier, MemberAccess
 * - Records: RecordLiteral, RecordLiteralEntry hierarchy
 * - Variants: VariantConstruction
 * - Functions: LambdaExpression, FunctionCall, LambdaParameters hierarchy
 * - Control flow: IfExpression, MatchExpression, DoExpression
 * - Effect system: PerformExpression, PerformHandler
 * - State machines: StateMachineExpression, StateMachineState hierarchy
 * - Statements: Statement hierarchy (LetStatement, ExpressionStatement)
 *
 * Part of the refactored AST module - previously in MezzeAST.kt.
 */

// ============================================================================
// ABILITY AND EFFECT RESOLUTION
// ============================================================================

/**
 * Resolved ability information for monomorphic dispatch
 * This is populated by the TypeChecker during ability resolution
 */
@Serializable
data class ResolvedAbility(
    val abilityName: String,                    // e.g., "NumAdd"
    val typeArgs: List<MezzeType>,               // e.g., [IntType]
    val isBuiltin: Boolean = false,             // true for built-in abilities with automatic implementation
    val implementationHash: String? = null      // Content-addressable hash of the implementation
)

/**
 * Resolved effect handler information for direct dispatch.
 * This is populated by the TypeChecker during effect resolution.
 *
 * @property effectName The name of the effect being handled (e.g., "Console")
 * @property performerName The name of the performer/handler (e.g., "NativeConsole")
 * @property implDeclarationHash Content-addressable hash of the handler implementation for runtime lookup
 * @property effectDeclarationHash Content-addressable hash of the effect declaration for operation lookup
 * @property isScopedEffect Whether this impl has initialize_scope/finalize_scope
 * @property initializeScopeExpr The initialize_scope expression (AST) for scoped effects
 * @property finalizeScopeExpr The finalize_scope expression (AST) for scoped effects
 */
@Serializable
data class ResolvedEffectHandler(
    val effectName: String,
    val performerName: String,
    val implDeclarationHash: String? = null,
    val effectDeclarationHash: String? = null,
    val isScopedEffect: Boolean = false,
    val initializeScopeExpr: Expression? = null,
    val finalizeScopeExpr: Expression? = null
)

// ============================================================================
// EXPRESSIONS (Unified - can be untyped or typed)
// ============================================================================

@Serializable
@JsonClassDiscriminator("exprKind")
sealed interface Expression {
    val inferredType: MezzeType? // null during parsing, filled by typechecker
    val sourceLocation: SourceLocation? get() = null // source location for error reporting (default implementation)

    // NEW: Type substitution for monomorphization
    // Maps generic type parameter names to concrete types
    // Example: for map<Int, String>, this would be {'a -> Int, 'b -> String}
    val typeSubstitution: Map<String, MezzeType> get() = emptyMap()
}

// ============================================================================
// LITERALS
// ============================================================================

@Serializable
data class IntLiteral(
    val value: Long,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression

@Serializable
data class NatLiteral(
    val value: Long,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression

@Serializable
data class FloatLiteral(
    val value: Double,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression

/**
 * String literal expression, supporting both simple strings and interpolated strings.
 *
 * For simple strings: parts is null and value contains the string content.
 * For interpolated strings: parts contains the list of StringPart/ExpressionPart segments.
 *
 * Example interpolated string: "Hello, $(name)! You have $(count) messages."
 * Would have parts: [StringPart("Hello, "), ExpressionPart(name), StringPart("! You have "), ExpressionPart(count), StringPart(" messages.")]
 */
@Serializable
data class StringLiteral(
    val value: String,
    val parts: List<StringInterpolationPart>? = null,  // null for simple strings, non-null for interpolated
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression {
    val isInterpolated: Boolean get() = parts != null
}

/**
 * A part of an interpolated string - either a literal string segment or an expression to interpolate.
 */
@Serializable
@JsonClassDiscriminator("interpolationPartKind")
sealed interface StringInterpolationPart

/**
 * A literal string segment in an interpolated string.
 */
@Serializable
data class StringPart(val value: String) : StringInterpolationPart

/**
 * An expression to be interpolated. The expression must evaluate to a String type.
 * No automatic conversion is performed - use to_str{val = ...} to convert other types explicitly.
 */
@Serializable
data class ExpressionPart(val expression: Expression) : StringInterpolationPart

@Serializable
data class FractionLiteral(
    val numerator: Long,
    val denominator: Long,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression

@Serializable
data class SeqLiteral(
    val elements: List<SeqElement>,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression {
    /**
     * Check if this seq literal contains any spread elements
     */
    fun hasSpreadElements(): Boolean = elements.any { it is SeqSpreadElement }

    /**
     * Get all regular (non-spread) elements
     */
    fun getRegularElements(): List<SeqRegularElement> = elements.filterIsInstance<SeqRegularElement>()

    /**
     * Get all spread elements
     */
    fun getSpreadElements(): List<SeqSpreadElement> = elements.filterIsInstance<SeqSpreadElement>()

    companion object {
        /**
         * Create a SeqLiteral from a list of expressions (all regular elements)
         * For backward compatibility
         */
        fun fromExpressions(expressions: List<Expression>, sourceLocation: SourceLocation? = null): SeqLiteral =
            SeqLiteral(expressions.map { SeqRegularElement(it) }, sourceLocation = sourceLocation)
    }
}

/**
 * Sealed class for seq elements - either regular expressions or spread expressions
 */
@Serializable
@JsonClassDiscriminator("seqElementKind")
sealed class SeqElement

/**
 * Regular seq element (a single expression)
 */
@Serializable
data class SeqRegularElement(val expression: Expression) : SeqElement()

/**
 * Spread element in a seq literal (...expr)
 */
@Serializable
data class SeqSpreadElement(val expression: Expression) : SeqElement()

// ============================================================================
// VARIABLES
// ============================================================================

/**
 * Identifier expression - references a variable, function, or qualified module member.
 *
 * Examples:
 * - Simple: `x`, `foo`
 * - Qualified: `bytes::from_string`, `std::math::pi`
 *
 * For qualified identifiers (e.g., `bytes::from_string`):
 * - `localName` = "from_string"
 * - `qualifier` = ModulePath(["bytes"])
 * - `name` = "bytes::from_string" (computed, for backwards compatibility)
 *
 * The `resolvedContentHash` is populated during type-checking and provides
 * content-addressed lookup for cross-module references.
 */
@Serializable
data class Identifier(
    /** The local name of the identifier (e.g., "from_string" in "bytes::from_string") */
    val localName: String,
    /** Module qualifier if this is a qualified reference (e.g., ModulePath(["bytes"])) */
    val qualifier: ModulePath? = null,
    /** Content hash of the referenced definition (populated by typechecker) */
    val resolvedContentHash: String? = null,
    /** Whether this references a native function (set by typechecker, avoids string prefix checks) */
    val isNativeFunction: Boolean = false,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression {
    /** Whether this is a qualified reference (e.g., bytes::from_string) */
    val isQualified: Boolean get() = qualifier != null && qualifier.segments.isNotEmpty()

    /**
     * Create a binding key for looking up this identifier.
     * Uses the last segment of the qualifier (short name) as the key qualifier.
     */
    fun toBindingKey(): BindingKey = BindingKey(
        qualifier = qualifier?.shortName,
        localName = localName
    )

}

/**
 * Pipe operator expression: left |> right
 *
 * The pipe operator passes the left expression to the right expression,
 * where the @ placeholder in the right expression is replaced with the left value.
 *
 * Rules:
 * - Every |> must have exactly one @ in the right-hand side
 * - @ must appear as a record field value (not field name)
 * - @ can be nested: x |> f { a = g { b = @ } }
 *
 * Examples:
 *   x |> f { a = @ }           -- becomes f { a = x }
 *   x |> f { a = g { b = @ } } -- becomes f { a = g { b = x } }
 *   x |> f { a = @ } |> g { b = @ } -- becomes g { b = f { a = x } }
 *
 * This node is preserved in the AST for formatting. The typechecker
 * desugars it by replacing the @ placeholder with the piped value.
 */
@Serializable
data class PipeExpression(
    val left: Expression,
    val right: Expression,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression

/**
 * Pipe placeholder: @
 *
 * Represents the @ symbol in a pipe expression's right-hand side.
 * During typechecking, this is replaced with the piped value.
 *
 * Rules:
 * - Only valid inside a PipeExpression's right-hand side
 * - Must appear as a record field value
 * - Exactly one @ per pipe expression
 *
 * Example: x |> f { y = @ }
 *          The @ is a PipePlaceholder that gets replaced with x
 */
@Serializable
data class PipePlaceholder(
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression

/**
 * Parenthesized expression - preserves user's explicit grouping.
 * The formatter outputs these with parentheses to maintain the original style.
 */
@Serializable
data class ParenthesizedExpression(
    val expression: Expression,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression

// ============================================================================
// RECORDS
// ============================================================================

@Serializable
data class RecordLiteral(
    val entries: List<RecordLiteralEntry>,
    val isOpen: Boolean = false,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression {
    // Thread-local to track nesting depth to prevent infinite recursion
    companion object {
        internal val toStringDepth = ThreadLocal.withInitial { 0 }
        internal const val MAX_DEPTH = 20
        internal const val TRUNCATE_DEPTH = 5

        /**
         * Helper for safe toString() implementations with depth tracking.
         */
        internal inline fun safeToString(
            typeName: String,
            truncatedContent: () -> String,
            fullContent: () -> String
        ): String {
            val depth = toStringDepth.get()
            if (depth > MAX_DEPTH) {
                return "$typeName{<max depth>}"
            }
            return try {
                toStringDepth.set(depth + 1)
                if (depth > TRUNCATE_DEPTH) {
                    "$typeName{${truncatedContent()}}"
                } else {
                    "$typeName(${fullContent()})"
                }
            } catch (e: StackOverflowError) {
                "$typeName{<StackOverflowError>}"
            } finally {
                toStringDepth.set(depth)
                if (depth == 0) toStringDepth.remove()
            }
        }
    }

    override fun toString(): String = Companion.safeToString(
        "RecordLiteral",
        truncatedContent = { "truncated" },
        fullContent = { "size=${entries.size}" }
    )
}

@Serializable
sealed class RecordLiteralEntry

@Serializable
data class RecordFieldEntry(
    val name: String,
    val value: Expression? = null  // null for shorthand {x} instead of {x = x}
) : RecordLiteralEntry() {
    override fun toString(): String = RecordLiteral.safeToString(
        "RecordFieldEntry",
        truncatedContent = { "truncated" },
        fullContent = { name }
    )
}

@Serializable
data class RecordSpreadEntry(
    val expression: Expression
) : RecordLiteralEntry() {
    override fun toString(): String = RecordLiteral.safeToString(
        "RecordSpreadEntry",
        truncatedContent = { "truncated" },
        fullContent = { "..." }
    )
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
data class MemberAccess(
    val receiver: Expression,
    val member: String,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null,
    // For module-qualified access (e.g., bytes::from_string), the content hash of the resolved function
    val resolvedContentHash: String? = null
) : Expression

// ============================================================================
// VARIANTS
// ============================================================================

/**
 * Resolution metadata for uppercase identifiers.
 * The parser creates VariantConstruction for all uppercase names (Inspect, Some, Console, etc.)
 * The typechecker resolves what each one actually is and populates this field.
 */
@Serializable
sealed class ConstructorResolution {
    /** Regular variant constructor (Some, Nothing, etc.) */
    @Serializable
    object VariantConstructor : ConstructorResolution()

    /** Ability reference (Inspect, Eq, Ord, user-defined abilities) */
    @Serializable
    data class AbilityReference(val abilityName: String) : ConstructorResolution()

    /** Effect reference (Log, Filesystem, user-defined effects) */
    @Serializable
    data class EffectReference(
        val effectName: String,
        val effectContentHash: String? = null
    ) : ConstructorResolution()

    /** Handler/Performer reference (Console, NativeRandom, etc.) */
    @Serializable
    data class HandlerReference(val handlerName: String, val effectName: String? = null) : ConstructorResolution()

    /** Not yet resolved by typechecker (default) */
    @Serializable
    object Unresolved : ConstructorResolution()
}

@Serializable
data class VariantConstruction(
    val constructor: String,
    val value: Expression? = null,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null,
    val resolution: ConstructorResolution = ConstructorResolution.Unresolved
) : Expression

// ============================================================================
// FUNCTIONS
// ============================================================================

@Serializable
data class LambdaExpression(
    val parameters: LambdaParameters,
    val body: Expression,
    val returnType: TypeAnnotation? = null,  // Explicit return type annotation
    val abilityConstraints: List<FunctionAbilityConstraint> = emptyList(),  // Ability constraints
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
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
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null,

    // NEW: Resolved ability implementations for this call
    // Maps content hash (or synthetic key for builtins) to its resolved implementation
    // For user-defined: {"abc123..." -> ResolvedAbility(..., implementationHash="abc123...")}
    // For builtins: {"builtin:NumAdd:Int" -> ResolvedAbility(..., isBuiltin=true)}
    val resolvedAbilities: Map<String, ResolvedAbility> = emptyMap(),

    // NEW: For monomorphic dispatch - direct reference to specialized function
    // When this is non-null, Truffle can call the function directly without runtime lookup
    val resolvedFunctionName: String? = null,

    // NEW: Type substitution for generic function instantiation
    // Maps type parameter names to concrete types
    override val typeSubstitution: Map<String, MezzeType> = emptyMap(),

    // NEW: Instantiated function type at this call site
    // For polymorphic functions, this contains the type with concrete types substituted
    // Example: double = {x} -> x * 2 has type forall a. {x: a} -> a where a: NumMul
    //          At call site double {x = 5}, instantiatedFunctionType is {x: Int} -> Int where Int: NumMul
    // This allows ability resolution to see concrete types instead of type variables
    val instantiatedFunctionType: MezzeType.FunctionType? = null,

    // TCO: True when this call is a self-recursive tail call
    // Set by the typechecker when the call is in tail position and calls the enclosing function
    val isTailCall: Boolean = false,

    // Content hash for monomorphic dispatch - allows direct lookup without string parsing
    val resolvedFunctionContentHash: String? = null
) : Expression {
    override fun toString(): String = RecordLiteral.safeToString(
        "FunctionCall",
        truncatedContent = { "truncated" },
        fullContent = { "..." }
    )
}

// ============================================================================
// CONTROL FLOW
// ============================================================================

@Serializable
data class IfExpression(
    val condition: Expression,
    val thenBranch: Expression,
    val elseBranch: Expression,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression

/**
 * Convert IfExpression to an equivalent MatchExpression on Bool.
 *
 * This enables unification of if-else and match at the typechecker level
 * while preserving IfExpression in the AST for tooling (error messages, LSP).
 *
 * The transformation is:
 *   if condition then thenBranch else elseBranch
 * becomes:
 *   match condition {
 *       True -> thenBranch
 *       False -> elseBranch
 *   }
 */
fun IfExpression.toMatchExpression(): MatchExpression {
    return MatchExpression(
        expression = condition,
        cases = listOf(
            MatchCase(
                patterns = listOf(ConstructorPattern("True")),
                expression = thenBranch
            ),
            MatchCase(
                patterns = listOf(ConstructorPattern("False")),
                expression = elseBranch
            )
        ),
        inferredType = inferredType,
        sourceLocation = sourceLocation
    )
}

@Serializable
data class MatchExpression(
    val expression: Expression,
    val cases: List<MatchCase>,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression

@Serializable
data class MatchCase(
    val patterns: List<Pattern>,        // Multiple patterns for | syntax
    val guard: Expression? = null,      // Optional guard condition (boolean)
    val expression: Expression,
    val ifLetBindings: List<IfLetGuard> = emptyList()  // Optional if let bindings
)

/**
 * Represents an "if let" binding in a match guard.
 *
 * Syntax: `if let Pattern = Expression`
 * Example: `if let Some {val = x} = lookup {key = k}`
 *
 * The pattern must match for the guard to pass, and any bindings
 * introduced by the pattern are available in subsequent guards
 * and the case body.
 */
@Serializable
data class IfLetGuard(
    val pattern: Pattern,
    val expression: Expression,
    val sourceLocation: SourceLocation? = null
)

// ============================================================================
// BLOCKS
// ============================================================================

@Serializable
data class DoExpression(
    val statements: List<Statement>,
    val result: Expression,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression

// ============================================================================
// EFFECT HANDLING
// ============================================================================

@Serializable
data class PerformExpression(
    val expression: Expression,
    val handlers: List<PerformHandler>,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null,

    // NEW: Resolved effect handler implementations
    // Maps effect name to its resolved handler
    val resolvedHandlers: Map<String, ResolvedEffectHandler> = emptyMap()
) : Expression

/**
 * Handler specification in a perform expression.
 *
 * Example: `perform log::Console {config = myConfig} in ...`
 * - performer: QualifiedRef(localName="Console", qualifier=["log"])
 * - parameters: RecordLiteral({config = myConfig})
 */
@Serializable
data class PerformHandler(
    /** The performer/handler reference (may be qualified like log::Console) */
    val performer: QualifiedRef,
    /** Optional parameters to configure the handler */
    val parameters: RecordLiteral? = null
)

@Serializable
data class EffectCallExpression(
    val operation: QualifiedRef,
    val arguments: Expression,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression

// ============================================================================
// STATE MACHINES (Continuation Transformation)
// ============================================================================

/**
 * State machine expression representing compiler-transformed continuation code.
 *
 * This AST node is generated by the ContinuationTransformer during Phase 2.
 * It represents code that has been transformed from continuation-using form
 * into an explicit state machine for proper multishot continuation support.
 */
@Serializable
data class StateMachineExpression(
    val states: List<StateMachineState>,
    val initialState: Int = 0,
    val capturedVariables: List<String> = emptyList(),
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression

/**
 * Individual state in a continuation state machine.
 */
@Serializable
@JsonClassDiscriminator("stateKind")
sealed interface StateMachineState {
    val id: Int
    val nextStateId: Int  // -1 for terminal states
}

/**
 * Compute state - executes a computation and transitions to next state.
 */
@Serializable
data class ComputeState(
    override val id: Int,
    val computation: Expression,
    override val nextStateId: Int
) : StateMachineState

/**
 * Yield state - suspends execution at this point, capturing continuation.
 * The captured variables list determines which variables need to be preserved.
 */
@Serializable
data class YieldState(
    override val id: Int,
    val valueExpression: Expression,
    val capturedVariables: List<String>,
    override val nextStateId: Int
) : StateMachineState

/**
 * Complete state - terminal state that produces the final result.
 */
@Serializable
data class CompleteState(
    override val id: Int,
    val resultExpression: Expression
) : StateMachineState {
    override val nextStateId: Int = -1  // Always terminal
}

// ============================================================================
// STATEMENTS
// ============================================================================

@Serializable
@JsonClassDiscriminator("stmtKind")
sealed interface Statement

@Serializable
data class LetStatement(
    val pattern: Pattern,                         // Pattern for destructuring (VariablePattern for simple bindings)
    val typeAnnotation: TypeAnnotation? = null,
    val expression: Expression
) : Statement {
    /**
     * For backward compatibility - returns the variable name if pattern is a simple VariablePattern.
     * Throws for complex patterns to ensure callers handle destructuring explicitly.
     */
    val variableName: String
        get() = (pattern as? VariablePattern)?.name
            ?: throw IllegalStateException(
                "LetStatement has a complex pattern (${pattern.kindDescription()}). " +
                "Use pattern directly for destructuring support."
            )

    /**
     * Check if this is a simple variable binding (not destructuring)
     */
    val isSimpleBinding: Boolean
        get() = pattern is VariablePattern

    companion object {
        /**
         * Create a simple let statement with just a variable name (backward compatibility)
         */
        fun simple(
            name: String,
            expression: Expression,
            typeAnnotation: TypeAnnotation? = null
        ) = LetStatement(
            VariablePattern(name),
            typeAnnotation,
            expression
        )
    }
}

@Serializable
data class ExpressionStatement(
    val expression: Expression
) : Statement
