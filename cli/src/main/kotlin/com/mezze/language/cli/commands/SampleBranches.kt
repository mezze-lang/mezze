package com.mezze.language.cli.commands

import com.mezze.language.ast.MezzeType
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedMatch
import com.mezze.language.typedast.TypedVar
import com.mezze.language.typedast.TypedVariantPattern
import com.mezze.language.typedast.TypedSeqPattern
import com.mezze.language.typedast.TypedRecordPattern
import com.mezze.language.typedast.TypedVarPattern
import com.mezze.language.typedast.BindingRef
import com.mezze.language.typedast.TypedBlock
import com.mezze.language.typedast.TypedIf
import com.mezze.language.typedast.TypedPerformOp
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
    data class Pinned(
        val source: String,
        val draws: Boolean,
        val why: String = "",
        /**
         * Whether the condition this pin targets HOLDS for it — the `then` side
         * (true) or the `else` side (false) — or null when it targets no condition
         * (a match arm). This is what lets an `if` know which pins put a row on
         * which side of it, so the branches INSIDE it can be reached on purpose:
         * see the path constraint in [inventory].
         */
        val outcome: Boolean? = null,
        /**
         * Whether a CANDIDATE value (as source) would land on the same side of this
         * pin's condition, when that can be decided — for a comparison on a whole
         * primitive parameter. Lets [merge] tell a real contradiction from two pins
         * that merely differ: inside `if n > 10`, `n = 11` already satisfies
         * `n < 5 — else`, so that row is kept rather than dropped.
         */
        val check: ((String) -> Boolean)? = null,
    )

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
        /**
         * Declarations of the modules this one imports. Needed to see THROUGH a
         * call to a stdlib function — `seq::size { xs }` is a reference by hash to
         * a declaration that lives in `std::collections::seq` — and it also lets a
         * constant imported from another module resolve like a local one.
         */
        imported: List<TypedLetDecl> = emptyList(),
        /**
         * Values of constants that folding could not resolve, EVALUATED by the
         * interpreter — see [unresolvedConstants]. Each replaces its declaration's
         * body with the literal, for the purposes of this analysis only, so every
         * reader of constants picks it up unchanged.
         */
        evaluated: Map<com.mezze.language.typedast.Hash, Evaluated> = emptyMap(),
        /**
         * Results of SCRIPTED effect operations, as further row inputs: key →
         * (label, type). See [SampleScripts]. An operation's result is a hidden
         * input like any argument, so `n > Config.threshold {}` is a comparison
         * between two inputs and is read exactly like `a < b`.
         */
        context: Map<String, Pair<String, MezzeType>> = emptyMap(),
    ): List<Row> {
        val wanted = mutableListOf<Map<String, Pinned>>()
        fun want(req: Map<String, Pinned>) {
            if (req.isEmpty()) return
            val key = req.mapValues { it.value.source }
            val i = wanted.indexOfFirst { w -> w.mapValues { it.value.source } == key }
            if (i < 0) { wanted.add(req); return }
            // Same values, another branch they reach: one row, and it says BOTH.
            // Dropping the second reason would hide that `n = 11` also tests
            // `n < 5 — else` inside `if n > 10`.
            wanted[i] = wanted[i].mapValues { (k, held) ->
                val extra = req.getValue(k).why
                if (extra.isEmpty() || held.why.split("; ").containsAll(extra.split("; "))) held
                else held.copy(why = (held.why.split("; ") + extra.split("; ")).distinct().joinToString("; "))
            }
        }
        val byHash = (imported + decls).associateBy { it.hash }.toMutableMap()
        for ((hash, value) in evaluated) {
            val d = byHash[hash] ?: continue
            byHash[hash] = d.copy(value = when (value) {
                is Evaluated.IntValue -> TypedIntLit(value.value, MezzeType.IntType, d.loc)
                is Evaluated.StrValue -> TypedStringLit(listOf(TypedStringPart.Literal(value.value)), MezzeType.StringType, d.loc)
            })
        }
        val locals = blockLocals(decl.value, params.keys)
        contextLabels = context.mapValues { it.value.first }
        contextLocals = locals.mapNotNull { (name, def) ->
            (def as? TypedPerformOp)?.let { SampleScripts.contextKey(it.effectHash, it.operationName) }
                ?.takeIf { it in context }?.let { name to it }
        }.toMap()
        @Suppress("NAME_SHADOWING")
        val params = params + context.mapValues { it.value.second }

        /**
         * Walk with the PATH CONSTRAINT: the pins that put a row on the way to the
         * current node. Every requirement found inside a branch is merged with it.
         *
         * Without this, a pin for an inner condition could point at code the row
         * never runs. `if strict then n > limit else n >= 0` pinned `n = 0` for the
         * `then` side of `n >= 0` while `strict` was drawn `True` — so the function
         * took the OTHER branch, and the row's reason described a test it did not
         * perform. A requirement whose pins contradict its path is dropped rather
         * than kept with a reason that lies.
         */
        fun visit(expr: TypedExpr, path: Map<String, Pinned>) {
            fun found(req: Map<String, Pinned>) { merge(path, req)?.let { want(it) } }
            when (expr) {
                is TypedIf -> {
                    val cond = condition(expr.condition, params, byHash, locals)
                    cond.forEach { found(it) }
                    visit(expr.condition, path)
                    val thenSide = cond.firstOrNull { r -> r.values.all { it.outcome == true } }
                    val elseSide = cond.firstOrNull { r -> r.values.all { it.outcome == false } }
                    visit(expr.thenBranch, thenSide?.let { merge(path, it) } ?: path)
                    visit(expr.elseBranch, elseSide?.let { merge(path, it) } ?: path)
                }
                is TypedMatch -> {
                    guards(expr, params, byHash, locals).forEach { found(it) }
                    visit(expr.scrutinee, path)
                    val arms = matchArms(expr, params)
                    arms.forEach { (_, f, p) -> found(mapOf(f to p)) }
                    expr.cases.forEachIndexed { i, case ->
                        case.guard?.let { visit(it, path) }
                        val arm = arms.firstOrNull { it.first == i }?.let { (_, f, p) -> mapOf(f to p) }
                        visit(case.body, arm?.let { merge(path, it) } ?: path)
                    }
                }
                is TypedCall -> {
                    condition(expr, params, byHash, locals).forEach { found(it) }
                    expr.forEachChild { visit(it, path) }
                }
                else -> expr.forEachChild { visit(it, path) }
            }
        }
        visit(decl.value, emptyMap())
        return pack(wanted)
    }

    /**
     * This module's top-level `Int` constants that the body refers to and that
     * folding cannot resolve — `let limit = compute { x = 21 }`.
     *
     * Their values are knowable without guessing: a top-level value cannot perform
     * effects (V0502), so evaluating it is deterministic and touches nothing. The
     * command evaluates these with the interpreter itself — so the value is exactly
     * what the program sees — and passes them back as `evaluated`.
     *
     * Only this module's constants: generated source refers to them by name, and a
     * name is unambiguous only in the module that declares it.
     */
    fun unresolvedConstants(
        decl: TypedLetDecl,
        decls: List<TypedLetDecl>,
        /** Constants of imported modules, with the module each comes from — see [importedConstants]. */
        imported: Map<com.mezze.language.typedast.Hash, Pair<TypedLetDecl, com.mezze.language.ast.ModulePath>> = emptyMap(),
    ): List<Constant> {
        val local = decls.associateBy { it.hash }
        val all = imported.mapValues { it.value.first } + local
        val out = linkedMapOf<com.mezze.language.typedast.Hash, Constant>()
        walk(decl.value) { e ->
            val ref = ((e as? TypedVar)?.binding as? BindingRef.ByHash)?.hash ?: return@walk
            val (target, module) = local[ref]?.let { it to null } ?: imported[ref] ?: return@walk
            if (target.scheme.type !is MezzeType.IntType && target.scheme.type !is MezzeType.StringType) return@walk
            if (knownValue(e, all) == null) out[ref] = Constant(target, module)
        }
        return out.values.toList()
    }

    /** A constant to evaluate: [module] is where an imported one lives, null for the target's own. */
    data class Constant(val decl: TypedLetDecl, val module: com.mezze.language.ast.ModulePath?)

    /** An evaluated constant's value: what the program sees, read back from the interpreter. */
    sealed interface Evaluated {
        data class IntValue(val value: Long) : Evaluated
        data class StrValue(val value: String) : Evaluated
    }

    /** Every constant of the loaded modules, by hash, with the module it is declared in. */
    fun importedConstants(
        loaded: Map<com.mezze.language.ast.ModulePath, com.mezze.language.typedast.TypedModule?>,
    ): Map<com.mezze.language.typedast.Hash, Pair<TypedLetDecl, com.mezze.language.ast.ModulePath>> =
        loaded.flatMap { (path, module) ->
            module?.decls.orEmpty().filterIsInstance<TypedLetDecl>().map { it.hash to (it to path) }
        }.toMap()

    /**
     * Every requirement that decides [cond]: comparisons, emptiness, and a bare
     * `Bool` parameter used as the condition itself (`if strict`), which pins
     * `True` and `False`.
     */
    private fun condition(
        cond: TypedExpr,
        params: Map<String, MezzeType>,
        byHash: Map<com.mezze.language.typedast.Hash, TypedLetDecl>,
        locals: Map<String, TypedExpr>,
    ): List<Map<String, Pinned>> {
        val out = mutableListOf<Map<String, Pinned>>()
        if (cond is TypedCall) {
            out.addAll(comparison(cond, params, byHash, locals))
            out.addAll(emptiness(cond, params, byHash))
        }
        if (isBool(cond.type)) pathOf(cond, params)?.let { path ->
            for (v in listOf(true, false)) {
                val leaf = Pinned(if (v) "True" else "False", draws = false)
                placeAt(path, params, leaf)?.let {
                    out.add(mapOf(path.param to it.copy(
                        why = "if ${path.label} — ${verdict(v)}",
                        outcome = v,
                        check = if (path.fields.isNotEmpty()) null else { src -> src == "True" },
                    )))
                }
            }
        }
        return out
    }

    /** The builtin Bool, by its canonical identity — a user variant `| True, False |` is not it. */
    private fun isBool(type: MezzeType): Boolean =
        type is MezzeType.VariantType &&
            type.identity == com.mezze.language.ast.BuiltinTypes.getBoolType().identity

    /**
     * [req] under [path]: the union of both, or null when they CONTRADICT — the
     * same parameter pinned to two different values. A contradicting requirement
     * asks for a row that cannot reach the branch it describes.
     */
    private fun merge(path: Map<String, Pinned>, req: Map<String, Pinned>): Map<String, Pinned>? {
        val out = LinkedHashMap(path)
        for ((k, v) in req) {
            val held = out[k]
            if (held == null || held.source == v.source) {
                out[k] = v
                continue
            }
            // Two different values for one parameter. Not necessarily a
            // contradiction: one value may satisfy BOTH conditions.
            val innerFitsPath = held.check?.invoke(v.source) == held.outcome
            val pathFitsInner = v.check?.invoke(held.source) == v.outcome
            out[k] = when {
                // e.g. path `n > 10 — then` (n = 11), inner `n < 15 — else` (n = 15):
                // 15 also satisfies `n > 10`, so use it and keep both reasons.
                held.outcome != null && innerFitsPath -> v.copy(why = held.why + "; " + v.why)
                // e.g. path n = 11, inner `n < 5 — else` (n = 5): 11 already
                // satisfies `n < 5 — else`, so the path's value serves both.
                v.outcome != null && pathFitsInner -> held.copy(why = held.why + "; " + v.why, outcome = held.outcome)
                else -> return null
            }
        }
        return out
    }

    /**
     * One row's pins — values for one or more parameters, chosen TOGETHER.
     *
     * A row rather than a per-parameter list because some branches constrain two
     * parameters at once: `a < b` needs `a` and `b` picked as a pair, and picking
     * each from its own list would put them in rows that have nothing to do with
     * each other.
     */
    data class Row(val pins: Map<String, Pinned>) {
        val why: String get() = pins.values.map { it.why }.filter { it.isNotEmpty() }.distinct().joinToString("; ")
    }

    /**
     * Requirements packed into as few rows as possible: each goes into the first
     * row that pins none of its parameters yet. Independent single-parameter pins
     * therefore share rows exactly as they did when each parameter had its own
     * list — row `i` carrying each parameter's `i`-th value — so adding row-level
     * pins costs the existing cases nothing.
     */
    private fun pack(wanted: List<Map<String, Pinned>>): List<Row> {
        val rows = mutableListOf<MutableMap<String, Pinned>>()
        for (req in wanted) {
            val home = rows.firstOrNull { row -> req.keys.none { it in row } }
            if (home != null) home.putAll(req) else rows.add(req.toMutableMap())
        }
        return rows.map { Row(it) }
    }

    // ── match arms ────────────────────────────────────────────────────────────

    private fun matchArms(match: TypedMatch, params: Map<String, MezzeType>): List<Triple<Int, String, Pinned>> {
        // The scrutinee is a parameter, or a field path into one (`match p.shape`).
        val path = pathOf(match.scrutinee, params) ?: return emptyList()
        seqElementType(match.scrutinee.type)?.let { elem -> return seqArms(match, path, params, elem) }
        if (isPrimitive(match.scrutinee.type)) return literalArms(match, path, params)
        val ctors = (match.scrutinee.type as? MezzeType.VariantType)?.constructors ?: return emptyList()
        val out = mutableListOf<Triple<Int, String, Pinned>>()
        for ((index, case) in match.cases.withIndex()) {
            for (pattern in case.patterns) {
                val tag = (pattern as? TypedVariantPattern)?.tag ?: continue
                // `containsKey` rather than a null check: a constructor with no
                // payload maps to null, and that is exactly the case to keep.
                if (!ctors.containsKey(tag)) continue
                val built = construct(Arm(tag, ctors[tag]))
                val placed = placeAt(path, params, built) ?: continue
                out.add(Triple(index, path.param, placed.copy(why = "match ${path.label} — `$tag` arm")))
            }
        }
        return out
    }

    /** The element type when [type] is a seq (`[Int]`), else null. */
    private fun seqElementType(type: MezzeType): MezzeType? =
        (type as? MezzeType.TypeApplication)
            ?.takeIf { it.constructor == com.mezze.language.ast.BuiltinTypes.SEQ && it.args.size == 1 }
            ?.args?.single()

    /**
     * A `match` on a seq: one value per SEQ PATTERN, of exactly the length the
     * pattern names. `[]` is the empty seq; `[h, ...rest]` is a one-element seq,
     * the shortest it accepts. A literal element pattern (`[0, ...rest]`) pins
     * that literal; any other element is drawn.
     *
     * Without this the empty-seq arm was almost never reached: a drawn seq is
     * rarely empty, so `[] -> d` stayed unreached in every short run.
     */
    private fun seqArms(
        match: TypedMatch,
        path: Path,
        params: Map<String, MezzeType>,
        elem: MezzeType,
    ): List<Triple<Int, String, Pinned>> {
        val out = mutableListOf<Triple<Int, String, Pinned>>()
        for ((index, case) in match.cases.withIndex()) {
            for (pattern in case.patterns) {
                val seq = pattern as? TypedSeqPattern ?: continue
                var draws = false
                val items = seq.elements.map { el ->
                    literalSource(el) ?: SampleGen.of(elem).also { draws = true }
                }
                val shape = if (seq.rest != null) "[${"_, ".repeat(seq.elements.size)}...]" else
                    "[${List(seq.elements.size) { "_" }.joinToString(", ")}]"
                val placed = placeAt(path, params, Pinned("[${items.joinToString(", ")}]", draws)) ?: continue
                out.add(Triple(index, path.param, placed.copy(why = "match ${path.label} — `$shape` arm")))
            }
        }
        return out
    }

    /**
     * A `match` on a primitive with LITERAL arms (`"admin" -> …`, `0 -> …`): one row
     * per literal, pinned to it. A drawn string or int essentially never equals a
     * specific literal, so these arms went unreached; the catch-all arm is left to
     * the draws, which reach it on nearly every row.
     */
    private fun literalArms(
        match: TypedMatch,
        path: Path,
        params: Map<String, MezzeType>,
    ): List<Triple<Int, String, Pinned>> {
        val out = mutableListOf<Triple<Int, String, Pinned>>()
        for ((index, case) in match.cases.withIndex()) {
            for (pattern in case.patterns) {
                val src = literalSource(pattern) ?: continue
                val placed = placeAt(path, params, Pinned(src, draws = false)) ?: continue
                out.add(Triple(index, path.param, placed.copy(why = "match ${path.label} — `$src` arm")))
            }
        }
        return out
    }

    /** Source for a literal element pattern, or null for anything that binds. */
    private fun literalSource(pattern: com.mezze.language.typedast.TypedPattern): String? {
        val lit = (pattern as? com.mezze.language.typedast.TypedLiteralPattern)?.value ?: return null
        return when (lit) {
            is TypedIntLit -> lit.value.toString()
            is com.mezze.language.typedast.TypedBoolLit -> if (lit.value) "True" else "False"
            is TypedStringLit -> lit.parts.singleOrNull()
                ?.let { (it as? TypedStringPart.Literal)?.value }
                ?.let { strLiteral(it) }
                ?: if (lit.parts.isEmpty()) strLiteral("") else null
            else -> null
        }
    }

    /**
     * A GUARD — `Circle { r } if r > 10`, or `n if n > limit` — reaches its arm only
     * for some payloads, so pinning the constructor alone lands on either side of it
     * by chance.
     *
     * The variables a pattern binds are treated as pseudo-parameters, typed from the
     * constructor's payload (or, for a bare variable pattern, from the scrutinee),
     * and the guard is read with exactly the machinery a function body gets —
     * constants, derived values, two-variable comparisons. Each requirement that
     * comes back is wrapped into the constructor, the unconstrained payload fields
     * drawn, and placed at the scrutinee's path.
     */
    private fun guards(
        match: TypedMatch,
        params: Map<String, MezzeType>,
        byHash: Map<com.mezze.language.typedast.Hash, TypedLetDecl>,
        locals: Map<String, TypedExpr>,
    ): List<Map<String, Pinned>> {
        val path = pathOf(match.scrutinee, params) ?: return emptyList()
        val ctors = (match.scrutinee.type as? MezzeType.VariantType)?.constructors
        val out = mutableListOf<Map<String, Pinned>>()
        for (case in match.cases) {
            val guard = case.guard ?: continue
            for (pattern in case.patterns) {
                when (pattern) {
                    // `n if n > limit` — the bound name IS the scrutinee.
                    is TypedVarPattern -> {
                        val pseudo = mapOf(pattern.name to match.scrutinee.type)
                        for (req in guardRequirements(guard, pseudo, byHash, locals)) {
                            val leaf = req[pattern.name] ?: continue
                            val placed = placeAt(path, params, leaf) ?: continue
                            out.add(mapOf(path.param to placed.copy(why = "match ${path.label} guard — ${leaf.why}")))
                        }
                    }
                    // `Circle { r } if r > 10` — names bound to payload fields.
                    is TypedVariantPattern -> {
                        val payload = ctors?.get(pattern.tag) as? MezzeType.RecordType ?: continue
                        val bound = payloadBindings(pattern) // var -> field
                        val pseudo = bound.mapNotNull { (v, f) -> payload.fields[f]?.let { v to it } }.toMap()
                        for (req in guardRequirements(guard, pseudo, byHash, locals)) {
                            val byField = req.mapKeys { (v, _) -> bound.getValue(v) }
                            var draws = false
                            val src = payload.fields.entries.joinToString(", ", prefix = "${pattern.tag} { ", postfix = " }") { (f, t) ->
                                val pin = byField[f]
                                if (pin == null || pin.draws) draws = true
                                "$f = ${pin?.source ?: SampleGen.of(t)}"
                            }
                            val why = req.values.map { it.why }.distinct().joinToString("; ")
                            val placed = placeAt(path, params, Pinned(src, draws)) ?: continue
                            out.add(mapOf(path.param to placed.copy(why = "match ${path.label} — `${pattern.tag}` guard: $why")))
                        }
                    }
                    else -> Unit
                }
            }
        }
        return out
    }

    /** The guard read as a body over pseudo-parameters: every comparison in it. */
    private fun guardRequirements(
        guard: TypedExpr,
        pseudo: Map<String, MezzeType>,
        byHash: Map<com.mezze.language.typedast.Hash, TypedLetDecl>,
        locals: Map<String, TypedExpr>,
    ): List<Map<String, Pinned>> {
        if (pseudo.isEmpty()) return emptyList()
        val out = mutableListOf<Map<String, Pinned>>()
        walk(guard) { e -> if (e is TypedCall) out.addAll(comparison(e, pseudo, byHash, locals)) }
        return out
    }

    /** Variable name → payload field it binds, for `Tag { field }` / `Tag { field = v }`. */
    private fun payloadBindings(pattern: TypedVariantPattern): Map<String, String> {
        val record = pattern.payload as? TypedRecordPattern ?: return emptyMap()
        return record.fields.mapNotNull { fp ->
            when (val inner = fp.pattern) {
                null -> fp.name to fp.name
                is TypedVarPattern -> inner.name to fp.name
                else -> null
            }
        }.toMap()
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
    ): List<Map<String, Pinned>> {
        val op = (call.function as? TypedFieldAccess)?.field ?: return emptyList()
        if (op !in COMPARISONS) return emptyList()
        val args = (call.arg as? TypedRecord)?.entries
            ?.filterIsInstance<TypedRecordEntry.Field>()?.associate { it.name to it.value } ?: return emptyList()
        val left = args["it"] ?: return emptyList()
        val right = args["r"] ?: return emptyList()
        if (!isPrimitive(left.type) || !isPrimitive(right.type)) return emptyList()

        // Either side may hold the parameter. `k < n` is `n > k` seen from `n`, so
        // the operator is MIRRORED rather than read as written.
        val leftPath = pathOf(left, params)
        val rightPath = pathOf(right, params)
        if (leftPath != null && rightPath != null) return relational(leftPath, rightPath, left.type, op, params)
        val (path, other, effectiveOp) = when {
            leftPath != null -> Triple(leftPath, right, op)
            rightPath != null -> Triple(rightPath, left, MIRROR.getValue(op))
            else -> return sizeComparison(left, right, op, params, byHash)
                ?: derivedComparison(left, right, op, params, byHash, locals)
                    ?.let { (param, pins) -> pins.map { mapOf(param to it) } } ?: emptyList()
        }
        val known = knownValue(other, byHash) ?: return emptyList()
        val shown = known.describe()
        val where = "${path.label} ${SYMBOL.getValue(effectiveOp)} $shown"
        val leaves = when (known) {
            is Known.IntValue -> intBoundary(known.value, effectiveOp)
                .map { (v, holds) -> Pinned(v.toString(), draws = false, why = "if $where — ${verdict(holds)}", outcome = holds) }
            is Known.FloatValue -> floatBoundary(known.value, effectiveOp)
                .map { (v, holds) -> Pinned(floatLiteral(v), draws = false, why = "if $where — ${verdict(holds)}", outcome = holds) }
            is Known.StrValue -> strBoundary(known.value, effectiveOp)
                .map { (v, holds) -> Pinned(strLiteral(v), draws = false, why = "if $where — ${verdict(holds)}", outcome = holds) }
        }
        val check: ((String) -> Boolean)? = if (path.fields.isNotEmpty()) null else when (known) {
            is Known.IntValue -> { src -> src.toLongOrNull()?.let { holds(it.compareTo(known.value), effectiveOp) } ?: false }
            is Known.FloatValue -> { src -> src.toDoubleOrNull()?.let { holds(it.compareTo(known.value), effectiveOp) } ?: false }
            is Known.StrValue -> { src -> holds(if (src == strLiteral(known.value)) 0 else 1, effectiveOp) }
        }
        return leaves.mapNotNull { leaf ->
            placeAt(path, params, leaf)?.copy(why = leaf.why, outcome = leaf.outcome, check = check)
        }
            .map { mapOf(path.param to it) }
    }

    /** A compile-time value, with how it should read in a row's reason. */
    private sealed interface Known {
        fun describe(): String
        data class IntValue(val value: Long, val via: String?) : Known {
            override fun describe() = if (via == null) "$value" else "$via (= $value)"
        }
        data class FloatValue(val value: Double, val via: String?) : Known {
            override fun describe() = if (via == null) floatLiteral(value) else "$via (= ${floatLiteral(value)})"
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
            is com.mezze.language.typedast.TypedFloatLit -> Known.FloatValue(expr.value, via)
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

    /**
     * The two floats either side of where a comparison against [k] flips: `k`
     * itself and the next representable value past it, so `score > 0.9` gets one
     * row at `0.9` (else) and one just above (then).
     */
    private fun floatBoundary(k: Double, op: String): List<Pair<Double, Boolean>> {
        if (k.isNaN() || k.isInfinite()) return emptyList()
        val (lo, hi) = when (op) {
            "gt", "le", "eq", "neq" -> k to Math.nextUp(k)
            "ge", "lt" -> Math.nextDown(k) to k
            else -> return emptyList()
        }
        return listOf(lo, hi).map { it to holds(it.compareTo(k), op) }
    }

    /** A float as Mezze source: plain decimal, never `1.0E-5`, which is not a literal. */
    private fun floatLiteral(d: Double): String =
        java.math.BigDecimal(d.toString()).toPlainString().let { if ('.' in it) it else "$it.0" }

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

    // ── sizes ─────────────────────────────────────────────────────────────────

    /**
     * The sequence parameter path whose SIZE [expr] computes, if it computes one.
     *
     * Two spellings reach the same native:
     *
     *  - `seq::size { xs = xs }` — a reference by HASH to a declaration that is a
     *    thin wrapper over `__native_seq_size`. Recognised by following the hash
     *    and checking the body calls that native; a user function that merely
     *    happens to be named `size` is a different hash and never matches.
     *  - `xs.size {}` — the inherent method on a SEQUENCE receiver. Keyed on the
     *    receiver being a `Seq`, as comparisons are keyed on primitive operands.
     */
    private fun sizeOf(
        expr: TypedExpr,
        params: Map<String, MezzeType>,
        byHash: Map<com.mezze.language.typedast.Hash, TypedLetDecl>,
        native: String = SEQ_SIZE,
        method: String = "size",
    ): Path? {
        val call = expr as? TypedCall ?: return null
        val fn = call.function
        if (fn is TypedVar) {
            val ref = fn.binding as? BindingRef.ByHash ?: return null
            if (!wrapsNative(byHash[ref.hash] ?: return null, native)) return null
            val only = (call.arg as? TypedRecord)?.entries
                ?.filterIsInstance<TypedRecordEntry.Field>()?.singleOrNull() ?: return null
            return pathOf(only.value, params)?.takeIf { isSeq(only.value.type) }
        }
        if (fn is TypedFieldAccess && fn.field == method && (isSeq(fn.record.type) || fn.record.type is MezzeType.StringType))
            return pathOf(fn.record, params)
        return null
    }

    private fun wrapsNative(decl: TypedLetDecl, native: String): Boolean {
        val body = (decl.value as? com.mezze.language.typedast.TypedLambda)?.body as? TypedCall ?: return false
        val callee = body.function as? TypedVar ?: return false
        return (callee.binding as? BindingRef.Native)?.name == native
    }

    private fun isSeq(type: MezzeType): Boolean =
        type is MezzeType.TypeApplication && type.constructor == com.mezze.language.ast.BuiltinTypes.SEQ && type.args.size == 1

    /** `size(xs) <op> k`: sequences of the lengths either side of the flip. */
    private fun sizeComparison(
        left: TypedExpr,
        right: TypedExpr,
        op: String,
        params: Map<String, MezzeType>,
        byHash: Map<com.mezze.language.typedast.Hash, TypedLetDecl>,
    ): List<Map<String, Pinned>>? {
        val (path, other, effectiveOp) = sizeOf(left, params, byHash)?.let { Triple(it, right, op) }
            ?: sizeOf(right, params, byHash)?.let { Triple(it, left, MIRROR.getValue(op)) }
            ?: return null
        val k = knownValue(other, byHash) as? Known.IntValue ?: return emptyList()
        val sized = typeAt(path, params) ?: return emptyList()
        val where = "size(${path.label}) ${SYMBOL.getValue(effectiveOp)} ${k.describe()}"
        return intBoundary(k.value, effectiveOp)
            // A length is never negative, and one past MAX_PINNED_LENGTH is a value
            // nobody reads in a table — that branch is left to the draws.
            .filter { (n, _) -> n in 0..MAX_PINNED_LENGTH }
            .mapNotNull { (n, holds) ->
                val leaf = ofLength(n.toInt(), sized) ?: return@mapNotNull null
                placeAt(path, params, leaf)?.copy(why = "if $where — ${verdict(holds)}", outcome = holds)
            }
            .map { mapOf(path.param to it) }
    }

    /**
     * `is_empty(xs)` anywhere in the body: one empty sequence, one with an element.
     * The emptiness test is a branch in its own right whether or not it sits in an
     * `if`, and the empty case is the one a sized draw reaches least often.
     */
    private fun emptiness(
        expr: TypedExpr,
        params: Map<String, MezzeType>,
        byHash: Map<com.mezze.language.typedast.Hash, TypedLetDecl>,
    ): List<Map<String, Pinned>> {
        val path = sizeOf(expr, params, byHash, SEQ_IS_EMPTY, "is_empty") ?: return emptyList()
        val sized = typeAt(path, params) ?: return emptyList()
        return listOf(0 to true, 1 to false).mapNotNull { (n, holds) ->
            placeAt(path, params, ofLength(n, sized) ?: return@mapNotNull null)
                ?.copy(why = "is_empty(${path.label}) — ${if (holds) "empty" else "not empty"}", outcome = holds)
        }.map { mapOf(path.param to it) }
    }

    /**
     * A value of [type] with exactly [n] elements: a seq of drawn elements, or a
     * string of `n` characters. Null for anything else.
     */
    private fun ofLength(n: Int, type: MezzeType): Pinned? = when {
        type is MezzeType.StringType -> Pinned(strLiteral("a".repeat(n)), draws = false)
        isSeq(type) -> seqOfLength(n, (type as MezzeType.TypeApplication).args.single())
        else -> null
    }

    /** A sequence literal of exactly [n] drawn elements. */
    private fun seqOfLength(n: Int, elem: MezzeType): Pinned =
        if (n == 0) Pinned("[]", draws = false)
        else Pinned((1..n).joinToString(", ", prefix = "[", postfix = "]") { SampleGen.of(elem) }, draws = true)

    /** The type at [path] within its parameter. */
    private fun typeAt(path: Path, params: Map<String, MezzeType>): MezzeType? {
        var t = params[path.param] ?: return null
        for (f in path.fields) t = (t as? MezzeType.RecordType)?.fields?.get(f) ?: return null
        return t
    }

    // ── two parameters ────────────────────────────────────────────────────────

    /**
     * `a < b` where BOTH sides are parameters (or paths into them): one row each
     * for less, equal and greater. Equal is the one worth spelling out — blind
     * draws over 64-bit ints essentially never produce two equal values, so the
     * `==` edge of every `<`/`<=` between two inputs went untested.
     *
     * Both leaves may sit in the SAME parameter (`p.x < p.y`); they are then placed
     * into one record together.
     */
    private fun relational(
        leftPath: Path,
        rightPath: Path,
        type: MezzeType,
        op: String,
        params: Map<String, MezzeType>,
    ): List<Map<String, Pinned>> {
        if (leftPath == rightPath) return emptyList()
        val pairs: List<Triple<String, String, Int>> = when (type) {
            is MezzeType.IntType -> listOf(Triple("0", "1", -1), Triple("1", "1", 0), Triple("1", "0", 1))
            is MezzeType.StringType -> listOf(Triple("\"a\"", "\"b\"", -1), Triple("\"a\"", "\"a\"", 0), Triple("\"b\"", "\"a\"", 1))
            else -> return emptyList()
        }
        val relation = mapOf(-1 to "<", 0 to "==", 1 to ">")
        return pairs.mapNotNull { (l, r, cmp) ->
            val outcome = holds(cmp, op)
            val why = "if ${leftPath.label} ${SYMBOL.getValue(op)} ${rightPath.label} — " +
                "${verdict(outcome)} (${relation.getValue(cmp)})"
            val lp = Pinned(l, draws = false, why = why)
            val rp = Pinned(r, draws = false, why = why)
            if (leftPath.param == rightPath.param) {
                val both = placeAll(params[leftPath.param] ?: return@mapNotNull null,
                    listOf(leftPath.fields to lp, rightPath.fields to rp)) ?: return@mapNotNull null
                mapOf(leftPath.param to both.copy(why = why, outcome = outcome))
            } else {
                val a = placeAt(leftPath, params, lp)?.copy(why = why, outcome = outcome) ?: return@mapNotNull null
                val b = placeAt(rightPath, params, rp)?.copy(why = why, outcome = outcome) ?: return@mapNotNull null
                mapOf(leftPath.param to a, rightPath.param to b)
            }
        }
    }

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
                ?.copy(
                    why = "if $where — ${verdict(h)}$via",
                    outcome = h,
                    check = if (lin.path.fields.isNotEmpty()) null else { src ->
                        src.toLongOrNull()?.let { x -> lin.at(x)?.let { v -> holds(v.compareTo(k.value), effectiveOp) } } ?: false
                    },
                )
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

    /** A parameter (or a scripted operation's result), followed by the fields reached through it. */
    private data class Path(val param: String, val fields: List<String>) {
        val label: String get() = (listOf(contextLabels[param] ?: param) + fields).joinToString(".")
    }

    /**
     * Per-inventory context for [pathOf]: the label of each scripted operation's
     * result, and the block-local names bound directly to one (`let t =
     * Config.threshold {}`). Set at the start of [inventory]; the CLI is
     * single-threaded and an inventory runs to completion before the next.
     */
    private var contextLabels: Map<String, String> = emptyMap()
    private var contextLocals: Map<String, String> = emptyMap()

    /**
     * The parameter path [expr] reads, if it reads one: a bare parameter, or a
     * chain of field accesses rooted at one. A Mezze function destructures its
     * parameter record into locals, so a parameter reads as a LOCAL variable of
     * that field's name.
     */
    private fun pathOf(expr: TypedExpr, params: Map<String, MezzeType>): Path? = when (expr) {
        is TypedVar -> when {
            expr.binding !is BindingRef.Local -> null
            expr.name in params -> Path(expr.name, emptyList())
            // `let t = Config.threshold {}` then `t`: the scripted operation's result.
            else -> contextLocals[expr.name]?.takeIf { it in params }?.let { Path(it, emptyList()) }
        }
        // `Config.threshold {}` used inline.
        is TypedPerformOp -> SampleScripts.contextKey(expr.effectHash, expr.operationName)
            .takeIf { it in params }?.let { Path(it, emptyList()) }
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
        val root = params[path.param] ?: return null
        return placeAll(root, listOf(path.fields to leaf))
    }

    /**
     * A value of [type] with each leaf placed at its field path and every other
     * field drawn. Two leaves may share a record (`p.x` and `p.y`); a leaf at the
     * ROOT alongside any other leaf is a contradiction, and yields null.
     */
    private fun placeAll(type: MezzeType, leaves: List<Pair<List<String>, Pinned>>): Pinned? {
        leaves.firstOrNull { it.first.isEmpty() }?.let { return if (leaves.size == 1) it.second else null }
        val record = type as? MezzeType.RecordType ?: return null
        if (leaves.any { it.first.first() !in record.fields }) return null
        var draws = false
        val parts = record.fields.entries.map { (name, fieldType) ->
            val sub = leaves.filter { it.first.first() == name }.map { it.first.drop(1) to it.second }
            if (sub.isEmpty()) {
                draws = true
                "$name = ${SampleGen.of(fieldType)}"
            } else {
                val inner = placeAll(fieldType, sub) ?: return null
                if (inner.draws) draws = true
                "$name = ${inner.source}"
            }
        }
        return Pinned(parts.joinToString(", ", prefix = "{ ", postfix = " }"), draws)
    }

    private fun isPrimitive(type: MezzeType): Boolean =
        type is MezzeType.IntType || type is MezzeType.StringType || type is MezzeType.FloatType

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
    private const val SEQ_SIZE = "__native_seq_size"
    private const val SEQ_IS_EMPTY = "__native_seq_is_empty"

    /** Longest sequence written out as a pinned row — past it, a row is unreadable. */
    private const val MAX_PINNED_LENGTH = 16L
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
