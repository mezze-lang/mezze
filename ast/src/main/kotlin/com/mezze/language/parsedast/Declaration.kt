package com.mezze.language.parsedast

import kotlinx.serialization.Serializable

/**
 * Top-level module — the result of parsing a .mz file.
 */
@Serializable
data class ParsedModule(
    val path: String,
    val decls: List<ParsedDecl>,
    val orphanComments: List<ParsedComment> = emptyList()
)


/**
 * All top-level declarations.
 *
 * Every declaration has:
 * - `loc` — source span
 * - `visibility` — pub or private
 * - `leadingComments` — comments above this declaration
 * - `trailingComment` — comment on the same line after
 */
@Serializable
sealed interface ParsedDecl {
    val loc: Loc
    val visibility: Visibility
    val leadingComments: List<ParsedComment>
    val trailingComment: ParsedComment?
}


/**
 * Let binding: `let x = expr` / `pub let x: Type = expr`
 *
 * Supports:
 * - Simple: `let x = 1`
 * - Typed: `pub let add: { l: Int, r: Int } -> Int = { l, r } -> l + r`
 * - Destructuring: `let { x, y } = get_point {}`
 * - Generic: `let identity = { x: 'a } -> x`  (type params inferred from annotation)
 */
@Serializable
data class ParsedLetDecl(
    val pattern: ParsedPattern,
    val typeAnnotation: ParsedTypeAnnotation? = null,
    val value: ParsedExpr,
    override val visibility: Visibility = Visibility.PRIVATE,
    override val leadingComments: List<ParsedComment> = emptyList(),
    override val trailingComment: ParsedComment? = null,
    override val loc: Loc
) : ParsedDecl {
    val isSimpleBinding: Boolean get() = pattern is ParsedVariablePattern
    val variableName: String get() = (pattern as ParsedVariablePattern).name
}


/**
 * Type declaration: `type X = ...` / `pub type X 'a = ...`
 */
@Serializable
data class ParsedTypeDecl(
    val name: String,
    val typeParams: List<String> = emptyList(),
    val definition: ParsedTypeDef,
    override val visibility: Visibility = Visibility.PRIVATE,
    override val leadingComments: List<ParsedComment> = emptyList(),
    override val trailingComment: ParsedComment? = null,
    override val loc: Loc,
    /** Content hash — populated by typechecker, not parser */
    val contentHash: String? = null
) : ParsedDecl

@Serializable
sealed interface ParsedTypeDef {
    val loc: Loc
}

/**
 * Record definition: `type Point = { x: Int, y: Int }`
 */
@Serializable
data class ParsedRecordDef(
    val fields: List<ParsedRecordFieldDef>,
    val isOpen: Boolean = false,
    override val loc: Loc
) : ParsedTypeDef

@Serializable
data class ParsedRecordFieldDef(
    val name: String,
    val type: ParsedTypeAnnotation,
    val loc: Loc
)

/**
 * Variant definition: `type Color = | Red, Green, Blue |`
 */
@Serializable
data class ParsedVariantDef(
    val constructors: List<ParsedVariantCtorDef>,
    val isOpen: Boolean = false,
    override val loc: Loc
) : ParsedTypeDef

@Serializable
data class ParsedVariantCtorDef(
    val name: String,
    val dataType: ParsedTypeAnnotation? = null,
    val loc: Loc
)

/**
 * Newtype definition: `newtype UserId Wrap Int`
 */
@Serializable
data class ParsedNewtypeDef(
    val constructor: String,
    val wrappedType: ParsedTypeAnnotation,
    override val loc: Loc
) : ParsedTypeDef


/**
 * Ability declaration: `ability Eq for 'a where { ... }`
 *
 * Examples:
 * - `ability Eq for 'a where { eq: {it: 'a, r: 'a} -> Bool }`
 * - `ability Into 'b for 'a where { into: {it: 'a} -> 'b }`
 * - `ability Ord for 'a where { ... }` with dependency on Eq
 */
