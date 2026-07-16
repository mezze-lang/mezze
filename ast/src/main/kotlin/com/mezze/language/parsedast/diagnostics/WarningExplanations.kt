package com.mezze.language.parsedast.diagnostics

/** Human-readable explanation for each [WarningCode], surfaced by `mez explain`. */
fun WarningCode.explain(): String = when (this) {
    WarningCode.W0101_UNUSED_VARIABLE ->
        "A bound name (a `let`, a lambda parameter, or a match binding) is never " +
        "used. Remove it, or prefix it with `_` (e.g. `_x`) to say it's intentionally unused."

    WarningCode.W0102_UNUSED_IMPORT ->
        "An imported name is never referenced in this module. Remove it from the " +
        "`use` clause to keep imports tidy."

    WarningCode.W0201_SHADOWED_BINDING ->
        "An inner binding hides an outer one of the same name. This is legal, but " +
        "often unintentional — the outer value is no longer reachable by that name. " +
        "Rename one of them if the shadowing wasn't intended."

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
}

/** Look up a warning explanation by its display code ("W0101") or full name. */
fun explainWarningCode(codeStr: String): String? {
    val match = WarningCode.entries.firstOrNull {
        it.display().equals(codeStr, ignoreCase = true) || it.name.equals(codeStr, ignoreCase = true)
    } ?: return null
    return "${match.display()} (${match.name.substringAfter('_')}): ${match.explain()}"
}
