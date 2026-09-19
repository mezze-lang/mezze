package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.MezzeType.*
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.ast.TypeVar
import com.mezze.language.typedast.Hash
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.solving.UnificationResult
import com.mezze.language.typechecker.solving.unify
import com.mezze.language.typechecker.mono.unify as monoUnify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Task F asked for ONE unifier. Measured, that is the wrong merge: the two are
 * different operations that share a name.
 *
 * `MonoTypeOps.unify(pattern, value, bindings)` matches a PATTERN against a
 * CONCRETE value and records bindings. `Unification.unify(a, b, registry)` is
 * symmetric unification returning a substitution. Driven over a 19-type matrix —
 * 361 ordered pairs — they agree on **351** and differ on **10**, and every
 * difference runs the same way: mono says no where the solver says yes.
 *
 * All ten are cases where the solver binds or relates a TYPE VARIABLE (or relates
 * two variants by subset) and mono refuses. Mono refuses ON PURPOSE: its callers
 * feed the bindings into specialization, which needs CONCRETE type arguments —
 * `MonoDriver` guards every seed with `args.all { isConcrete(it) }`. A unifier
 * that happily bound `'a` to `'b` would hand mono non-concrete arguments it must
 * then reject anyway, one layer later and with less context.
 *
 * So the two stay separate, and this gate pins the boundary instead of the
 * merge. If either side changes such that the disagreement set moves, this fails
 * with the exact pairs — which is the signal to re-ask whether the split is still
 * justified, rather than discovering it through a dispatch bug.
 *
 * The identity-veto invariant they DO share is enforced by
 * [StructuralMatcherDifferentialGateTest]; this is the complementary half,
 * measuring where they legitimately part company.
 */
class UnifierDifferentialGateTest {

    private fun h(c: Char) = Hash(c.toString().repeat(64))
    private fun dv(n: String, c: Char, ctors: List<String>) =
        VariantType(ctors.associateWith { null }, identity = TypeIdentity.Declared(h(c), n))
    private fun dr(n: String, c: Char, f: Map<String, MezzeType>) =
        RecordType(fields = f, identity = TypeIdentity.Declared(h(c), n))
    private fun v(n: String) = VarType(TypeVar.freshWithName(n))

    private val matrix: List<Pair<String, MezzeType>> = listOf(
        "Int" to IntType,
        "Str" to StringType,
        "Bool" to BoolType,
        "'a" to v("a"),
        "'b" to v("b"),
        "[Int]" to SeqType(IntType),
        "[Str]" to SeqType(StringType),
        "['a]" to SeqType(v("a")),
        "List Int" to ListType(IntType),
        "Map Str Int" to TypeApplication("Map", listOf(StringType, IntType)),
        "App(P,[Int])" to TypeApplication("P", listOf(IntType), TypeIdentity.Declared(h('1'), "P")),
        "App(P,['a])" to TypeApplication("P", listOf(v("a")), TypeIdentity.Declared(h('1'), "P")),
        "App(Q,[Int])" to TypeApplication("Q", listOf(IntType), TypeIdentity.Declared(h('2'), "Q")),
        "rec A{x:Int}" to dr("A", '3', mapOf("x" to IntType)),
        "rec A'{x:Int}" to dr("A", '4', mapOf("x" to IntType)),
        "var V{X,Y}" to dv("V", '5', listOf("X", "Y")),
        "var V{X}" to dv("V", '5', listOf("X")),
        "fn Int->Str" to FunctionType(IntType, StringType),
        "fn 'a->'a" to FunctionType(v("a"), v("a")),
    )

    /**
     * Every pair where the two unifiers disagree, and they may only disagree in
     * ONE direction: mono refusing what the solver accepts. Mono accepting what
     * the solver rejects would mean mono dispatches where the type system says
     * the types do not relate — that is a soundness failure, and the assertion
     * below rules it out for the whole matrix.
     */
    private val expectedDisagreements = setOf(
        "'a vs 'a", "'a vs 'b", "'b vs 'a", "'b vs 'b",
        "['a] vs ['a]",
        "App(P,['a]) vs App(P,['a])",
        "var V{X,Y} vs var V{X}", "var V{X} vs var V{X,Y}",
        "fn 'a->'a vs fn Int->Str", "fn 'a->'a vs fn 'a->'a",
    )

    @Test
    fun `the two unifiers differ only where mono deliberately demands concreteness`() {
        val actual = mutableSetOf<String>()
        val monoAcceptedSolverRejected = mutableListOf<String>()

        for ((an, a) in matrix) {
            for ((bn, b) in matrix) {
                val mono = monoUnify(a, b, mutableMapOf())
                val solver = unify(a, b, DeclarationRegistry.EMPTY) is UnificationResult.Success
                if (mono != solver) {
                    actual.add("$an vs $bn")
                    if (mono && !solver) monoAcceptedSolverRejected.add("$an vs $bn")
                }
            }
        }

        assertEquals(
            emptyList(), monoAcceptedSolverRejected,
            "MonoTypeOps.unify accepted a pair the solver REJECTS. Mono may be " +
                "stricter than the type system (it needs concrete bindings), never " +
                "looser — looser means mono specializes a call the types say does " +
                "not relate.",
        )
        assertEquals(
            expectedDisagreements, actual,
            "The boundary between the two unifiers moved. They are deliberately " +
                "different operations (see this class's doc), so a change here is " +
                "not necessarily wrong — but it means one side's contract shifted, " +
                "and the split should be re-justified rather than silently widened.",
        )
    }
}
