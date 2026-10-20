package com.mezze.language.typechecker.emit

import com.mezze.language.ast.MezzeType
import com.mezze.language.typedast.*

/*
 * Questions to the compiler (introspection §9.2), at emit: a `perform` of a
 * compile-time handler becomes the comptime site the rest of the pipeline
 * already folds. Split from TypedAstBuilder, whose size is held by
 * EmitFileSizeRatchetTest; the siblings (CallEmit, PatternEmit) are the same
 * shape: extension functions over the builder's state.
 */

/**
 * A `perform` of a compile-time handler is a question to the compiler, folded
 * during the build (introspection §9.2). Here it becomes the comptime site the
 * rest of the pipeline already handles — `Comptime.comptime { eval = {} -> it }`
 * — so closedness, the result shape and folding are not built twice.
 *
 * Wrapping a subtree in a thunk is sound only if the subtree reads nothing from
 * the function around it, which is the check: a run-time value read here is a
 * compile error, never a silently wrong slot.
 */
/** Whether these handlers include one of `Comptime` (`ComptimeEval`). */
internal fun answersComptime(handlers: List<TypedHandlerRef>): Boolean =
    handlers.any { it.effectHash == com.mezze.language.typechecker.WellKnownHashConstants.EFFECT_COMPTIME }

/**
 * A perform's body. `perform ComptimeEval in perform EvalCodebase in …`: the
 * outer perform answers the inner's `Comptime`, so the OUTER is the question to
 * fold, and the inner must neither reject nor fold itself.
 */
internal fun TypedAstBuilder.buildPerformBody(
    expr: com.mezze.language.parsedast.ParsedPerform,
    file: String,
    contextType: MezzeType?,
    answersComptime: Boolean,
): TypedExpr {
    if (!(answersComptime && expr.body is com.mezze.language.parsedast.ParsedPerform)) return buildExpr(expr.body, file, contextType)
    questionAnsweredOutside++
    try { return buildExpr(expr.body, file, contextType) } finally { questionAnsweredOutside-- }
}

/**
 * The compile-time handlers in reach: those imported (decided over every loaded
 * module, in TypeChecker) and those declared in this module. The module's own
 * handlers are built first, so by the time any other declaration asks, all of
 * them exist.
 */
private fun TypedAstBuilder.compileTimeInReach(): com.mezze.language.typechecker.comptime.CompileTimePerformers.Found {
    val imported = com.mezze.language.typechecker.comptime.CompileTimePerformers.Found(
        importContext?.compileTimePerformers.orEmpty(),
        importContext?.comptimeLeavingPerformers.orEmpty(),
    )
    val local = localCompileTime
        ?: com.mezze.language.typechecker.comptime.CompileTimePerformers.ofPerformers(builtPerformers, imported)
            .also { localCompileTime = it }
    return com.mezze.language.typechecker.comptime.CompileTimePerformers.Found(
        imported.compileTime + local.compileTime,
        imported.leavesComptime + local.leavesComptime,
        local.answerer,
    )
}

internal fun TypedAstBuilder.foldQuestion(perform: TypedPerform, answersComptime: Boolean, outerNames: Set<String>): TypedExpr {
    // Inside a handler's own operations a question is forwarded, not asked: that
    // handler is compile-time itself, and is folded where IT is performed.
    if (insideComptimeBlock > 0 || insidePerformerBody > 0) return perform
    val inReach = compileTimeInReach()
    val compileTime = inReach.compileTime
    if (compileTime.isEmpty()) return perform
    val asks = perform.handlers.any { it.performerHash in compileTime }
    val answersInner = answersComptime &&
        (perform.body as? TypedPerform)?.handlers?.any { it.performerHash in compileTime } == true
    if (!asks && !answersInner) return perform
    if (!answersInner && questionAnsweredOutside > 0) return perform
    val comptime = com.mezze.language.typechecker.WellKnownHashConstants.EFFECT_COMPTIME
    // Run-time work inside a question is caught where it is precise — when the
    // evaluation comes back suspended on an effect nothing handled
    // (TruffleComptimeEvaluator). The row here cannot say it: passing an
    // effectful function as a VALUE (`ref { of = shout }`) carries its latent
    // effects forward, though nothing calls it.

    // `perform EvalCodebase in …` leaves `Comptime` to whoever handles it,
    // like a `comptime` block does: its row keeps `<Comptime>`, which composes
    // with any other compile-time work and is consented to once, outside. It is
    // still answered HERE, at build — its evaluation is given the `Comptime`
    // handler `std::codebase` itself uses.
    val leaving = inReach.leavesComptime
    val leavesComptime = asks && !answersComptime && perform.handlers.any { it.performerHash in leaving }
    val evaluated: TypedExpr = if (!leavesComptime) perform else {
        val answerer = importContext?.comptimeAnswererHash ?: inReach.answerer?.performerHash
            ?: com.mezze.language.typechecker.comptime.ComptimeRules.rejectUnansweredQuestion(perform.loc)
        val name = importContext?.comptimeAnswererName ?: inReach.answerer?.performerName
            ?: com.mezze.language.typechecker.comptime.ComptimeRules.rejectUnansweredQuestion(perform.loc)
        TypedPerform(
            listOf(TypedHandlerRef(answerer, comptime, name, null, perform.loc)),
            perform, perform.type, perform.loc,
        )
    }

    val read = mutableListOf<String>()
    fun walk(e: TypedExpr) {
        if (e is TypedVar && e.binding is BindingRef.Local && e.name in outerNames) read += e.name
        e.forEachChild { walk(it) }
    }
    walk(perform)
    perform.handlers.forEach { h -> h.params?.let { walk(it) } }
    com.mezze.language.typechecker.comptime.ComptimeRules.checkQuestionIsClosed(read, perform.loc)

    val thunkType = MezzeType.FunctionType(MezzeType.RecordType(emptyMap()), perform.type)
    val thunk = TypedLambda(emptyList(), evaluated, emptyList(), thunkType, perform.loc)
    val arg = TypedRecord(
        listOf(TypedRecordEntry.Field("eval", thunk, perform.loc)),
        MezzeType.RecordType(mapOf("eval" to thunkType)),
        perform.loc,
    )
    return TypedPerformOp(
        com.mezze.language.typechecker.WellKnownHashConstants.EFFECT_COMPTIME,
        "comptime", arg, perform.type, perform.loc,
    )
}
