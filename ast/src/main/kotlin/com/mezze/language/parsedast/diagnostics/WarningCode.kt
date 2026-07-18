package com.mezze.language.parsedast.diagnostics

/**
 * Stable identifier for every distinct WARNING the Mezze compiler can emit.
 *
 * Warnings are on by default and non-fatal — they never fail type-checking, but
 * they flag likely mistakes or dead code. This is the linter surface.
 *
 * Codes are `W0xxx`, STATIC — once assigned, never reused or renumbered. New
 * entries are appended; removed warnings are deprecated, not deleted, so a
 * given `W0xxx` always means the same thing across versions.
 *
 * Category ranges:
 * - `W01xx` unused / dead bindings (unused var, unused import)
 * - `W02xx` shadowing — reserved; same-scope shadowing is a hard error
 *   (T0306), and cross-scope shadowing is intentionally allowed, so there is
 *   no shadowing WARNING. Range kept reserved so codes stay stable.
 * - `W03xx` redundant / dead code (constant condition, discarded value, ...)
 * - `W04xx` effect / handler hygiene (unused handler, ...)
 */
enum class WarningCode {
    // ─── Unused / dead bindings (W01xx) ──────────────────────────────────
    W0101_UNUSED_VARIABLE,   // a bound name (let / lambda param / match binding) is never used
    W0102_UNUSED_IMPORT,     // an imported name is never referenced

    // ─── Redundant / dead code (W03xx) ───────────────────────────────────
    W0301_DISCARDED_VALUE,   // a non-unit expression's result is computed then thrown away
    W0302_CONSTANT_CONDITION, // `if True/False then ...` — one branch is dead
    W0303_REDUNDANT_BOOLEAN,  // `!(!b)`, `x == True`, `if b then True else False`

    // ─── Effect / handler hygiene (W04xx) ────────────────────────────────
    W0401_UNUSED_HANDLER;    // `perform Handler in body` where body performs no op of the handler's effect

    /** The wire code, e.g. "W0101". */
    fun display(): String = name.substringBefore('_')
}
