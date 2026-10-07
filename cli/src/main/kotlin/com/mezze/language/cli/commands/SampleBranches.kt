package com.mezze.language.cli.commands

import com.mezze.language.ast.MezzeType
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedMatch
import com.mezze.language.typedast.TypedVar
import com.mezze.language.typedast.TypedVariantPattern
import com.mezze.language.typedast.BindingRef
import com.mezze.language.typedast.TypedCall
import com.mezze.language.typedast.TypedFieldAccess
import com.mezze.language.typedast.TypedIntLit
import com.mezze.language.typedast.TypedRecord
import com.mezze.language.typedast.TypedRecordEntry
import com.mezze.language.typedast.TypedStringLit
import com.mezze.language.typedast.TypedStringPart
import com.mezze.language.typedast.forEachChild

/**
 * What `mez sample` learns about a function by READING it, before running it.
 *
 * ## Why
 *
 * A sample run is only worth reading if it exercised the function, and blind
 * draws mostly do not. Two measured cases:
 *
 *  - a `match` over a variant: a derived impl weights an EMPTY constructor at one
 *    in eight, so on an eight-constructor type a seeded run reached 2 of 8 arms in
 *    16 rows and 7 of 8 in 100;
 *  - `n > max_value` with `let max_value = 100`: the seeded sampler produced Int's
 *    extremes and `1`, and not one row where the function changes its answer.
 *
 * The function itself says which values matter, and the command has already
 * type-checked it. So the leading rows are BUILT from what the body tests rather
 * than drawn, each carrying the reason it exists — row `i` pins each parameter to
 * its `i`-th interesting value. Rows past that draw as before, because beyond
 * reaching the branches what is wanted is variety.
 *
 * ## What is read
 *
 *  - a `match` on a parameter: one value per constructor arm, payload still drawn;
 *  - a comparison of a parameter against a value KNOWN AT COMPILE TIME: the two
 *    values either side of where it flips. "Known" means a literal, a top-level
 *    constant (resolved by HASH, never by name), a chain of them, or `Int`
 *    arithmetic over them. A top-level value cannot perform effects (V0502), so
 *    this needs nothing to run and is the same on every run.
 *
 * ## What is deliberately not read, yet
 *
 *  - a comparison against a constant defined by a FUNCTION CALL — evaluating it
 *    needs the compile-time evaluator, not folding; it is left unpinned rather
 *    than guessed;
 *  - a `match` or comparison on a FIELD of a parameter, or on a value derived from
 *    one through `let`s;
 *  - comparisons between two parameters, sizes, and branches on what an EFFECT
 *    returns (which a scripted performer, not an input, would reach);
 *  - guards.
 *
 * An unread branch still gets ordinary draws: this narrows what is missed rather
 * than claiming to cover it.
 */
object SampleBranches {

    /** A constructor to build, and the payload record it needs (null when empty). */
    data class Arm(val tag: String, val payload: MezzeType?)

    /**
     * One value to pin a parameter to for one row: its source, whether producing
     * it DRAWS anything, and WHY this row exists.
     *
     * `draws` is load-bearing. A row pinned entirely to draw-free values performs
     * no `Sample`, and wrapping it in `perform CoveringSample` is then a handler
     * that can never fire — rejected by V0510, as for a zero-argument function.
     *
     * `why` is what makes a pinned row readable: it names the branch it targets,
     * so a reader sees `if n > max_value (= 100) — then` beside `n = 101` rather
     * than wondering where 101 came from.
     */
    data class Pinned(val source: String, val draws: Boolean, val why: String = "")