@Serializable
data class ParsedAbilityDecl(
    val name: String,
    val typeParams: List<String>,
    val receiverTypeParam: String? = null,
    val associatedTypes: List<String> = emptyList(),
    val constraints: List<ParsedAbilityConstraint> = emptyList(),
    val dependencies: List<ParsedAbilityDep> = emptyList(),
    val operations: List<ParsedAbilityOp>,
    override val visibility: Visibility = Visibility.PUBLIC,
    override val leadingComments: List<ParsedComment> = emptyList(),
    override val trailingComment: ParsedComment? = null,
    override val loc: Loc,
    /** Content hash — populated by typechecker, not parser */
    val contentHash: String? = null
) : ParsedDecl

/**
 * Operation in an ability declaration.
 *
 * Example: `eq: {it: 'a, r: 'a} -> Bool`
 */
@Serializable
data class ParsedAbilityOp(
    val name: String,
    val paramType: ParsedTypeAnnotation,
    val returnType: ParsedTypeAnnotation,
    val effects: ParsedEffectRow? = null,
    val abilityConstraints: List<ParsedFnAbilityConstraint> = emptyList(),
    val doc: String? = null,
    val loc: Loc
)


/**
 * The target of an impl block — named ability or inherent.
 */
@Serializable
sealed interface ParsedImplTarget {
    /** Named ability: `impl Eq for Int` */
    @Serializable
    data class Named(val ability: ParsedQualifiedRef) : ParsedImplTarget

    /** Inherent impl: `impl Str where { ... }` */
    @Serializable
    data object Inherent : ParsedImplTarget
}

/**
 * Ability implementation: `impl Eq for Int where { ... }`
 *
 * Also covers inherent impls: `impl Str where { ... }`
 *
 * Examples:
 * - `impl Eq for Int where { eq = {it, r} -> ... }`
 * - `impl Eq 'a for Option 'a where { ... }` (generic impl)
 * - `impl Into Str for Int where { into = {it} -> ... }` (multi-param ability)
 * - `impl Str where { length = {it} -> ... }` (inherent)
 */
@Serializable
data class ParsedImplAbilityDecl(
    val target: ParsedImplTarget,
    val targetTypes: List<ParsedTypeAnnotation>,
    val abilityTypeArgs: List<ParsedTypeAnnotation> = emptyList(),
    val forallVars: List<String> = emptyList(),
    val associatedTypeBindings: Map<String, ParsedTypeAnnotation> = emptyMap(),
    val operations: List<ParsedImplOp>,
    override val visibility: Visibility = Visibility.PRIVATE,
    override val leadingComments: List<ParsedComment> = emptyList(),
    override val trailingComment: ParsedComment? = null,
    override val loc: Loc,
    /** Content hash — populated by typechecker, not parser */
    val contentHash: String? = null
) : ParsedDecl {
    val isInherent: Boolean get() = target is ParsedImplTarget.Inherent

    /** Convenience: get the ability name for named impls, or null for inherent impls */
    val abilityName: String? get() = (target as? ParsedImplTarget.Named)?.ability?.localName

    /** Convenience: get the ability ParsedQualifiedRef for named impls */
    val abilityRef: ParsedQualifiedRef? get() = (target as? ParsedImplTarget.Named)?.ability

    /**
     * All type arguments combined: concrete abilityTypeArgs (before 'for') + targetTypes (after 'for').
     * Maps positionally to the ability's type params (extra params first, self last).
     *
     * Type variable abilityTypeArgs are quantifiers (not type arguments), so they are excluded.
     */
    val allTypeArgs: List<ParsedTypeAnnotation> get() {
        val concreteArgs = abilityTypeArgs.filter {
            it !is ParsedNamedType || !it.name.startsWith("'")
        }
        return concreteArgs + targetTypes
    }
}

/**
 * Operation in an ability impl.
 *
 * Example: `eq = {it, r} -> __native_int_eq {l = it, r = r}`
 *
 * paramType and returnType are optional because they can be inferred
 * from the ability declaration.
 */
