package com.mezze.language.ast

/**
 * A type rendered into a canonical string, for DISPLAY.
 *
 * This was once the signature index itself: the rendering was stored on `decls`
 * and searched as text. That was the defect — a rendered signature cannot be read
 * back as a type, so it could only be displayed or substring-matched, and
 * `LIKE '%Log%'` also finds `Logger`. The index is now the `type_nodes` tables
 * (see `SignatureIndex`), and this renders what those rows rebuild.
 *
 * Canonical still matters for display, because the same signature shown two ways
 * reads as two signatures.
 *
 * Three things are canonicalised, each because it would otherwise split one
 * signature into many:
 *
 *  - **Type variables are renamed by first appearance** — `'a`, `'b`, `'c`…
 *    The names a user picked are arbitrary, so `{xs: ['a]} -> Int` and
 *    `{xs: ['t]} -> Int` are one signature and must render identically.
 *
 *  - **Record fields are sorted by name.** Mezze records are *named*, so field
 *    order carries no meaning: `{x: Int, y: Str}` and `{y: Str, x: Int}` are
 *    the same type. This is also why signature search here avoids the
 *    argument-permutation problem that costs Hoogle real effort — positional
 *    parameters would need every ordering considered, named ones need one.
 *
 *  - **Effects and ability constraints are sorted.** They are sets; the order
 *    the typechecker happened to collect them in is not information.
 *
 * What is deliberately NOT done: no simplification, no alias expansion, no
 * normalisation of the *structure*. This is a rendering, not a solver. Matching
 * `['a] -> Int` against `[Str] -> Int` is subsumption and belongs to the query
 * side, which unifies against the real types — the string index only narrows
 * candidates.
 */
object SignatureNormalizer {

    /** The canonical rendering of [type], with type variables renamed. */
    fun render(type: MezzeType): String = Renamer().render(type)

    // `effectNames` and `constraintNames` lived here to fill the two JSON-array
    // columns. Those are junction tables now, written from the same walk that
    // writes the nodes, so re-deriving the names off a type at read time would be
    // a second source for one fact — delete it rather than keep it in step.

    /**
     * Renaming is stateful across one rendering — the same variable must get
     * the same name everywhere it appears, or `{x: 'a} -> 'a` would render with
     * two different names and stop looking like the identity function.
     */
    private class Renamer {
        private val assigned = LinkedHashMap<String, String>()

        fun render(type: MezzeType): String = when (type) {
            is MezzeType.IntType -> "Int"
            is MezzeType.FloatType -> "Float"
            is MezzeType.StringType -> "Str"
            is MezzeType.FracType -> "Frac"
            is MezzeType.CharType -> "Char"
            is MezzeType.BytesType -> "Bytes"

            is MezzeType.VarType -> nameFor(type.variable.name)

            is MezzeType.RecordType -> {
                // Sorted: field order is not part of a record's identity.
                val body = type.fields.entries
                    .sortedBy { it.key }
                    .joinToString(", ") { (name, fieldType) -> "$name: ${render(fieldType)}" }
                when {
                    type.isOpen && body.isEmpty() -> "{ ... }"
                    type.isOpen -> "{ $body, ... }"
                    body.isEmpty() -> "{}"
                    else -> "{ $body }"
                }
            }

            is MezzeType.VariantType -> type.identity.displayNameOrNull() ?: "?variant"

            is MezzeType.NewtypeType -> type.identity.displayNameOrNull() ?: "?newtype"

            is MezzeType.TypeApplication -> {
                // `constructor` is the head's NAME, not a nested type.
                if (type.args.isEmpty()) type.constructor
                else "${type.constructor} ${type.args.joinToString(" ") { renderArg(it) }}"
            }

            is MezzeType.FunctionType -> {
                val params = render(type.paramType)
                val returns = render(type.returnType)
                val constraints = type.abilityConstraints
                    .map { constraint ->
                        val args = constraint.typeArgs.joinToString(" ") { renderArg(it) }
                        if (args.isEmpty()) constraint.abilityName
                        else "${constraint.abilityName} $args"
                    }
                    .distinct()
                    .sorted()
                    .joinToString(", ")
                val withClause = if (constraints.isEmpty()) "" else " with $constraints"
                val effects = type.effects?.allEffectNames?.sorted().orEmpty()
                val effectClause = if (effects.isEmpty()) "" else " <${effects.joinToString(", ")}>"
                "$params$withClause -> $returns$effectClause"
            }

            // Anything without a stable rendering falls back to toString. It
            // will simply fail to match a query rather than corrupt the index —
            // but it is worth noticing if it shows up, hence the marker.
            else -> "?${type::class.simpleName}"
        }

        /** Parenthesise a composite argument so `Seq (Option Int)` stays readable. */
        private fun renderArg(type: MezzeType): String {
            val rendered = render(type)
            val needsParens = type is MezzeType.FunctionType ||
                (type is MezzeType.TypeApplication && type.args.isNotEmpty())
            return if (needsParens) "($rendered)" else rendered
        }

        /** `'a`, `'b`, … in order of first appearance. */
        private fun nameFor(original: String): String =
            assigned.getOrPut(original) { "'" + letterAt(assigned.size) }

        /** 0 -> a, 25 -> z, 26 -> a1, 27 -> b1 … so this never runs out. */
        private fun letterAt(index: Int): String {
            val letter = ('a' + (index % 26))
            val cycle = index / 26
            return if (cycle == 0) letter.toString() else "$letter$cycle"
        }
    }
}