    /**
     * Per parameter field, the values worth pinning it to — one per branch it
     * decides — in SOURCE ORDER, de-duplicated by value.
     *
     * Two kinds of branch are read, both decided by the parameter directly:
     *
     *  - a `match` on it: one value per constructor arm;
     *  - a comparison of it against a value KNOWN AT COMPILE TIME — a literal, or
     *    a top-level constant (`n > max_value`): the two values either side of the
     *    point where the comparison flips.
     *
     * [decls] is the module's top-level declarations, which is where a constant's
     * value is read from. A reference to one arrives as `ByHash`, so it is resolved
     * by HASH — never by matching its name.
     */
    fun inventory(
        decl: TypedLetDecl,
        fieldNames: Set<String>,
        decls: List<TypedLetDecl> = emptyList(),
    ): Map<String, List<Pinned>> {
        val found = linkedMapOf<String, MutableList<Pinned>>()
        fun add(field: String, pinned: Pinned) {
            val list = found.getOrPut(field) { mutableListOf() }
            if (list.none { it.source == pinned.source }) list.add(pinned)
        }
        val byHash = decls.associateBy { it.hash }

        walk(decl.value) { expr ->
            when (expr) {
                is TypedMatch -> matchArms(expr, fieldNames).forEach { (f, p) -> add(f, p) }
                is TypedCall -> comparison(expr, fieldNames, byHash)?.let { (f, ps) -> ps.forEach { add(f, it) } }
                else -> Unit
            }
        }
        return found.filterValues { it.isNotEmpty() }
    }

    /** The reason row [row] exists, or null when it pins nothing. */
    fun why(branches: Map<String, List<Pinned>>, row: Int): String? =
        branches.values.mapNotNull { it.getOrNull(row)?.why?.takeIf { w -> w.isNotEmpty() } }
            .joinToString("; ").ifEmpty { null }

    // ── match arms ────────────────────────────────────────────────────────────

    private fun matchArms(match: TypedMatch, fieldNames: Set<String>): List<Pair<String, Pinned>> {
        // A Mezze function destructures its parameter record into locals, so a
        // match ON a parameter reads as a plain variable of that field's name.
        val scrutinee = match.scrutinee as? TypedVar ?: return emptyList()
        if (scrutinee.name !in fieldNames) return emptyList()
        val ctors = (match.scrutinee.type as? MezzeType.VariantType)?.constructors ?: return emptyList()
        val out = mutableListOf<Pair<String, Pinned>>()
        for (case in match.cases) {
            for (pattern in case.patterns) {
                val tag = (pattern as? TypedVariantPattern)?.tag ?: continue
                // `containsKey` rather than a null check: a constructor with no
                // payload maps to null, and that is exactly the case to keep.
                if (!ctors.containsKey(tag)) continue
                val built = construct(Arm(tag, ctors[tag]))
                out.add(scrutinee.name to built.copy(why = "match ${scrutinee.name} — `$tag` arm"))
            }
        }
        return out
    }

    /**
     * Mezze source constructing [arm], with any payload fields drawn as usual: the
     * SHAPE is pinned while the VALUES inside still vary from row to row.
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

    // ── comparisons ───────────────────────────────────────────────────────────

    /**
     * `x <op> k` where `x` is a parameter and `k` is known at compile time: the
     * values either side of where the comparison flips.
     *
     * A comparison operator arrives as an `Ord`/`Eq` method call —
     * `n > max_value` is `n.gt { it = n, r = max_value }`. It is recognised only
     * when the operands are PRIMITIVE (`Int`, `Str`), where `gt` IS `Ord`'s: a user
     * type with a field that happens to be called `gt` is never read as a
     * comparison, so this is not name-matching on a method.
     */
    private fun comparison(
        call: TypedCall,
        fieldNames: Set<String>,
        byHash: Map<com.mezze.language.typedast.Hash, TypedLetDecl>,
    ): Pair<String, List<Pinned>>? {
        val op = (call.function as? TypedFieldAccess)?.field ?: return null
        if (op !in COMPARISONS) return null
        val args = (call.arg as? TypedRecord)?.entries
            ?.filterIsInstance<TypedRecordEntry.Field>()?.associate { it.name to it.value } ?: return null
        val left = args["it"] ?: return null
        val right = args["r"] ?: return null
        if (!isPrimitive(left.type) || !isPrimitive(right.type)) return null

        // Either side may hold the parameter. `k < n` is `n > k` seen from `n`, so
        // the operator is MIRRORED rather than read as written.
        val (param, other, effectiveOp) = when {
            isParam(left, fieldNames) -> Triple(left as TypedVar, right, op)
            isParam(right, fieldNames) -> Triple(right as TypedVar, left, MIRROR.getValue(op))
            else -> return null
        }
        val known = knownValue(other, byHash) ?: return null
        val shown = known.describe()
        val where = "${param.name} ${SYMBOL.getValue(effectiveOp)} $shown"
        val pins = when (known) {
            is Known.IntValue -> intBoundary(known.value, effectiveOp)
                .map { (v, holds) -> Pinned(v.toString(), draws = false, why = "if $where — ${verdict(holds)}") }
            is Known.StrValue -> strBoundary(known.value, effectiveOp)
                .map { (v, holds) -> Pinned(strLiteral(v), draws = false, why = "if $where — ${verdict(holds)}") }
        }
        return param.name to pins
    }

