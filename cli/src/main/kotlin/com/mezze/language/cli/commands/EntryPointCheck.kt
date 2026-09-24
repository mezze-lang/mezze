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
 *   - the residual effect row carries NO concrete effect — only the `*`
 *     performed-marker and/or an open row-var tail. The entry has no
 *     enclosing handler, so any concrete effect left undischarged (Console,
 *     Time, Async, a user effect) leaks a MezzeSuspend at runtime; handle
 *     them with `perform <Performer> in …` inside the entry.
 *
 * The typechecker already infers a type for `main` (including its effect
 * row). This module decides whether that type is acceptable as an entry
 * point — that's a CLI rule, not a typechecker rule. (The typechecker does
 * NOT special-case the entry: `main` is not a keyword; the entry name comes
 * from Mezze.toml.)
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
        data class UnhandledEffects(val effects: List<String>) : Result()
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

        // The entry has no enclosing handler, so any CONCRETE effect left on its
        // residual row leaks a MezzeSuspend at runtime — there is no host
        // auto-discharge for "ambient" effects (a bare `println` entry prints
        // nothing). `*` (performed) and an open row-var tail are not concrete
        // effects (absent from allEffectNames), so `{} -> {} <*>` is fine.
        val unhandled = residualEffects(type)?.allEffectNames?.sorted().orEmpty()
        if (unhandled.isNotEmpty()) {
            return Result.UnhandledEffects(unhandled)
        }

        return Result.Ok
    }

    /** Effects on the LAST arrow of a (possibly curried) function type. */
    private fun residualEffects(fn: MezzeType.FunctionType): MezzeType.EffectRow? {
        var cursor = fn
        var inner: MezzeType = cursor.returnType
        while (inner is MezzeType.FunctionType) { cursor = inner; inner = cursor.returnType }
        return cursor.effects
    }

    /**
     * The whole entry rule for a module: find the entry the manifest names, then
     * judge it. Null when the entry is present and acceptable.
     *
     * One place, because there were three — `mez check`, `mez build` and the wire
     * runner each found the decl, checked it, and wrote their own message. They had
     * drifted: build and the runner rejected a MISSING entry, `check` skipped its
     * whole entry block when the decl was absent. So a manifest naming an entry no
     * module defines passed `mez check` and then failed `mez build`, and the one
     * command whose job is to tell you in advance stayed quiet.
     */
    fun entryError(decls: List<TypedLetDecl>, entryName: String): String? {
        val decl = decls.find { it.name == entryName }
            ?: return "App must define a '$entryName' function with type `{} -> {} <*>` " +
                "(the entry named by Mezze.toml's `entry = \"$entryName\"`)"
        return errorMessage(check(decl), entryName)
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
        is Result.UnhandledEffects -> {
            val list = result.effects.joinToString(", ")
            "Entry '$entryName' performs $list, but nothing discharges it — the entry has no " +
                "enclosing handler, so it leaks at runtime (you get a raw suspended value, not the result). " +
                "Wrap the body in a handler: `perform <Performer> in …` " +
                "(e.g. `Console` for Log, `SystemTime` for Time, `LoomAsync` for Async)."
        }
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
        is MezzeType.VarType -> "'${type.variable.name}"
        // A named variant — Bool among them, since it is a builtin variant rather
        // than a primitive — renders as its name, not as a Kotlin toString.
        is MezzeType.VariantType -> type.identity.displayNameOrNull() ?: type.toString()
        else -> type.toString()
    }
}
