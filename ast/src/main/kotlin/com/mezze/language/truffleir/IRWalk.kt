package com.mezze.language.truffleir

/**
 * The node with each DIRECT sub-expression replaced by [f] of it.
 *
 * Exhaustive over every [NodeIR] — a sealed `when` with no `else`, so a new node
 * type is a compile error here until its children are listed. That is the point:
 * a transforming pass built on this cannot silently skip what it never knew
 * existed. (A coverage instrumenter that missed a node would report the branches
 * inside it as never reached — wrong in the one direction a coverage report must
 * not err.) The parsed side has the same property in `ParsedExpr.children()`.
 *
 * Not recursive: [f] decides whether to descend, typically by calling
 * `transformChildren` on what it is handed.
 *
 * A `MakeLambdaIR` in a fused chain's stage or sink is a reference to another
 * function, with no sub-expressions of its own, and stays as it is — so the chain
 * keeps the exact lambda type it requires.
 */
fun NodeIR.transformChildren(f: (NodeIR) -> NodeIR): NodeIR = when (this) {
    is LongLitIR, is DoubleLitIR, is BoolLitIR, is StringLitIR, is UnitLitIR, is CharLitIR, is FracLitIR,
    is ReadLocalIR, is ReadCaptureIR, is ReadArgIR, is ReadGlobalByHashIR, is ReadGlobalFnByHashIR,
    is EffectBindingIR, is MakeLambdaIR -> this

    is InterpolatedStringIR -> copy(parts = parts.map { p ->
        when (p) {
            is StringPartIR.Lit -> p
            is StringPartIR.Expr -> StringPartIR.Expr(f(p.node))
        }
    })
    is WriteLocalIR -> copy(value = f(value))
    is CallDirectIR -> copy(args = args.map(f))
    is PartialApplyDirectIR -> copy(partialFields = partialFields.map { it.copy(value = f(it.value)) })
    is CallLocalIR -> copy(arg = f(arg))
    is CallNativeIR -> copy(arg = f(arg), args = args.map(f))
    is CallIndirectIR -> copy(function = f(function), arg = f(arg))
    is InspectIR -> copy(value = f(value))
    is CallIndirectScalarIR -> copy(function = f(function), args = args.map(f))
    is DictMethodCallIR -> copy(arg = f(arg))
    is InlineBinaryOpIR -> copy(left = f(left), right = f(right))
    is SpecializedCallIR -> copy(args = args.map(f))
    is MakeRecordIR -> copy(fields = fields.map { it.copy(value = f(it.value)) })
    is ReadFieldIR -> copy(record = f(record))
    is RecordUpdateIR -> copy(record = f(record), updates = updates.map { it.copy(value = f(it.value)) })
    is MakeVariantIR -> copy(payload = payload?.let(f))
    is MatchIR -> copy(scrutinee = f(scrutinee), cases = cases.map { c ->
        c.copy(
            pattern = c.pattern.transformChildren(f),
            guard = c.guard?.let(f),
            body = f(c.body),
            ifLetBindings = c.ifLetBindings.map { b -> b.copy(expr = f(b.expr), pattern = b.pattern.transformChildren(f)) },
        )
    })
    is MakeSeqIR -> copy(elements = elements.map { e ->
        when (e) {
            is SeqElementIR.Regular -> SeqElementIR.Regular(f(e.node))
            is SeqElementIR.Spread -> SeqElementIR.Spread(f(e.node))
        }
    })
    is SeqGetIR -> copy(seq = f(seq), index = f(index))
    is IfIR -> copy(cond = f(cond), thenBr = f(thenBr), elseBr = f(elseBr))
    is BlockIR -> copy(stmts = stmts.map { s ->
        when (s) {
            is StmtIR.Let -> s.copy(value = f(s.value))
            is StmtIR.Expr -> s.copy(node = f(s.node))
        }
    }, result = f(result))
    is FusedChainIR -> copy(
        source = when (val s = source) {
            is SourceIR.RangeSeq -> SourceIR.RangeSeq(f(s.start), f(s.end))
            is SourceIR.RangeStepSeq -> SourceIR.RangeStepSeq(f(s.start), f(s.end), f(s.step))
            is SourceIR.RawSeq -> SourceIR.RawSeq(f(s.xs))
            is SourceIR.RawList -> SourceIR.RawList(f(s.xs))
            is SourceIR.Opaque -> SourceIR.Opaque(f(s.expr))
        },
        stages = stages.map { st ->
            when (st) {
                is StageIR.Take -> StageIR.Take(f(st.n))
                is StageIR.Drop -> StageIR.Drop(f(st.n))
                is StageIR.Map, is StageIR.Filter, is StageIR.TakeWhile, is StageIR.DropWhile -> st
            }
        },
        sink = when (val k = sink) {
            is SinkIR.Fold -> k.copy(init = f(k.init))
            is SinkIR.Scan -> k.copy(init = f(k.init))
            is SinkIR.ForEach, SinkIR.Sum, SinkIR.SumFloat, SinkIR.Product, SinkIR.ProductFloat, SinkIR.Count,
            is SinkIR.Reduce, is SinkIR.Any, is SinkIR.All, is SinkIR.Find, is SinkIR.FindIndex,
            SinkIR.ToSeq, SinkIR.ToList, is SinkIR.None, SinkIR.First, SinkIR.Last, SinkIR.Min, SinkIR.Max,
            is SinkIR.MinBy, is SinkIR.MaxBy, is SinkIR.Partition -> k
        },
        fallback = fallback?.let(f),
    )
    is HandleIR -> copy(body = f(body), handlers = handlers.map { h -> h.copy(params = h.params?.let(f)) })
    is PerformOpIR -> copy(arg = f(arg))
}

/** A pattern's sub-expressions: only a literal pattern has one. */
fun PatternIR.transformChildren(f: (NodeIR) -> NodeIR): PatternIR = when (this) {
    is PatternIR.Literal -> PatternIR.Literal(f(value))
    is PatternIR.Variant, is PatternIR.Wildcard, is PatternIR.Record, is PatternIR.Seq, is PatternIR.StringInterp -> this
}

/** Visit each DIRECT sub-expression — [transformChildren] read-only. */
inline fun NodeIR.forEachChildIR(crossinline visit: (NodeIR) -> Unit) {
    transformChildren { visit(it); it }
}

/** Rewrite every node, children first. */
fun NodeIR.transformBottomUp(f: (NodeIR) -> NodeIR): NodeIR = f(transformChildren { it.transformBottomUp(f) })