    /** A compile-time value, with how it should read in a row's reason. */
    private sealed interface Known {
        fun describe(): String
        data class IntValue(val value: Long, val via: String?) : Known {
            override fun describe() = if (via == null) "$value" else "$via (= $value)"
        }
        data class StrValue(val value: String, val via: String?) : Known {
            override fun describe() = if (via == null) strLiteral(value) else "$via (= ${strLiteral(value)})"
        }
    }

    /**
     * The value of [expr] if it is known without running anything: a literal, or a
     * reference to a top-level constant whose own value is known — followed through
     * a chain of constants (`let a = b`, `let b = 100`).
     *
     * A top-level value cannot perform effects (V0502), so its value is the same on
     * every run. Anything not resolvable this way — a constant defined by a
     * computation, or one from another module — yields null, and that branch is
     * simply not pinned; it still gets ordinary draws.
     */
    private fun knownValue(
        expr: TypedExpr,
        byHash: Map<com.mezze.language.typedast.Hash, TypedLetDecl>,
        via: String? = null,
        depth: Int = 0,
    ): Known? {
        if (depth > MAX_CONSTANT_CHAIN) return null
        return when (expr) {
            is TypedIntLit -> Known.IntValue(expr.value, via)
            is TypedStringLit -> {
                val parts = expr.parts
                if (parts.all { it is TypedStringPart.Literal }) {
                    Known.StrValue(parts.joinToString("") { (it as TypedStringPart.Literal).value }, via)
                } else null
            }
            is TypedVar -> {
                val ref = expr.binding as? BindingRef.ByHash ?: return null
                val target = byHash[ref.hash] ?: return null
                knownValue(target.value, byHash, via ?: expr.name, depth + 1)
            }
            is TypedCall -> foldInt(expr, byHash, via, depth)
            else -> null
        }
    }

