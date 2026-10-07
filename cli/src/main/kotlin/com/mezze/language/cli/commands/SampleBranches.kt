package com.mezze.language.cli.commands

import com.mezze.language.ast.MezzeType
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedMatch
import com.mezze.language.typedast.TypedVar
import com.mezze.language.typedast.TypedVariantPattern
import com.mezze.language.typedast.BindingRef
import com.mezze.language.typedast.TypedBlock
import com.mezze.language.typedast.TypedStatement
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
        params: Map<String, MezzeType>,
        decls: List<TypedLetDecl> = emptyList(),
    ): Map<String, List<Pinned>> {
        val found = linkedMapOf<String, MutableList<Pinned>>()
        fun add(field: String, pinned: Pinned) {
            val list = found.getOrPut(field) { mutableListOf() }
            if (list.none { it.source == pinned.source }) list.add(pinned)
        }
        val byHash = decls.associateBy { it.hash }
        val locals = blockLocals(decl.value, params.keys)

        walk(decl.value) { expr ->
            when (expr) {
                is TypedMatch -> matchArms(expr, params).forEach { (f, p) -> add(f, p) }
                is TypedCall -> comparison(expr, params, byHash, locals)?.let { (f, ps) -> ps.forEach { add(f, it) } }
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

    private fun matchArms(match: TypedMatch, params: Map<String, MezzeType>): List<Pair<String, Pinned>> {
        // The scrutinee is a parameter, or a field path into one (`match p.shape`).
        val path = pathOf(match.scrutinee, params) ?: return emptyList()
        val ctors = (match.scrutinee.type as? MezzeType.VariantType)?.constructors ?: return emptyList()
        val out = mutableListOf<Pair<String, Pinned>>()
        for (case in match.cases) {
            for (pattern in case.patterns) {
                val tag = (pattern as? TypedVariantPattern)?.tag ?: continue
                // `containsKey` rather than a null check: a constructor with no
                // payload maps to null, and that is exactly the case to keep.
                if (!ctors.containsKey(tag)) continue
                val built = construct(Arm(tag, ctors[tag]))
                val placed = placeAt(path, params, built) ?: continue
                out.add(path.param to placed.copy(why = "match ${path.label} — `$tag` arm"))
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
        params: Map<String, MezzeType>,
        byHash: Map<com.mezze.language.typedast.Hash, TypedLetDecl>,
        locals: Map<String, TypedExpr> = emptyMap(),
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
        val leftPath = pathOf(left, params)
        val rightPath = pathOf(right, params)
        val (path, other, effectiveOp) = when {
            leftPath != null -> Triple(leftPath, right, op)
            rightPath != null -> Triple(rightPath, left, MIRROR.getValue(op))
            else -> return derivedComparison(left, right, op, params, byHash, locals)
        }
        val known = knownValue(other, byHash) ?: return null
        val shown = known.describe()
        val where = "${path.label} ${SYMBOL.getValue(effectiveOp)} $shown"
        val leaves = when (known) {
            is Known.IntValue -> intBoundary(known.value, effectiveOp)
                .map { (v, holds) -> Pinned(v.toString(), draws = false, why = "if $where — ${verdict(holds)}") }
            is Known.StrValue -> strBoundary(known.value, effectiveOp)
                .map { (v, holds) -> Pinned(strLiteral(v), draws = false, why = "if $where — ${verdict(holds)}") }
        }
        return path.param to leaves.mapNotNull { leaf -> placeAt(path, params, leaf)?.copy(why = leaf.why) }
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

    // ── derived values ────────────────────────────────────────────────────────

    /**
     * `a * x + b` where `x` is the Int at a parameter path. What a comparison side
     * reduces to when it is computed FROM a parameter — `let m = n * 2` then
     * `m > 10` — rather than being the parameter itself.
     */
    private data class Linear(val path: Path, val a: Long, val b: Long, val shown: String) {
        fun at(x: Long): Long? = try { Math.addExact(Math.multiplyExact(a, x), b) } catch (_: ArithmeticException) { null }
    }

    /**
     * Every `let` bound inside a `do` block of the function, by name — the
     * definitions a derived comparison side is read back through.
     *
     * A name bound MORE THAN ONCE, or one that shadows a parameter, is left out:
     * which binding a given use refers to is then a scoping question this does not
     * answer, and reading the wrong one would pin the wrong side of the branch.
     */
    private fun blockLocals(root: TypedExpr, paramNames: Set<String>): Map<String, TypedExpr> {
        val seen = mutableMapOf<String, TypedExpr>()
        val ambiguous = mutableSetOf<String>()
        walk(root) { e ->
            if (e is TypedBlock) for (st in e.statements) {
                if (st !is TypedStatement.Let) continue
                if (st.name in seen || st.name in paramNames) ambiguous.add(st.name) else seen[st.name] = st.value
            }
        }
        return seen - ambiguous
    }

    /** [expr] as linear in one parameter path, through `+ - *` with known values and through locals. */
    private fun linearOf(
        expr: TypedExpr,
        params: Map<String, MezzeType>,
        byHash: Map<com.mezze.language.typedast.Hash, TypedLetDecl>,
        locals: Map<String, TypedExpr>,
        depth: Int = 0,
    ): Linear? {
        if (depth > MAX_CONSTANT_CHAIN || expr.type !is MezzeType.IntType) return null
        pathOf(expr, params)?.let { return Linear(it, 1, 0, it.label) }
        if (expr is TypedVar && expr.binding is BindingRef.Local) {
            val def = locals[expr.name] ?: return null
            return linearOf(def, params, byHash, locals, depth + 1)
        }
        val call = expr as? TypedCall ?: return null
        val op = (call.function as? TypedFieldAccess)?.field ?: return null
        if (op !in setOf("add", "sub", "mul")) return null
        val args = (call.arg as? TypedRecord)?.entries
            ?.filterIsInstance<TypedRecordEntry.Field>()?.associate { it.name to it.value } ?: return null
        val l = args["it"] ?: return null
        val r = args["r"] ?: return null
        val sym = mapOf("add" to "+", "sub" to "-", "mul" to "*").getValue(op)
        return try {
            val ll = linearOf(l, params, byHash, locals, depth + 1)
            val rl = linearOf(r, params, byHash, locals, depth + 1)
            val lk = (knownValue(l, byHash) as? Known.IntValue)?.value
            val rk = (knownValue(r, byHash) as? Known.IntValue)?.value
            when {
                ll != null && rk != null -> when (op) {
                    "add" -> Linear(ll.path, ll.a, Math.addExact(ll.b, rk), "${ll.shown} $sym $rk")
                    "sub" -> Linear(ll.path, ll.a, Math.subtractExact(ll.b, rk), "${ll.shown} $sym $rk")
                    else -> if (rk == 0L) null
                        else Linear(ll.path, Math.multiplyExact(ll.a, rk), Math.multiplyExact(ll.b, rk), "${grouped(ll.shown)} $sym $rk")
                }
                rl != null && lk != null -> when (op) {
                    "add" -> Linear(rl.path, rl.a, Math.addExact(rl.b, lk), "$lk $sym ${rl.shown}")
                    "sub" -> Linear(rl.path, Math.negateExact(rl.a), Math.subtractExact(lk, rl.b), "$lk $sym ${grouped(rl.shown)}")
                    else -> if (lk == 0L) null
                        else Linear(rl.path, Math.multiplyExact(rl.a, lk), Math.multiplyExact(rl.b, lk), "$lk $sym ${grouped(rl.shown)}")
                }
                // Two parameter-dependent sides, or nothing known: not linear in ONE path.
                else -> null
            }
        } catch (_: ArithmeticException) {
            null
        }
    }

    /**
     * A comparison where one side is DERIVED from a parameter and the other is
     * known: solve for the parameter values either side of the flip.
     *
     * Rather than dividing — which loses exactness for `a != ±1` and sign for
     * `a < 0` — this evaluates the candidates around `(k - b) / a` and keeps the
     * first adjacent pair on which the comparison disagrees. For `==` with no
     * integer solution (`2 * n == 7`) there is no such pair, and only the side
     * that is reachable is pinned.
     */
    private fun derivedComparison(
        left: TypedExpr,
        right: TypedExpr,
        op: String,
        params: Map<String, MezzeType>,
        byHash: Map<com.mezze.language.typedast.Hash, TypedLetDecl>,
        locals: Map<String, TypedExpr>,
    ): Pair<String, List<Pinned>>? {
        val (lin, other, effectiveOp, sideLabel) = run {
            val l = linearOf(left, params, byHash, locals)
            if (l != null) return@run Quad(l, right, op, describeSide(left).takeIf { it != "…" } ?: l.shown)
            val r = linearOf(right, params, byHash, locals) ?: return null
            Quad(r, left, MIRROR.getValue(op), describeSide(right).takeIf { it != "…" } ?: r.shown)
        }
        val k = (knownValue(other, byHash) as? Known.IntValue) ?: return null
        val center = try { Math.floorDiv(Math.subtractExact(k.value, lin.b), lin.a) } catch (_: ArithmeticException) { return null }
        val xs = (-2L..3L).mapNotNull { d -> try { Math.addExact(center, d) } catch (_: ArithmeticException) { null } }
        val results = xs.mapNotNull { x -> lin.at(x)?.let { v -> x to holds(v.compareTo(k.value), effectiveOp) } }
        val where = "$sideLabel ${SYMBOL.getValue(effectiveOp)} ${k.describe()}"
        val via = if (sideLabel == lin.shown) "" else " ($sideLabel = ${lin.shown})"
        fun pin(x: Long, h: Boolean): Pinned? =
            placeAt(lin.path, params, Pinned(x.toString(), draws = false))
                ?.copy(why = "if $where — ${verdict(h)}$via")
        val flip = results.zipWithNext().firstOrNull { (p, q) -> p.second != q.second }
        val pins = if (flip != null) {
            listOfNotNull(pin(flip.first.first, flip.first.second), pin(flip.second.first, flip.second.second))
        } else {
            results.firstOrNull()?.let { (x, h) -> listOfNotNull(pin(x, h)) } ?: emptyList()
        }
        return lin.path.param to pins
    }

    /** Parenthesise a shown sum before multiplying or subtracting it, so the text means what was computed. */
    private fun grouped(shown: String): String = if (" + " in shown || " - " in shown) "($shown)" else shown

    private data class Quad(val lin: Linear, val other: TypedExpr, val op: String, val label: String)

    /** How a derived side reads in a reason: the local's name, or the path. */
    private fun describeSide(expr: TypedExpr): String = when (expr) {
        is TypedVar -> expr.name
        is TypedFieldAccess -> describeSide(expr.record) + "." + expr.field
        else -> "…"
    }

    // ── paths ─────────────────────────────────────────────────────────────────

    /** A parameter, followed by the fields reached through it: `p.shape.r`. */
    private data class Path(val param: String, val fields: List<String>) {
        val label: String get() = (listOf(param) + fields).joinToString(".")
    }

    /**
     * The parameter path [expr] reads, if it reads one: a bare parameter, or a
     * chain of field accesses rooted at one. A Mezze function destructures its
     * parameter record into locals, so a parameter reads as a LOCAL variable of
     * that field's name.
     */
    private fun pathOf(expr: TypedExpr, params: Map<String, MezzeType>): Path? = when (expr) {
        is TypedVar ->
            if (expr.name in params && expr.binding is BindingRef.Local) Path(expr.name, emptyList()) else null
        is TypedFieldAccess -> pathOf(expr.record, params)?.let { it.copy(fields = it.fields + expr.field) }
        else -> null
    }

    /**
     * The whole parameter value with [leaf] placed at [path], every other field
     * drawn as usual — so `p.x > 0` pins `p = { x = 1, y = <draw> }`: the field the
     * branch reads is fixed and the rest still varies.
     *
     * Null when the path goes through a type this cannot write out field by field
     * (anything that is not a record); that branch is then left to the draws.
     */
    private fun placeAt(path: Path, params: Map<String, MezzeType>, leaf: Pinned): Pinned? {
        fun build(type: MezzeType, rest: List<String>): Pinned? {
            if (rest.isEmpty()) return leaf
            val record = type as? MezzeType.RecordType ?: return null
            val target = rest.first()
            if (target !in record.fields) return null
            var draws = false
            val parts = record.fields.entries.map { (name, fieldType) ->
                if (name == target) {
                    val inner = build(fieldType, rest.drop(1)) ?: return null
                    if (inner.draws) draws = true
                    "$name = ${inner.source}"
                } else {
                    draws = true
                    "$name = ${SampleGen.of(fieldType)}"
                }
            }
            return Pinned(parts.joinToString(", ", prefix = "{ ", postfix = " }"), draws)
        }
        val root = params[path.param] ?: return null
        return build(root, path.fields)
    }

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
