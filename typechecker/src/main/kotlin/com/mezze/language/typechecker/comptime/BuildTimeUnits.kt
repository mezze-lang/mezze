package com.mezze.language.typechecker.comptime

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typedast.BindingRef
import com.mezze.language.typedast.BuildTimeHandler
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedBlock
import com.mezze.language.typedast.TypedBuildTime
import com.mezze.language.typedast.TypedCall
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedHandlerRef
import com.mezze.language.typedast.TypedLambda
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedMatch
import com.mezze.language.typedast.TypedModule
import com.mezze.language.typedast.TypedPerform
import com.mezze.language.typedast.TypedPerformOp
import com.mezze.language.typedast.TypedStatement
import com.mezze.language.typedast.TypedVar
import com.mezze.language.typedast.forEachChild
import com.mezze.language.typedast.mapChildren

/**
 * What runs while the program is built (introspection §9.2).
 *
 * ## The rule
 *
 * A `Comptime` request answered by `ComptimeEval` is answered at build. The
 * build evaluates the smallest CLOSED expression around the request — one that
 * reads no local from outside itself, and is not a function — under the user's
 * own handlers between it and that answer:
 *
 *  - `Codebase.decls { module = "app" }` under `ComptimeEvalCodebase` is closed by
 *    itself, so that call is the unit.
 *  - A chain — `match Codebase.resolve { … } is Some { val } -> Codebase.info
 *    { decl = val } …` — is closed only as a whole, so the `match` is the unit.
 *    A question inside a lambda grows to the call that consumes the lambda.
 *  - `perform ComptimeEval in ask {}`, where `ask` asks and leaves `<Comptime>`:
 *    the call `ask {}` is the unit; nothing else in the function moves.
 *  - A request that reaches its answer without closing reads a run-time value:
 *    a compile error naming it (V0802).
 *
 * A request is answered by `ComptimeEval` written around it, or by a handler
 * that answers inside its own operations — `ComptimeEvalCodebase` is
 * `ComptimeEval` around `EvalCodebase` for every operation, so it is the same
 * rule. Nothing is inserted: a request with no answer around it leaves
 * `<Comptime>` in the function's type, like any unhandled effect.
 *
 * An explicit `Comptime.comptime { eval }` is not a request here: its thunk is
 * self-contained and is folded where written, as before.
 *
 * ## Representation
 *
 * [TypedBuildTime] around the unit, carrying those handlers. The unit itself is
 * untouched — the typed tree says what was written, and when part of it runs.
 */
object BuildTimeUnits {

    /**
     * Handlers that raise `Comptime` from their operations, by identity, and of
     * those, the ones that leave it to an outer handler
     * ([CompileTimePerformers]): `EvalCodebase` leaves it, `ComptimeEvalCodebase`
     * answers it inside.
     */
    data class Handlers(val raises: Set<Hash>, val leaves: Set<Hash>) {
        fun answersInside(h: Hash) = h in raises && h !in leaves
    }

    /** Where a request is answered: the handlers from the outermost answer inwards, and which `perform` that is. */
    private class Answer(val chain: List<BuildTimeHandler>, val boundary: TypedPerform)

    /**
     * The answers in force at a point: for `Comptime` itself, and for each
     * question effect a handler on the path turns into an answered `Comptime`.
     */
    private class Ctx(val comptime: Answer?, val effects: Map<Hash, Answer>) {
        companion object { val EMPTY = Ctx(null, emptyMap()) }
    }

    private class Out(val expr: TypedExpr, val pending: List<Answer>, val free: Set<String>)

    private val COMPTIME get() = WellKnownHashConstants.EFFECT_COMPTIME

