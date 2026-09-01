package com.mezze.interpreter.runtime

import com.mezze.language.truffleir.BlockIR
import com.mezze.language.truffleir.CallDirectIR
import com.mezze.language.truffleir.CallIndirectIR
import com.mezze.language.truffleir.CallIndirectScalarIR
import com.mezze.language.truffleir.CallLocalIR
import com.mezze.language.truffleir.CallNativeIR
import com.mezze.language.truffleir.DictMethodCallIR
import com.mezze.language.truffleir.EffectBindingIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.HandleIR
import com.mezze.language.truffleir.IfIR
import com.mezze.language.truffleir.InlineBinaryOpIR
import com.mezze.language.truffleir.InterpolatedStringIR
import com.mezze.language.truffleir.MakeLambdaIR
import com.mezze.language.truffleir.MakeRecordIR
import com.mezze.language.truffleir.MakeSeqIR
import com.mezze.language.truffleir.MakeVariantIR
import com.mezze.language.truffleir.MatchIR
import com.mezze.language.truffleir.NodeIR
import com.mezze.language.truffleir.PartialApplyDirectIR
import com.mezze.language.truffleir.PerformOpIR
import com.mezze.language.truffleir.ReadFieldIR
import com.mezze.language.truffleir.ReadGlobalByHashIR
import com.mezze.language.truffleir.ReadGlobalFnByHashIR
import com.mezze.language.truffleir.RecordUpdateIR
import com.mezze.language.truffleir.SeqElementIR
import com.mezze.language.truffleir.SeqGetIR
import com.mezze.language.truffleir.SpecializedCallIR
import com.mezze.language.truffleir.StmtIR
import com.mezze.language.truffleir.StringPartIR
import com.mezze.language.truffleir.WriteLocalIR
import com.mezze.language.typedast.Hash

object ReachabilityWalker {

    fun interface MissingHashLogger {
        fun onMissing(hash: Hash)
    }

    private val silentLogger = MissingHashLogger { _ -> }

    fun walk(
        roots: Set<Hash>,
        lookup: (Hash) -> FunctionIR?,
        logger: MissingHashLogger = silentLogger,
    ): Set<Hash> {
        val visited = LinkedHashSet<Hash>()
        val queue = ArrayDeque<Hash>()
        for (r in roots) {
            if (visited.add(r)) queue.add(r)
        }
        while (queue.isNotEmpty()) {
            val h = queue.removeFirst()
            val fn = lookup(h)
            if (fn == null) {
                logger.onMissing(h)
                continue
            }
            val refs = mutableSetOf<Hash>()
            collectHashRefs(fn.body, refs)
            for (ref in refs) {
                if (visited.add(ref)) queue.add(ref)
            }
        }
        return visited
    }

    private fun collectHashRefs(node: NodeIR, refs: MutableSet<Hash>) {
        when (node) {
            is CallDirectIR -> { refs.add(node.targetHash); node.args.forEach { collectHashRefs(it, refs) } }
            is PartialApplyDirectIR -> { refs.add(node.targetHash); node.partialFields.forEach { collectHashRefs(it.value, refs) } }
            is MakeLambdaIR -> refs.add(node.functionHash)
            is HandleIR -> {
                collectHashRefs(node.body, refs)
                for (h in node.handlers) {
                    refs.add(h.handlerHash)
                    refs.add(h.effectHash)
                    h.params?.let { collectHashRefs(it, refs) }
                }
            }
            is PerformOpIR -> { refs.add(node.effectHash); collectHashRefs(node.arg, refs) }
            is BlockIR -> {
                for (s in node.stmts) when (s) {
                    is StmtIR.Let -> collectHashRefs(s.value, refs)
                    is StmtIR.Expr -> collectHashRefs(s.node, refs)
                }
                collectHashRefs(node.result, refs)
            }
            is CallLocalIR -> collectHashRefs(node.arg, refs)
            is CallNativeIR -> {
                collectHashRefs(node.arg, refs)
                node.args.forEach { collectHashRefs(it, refs) }
            }
            is CallIndirectIR -> { collectHashRefs(node.function, refs); collectHashRefs(node.arg, refs) }
            is com.mezze.language.truffleir.InspectIR -> collectHashRefs(node.value, refs)
            is CallIndirectScalarIR -> { collectHashRefs(node.function, refs); node.args.forEach { collectHashRefs(it, refs) } }
            is MakeRecordIR -> node.fields.forEach { collectHashRefs(it.value, refs) }
            is ReadFieldIR -> collectHashRefs(node.record, refs)
            is RecordUpdateIR -> { collectHashRefs(node.record, refs); node.updates.forEach { collectHashRefs(it.value, refs) } }
            is MakeVariantIR -> node.payload?.let { collectHashRefs(it, refs) }
            is MatchIR -> {
                collectHashRefs(node.scrutinee, refs)
                for (c in node.cases) { collectHashRefs(c.body, refs); c.guard?.let { collectHashRefs(it, refs) } }
            }
            is IfIR -> { collectHashRefs(node.cond, refs); collectHashRefs(node.thenBr, refs); collectHashRefs(node.elseBr, refs) }
            is WriteLocalIR -> collectHashRefs(node.value, refs)
            is InlineBinaryOpIR -> { collectHashRefs(node.left, refs); collectHashRefs(node.right, refs) }
            is SpecializedCallIR -> node.args.forEach { collectHashRefs(it, refs) }
            is InterpolatedStringIR -> {
                for (p in node.parts) if (p is StringPartIR.Expr) collectHashRefs(p.node, refs)
            }
            is MakeSeqIR -> {
                for (e in node.elements) when (e) {
                    is SeqElementIR.Regular -> collectHashRefs(e.node, refs)
                    is SeqElementIR.Spread -> collectHashRefs(e.node, refs)
                }
            }
            is SeqGetIR -> { collectHashRefs(node.seq, refs); collectHashRefs(node.index, refs) }
            is DictMethodCallIR -> collectHashRefs(node.arg, refs)
            is ReadGlobalByHashIR -> refs.add(node.hash)
            is ReadGlobalFnByHashIR -> refs.add(node.hash)
            is EffectBindingIR -> {}
            else -> {}
        }
    }
}
