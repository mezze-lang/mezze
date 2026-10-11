package com.mezze.language.truffleir

import com.mezze.language.typedast.Hash

/**
 * Branch coverage, as a rewrite of IR that is about to be run (S-23).
 *
 * Every `if` branch and every `match` arm of the TARGET gets a call that records,
 * when it runs, that it was reached. Which sites ran is then known exactly, rather
 * than inferred from the inputs — the only way to learn whether a guard
 * (`Circle { r } if r > 10`) or a branch on a computed value (`match xs.len is …`)
 * was ever taken.
 *
 * A compilation mode, not a language feature: nothing in the program changes, no
 * effect row gains anything, and only a caller that asks for coverage ever runs
 * instrumented IR. The hit is a non-pure native so no pass can drop it.
 *
 * The site wraps an arm's BODY, never its guard: reaching the body is what "this
 * arm ran" means, so an arm whose guard never passed shows as not reached.
 *
 * A site is a place in the TARGET'S SOURCE, not a node in one function: lowering
 * inlines small functions into their callers (`TrivialWrapperInliner` takes
 * branching helpers like `sign` on purpose), so a target's branch may run as a
 * copy inside whatever called it. Every `if`/`match` in the program whose source
 * lies inside the target is instrumented, wherever it ended up, and copies of one
 * branch share one site. Lambdas written in the target fall out of the same rule;
 * what the target CALLS lies elsewhere and is not its branches.
 */
object BranchCoverage {

    /** The native the instrumented IR calls; the runtime records each site it reaches. */
    const val HIT = "__native_observe_hit"

    /** One branch site: where it is, and which branch of what. */
    data class Site(val id: Int, val src: SourceInfo, val kind: Kind, val arm: Int) {
        enum class Kind { THEN, ELSE, ARM }
    }

    data class Instrumented(val functions: List<FunctionIR>, val sites: List<Site>)

    /**
     * Instrument every branch whose source lies inside [target]'s, in all of
     * [functions]. Ids follow source order, so two compiles of one program agree.
     */
    fun instrument(functions: List<FunctionIR>, target: Hash): Instrumented {
        val span = functions.firstOrNull { it.hash == target }?.sourceSection
            ?: return Instrumented(functions, emptyList())
        fun inside(s: SourceInfo) = s.fileIndex == span.fileIndex &&
            (s.startLine > span.startLine || (s.startLine == span.startLine && s.startCol >= span.startCol)) &&
            (s.endLine < span.endLine || (s.endLine == span.endLine && s.endCol <= span.endCol))

        // Sites first, in source order: the same branch inlined twice is one site.
        val keys = sortedSetOf<Key>(compareBy({ it.src.startLine }, { it.src.startCol }, { it.arm }))
        for (fn in functions) fn.body.transformBottomUp { node ->
            when (node) {
                is IfIR -> if (inside(node.src)) {
                    keys.add(Key(node.thenBr.src, Site.Kind.THEN, 0)); keys.add(Key(node.elseBr.src, Site.Kind.ELSE, 1))
                }
                is MatchIR -> if (inside(node.src)) node.cases.forEachIndexed { i, c -> keys.add(Key(c.src, Site.Kind.ARM, i)) }
                else -> {}
            }
            node
        }
        val ids = keys.withIndex().associate { (i, k) -> k to i }
        val sites = keys.mapIndexed { i, k -> Site(i, k.src, k.kind, k.arm) }

        fun hit(key: Key, body: NodeIR): NodeIR {
            val call = CallNativeIR(
                name = HIT,
                arg = MakeRecordIR(listOf(FieldIR("site", LongLitIR(ids.getValue(key).toLong(), body.src))), body.src),
                src = body.src,
                isPure = false,
            )
            return BlockIR(listOf(StmtIR.Expr(call, body.src)), body, body.src)
        }

        val rewritten = functions.map { fn ->
            fn.copy(body = fn.body.transformBottomUp { node ->
                when (node) {
                    is IfIR -> if (!inside(node.src)) node else node.copy(
                        thenBr = hit(Key(node.thenBr.src, Site.Kind.THEN, 0), node.thenBr),
                        elseBr = hit(Key(node.elseBr.src, Site.Kind.ELSE, 1), node.elseBr),
                    )
                    is MatchIR -> if (!inside(node.src)) node else node.copy(cases = node.cases.mapIndexed { i, c ->
                        c.copy(body = hit(Key(c.src, Site.Kind.ARM, i), c.body))
                    })
                    else -> node
                }
            })
        }
        return Instrumented(rewritten, sites)
    }

    private data class Key(val src: SourceInfo, val kind: Site.Kind, val arm: Int)
}
