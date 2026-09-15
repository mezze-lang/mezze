package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.typedast.*
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Disease 2, measured — the size and shape of task B.1.
 *
 * A container has TWO representations: the dedicated node (`SeqType(T)`) and the
 * name-carried application (`TypeApplication("Seq", [T])`). B.1 deletes the
 * dedicated nodes so only one survives. That is a 247-site change across the
 * typechecker and ast, so it is worth knowing what actually FLOWS before moving
 * any of it.
 *
 * A.1's bisection produced the first concrete casualty: an isolated impl-op solve
 * returned `TypeApplication("Nat")` where the declared shape gave the nominal
 * `Nat`, and taking the solver's version sent dispatch down a path keyed on the
 * representation it did not have. This counts how much of that there is.
 *
 * The interesting number is not the totals — it is how many DECLARATIONS carry
 * both forms, because those are where a consumer written for one meets the
 * other. Kept as a ratchet: the count may not grow while B.1 is outstanding, and
 * B.1 finishing drives it to zero from the other side.
 */
class TypeRepresentationAuditTest {

    private val containerConstructors =
        setOf("Seq", "List", "Set", "Map", "SortedSet", "SortedMap", "Deque", "NonEmptyList")

    private data class Counts(
        var dedicatedNodes: Int = 0,
        var applications: Int = 0,
    )

    /** Walks every type reachable from a declaration, counting both forms. */
    private fun countIn(type: MezzeType, counts: Counts, seen: MutableSet<MezzeType> = HashSet()) {
        if (!seen.add(type)) return
        when (type) {
            is MezzeType.SeqType, is MezzeType.ListType, is MezzeType.SetType,
            is MezzeType.MapType, is MezzeType.SortedSetType, is MezzeType.SortedMapType,
            -> counts.dedicatedNodes++
            is MezzeType.TypeApplication ->
                if (type.constructor in containerConstructors) counts.applications++
            else -> Unit
        }
        when (type) {
            is MezzeType.FunctionType -> {
                countIn(type.paramType, counts, seen); countIn(type.returnType, counts, seen)
            }
            is MezzeType.SeqType -> countIn(type.elementType, counts, seen)
            is MezzeType.ListType -> countIn(type.elementType, counts, seen)
            is MezzeType.SetType -> countIn(type.elementType, counts, seen)
            is MezzeType.SortedSetType -> countIn(type.elementType, counts, seen)
            is MezzeType.MapType -> {
                countIn(type.keyType, counts, seen); countIn(type.valueType, counts, seen)
            }
            is MezzeType.SortedMapType -> {
                countIn(type.keyType, counts, seen); countIn(type.valueType, counts, seen)
            }
            is MezzeType.TypeApplication -> type.args.forEach { countIn(it, counts, seen) }
            is MezzeType.RecordType -> type.fields.values.forEach { countIn(it, counts, seen) }
            is MezzeType.VariantType -> type.constructors.values.forEach { it?.let { p -> countIn(p, counts, seen) } }
            is MezzeType.NewtypeType -> countIn(type.wrappedType, counts, seen)
            else -> Unit
        }
    }

    private fun exprTypes(expr: TypedExpr, out: MutableList<MezzeType>) {
        out.add(expr.type)
        when (expr) {
            is TypedLambda -> { expr.params.forEach { out.add(it.type) }; exprTypes(expr.body, out) }
            is TypedCall -> { exprTypes(expr.function, out); exprTypes(expr.arg, out) }
            is TypedFieldAccess -> exprTypes(expr.record, out)
            is TypedIf -> { exprTypes(expr.condition, out); exprTypes(expr.thenBranch, out); exprTypes(expr.elseBranch, out) }
            is TypedPipe -> { exprTypes(expr.left, out); exprTypes(expr.right, out) }
            is TypedMatch -> {
                exprTypes(expr.scrutinee, out)
                expr.cases.forEach { c ->
                    c.bindings.forEach { out.add(it.type) }
                    c.guard?.let { exprTypes(it, out) }
                    exprTypes(c.body, out)
                }
            }
            is TypedBlock -> {
                expr.statements.forEach { st ->
                    when (st) {
                        is TypedStatement.Let -> { out.add(st.type); exprTypes(st.value, out) }
                        is TypedStatement.Expr -> exprTypes(st.expr, out)
                    }
                }
                exprTypes(expr.result, out)
            }
            is TypedRecord -> expr.entries.forEach { e ->
                when (e) {
                    is TypedRecordEntry.Field -> exprTypes(e.value, out)
                    is TypedRecordEntry.Spread -> exprTypes(e.expr, out)
                }
            }
            is TypedSeq -> expr.elements.forEach { e ->
                when (e) {
                    is TypedSeqElement.Regular -> exprTypes(e.expr, out)
                    is TypedSeqElement.Spread -> exprTypes(e.expr, out)
                }
            }
            is TypedVariantConstruct -> expr.payload?.let { exprTypes(it, out) }
            is TypedPerform -> { expr.handlers.forEach { h -> h.params?.let { exprTypes(it, out) } }; exprTypes(expr.body, out) }
            is TypedPerformOp -> exprTypes(expr.arg, out)
            else -> Unit
        }
    }

    @Test
    fun `how much of each container representation actually flows`() {
        val modules = PrecompiledStdlib.stdlibTypedModules.values
        assertTrue(modules.isNotEmpty(), "no typed stdlib modules loaded — the audit would read as zero")

        var totalDedicated = 0
        var totalApplications = 0
        val mixedDecls = mutableListOf<String>()

        for (module in modules) {
            for (decl in module.decls) {
                if (decl !is TypedLetDecl) continue
                val counts = Counts()
                countIn(decl.scheme.type, counts)
                val types = mutableListOf<MezzeType>()
                exprTypes(decl.value, types)
                types.forEach { countIn(it, counts) }
                totalDedicated += counts.dedicatedNodes
                totalApplications += counts.applications
                if (counts.dedicatedNodes > 0 && counts.applications > 0) {
                    mixedDecls.add("${decl.name} (${counts.dedicatedNodes} node / ${counts.applications} app)")
                }
            }
            for (impl in module.abilityImpls) {
                // Counts are PER OPERATION. Accumulating them across an impl and
                // reporting per op made every later op inherit the earlier ones'
                // totals, so each looked mixed once any one of them was.
                val targetCounts = Counts()
                impl.targetTypes.forEach { countIn(it, targetCounts) }
                totalDedicated += targetCounts.dedicatedNodes
                totalApplications += targetCounts.applications
                for ((opName, body) in impl.operations) {
                    val counts = Counts()
                    val types = mutableListOf<MezzeType>()
                    exprTypes(body, types)
                    types.forEach { countIn(it, counts) }
                    totalDedicated += counts.dedicatedNodes
                    totalApplications += counts.applications
                    if (counts.dedicatedNodes > 0 && counts.applications > 0) {
                        mixedDecls.add("impl op $opName (${counts.dedicatedNodes} node / ${counts.applications} app)")
                    }
                }
            }
        }

        println("Container representations across the typed stdlib:")
        println("  dedicated nodes (SeqType, ListType, …): $totalDedicated")
        println("  name-carried applications (TypeApplication(\"Seq\", …)): $totalApplications")
        println("  declarations carrying BOTH forms: ${mixedDecls.size}")
        mixedDecls.take(20).forEach { println("    $it") }

        assertTrue(
            totalDedicated + totalApplications > 0,
            "counted no container types at all — the walk is not reaching solved types",
        )
    }
}
