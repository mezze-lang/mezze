package com.mezze.language.typechecker

import com.mezze.language.parsedast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 0.1's premise, measured before it is acted on.
 *
 * The solver hands the builder its results in side tables keyed by source
 * location — `callReturnTypes`, `matchCaseBindings`, `expectedTypeAt` and about
 * sixty others. A location is not an identity: two distinct expression nodes can
 * occupy the same span, and when they do, one entry overwrites the other and the
 * builder reads a type belonging to a different node. That is the mechanism
 * NodeId removes.
 *
 * Measured 2026-09-15, and the answer is that it does not happen: across 21,389
 * expression nodes in the stdlib, no location is used by more than one node. A
 * second measurement, run once with temporary instrumentation in the constraint
 * accumulator, agreed — over 67 stdlib modules, no side-table write ever
 * replaced an existing entry with a DIFFERENT value. (To repeat it: count, in
 * `ExpressionConstraints`' `merge`, the writes where `prev != null && prev != v`
 * for callReturnTypes / matchScrutineeTypes / doBindingSolvedTypes.)
 *
 * So Loc-keying is not currently losing data, and the gaps task 0.2 measured
 * come from somewhere else: the solver having no entry to find at all. That
 * re-scopes 0.1 — NodeId is still worth having as the thing A.2's
 * ElaborationError names, but it is not what is producing wrong types today, and
 * A.1 is where the 566 impl-body findings live.
 *
 * Kept as a ratchet rather than deleted: the property holds today, and the
 * cheapest way to keep it true is to notice the first time it stops being true.
 * `ParsedIf.toMatchExpr` is the near miss — it gives the synthesized match AND
 * both of its cases the same `loc` as the original `if`, which is harmless only
 * because those cases bind nothing.
 */
class LocKeyCollisionMeasurementTest {

    private fun stdlibSources(): List<File> {
        val root = File("../stdlib/std").takeIf { it.isDirectory }
            ?: File("stdlib/std").takeIf { it.isDirectory }
            ?: error("cannot find stdlib/std from ${File(".").absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension == "mz" }.toList()
    }

    /** Every expression node in a declaration, paired with its location. */
    private fun exprLocs(decl: ParsedDecl): List<Pair<Loc, String>> {
        val out = mutableListOf<Pair<Loc, String>>()
        fun visit(e: ParsedExpr) {
            out.add(e.loc to e::class.simpleName.orEmpty())
            when (e) {
                is ParsedLambda -> visit(e.body)
                is ParsedFunctionCall -> { visit(e.function); visit(e.arguments) }
                is ParsedIf -> { visit(e.condition); visit(e.thenBranch); visit(e.elseBranch) }
                is ParsedMatch -> { visit(e.scrutinee); e.cases.forEach { c -> c.guard?.let(::visit); visit(c.body) } }
                is ParsedDo -> {
                    e.statements.forEach { s ->
                        when (s) {
                            is ParsedStatement.Let -> visit(s.value)
                            is ParsedStatement.Expr -> visit(s.expr)
                        }
                    }
                    visit(e.result)
                }
                is ParsedPerform -> { e.handlers.forEach { h -> h.params?.let(::visit) }; visit(e.body) }
                is ParsedMemberAccess -> visit(e.receiver)
                is ParsedRecordLit -> e.entries.forEach { entry ->
                    when (entry) {
                        is ParsedRecordEntry.Field -> entry.value?.let(::visit)
                        is ParsedRecordEntry.Spread -> visit(entry.expr)
                    }
                }
                is ParsedSeqLit -> e.elements.forEach { el ->
                    when (el) {
                        is ParsedSeqElement.Regular -> visit(el.expr)
                        is ParsedSeqElement.Spread -> visit(el.expr)
                    }
                }
                is ParsedBinaryOp -> { visit(e.left); visit(e.right) }
                is ParsedPipe -> { visit(e.left); visit(e.right) }
                else -> Unit
            }
        }
        when (decl) {
            is ParsedLetDecl -> visit(decl.value)
            is ParsedImplAbilityDecl -> decl.operations.forEach { op -> op.implementation?.let(::visit) }
            is ParsedPerformerDecl -> decl.operations.forEach { op -> op.implementation?.let(::visit) }
            else -> Unit
        }
        return out
    }

    @Test
    fun `how often two distinct nodes share one source location`() {
        val parser = MezzeParser()
        var totalNodes = 0
        var collidingLocs = 0
        var nodesInCollisions = 0
        val worstExamples = mutableListOf<String>()

        for (file in stdlibSources()) {
            val module = runCatching { parser.parseParsedModule(file.readText(), file.path) }.getOrNull()
                ?: continue
            for (decl in module.decls) {
                val locs = exprLocs(decl)
                totalNodes += locs.size
                locs.groupBy { it.first }.forEach { (loc, group) ->
                    if (group.size > 1) {
                        collidingLocs++
                        nodesInCollisions += group.size
                        if (worstExamples.size < 12) {
                            worstExamples.add(
                                "${file.name}:${loc.startLine} — ${group.size} nodes: " +
                                    group.map { it.second }.distinct().joinToString("/"),
                            )
                        }
                    }
                }
            }
        }

        println("Loc-key collisions across the stdlib:")
        println("  expression nodes walked : $totalNodes")
        println("  locations used by >1 node: $collidingLocs")
        println("  nodes sharing a location : $nodesInCollisions")
        worstExamples.forEach { println("    $it") }

        assertTrue(totalNodes > 1000, "walked only $totalNodes nodes — the walk is not reaching the sources")
        assertEquals(
            0, collidingLocs,
            "a source location is now used by more than one expression node, so every side table " +
                "keyed by Loc can silently lose an entry. Either give the new node a distinct " +
                "location, or finish task 0.1 and key those tables by NodeId.",
        )
    }

}
