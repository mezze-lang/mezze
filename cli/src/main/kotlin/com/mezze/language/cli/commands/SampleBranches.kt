package com.mezze.language.cli.commands

import com.mezze.language.ast.MezzeType
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedMatch
import com.mezze.language.typedast.TypedVar
import com.mezze.language.typedast.TypedVariantPattern
import com.mezze.language.typedast.forEachChild

/**
 * Which constructors a function's own `match` arms discriminate on, per parameter.
 *
 * ## Why this exists
 *
 * A sample run is only worth reading if it exercised the function. Drawing values
 * blindly does not: a derived variant picks its constructor by bisecting over
 * `Bool` draws and weights an EMPTY constructor at one in eight, so an arm
 * reachable only through one shows up in about an eighth of rows. MEASURED on an
 * eight-constructor type, seven of them empty: a seeded run covered 2 of 8 arms in
 * 16 rows and 7 of 8 in 100. A run that never reached an arm looks exactly like a
 * run that reached it and found nothing wrong.
 *
 * Steering the DRAWS cannot fix that on its own, because a draw-level sampler does
 * not know which decisions matter — enumerating choice prefixes uniformly spends
 * seven eighths of its rows re-deriving the same carrying constructor. The thing
 * that knows is the function: its `match` arms ARE the list of shapes worth
 * generating, and `mez sample` has already type-checked it.
 *
 * So the leading rows are built from the arms rather than drawn: row `i` pins the
 * parameter to the `i`-th constructor the function matches on, and the payload
 * inside is still drawn normally. Rows past the arm count go back to ordinary
 * draws, because beyond covering the shapes what is wanted is variety.
 *
 * ## What it deliberately does not do
 *
 * Only a `match` whose scrutinee IS a parameter is used. A match on a computed
 * value (`match xs.len is …`) would need the sampler to invert that computation,
 * which is a solver, not an inventory. Those arms are simply not pinned, and the
 * draw-level sampler still varies them — this narrows what is missed rather than
 * pretending to cover it.
 *
 * Guards are likewise not inspected: `Circle { r } if r > 10` pins the
 * constructor, not the guard. See the branch-coverage item for what reporting the
 * difference would take.
 */
object SampleBranches {

    /** A constructor to build, and the payload record it needs (null when empty). */
    data class Arm(val tag: String, val payload: MezzeType?)

    /**
     * A pinned field: the source, and whether producing it DRAWS anything.
     *
     * The flag is load-bearing rather than informational. A row pinned entirely to
     * empty constructors performs no `Sample` at all, and wrapping it in
     * `perform CoveringSample` is then a handler that can never fire — which the
     * compiler rejects outright (V0510), exactly as it already does for a
     * zero-argument function. So the command has to know whether a row draws before
     * it decides to install a handler.
     */
    data class Pinned(val source: String, val draws: Boolean)

    /**
     * Per parameter field, the constructors its matches discriminate on — in SOURCE
     * ORDER, de-duplicated, so row 0 gets the first arm the reader sees.
     *
     * Empty for a parameter nothing matches on, which is the common case for a
     * function that just computes; those fields keep drawing normally.
     */
    fun inventory(decl: TypedLetDecl, fieldNames: Set<String>): Map<String, List<Arm>> {
        val found = linkedMapOf<String, MutableList<Arm>>()
        walk(decl.value) { expr ->
            val match = expr as? TypedMatch ?: return@walk
            // A Mezze function destructures its parameter record into locals, so a
            // match ON a parameter reads as a plain variable of that field's name.
            val scrutinee = match.scrutinee as? TypedVar ?: return@walk
            if (scrutinee.name !in fieldNames) return@walk
            val ctors = (match.scrutinee.type as? MezzeType.VariantType)?.constructors ?: return@walk
            val arms = found.getOrPut(scrutinee.name) { mutableListOf() }
            for (case in match.cases) {
                for (pattern in case.patterns) {
                    val tag = (pattern as? TypedVariantPattern)?.tag ?: continue
                    // `containsKey` rather than a null check: a constructor with no
                    // payload maps to null, and that is exactly the case to keep.
                    if (!ctors.containsKey(tag)) continue
                    if (arms.none { it.tag == tag }) arms.add(Arm(tag, ctors[tag]))
                }
            }
        }
        return found.filterValues { it.isNotEmpty() }
    }

    /**
     * Mezze source constructing [arm], with any payload fields drawn as usual.
     *
     * An empty constructor is its bare name; a carrying one takes its payload
     * record written out field by field, so the SHAPE is pinned while the VALUES
     * inside still vary from row to row.
     */
    fun construct(arm: Arm): Pinned {
        val payload = arm.payload ?: return Pinned(arm.tag, draws = false)
        val fields = (payload as? MezzeType.RecordType)?.fields
            // A payload this command cannot write out field-by-field is left to the
            // ordinary draw rather than guessed at.
            ?: return Pinned(arm.tag, draws = false)
        if (fields.isEmpty()) return Pinned("${arm.tag} {}", draws = false)
        val source = fields.entries.joinToString(", ", prefix = "${arm.tag} { ", postfix = " }") { (name, type) ->
            "$name = ${SampleGen.of(type)}"
        }
        return Pinned(source, draws = true)
    }

    /** Depth-first over every sub-expression, including the node itself. */
    private fun walk(expr: TypedExpr, visit: (TypedExpr) -> Unit) {
        visit(expr)
        expr.forEachChild { walk(it, visit) }
    }
}
