package com.mezze.language.codebase

import com.mezze.language.ast.MezzeType
import com.mezze.language.comptime.HostValue
import com.mezze.language.comptime.HostValue.Bool
import com.mezze.language.comptime.HostValue.Opt
import com.mezze.language.comptime.HostValue.Companion.record
import com.mezze.language.comptime.SyntaxEncoder
import com.mezze.language.parsedast.Loc
import com.mezze.language.typedast.BindingRef
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedBuildTime
import com.mezze.language.typedast.TypedCall
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedFieldAccess
import com.mezze.language.typedast.TypedPerformOp
import com.mezze.language.typedast.TypedVar
import com.mezze.language.typedast.forEachChild

/**
 * What the compiler made of each written node: the `facts` [SyntaxEncoder] puts
 * on the tree.
 *
 * The facts come from the typed tree, joined by SPAN: the compiler keeps the
 * span of what was written on what it made of it, so `a > b`, which it turns
 * into the call `a.gt { r = b }`, still sits at the span of `a > b`. A node with
 * no typed counterpart at its span — parentheses, a newtype wrapper, anything in
 * code that did not type-check — has no facts, and says so.
 */
internal class SyntaxFacts(
    private val typeShape: (MezzeType) -> HostValue,
    private val declRef: (CodebaseQueries.DeclRef) -> HostValue,
    private val reached: (Hash) -> CodebaseQueries.DeclRef?,
    /** What a call through [ability] on [receiver] reaches (see [CodebaseQueries.dispatched]). */
    private val dispatched: (ability: Hash, receiver: MezzeType) -> CodebaseQueries.DeclRef?,
    typed: List<TypedExpr>,
) {
    private data class At(val startLine: Int, val startCol: Int, val endLine: Int, val endCol: Int)

    private fun at(loc: Loc) = At(loc.startLine, loc.startCol, loc.endLine, loc.endCol)

    /** Every typed node, by the span it was written at, outermost first. */
    private val bySpan: Map<At, List<TypedExpr>> = HashMap<At, MutableList<TypedExpr>>().also { index ->
        fun walk(e: TypedExpr) {
            index.getOrPut(at(e.loc)) { mutableListOf() } += e
            e.forEachChild { walk(it) }
        }
        typed.forEach { walk(it) }
    }

    /** An encoder that puts these facts on every node. */
    fun encoder(): SyntaxEncoder = SyntaxEncoder { loc -> facts(at(loc)) }

    /** The facts at a span, for an occurrence found through the index. */
    fun factsAt(startLine: Int, startCol: Int, endLine: Int, endCol: Int): HostValue =
        facts(At(startLine, startCol, endLine, endCol))

    private fun facts(span: At): HostValue {
        val nodes = bySpan[span] ?: return Opt(null)
        val node = nodes.firstOrNull { it !is TypedBuildTime } ?: return Opt(null)
        fun byHash(e: TypedExpr?): Hash? = ((e as? TypedVar)?.binding as? BindingRef.ByHash)?.hash
        val refersTo = nodes.firstNotNullOfOrNull { byHash(it) }?.let(reached)
        val calls = when (node) {
            // A call by hash; or a method or operator dispatched through an
            // ability, which reaches that ability's impl for the receiver's type.
            is TypedCall -> byHash(node.function)?.let(reached)
                ?: (node.function as? TypedFieldAccess)?.let { fa ->
                    fa.dispatchedAbility?.let { dispatched(it, fa.record.type) }
                }
            is TypedPerformOp -> reached(node.effectHash)
            else -> null
        }
        return Opt(record(
            "ty" to typeShape(node.type),
            "refers_to" to Opt(refersTo?.let(declRef)),
            "calls" to Opt(calls?.let(declRef)),
            "at_build" to Bool(nodes.any { it is TypedBuildTime }),
        ))
    }
}
