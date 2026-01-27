@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.funk.language.ast

import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Funk AST Module - Public API
 *
 * This module provides the complete Abstract Syntax Tree (AST) representation for the Funk language.
 * All AST node types are accessible from this package.
 *
 * ## Architecture
 *
 * The AST supports both untyped and typed phases:
 * 1. Parser produces untyped AST (inferredType = null)
 * 2. Typechecker enhances the same AST with type information (inferredType filled)
 * 3. Interpreter/Compiler uses the typed AST
 *
 * This eliminates the need for separate parser AST and typechecker AST conversions.
 *
 * ## Module Organization
 *
 * The AST module is organized into the following internal files:
 *
 * ### Common.kt
 * Shared types and utilities used across the AST:
 * - `TypeVar` - Type variables for polymorphism
 * - `RowVar` - Row variables for row polymorphism
 * - `SourceLocation` - Source code location tracking
 * - `Visibility` - Public/Private visibility
 * - `UsePath` - Module import paths
 *
 * ### BuiltinAbilities.kt
 * Built-in ability system:
 * - `BuiltinAbilities` - Built-in abilities (Eq, Ord, NumAdd, etc.)
 * - `BuiltinTypes` - Built-in type constructors and utilities
 *
 * ### Type.kt
 * Type system representations:
 * - `FunkType` - Sealed interface for all type representations
 *   - Primitive types: IntType, FloatType, StringType, BooleanType, NatType, FracType
 *   - Complex types: RecordType, VariantType, FunctionType, ListType, MapType, SetType
 *   - Polymorphic types: VarType, TypeApplication, ForAll
 * - `TypeScheme` - Polymorphic type schemes for let-polymorphism
 * - `TypeAnnotation` - Parser output type annotations (converted to FunkType by typechecker)
 * - `UsageContext` - Type usage context for error messages
 * - Type utilities: convertTypeAnnotation, normalizeTypeVariables, mergeConstraints
 *
 * ### Expression.kt
 * Expression AST nodes:
 * - `Expression` - Sealed interface for all expressions
 *   - Literals: IntLiteral, FloatLiteral, StringLiteral, BooleanLiteral
 *   - Control flow: IfExpression, MatchExpression, DoExpression
 *   - Functions: Lambda, FunctionCall, Perform, Resume
 *   - Data: RecordLiteral, ArrayLiteral, VariantConstructor
 *   - Variables: Variable, LetExpression
 *   - Operators: BinaryOp, UnaryOp
 * - `ResolvedAbility` - Resolved ability implementation
 * - `ResolvedEffectHandler` - Resolved effect handler
 *
 * ### Declaration.kt
 * Module-level declarations:
 * - `Program` - Top-level program container
 * - `ProgramItem` - Sealed interface for all declarations
 *   - `LetBinding` - Value/function bindings
 *   - `TypeDeclaration` - Type aliases
 *   - `NewtypeDeclaration` - Newtype declarations
 *   - `EffectDeclaration` - Effect declarations
 *   - `ImplDeclaration` - Effect implementation declarations
 *   - `AbilityDeclaration` - Ability interface declarations
 *   - `ImplAbilityDeclaration` - Ability implementation declarations
 *   - `ImportDeclaration` - Module imports
 *   - `ModuleDeclaration` - Module declarations
 *
 * ### Pattern.kt
 * Pattern matching AST nodes:
 * - `Pattern` - Sealed interface for all patterns
 *   - `LiteralPattern` - Match on literal values
 *   - `VariablePattern` - Bind variables
 *   - `WildcardPattern` - Match anything (_)
 *   - `VariantPattern` - Match variant constructors
 *   - `RecordPattern` - Match record structures
 *   - `ArrayPattern` - Match arrays with optional rest patterns
 *   - `AsPattern` - Named patterns (pattern as name)
 * - `LiteralValue` - Literal values for patterns
 *
 * ## Usage
 *
 * All types are in the `com.funk.language.ast` package and can be imported directly:
 *
 * ```kotlin
 * import com.funk.language.ast.*
 *
 * // Create an untyped expression
 * val expr = IntLiteral(42, null)
 *
 * // Create a typed expression
 * val typedExpr = IntLiteral(42, FunkType.IntType)
 *
 * // Create a program
 * val program = Program(listOf(
 *     LetBinding(
 *         pattern = VariablePattern("x"),
 *         expression = IntLiteral(42, FunkType.IntType)
 *     )
 * ))
 * ```
 *
 * ## Serialization
 *
 * All AST nodes are annotated with `@Serializable` and use kotlinx.serialization.
 * Sealed interfaces use `@JsonClassDiscriminator` for polymorphic serialization.
 */

// All types are defined in the internal implementation files:
// - Common.kt: TypeVar, RowVar, SourceLocation, Visibility, UsePath, BuiltinAbilities
// - Type.kt: FunkType, TypeScheme, TypeAnnotation, UsageContext
// - Expression.kt: Expression, ResolvedAbility, ResolvedEffectHandler
// - Declaration.kt: Program, ProgramItem and all declarations
// - Pattern.kt: Pattern, LiteralValue

// No re-exports needed - all types remain in the same package (com.funk.language.ast)
// and are automatically accessible to external modules.