    /**
     * `Int` arithmetic over values that are themselves known — `6 * 7`,
     * `base + 5` — folded here so an ordinary derived constant is understood
     * without running anything.
     *
     * Overflow, and division by zero, yield null rather than a value: in the
     * program both RAISE, so there is no value to pin against, and inventing one
     * would put the row on the wrong side of the branch. Anything beyond plain
     * arithmetic — a function call — is left unresolved.
     */
    private fun foldInt(
        call: TypedCall,
        byHash: Map<com.mezze.language.typedast.Hash, TypedLetDecl>,
        via: String?,
        depth: Int,
    ): Known? {
        val op = (call.function as? TypedFieldAccess)?.field ?: return null
        if (op !in ARITHMETIC || call.type !is MezzeType.IntType) return null
        val args = (call.arg as? TypedRecord)?.entries
            ?.filterIsInstance<TypedRecordEntry.Field>()?.associate { it.name to it.value } ?: return null
        val l = (knownValue(args["it"] ?: return null, byHash, null, depth + 1) as? Known.IntValue)?.value ?: return null
        val r = (knownValue(args["r"] ?: return null, byHash, null, depth + 1) as? Known.IntValue)?.value ?: return null
        val v = try {
            when (op) {
                "add" -> Math.addExact(l, r)
                "sub" -> Math.subtractExact(l, r)
                "mul" -> Math.multiplyExact(l, r)
                // JVM truncating division, which is what `Int` `/` is at run time — floorDiv
                // would differ for negative operands and pin the wrong side.
                "div" -> if (r == 0L) return null else l / r
                else -> return null
            }
        } catch (_: ArithmeticException) {
            return null
        }
        return Known.IntValue(v, via)
    }

    /**
     * The pair of values either side of where `x <op> k` flips, each with whether
     * the comparison HOLDS for it. An edge that would overflow `Int` is dropped
     * rather than wrapped — a wrapped value is on the wrong side.
     */
    private fun intBoundary(k: Long, op: String): List<Pair<Long, Boolean>> {
        val (lo, hi) = when (op) {
            "gt", "le", "eq", "neq" -> k to k.safeInc()
            "ge", "lt" -> k.safeDec() to k
            else -> return emptyList()
        }
        return listOfNotNull(lo, hi).map { it to holds(it.compareTo(k), op) }
    }

    /** For a string only equality has a boundary worth naming: `k`, and not-`k`. */
    private fun strBoundary(k: String, op: String): List<Pair<String, Boolean>> =
        if (op != "eq" && op != "neq") emptyList()
        else listOf(k, k + "x").map { it to holds(if (it == k) 0 else 1, op) }

    private fun holds(cmp: Int, op: String): Boolean = when (op) {
        "gt" -> cmp > 0
        "ge" -> cmp >= 0
        "lt" -> cmp < 0
        "le" -> cmp <= 0
        "eq" -> cmp == 0
        "neq" -> cmp != 0
        else -> false
    }

    private fun verdict(holds: Boolean) = if (holds) "then" else "else"

    private fun Long.safeInc(): Long? = if (this == Long.MAX_VALUE) null else this + 1
    private fun Long.safeDec(): Long? = if (this == Long.MIN_VALUE) null else this - 1

    private fun isParam(expr: TypedExpr, fieldNames: Set<String>): Boolean =
        expr is TypedVar && expr.name in fieldNames && expr.binding is BindingRef.Local

    private fun isPrimitive(type: MezzeType): Boolean =
        type is MezzeType.IntType || type is MezzeType.StringType

    /**
     * A string as Mezze source. `$` is escaped because `$(` opens an
     * interpolation; a pinned value must be the literal text, never code.
     */
    private fun strLiteral(v: String): String = buildString {
        append('"')
        for (c in v) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\t' -> append("\\t")
            '$' -> append("\\$")
            else -> append(c)
        }
        append('"')
    }

    private val COMPARISONS = setOf("gt", "ge", "lt", "le", "eq", "neq")
    private val ARITHMETIC = setOf("add", "sub", "mul", "div")
    private val MIRROR = mapOf("gt" to "lt", "lt" to "gt", "ge" to "le", "le" to "ge", "eq" to "eq", "neq" to "neq")
    private val SYMBOL = mapOf("gt" to ">", "ge" to ">=", "lt" to "<", "le" to "<=", "eq" to "==", "neq" to "!=")

    /** How far a chain of constants is followed before giving up. */
    private const val MAX_CONSTANT_CHAIN = 16

    /** Depth-first over every sub-expression, including the node itself. */
    private fun walk(expr: TypedExpr, visit: (TypedExpr) -> Unit) {
        visit(expr)
        expr.forEachChild { walk(it, visit) }
    }
}
