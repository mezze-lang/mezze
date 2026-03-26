package com.mezze.language.typedast

import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.Loc
import kotlinx.serialization.Serializable

/**
 * TypedAST patterns — all patterns have a type.
 */

@Serializable
sealed interface TypedPattern {
    val type: MezzeType
    val loc: Loc
}

@Serializable data class TypedVarPattern(val name: String, override val type: MezzeType, override val loc: Loc) : TypedPattern
@Serializable data class TypedWildcardPattern(override val type: MezzeType, override val loc: Loc) : TypedPattern
@Serializable data class TypedLiteralPattern(val value: TypedExpr, override val type: MezzeType, override val loc: Loc) : TypedPattern

@Serializable
data class TypedVariantPattern(
    val tag: String,
    val payload: TypedPattern?,
    override val type: MezzeType,
    override val loc: Loc
) : TypedPattern

@Serializable
data class TypedRecordPattern(
    val fields: List<TypedFieldPattern>,
    val isOpen: Boolean,
    override val type: MezzeType,
    override val loc: Loc
) : TypedPattern

@Serializable
data class TypedFieldPattern(
    val name: String,
    val pattern: TypedPattern?,
    val loc: Loc
)

@Serializable
data class TypedSeqPattern(
    val elements: List<TypedPattern>,
    val rest: String?,
    override val type: MezzeType,
    override val loc: Loc
) : TypedPattern