    /**
     * The functions whose questions are NOT answered inside them: their
     * `<Comptime>` comes from a question and leaves to the caller, so a call to
     * one, under an answer, is itself a request. A fixpoint, from [known].
     *
     * Distinguished from a function whose `<Comptime>` comes only from an
     * explicit `comptime` block — that one is folded where it is written, and a
     * call to it is ordinary.
     */
    fun askingFunctions(decls: List<TypedLetDecl>, handlers: Handlers, known: Set<Hash>): Set<Hash> {
        val asking = HashSet(known)
        var changed = true
        while (changed) {
            changed = false
            for (decl in decls) {
                if (decl.hash in asking) continue
                if (asksUnanswered(decl.value, handlers, asking)) {
                    asking += decl.hash
                    changed = true
                }
            }
        }
        return asking - known
    }

    /** Whether [e] asks a question nothing inside it answers. */
    private fun asksUnanswered(e: TypedExpr, handlers: Handlers, asking: Set<Hash>): Boolean {
        var found = false
        fun walk(x: TypedExpr, answered: Boolean) {
            if (found) return
            when {
                x is TypedPerformOp && x.effectHash == COMPTIME && x.operationName == "comptime" -> return
                x is TypedPerform -> {
                    val answers = answered || x.handlers.any { it.effectHash == COMPTIME }
                    if (!answers && x.handlers.any { it.performerHash in handlers.leaves }) { found = true; return }
                    x.handlers.forEach { h -> h.params?.let { walk(it, answers) } }
                    walk(x.body, answers)
                    return
                }
                x is TypedCall && !answered && (x.function as? TypedVar)?.let { v ->
                    (v.binding as? BindingRef.ByHash)?.hash in asking
                } == true -> { found = true; return }
            }
            x.forEachChild { walk(it, answered) }
        }
        walk(e, answered = false)
        return found
    }

    /**
     * [module] with every build-time unit marked. Function bodies only: a
     * handler's own operations are where questions are ANSWERED, not asked, and
     * a `comptime` block already runs while compiling.
     */
    fun mark(module: TypedModule, handlers: Handlers, asking: Set<Hash>): TypedModule {
        val pass = Pass(handlers, asking)
        val decls = module.decls.map { d ->
            if (d is TypedLetDecl) {
                val value = pass.top(d.value)
                if (value === d.value) d else d.copy(value = value)
            } else d
        }
        val impls = module.abilityImpls.map { impl ->
            val ops = impl.operations.mapValues { (_, body) -> pass.top(body) }
            if (ops.all { (k, v) -> v === impl.operations[k] }) impl else impl.copy(operations = ops)
        }
        return module.copy(decls = decls, abilityImpls = impls)
    }

    /**
     * [unit] as the build lowers it. The typed tree holds the unit as written;
     * only here, for the lowered code, does it become a build site: the user's
     * own handlers between it and its answer, around it, installed as their
     * `perform`s installed them, as a closed thunk the build evaluates and
     * replaces with the value.
     */
    fun buildSite(unit: TypedBuildTime): TypedPerformOp {
        val body = unit.handlers.asReversed().fold(unit.expr) { inner, h ->
            TypedPerform(listOf(h.handler), inner, unit.type, unit.loc, fullyDischarges = h.fullyDischarges)
        }
        val thunkType = MezzeType.FunctionType(MezzeType.RecordType(emptyMap()), unit.type)
        val thunk = TypedLambda(emptyList(), body, emptyList(), thunkType, unit.loc)
        val arg = com.mezze.language.typedast.TypedRecord(
            listOf(com.mezze.language.typedast.TypedRecordEntry.Field("eval", thunk, unit.loc)),
            MezzeType.RecordType(mapOf("eval" to thunkType)),
            unit.loc,
        )
        return TypedPerformOp(COMPTIME, "comptime", arg, unit.type, unit.loc)
    }

    private class Pass(val handlers: Handlers, val asking: Set<Hash>) {

        fun top(body: TypedExpr): TypedExpr = walk(body, Ctx.EMPTY, emptySet()).expr

