package com.mezze.language.parsedast.diagnostics

/** Human-readable explanation for each [WarningCode], surfaced by `mez explain`. */
fun WarningCode.explain(): String = when (this) {
    WarningCode.W0101_UNUSED_VARIABLE ->
        "A `let` binds a name that is never used. Remove the binding. If the expression " +
        "must still run for its effect, keep it on its own as a statement: a statement's " +
        "value is dropped and its effects still happen. (`let _ = …` is not a thing: " +
        "`_` is only a pattern wildcard.)"

    WarningCode.W0104_UNUSED_PATTERN_BINDING ->
        "A pattern binds a name that is never used. For a record field, leave the field out " +
        "and end the pattern with `...` (`Ok { ... }`, `{ a, ... }`). For one positional value " +
        "(a list element, a `$(…)` string hole) write `_`. For a list's tail `...rest`, write " +
        "`...` without a name. A name starting with `_` is an ordinary name and is not exempt."

    WarningCode.W0103_UNUSED_PARAMETER ->
        "A lambda's parameter is never used. A parameter is not a free name: a " +
        "lambda's parameters destructure the record its caller passes, so `x` IS " +
        "the field `x`, and renaming it `_x` makes callers pass `_x` instead. " +
        "Remove the parameter if no caller needs to pass it. A lambda whose shape " +
        "is given — a callback, an impl operation, an annotated binding — is never " +
        "warned about, since its parameters are fixed by that shape."

    WarningCode.W0102_UNUSED_IMPORT ->
        "An imported name is never referenced in this module. Remove it from the " +
        "`use` clause to keep imports tidy."

    WarningCode.W0301_DISCARDED_VALUE ->
        "A non-unit expression's result is computed and then thrown away (it's not " +
        "the last expression and isn't bound). This is usually a mistake — bind it " +
        "with `let`, or remove it if it's pure and has no effect."

    WarningCode.W0302_CONSTANT_CONDITION ->
        "The condition of this `if`/`match` is a constant, so one branch can never " +
        "run. Simplify to the branch that always executes."

    WarningCode.W0303_REDUNDANT_BOOLEAN ->
        "A boolean expression can be simplified — e.g. `!(!b)` is `b`, " +
        "`if b then True else False` is `b`, and `x == True` is `x`."

    WarningCode.W0401_UNUSED_HANDLER ->
        "A `perform Handler in body` handles an effect that the body doesn't " +
        "perform. The handler is dead — remove it from the `perform` clause, " +
        "or add an op that uses it."

    WarningCode.W0501_DEPRECATED ->
        "This uses a declaration its author marked `#{ deprecated: True }`. The " +
        "author's `since` and `note` fields, when present, say when and what to use " +
        "instead. It still works; the warning is the author asking you to move off it."
}

/** Look up a warning explanation by its display code ("W0101") or full name. */
fun explainWarningCode(codeStr: String): String? {
    val match = WarningCode.entries.firstOrNull {
        it.display().equals(codeStr, ignoreCase = true) || it.name.equals(codeStr, ignoreCase = true)
    } ?: return null
    return "${match.display()} (${match.name.substringAfter('_')}): ${match.explain()}"
}