@Serializable
data class ParsedImplOp(
    val name: String,
    val paramType: ParsedTypeAnnotation? = null,
    val returnType: ParsedTypeAnnotation? = null,
    val implementation: ParsedExpr,
    val abilityConstraints: List<ParsedFnAbilityConstraint> = emptyList(),
    val loc: Loc
)


/**
 * Effect declaration: `effect FileSystem where { ... }`
 *
 * Effects are always public.
 */
@Serializable
data class ParsedEffectDecl(
    val name: String,
    val operations: List<ParsedEffectOp>,
    val associatedTypes: List<String> = emptyList(),
    override val visibility: Visibility = Visibility.PUBLIC,
    override val leadingComments: List<ParsedComment> = emptyList(),
    override val trailingComment: ParsedComment? = null,
    override val loc: Loc,
    /** Content hash — populated by typechecker, not parser */
    val contentHash: String? = null
) : ParsedDecl

/**
 * Operation in an effect declaration.
 *
 * Example: `read_file: { path: Str } -> Result Str Error <FileSystem>`
 */
@Serializable
data class ParsedEffectOp(
    val name: String,
    val paramType: ParsedTypeAnnotation,
    val returnType: ParsedTypeAnnotation,
    val effects: List<String> = emptyList(),
    val doc: String? = null,
    val loc: Loc
)


/**
 * Performer declaration: `impl FileSystem for LocalDisk where { ... }`
 *
 * Example:
 * - `impl FileSystem for LocalDisk where { read_file = { path } -> ... }`
 * - `impl FileSystem for FileLogger { path: Str } where { ... }` (with params)
 */
@Serializable
data class ParsedPerformerDecl(
    val effect: ParsedQualifiedRef,
    val performerName: String,
    val params: List<ParsedPerformerParam> = emptyList(),
    val associatedTypeBindings: Map<String, ParsedTypeAnnotation> = emptyMap(),
    val operations: List<ParsedPerformerOp>,
    override val visibility: Visibility = Visibility.PRIVATE,
    override val leadingComments: List<ParsedComment> = emptyList(),
    override val trailingComment: ParsedComment? = null,
    override val loc: Loc,
    /** Content hash — populated by typechecker, not parser */
    val contentHash: String? = null
) : ParsedDecl

/**
 * Operation in a performer.
 *
 * Example: `read_file = { path } -> __native_fs_read_file { path = path }`
 *
 * Types are optional — inferred from effect declaration.
 */
@Serializable
data class ParsedPerformerOp(
    val name: String,
    val paramType: ParsedTypeAnnotation? = null,
    val returnType: ParsedTypeAnnotation? = null,
    val implementation: ParsedExpr,
    val effects: ParsedEffectRow? = null,
    val loc: Loc
)


/** Use declaration: `use std::int` / `use std::int::{add, multiply}` */
@Serializable
data class ParsedUseDecl(
    val path: ParsedUsePath,
    val alias: String? = null,
    override val visibility: Visibility = Visibility.PRIVATE,
    override val leadingComments: List<ParsedComment> = emptyList(),
    override val trailingComment: ParsedComment? = null,
    override val loc: Loc
) : ParsedDecl

/** Public re-export: `pub use std::int` */
@Serializable
data class ParsedPubUseDecl(
    val path: ParsedUsePath,
    val alias: String? = null,
    override val visibility: Visibility = Visibility.PUBLIC,
    override val leadingComments: List<ParsedComment> = emptyList(),
    override val trailingComment: ParsedComment? = null,
    override val loc: Loc
) : ParsedDecl


/** Bare expression at the top level of a module. */
@Serializable
data class ParsedTopLevelExpr(
    val expression: ParsedExpr,
    override val visibility: Visibility = Visibility.PRIVATE,
    override val leadingComments: List<ParsedComment> = emptyList(),
    override val trailingComment: ParsedComment? = null,
    override val loc: Loc
) : ParsedDecl