        fun walk(e: TypedExpr, ctx: Ctx, scope: Set<String>): Out {
            // Already a build-time block: everything inside runs while compiling.
            if (e is TypedPerformOp && e.effectHash == COMPTIME && e.operationName == "comptime") {
                return Out(e, emptyList(), freeNames(e, scope))
            }
            val pending = mutableListOf<Answer>()
            val free = HashSet<String>()
            var changed = false
            fun child(c: TypedExpr, cCtx: Ctx, cScope: Set<String>, bound: Set<String>): TypedExpr {
                val out = walk(c, cCtx, cScope)
                pending += out.pending
                out.free.forEach { if (it !in bound) free += it }
                if (out.expr !== c) changed = true
                return out.expr
            }

            val rebuilt: TypedExpr = when (e) {
                is TypedVar -> { if (e.name in scope) free += e.name; e }
                is TypedLambda -> {
                    val params = e.params.mapTo(HashSet()) { it.name }
                    val body = child(e.body, ctx, scope + params, params)
                    if (body === e.body) e else e.copy(body = body)
                }
                is TypedBlock -> walkBlock(e, ctx, scope, ::child)
                is TypedMatch -> {
                    val scrutinee = child(e.scrutinee, ctx, scope, emptySet())
                    val cases = e.cases.map { c ->
                        val names = c.bindings.mapTo(HashSet()) { it.name } + c.ifLetBindings.flatMap { it.bindingNames }
                        val inner = scope + names
                        c.copy(
                            guard = c.guard?.let { child(it, ctx, inner, names) },
                            body = child(c.body, ctx, inner, names),
                            ifLetBindings = c.ifLetBindings.map { b -> b.copy(expr = child(b.expr, ctx, inner, names)) },
                        )
                    }
                    e.copy(scrutinee = scrutinee, cases = cases)
                }
                is TypedPerform -> {
                    val inner = enter(e, ctx)
                    val params = e.handlers.map { h -> h.copy(params = h.params?.let { child(it, ctx, scope, emptySet()) }) }
                    val bodyOut = walk(e.body, inner, scope)
                    bodyOut.free.forEach { free += it }
                    if (bodyOut.expr !== e.body) changed = true
                    // A request answered HERE that never closed reads a run-time value.
                    val (mine, outer) = bodyOut.pending.partition { it.boundary === e }
                    if (mine.isNotEmpty()) {
                        ComptimeRules.checkQuestionIsClosed(bodyOut.free.toList(), e.loc)
                        ComptimeRules.rejectUnfoldableQuestion(e.loc)
                    }
                    pending += outer
                    e.copy(handlers = params, body = bodyOut.expr)
                }
                else -> e.mapChildren { c -> child(c, ctx, scope, emptySet()) }
            }

            request(e, ctx)?.let { pending += it }
            val result = if (changed) rebuilt else e
            return close(result, pending, free)
        }

        private fun walkBlock(
            e: TypedBlock, ctx: Ctx, scope: Set<String>,
            child: (TypedExpr, Ctx, Set<String>, Set<String>) -> TypedExpr,
        ): TypedExpr {
            val seen = HashSet<String>()
            val statements = e.statements.map { st ->
                when (st) {
                    is TypedStatement.Let -> {
                        val visible = if (st.isRecursive) seen + st.name else seen
                        val value = child(st.value, ctx, scope + visible, visible)
                        seen += st.name
                        st.copy(value = value)
                    }
                    is TypedStatement.Expr -> st.copy(expr = child(st.expr, ctx, scope + seen, seen.toSet()))
                }
            }
            val result = child(e.result, ctx, scope + seen, seen.toSet())
            return e.copy(statements = statements, result = result)
        }

        /** The answers inside `perform [p] in …`. */
        private fun enter(p: TypedPerform, ctx: Ctx): Ctx {
            var comptime = ctx.comptime
            val effects = HashMap(ctx.effects)
            fun installed(h: TypedHandlerRef) = BuildTimeHandler(h, p.fullyDischarges)
            p.handlers.firstOrNull { it.effectHash == COMPTIME }?.let { comptime = Answer(listOf(installed(it)), p) }
            for (h in p.handlers) {
                if (h.effectHash == COMPTIME) continue
                when {
                    handlers.answersInside(h.performerHash) -> effects[h.effectHash] = Answer(listOf(installed(h)), p)
                    h.performerHash in handlers.leaves -> {
                        val answer = comptime
                        if (answer != null) effects[h.effectHash] = Answer(answer.chain + installed(h), answer.boundary)
                        else effects.remove(h.effectHash)
                    }
                    // Any other handler of the effect answers it at run time.
                    else -> effects.remove(h.effectHash)
                }
            }
            return Ctx(comptime, effects)
        }

