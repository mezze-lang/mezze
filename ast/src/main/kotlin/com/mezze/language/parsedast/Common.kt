package com.mezze.language.parsedast

import kotlinx.serialization.Serializable

/**
 * The token that separates segments in a module path: `a::b::c`.
 * Defined here, in the AST module, so every layer (parser, typechecker,
 * codebase, lsp) speaks the same separator without hardcoded literals.
 */
const val MODULE_PATH_SEPARATOR = "::"

/**
 * The root namespace for the standard library. Modules whose path starts
 * with `STDLIB_NAMESPACE_PREFIX` (= "std::") are stdlib, never user code.
 */
const val STDLIB_NAMESPACE = "std"
const val STDLIB_NAMESPACE_PREFIX = "$STDLIB_NAMESPACE$MODULE_PATH_SEPARATOR"

/**
 * Module path segments — e.g., std::collections::list = ["std", "collections", "list"]
 */
@Serializable
data class ParsedModulePath(
    val segments: List<String>
) {
    val shortName: String get() = segments.last()
    override fun toString(): String = segments.joinToString(MODULE_PATH_SEPARATOR)
}

/**
 * Qualified reference to an effect, ability, performer, or type.
 *
 * Examples:
 * - `Console` → localName="Console", qualifier=null
 * - `log::Console` → localName="Console", qualifier=["log"]
 * - `std::effects::log::Console` → localName="Console", qualifier=["std","effects","log"]
 */
@Serializable
data class ParsedQualifiedRef(
    val localName: String,
    val qualifier: ParsedModulePath? = null,
    val loc: Loc
) {
    val isQualified: Boolean get() = qualifier != null
    override fun toString(): String =
        if (qualifier != null) "$qualifier::$localName" else localName
}

/**
 * Import path.
 */
@Serializable
sealed interface ParsedUsePath {
    val loc: Loc

    /** Simple module import: `use std::int` */
    @Serializable
    data class Module(
        val path: ParsedModulePath,
        override val loc: Loc
    ) : ParsedUsePath

    /** Selective import: `use std::int::{add, multiply as mul}` */
    @Serializable
    data class Selective(
        val modulePath: ParsedModulePath,
        val items: List<ParsedImportItem>,
        override val loc: Loc
    ) : ParsedUsePath
}

/**
 * An item in a selective import.
 *
 * Examples:
 * - `add` → name="add", alias=null
 * - `multiply as mul` → name="multiply", alias="mul"
 */
@Serializable
data class ParsedImportItem(
    val name: String,
    val alias: String? = null,
    val loc: Loc
)

/**
 * String interpolation part — literal text or expression.
 */
@Serializable
sealed interface ParsedStringPart {
    @Serializable
    data class Literal(val value: String) : ParsedStringPart

    @Serializable
    data class Interpolation(val expr: ParsedExpr) : ParsedStringPart
}

/**
 * Seq element — regular or spread.
 */
@Serializable
sealed interface ParsedSeqElement {
    @Serializable
    data class Regular(val expr: ParsedExpr) : ParsedSeqElement

    @Serializable
    data class Spread(val expr: ParsedExpr, val loc: Loc) : ParsedSeqElement
}

/**
 * Record literal entry — field (with optional punning) or spread.
 */
@Serializable
sealed interface ParsedRecordEntry {
    /**
     * Field entry: `x = expr` or just `x` (punning, value=null).
     */
    @Serializable
    data class Field(
        val name: String,
        val value: ParsedExpr?,
        val loc: Loc
    ) : ParsedRecordEntry

    /**
     * Spread entry: `...expr`
     */
    @Serializable
    data class Spread(
        val expr: ParsedExpr,
        val loc: Loc
    ) : ParsedRecordEntry
}

/**
 * Handler reference in a perform expression.
 *
 * Examples:
 * - `perform Console in ...` → performer=Console, params=null
 * - `perform SeededRandom {seed = 42} in ...` → performer=SeededRandom, params={seed=42}
 * - `perform log::Console in ...` → performer=log::Console, params=null
 */
@Serializable
data class ParsedPerformHandler(
    val performer: ParsedQualifiedRef,
    val params: ParsedRecordLit? = null
)

/**
 * Lambda parameter forms.
 */
@Serializable
sealed interface ParsedLambdaParams {
    /**
     * Typed parameters: `{ x: Int, y: Str }`
     */
    @Serializable
    data class Typed(val fields: List<ParsedRecordTypeEntry>) : ParsedLambdaParams

    /**
     * Untyped parameters: `{ x, y }` or `{ x, y, ... }`
     */
    @Serializable
    data class Untyped(
        val names: List<String>,
        val isOpen: Boolean = false
    ) : ParsedLambdaParams
}

/**
 * Ability constraint on a function.
 *
 * Examples:
 * - `with Eq 'a` → abilityName="Eq", typeArgs=['a]
 * - `with Container 'c having 'item = Int` → abilityName="Container", typeArgs=['c], associatedTypeBindings={'item: Int}
 */
@Serializable
data class ParsedFnAbilityConstraint(
    val abilityName: String,
    val typeArgs: List<ParsedTypeAnnotation> = emptyList(),
    val associatedTypeBindings: Map<String, ParsedTypeAnnotation> = emptyMap(),
    val loc: Loc
)

/**
 * Ability dependency declared on an ability.
 *
 * Example: `ability Ord for 'a` depends on `Eq 'a`
 */
@Serializable
data class ParsedAbilityDep(
    val abilityName: String,
    val typeArgs: List<ParsedTypeAnnotation> = emptyList(),
    val loc: Loc
)

/**
 * Ability constraint on the ability declaration itself.
 *
 * Example: in `ability Ord for 'a where 'a: Eq`, the constraint is Eq on 'a.
 */
@Serializable
data class ParsedAbilityConstraint(
    val abilityName: String,
    val typeParam: String,
    val typeArgs: List<ParsedTypeAnnotation> = emptyList(),
    val loc: Loc
)

/**
 * Performer parameter.
 *
 * Example: in `impl FileSystem for FileLogger { path: Str }`, path is a param.
 */
@Serializable
data class ParsedPerformerParam(
    val name: String,
    val type: ParsedTypeAnnotation,
    val loc: Loc
)
