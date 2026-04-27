package com.mezze.language.cli.commands

import com.mezze.language.ast.MezzeType
import com.mezze.language.typedast.TypedLetDecl

/**
 * The CLI's convention: an entry function (the binding pointed at by
 * Mezze.toml's `entry = "..."`) must have type `{} -> {} <*>` from the
 * user's perspective.
 *
 * Concretely, ignoring compiler-injected dictionary params:
 *   - param record has no user-visible fields and is closed (`{}`)
 *   - return record has no user-visible fields and is closed (`{}`)
 *   - effect row is unconstrained (`<*>` — Console, IO, async, etc. all OK)
 *
 * The typechecker already infers a type for `main`. This module decides
 * whether that type is acceptable as an entry point — that's a CLI rule,
 * not a typechecker rule.
 */
object EntryPointCheck {

    // The typechecker injects synthetic record fields for ability dispatch
    // (and, in future, other compiler-managed params). Strip them before
    // comparing record shapes — `MagicParams.isMagic` is the single source
    // of truth for "is this name compiler-injected?".

    sealed class Result {
        object Ok : Result()
        data class NotAFunction(val actualType: MezzeType) : Result()
        data class WrongParamShape(val actualParamType: MezzeType, val visibleFieldNames: List<String>) : Result()
        data class WrongReturnShape(val actualReturnType: MezzeType) : Result()
    }

    fun check(decl: TypedLetDecl): Result {
        val type = decl.scheme.type
        if (type !is MezzeType.FunctionType) return Result.NotAFunction(type)

        val visibleParams = userVisibleFields(type.paramType)
            ?: return Result.WrongParamShape(type.paramType, emptyList())
        if (visibleParams.isNotEmpty()) {
            return Result.WrongParamShape(type.paramType, visibleParams.keys.toList())
        }

        if (!isEmptyClosedRecord(type.returnType)) {
            return Result.WrongReturnShape(type.returnType)
        }

        return Result.Ok
    }

    /** Format a [Result] into a one-line user-facing error, or null if Ok. */
    fun errorMessage(result: Result, entryName: String): String? = when (result) {
        Result.Ok -> null
        is Result.NotAFunction ->
            "Entry '$entryName' must be a function `{} -> {} <*>`, got: ${render(result.actualType)}"
        is Result.WrongParamShape -> {
            val extras = result.visibleFieldNames.takeIf { it.isNotEmpty() }?.joinToString(", ") { "`$it`" }
            val detail = if (extras != null) {
                " — got fields: $extras"
            } else {
                " — got: ${render(result.actualParamType)}"
            }
            "Entry '$entryName' must take no parameters (`{}`)$detail"
        }
        is Result.WrongReturnShape ->
            "Entry '$entryName' must return `{}`, got: ${render(result.actualReturnType)}"
    }

    /** Return user-visible fields, or null if not a closed record. */
    private fun userVisibleFields(type: MezzeType): Map<String, MezzeType>? {
        if (type !is MezzeType.RecordType) return null
        if (type.isOpen) return null
        return type.fields.filterKeys { !com.mezze.language.ast.MagicParams.isMagic(it) }
    }

    private fun isEmptyClosedRecord(type: MezzeType): Boolean =
        userVisibleFields(type)?.isEmpty() ?: false

    /**
     * Compact rendering for diagnostic messages. Strips dict params from
     * records. Falls back to type.toString() for shapes not special-cased.
     */
    private fun render(type: MezzeType): String = when (type) {
        is MezzeType.IntType -> "Int"
        is MezzeType.FloatType -> "Float"
        is MezzeType.StringType -> "Str"
        is MezzeType.BoolType -> "Bool"
        is MezzeType.RecordType -> {
            val visible = type.fields.filterKeys { !com.mezze.language.ast.MagicParams.isMagic(it) }
            if (visible.isEmpty() && type.isClosed) "{}"
            else if (visible.isEmpty()) "{ ... }"
            else {
                val body = visible.entries.joinToString(", ") { (k, v) -> "$k: ${render(v)}" }
                if (type.isOpen) "{ $body, ... }" else "{ $body }"
            }
        }
        is MezzeType.FunctionType -> {
            val params = render(type.paramType)
            val returns = render(type.returnType)
            val effects = if (type.effects != null) " <*>" else ""
            "$params -> $returns$effects"
        }
        is MezzeType.SeqType -> "[${render(type.elementType)}]"
        is MezzeType.VarType -> "'${type.variable.name}"
        else -> type.toString()
    }
}