        /** The answer to [e] as a request, if it is one. */
        private fun request(e: TypedExpr, ctx: Ctx): Answer? = when (e) {
            is TypedPerformOp ->
                if (e.effectHash == COMPTIME) ctx.comptime else ctx.effects[e.effectHash]
            is TypedCall -> {
                val row = (e.function.type as? MezzeType.FunctionType)?.effects
                row?.effectHashes?.values?.firstNotNullOfOrNull { ctx.effects[it] }
                    ?: ctx.comptime?.takeIf {
                        ((e.function as? TypedVar)?.binding as? BindingRef.ByHash)?.hash in asking
                    }
            }
            else -> null
        }

        /**
         * Mark [e] as a unit if it holds requests, is closed and is a value — or
         * pass the requests up to whatever encloses it.
         */
        private fun close(e: TypedExpr, pending: List<Answer>, free: Set<String>): Out {
            if (pending.isEmpty()) return Out(e, pending, free)
            val first = pending.first()
            val oneAnswer = pending.all { it.boundary === first.boundary && it.chain == first.chain }
            // What the build computes is written into the program: data all the
            // way down, so not a function, nor anything that holds one.
            val isValue = e !is TypedLambda && !holdsFunction(e.type)
            if (free.isEmpty() && isValue && oneAnswer) {
                // A unit inside this one is computed by this one's evaluation.
                return Out(TypedBuildTime(unmark(e), first.chain, e.type, e.loc), emptyList(), free)
            }
            return Out(e, pending, free)
        }

        private fun holdsFunction(t: MezzeType): Boolean = when (t) {
            is MezzeType.FunctionType -> true
            is MezzeType.RecordType -> t.fields.values.any(::holdsFunction)
            is MezzeType.VariantType -> t.constructors.values.any { it != null && holdsFunction(it) }
            is MezzeType.TypeApplication -> t.args.any(::holdsFunction)
            is MezzeType.NewtypeType -> holdsFunction(t.wrappedType)
            is MezzeType.EffectRow -> false
            MezzeType.IntType, MezzeType.FloatType, MezzeType.StringType, MezzeType.CharType,
            MezzeType.FracType, MezzeType.BytesType -> false
            is MezzeType.VarType -> false
        }

        private fun unmark(e: TypedExpr): TypedExpr =
            if (e is TypedBuildTime) unmark(e.expr) else e.mapChildren(::unmark)

        /** The names from [scope] that [e] reads, without marking anything. */
        private fun freeNames(e: TypedExpr, scope: Set<String>): Set<String> {
            val out = HashSet<String>()
            fun go(x: TypedExpr, bound: Set<String>) {
                when (x) {
                    is TypedVar -> if (x.name in scope && x.name !in bound) out += x.name
                    is TypedLambda -> go(x.body, bound + x.params.map { it.name })
                    is TypedBlock -> {
                        var seen = bound
                        for (st in x.statements) when (st) {
                            is TypedStatement.Let -> {
                                go(st.value, if (st.isRecursive) seen + st.name else seen)
                                seen = seen + st.name
                            }
                            is TypedStatement.Expr -> go(st.expr, seen)
                        }
                        go(x.result, seen)
                    }
                    is TypedMatch -> {
                        go(x.scrutinee, bound)
                        for (c in x.cases) {
                            val inner = bound + c.bindings.map { it.name } + c.ifLetBindings.flatMap { it.bindingNames }
                            c.guard?.let { go(it, inner) }
                            go(c.body, inner)
                            c.ifLetBindings.forEach { go(it.expr, inner) }
                        }
                    }
                    else -> x.forEachChild { go(it, bound) }
                }
            }
            go(e, emptySet())
            return out
        }
    }
}
