package com.mezze.language.typechecker

import com.mezze.language.ast.PrimitiveTypes
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
    /** An APPLIED variant whose payload does NOT mention the arg — a phantom
     *  parameter, where `typeArgs` is the only thing carrying the difference
     *  (BUG 33). */
    private fun dvApplied(n: String, c: Char, args: List<MezzeType>) =
        VariantType(
            constructors = mapOf("Tg" to RecordType(fields = mapOf("n" to IntType))),
            typeArgs = args,
            identity = TypeIdentity.Declared(h(c), n),
        )
    private fun dr(n: String, c: Char, f: Map<String, MezzeType>) =
        RecordType(fields = f, identity = TypeIdentity.Declared(h(c), n))
    private fun v(n: String) = VarType(TypeVar.freshWithName(n))

    private val matrix: List<Pair<String, MezzeType>> = listOf(
        "Int" to IntType,
        "Str" to StringType,
        "Bool" to PrimitiveTypes.BOOL_TYPE,
        "'a" to v("a"),
        "'b" to v("b"),
        "[Int]" to TypeApplication("Seq", listOf(IntType)),
        "[Str]" to TypeApplication("Seq", listOf(StringType)),
        "['a]" to TypeApplication("Seq", listOf(v("a"))),
        "List Int" to TypeApplication("List", listOf(IntType)),
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
        // BUG 33: an applied variant whose parameter is PHANTOM — no payload
        // mentions it, so `typeArgs` is the only carrier of the difference. The
        // matrix had no applied variant at all, which is why the solver gaining
        // a typeArgs check registered here as nothing.
        "var T[Int]" to dvApplied("T", '6', listOf(IntType)),
        "var T[Str]" to dvApplied("T", '6', listOf(StringType)),
        "var T['a]" to dvApplied("T", '6', listOf(v("a"))),
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

        // Added when B.1 deleted MezzeType.ListType and `List Int` became an
        // ordinary TypeApplication. RE-JUSTIFIED rather than absorbed:
        //
        // `List` genuinely IS a variant — `| Cons { head, tail }, Nil |`, which
        // BuiltinTypes spells out — so the SOLVER can now relate `List Int` to an
        // open variant variable, which it could not do through an opaque
        // dedicated node. Mono still refuses, for the reason it refuses
        // everything else on this list: it needs concrete bindings to seed a
        // specialization, and a variant row is not one.
        //
        // The direction is what makes this safe, and it is asserted separately
        // above: solver-accepts / mono-refuses. Four new pairs, all that way.
        "List Int vs var V{X,Y}", "var V{X,Y} vs List Int",
        "List Int vs var V{X}", "var V{X} vs List Int",

        // Added when #14 deleted MezzeType.BoolType and Bool became the builtin
        // variant `| True, False |`. This is the SAME move as List's four above,
        // for the same reason and with the same justification:
        //
        // Bool genuinely IS a variant — that is the whole point of the collapse,
        // and it is why `x = True` infers Bool and a match over it is checked
        // against two arms — so the SOLVER can relate it to an open variant
        // variable, which it could not do through the opaque `BoolType` node.
        // Mono still refuses, because a variant row is not a concrete binding it
        // can seed a specialization with.
        //
        // The direction is what makes this safe, and the assertion above rules
        // out the other one for the whole matrix: solver-accepts / mono-refuses.
        // Four new pairs, all that way. Bool's RUNTIME representation is
        // untouched by any of this — it is a primitive boolean either way.
        "Bool vs var V{X,Y}", "var V{X,Y} vs Bool",
        "Bool vs var V{X}", "var V{X} vs Bool",

        // ── Added with the three APPLIED phantom-param variants (BUG 33) ──
        //
        // The matrix had no applied variant, so the solver gaining a typeArgs
        // check registered here as nothing. Adding `var T[Int]`, `var T[Str]`
        // and `var T['a]` exposed it: before the mono fix the FIRST assertion
        // failed with `[var T[Int] vs var T[Str], var T[Str] vs var T[Int]]` —
        // mono accepting a pair the solver rejects, the one direction this gate
        // calls a soundness failure. Mono now compares typeArgs too, so that
        // pair AGREES (both refuse) and is absent from this set.
        //
        // These eleven are the two families already justified above, now with a
        // third declared variant in the matrix. Nothing new in kind:
        //
        // (a) Bool-and-List-are-variants, nine pairs. Exactly the eight
        //     `Bool vs var V{…}` / `List Int vs var V{…}` entries above: the
        //     SOLVER relates a builtin-variant to another declared variant,
        //     mono refuses because a variant row is not a concrete binding it
        //     can seed with. `List Int` pairs with `T[Int]` but NOT with
        //     `T[Str]` — where the args disagree both sides refuse, which is
        //     the typeArgs check working through this family too.
        "Bool vs var T[Int]", "var T[Int] vs Bool",
        "Bool vs var T[Str]", "var T[Str] vs Bool",
        "Bool vs var T['a]", "var T['a] vs Bool",
        "List Int vs var T[Int]", "var T[Int] vs List Int",
        "List Int vs var T['a]", "var T['a] vs List Int",

        // (b) mono-will-not-bind-a-variable, one pair. The same stance as
        //     `['a] vs ['a]` and `App(P,['a]) vs App(P,['a])` directly above,
        //     and it is PATCH-CAUSED: mono now unifies a variant's typeArgs, so
        //     `T['a]` against `T['a]` asks it to relate `'a` to `'a`, which it
        //     refuses on purpose — specialization needs concrete arguments.
        //     Before the patch mono ignored the args and vacuously accepted.
        //     That this moved INTO the mono-refuses column is the fix working:
        //     stricter than the type system is the allowed direction.
        "var T['a] vs var T['a]",
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
