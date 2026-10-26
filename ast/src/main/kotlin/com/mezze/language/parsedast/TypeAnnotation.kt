package com.mezze.language.parsedast

import kotlinx.serialization.Serializable

/**
 * Type annotations — syntax as written by the user, not resolved types.
 *
 * These are converted to MezzeType by the typechecker.
 * No resolvedContentHash, no inferredType, no typechecker artifacts.
 */
@Serializable
sealed interface ParsedTypeAnnotation {
    val loc: Loc
}

/**
 * Simple named type or type variable.
 *
 * Examples:
 * - `Int` → name="Int"
 * - `'a` → name="'a"
 * - `Bool` → name="Bool"
 */
@Serializable
data class ParsedNamedType(
    val name: String,
    override val loc: Loc
) : ParsedTypeAnnotation

/**
 * Qualified type reference.
 *
 * Examples:
 * - `codec::Entry` → modulePath=["codec"], typeName="Entry"
 * - `codec::Entry Str Json` → modulePath=["codec"], typeName="Entry", args=[Str, Json]
 */
@Serializable
data class ParsedQualifiedType(
    val modulePath: List<String>,
    val typeName: String,
    val args: List<ParsedTypeAnnotation> = emptyList(),
    override val loc: Loc
) : ParsedTypeAnnotation

/**
 * Type application — generic type applied to arguments.
 *
 * Examples:
 * - `Option Int` → constructor="Option", args=[Int]
 * - `Result Str Error` → constructor="Result", args=[Str, Error]
 * - `Map Str Int` → constructor="Map", args=[Str, Int]
 */
@Serializable
data class ParsedTypeApp(
    val constructor: String,
    val args: List<ParsedTypeAnnotation>,
    override val loc: Loc
) : ParsedTypeAnnotation

/**
 * Record type annotation.
 *
 * Examples:
 * - `{ x: Int, y: Str }` → entries=[x:Int, y:Str], isOpen=false
 * - `{ x: Int, ... }` → entries=[x:Int], isOpen=true
 */
@Serializable
data class ParsedRecordType(
    val entries: List<ParsedRecordTypeEntry>,
    val isOpen: Boolean = false,
    override val loc: Loc
) : ParsedTypeAnnotation

@Serializable
data class ParsedRecordTypeEntry(
    val name: String,
    val type: ParsedTypeAnnotation,
    val loc: Loc
)

/**
 * Variant type annotation.
 *
 * Examples:
 * - `| Some { val: 'a }, None |` → constructors=[Some{val:'a}, None], isOpen=false
 * - `| Red, Green, Blue |` → constructors=[Red, Green, Blue], isOpen=false
 */
@Serializable
data class ParsedVariantType(
    val constructors: List<ParsedVariantTypeCtor>,
    val isOpen: Boolean = false,
    override val loc: Loc
) : ParsedTypeAnnotation

@Serializable
data class ParsedVariantTypeCtor(
    val name: String,
    val dataType: ParsedTypeAnnotation? = null,
    val loc: Loc
)

/**
 * Function type annotation.
 *
 * Examples:
 * - `{ x: Int } -> Str` → paramType={x:Int}, returnType=Str, effects=null
 * - `{ x: Int } -> Str <FileSystem>` → effects=<FileSystem>
 * - `{ x: 'a } with Eq 'a -> Bool` → abilityConstraints=[Eq 'a]
 *
 * Syntax order: `{ params } with Constraints -> Return <Effects>`. The
 * `with` clause MUST precede the arrow — post-arrow `with` is rejected
 * by the parser (P0120). This keeps the return slot focused on the value
 * type and its effect row, with constraints living adjacent to where
 * type variables are introduced (the param block).
 */
@Serializable
data class ParsedFunctionType(
    val paramType: ParsedTypeAnnotation,
    val returnType: ParsedTypeAnnotation,
    val effects: ParsedEffectRow? = null,
    val abilityConstraints: List<ParsedFnAbilityConstraint> = emptyList(),
    override val loc: Loc
) : ParsedTypeAnnotation

/**
 * Seq type annotation: `[Int]`, `[Str]`
 */
@Serializable
data class ParsedSeqType(
    val elementType: ParsedTypeAnnotation,
    override val loc: Loc
) : ParsedTypeAnnotation

/**
 * Effect row annotation on function types.
 *
 * Examples:
 * - `<FileSystem>` → effects=["FileSystem"], isPolymorphic=false
 * - `<FileSystem, Log>` → effects=["FileSystem","Log"]
 * - `<...>` → isPolymorphic=true
 * - `<*>` → isPerformed=true
 * - `<FileSystem, ...>` → effects=["FileSystem"], isPolymorphic=true
 * - `<Exception having 'error = Str>` → effectAnnotations=[Exception{error:Str}]
 */
@Serializable
data class ParsedEffectRow(
    val effects: List<String> = emptyList(),
    val effectAnnotations: List<ParsedEffectAnnotation> = emptyList(),
    val isPolymorphic: Boolean = false,
    val isPerformed: Boolean = false,
    /**
     * Effects written with a `?` — MAY be performed, and if they are, with the
     * instance this row states.
     *
     * `<...>` is zero or more and `<E, ...>` is at least E, so neither can say
     * "optional, and if present then THIS". A callback slot that CATCHES an
     * effect needs exactly that: `http_router`'s verbs discharge Exception for
     * their handler, so a pure handler is fine, and a throwing one must throw
     * what the verb can turn into a response. Written bare (`<...>`) the thrown
     * type is absorbed unchecked and the mistake surfaces at run time; written
     * `<Exception, ...>` every handler would have to throw.
     */
    val optionalEffects: Set<String> = emptySet(),
    val loc: Loc,
    /**
     * What each effect name meant WHERE THE ROW WAS WRITTEN, by identity — set
     * when the row is part of a type declaration and its module is stamped
     * (DeclIdentity). A type declaration is expanded wherever it is used, and
     * `type Sampler 'a = {} -> 'a <Sample>` used from another module must still
     * mean the declaring module's `Sample`. Null for a row nobody stamped.
     */
    val resolved: Map<String, com.mezze.language.typedast.Hash>? = null,
) {
    val allEffectNames: Set<String>
        get() = effects.toSet() + effectAnnotations.map { it.name }.toSet()
}

/**
 * Effect annotation with optional associated types.
 *
 * Examples:
 * - `Exception` → name="Exception", associatedTypes={}
 * - `Exception having 'error = Str` → name="Exception", associatedTypes={'error: Str}
 */
@Serializable
data class ParsedEffectAnnotation(
    val name: String,
    val associatedTypes: Map<String, ParsedTypeAnnotation> = emptyMap(),
    val loc: Loc
)
