package com.mezze.language.typechecker.solving


import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.ast.solving.occursCheck
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Three walkers answer questions about the same [MezzeType.FunctionType], and they
 * must agree about WHERE a type variable can hide:
 *
 *  - [MezzeType.anyChild] — the structural reference, four positions on an arrow:
 *    `paramType`, `returnType`, the ability constraints' `typeArgs` AND
 *    `associatedTypeBindings`, and the arrow's own `effects` row.
 *  - [occursCheck] — the soundness gate for binding a variable.
 *  - [collectFreeTypeVars] — what generalization quantifies.
 *
 * The third saw only two and a half: it skipped `effects` entirely and skipped the
 * constraints' `associatedTypeBindings`. Those are precisely the positions a
 * `having` variable lives in (`<Exception having 'error = 'e>`,
 * `with NumAdd Int 'a having 'out = 'r`). A variable that generalization does not
 * quantify is not freshened by `TypeScheme.instantiate`, so every call site of that
 * binding shares ONE variable — the first caller to pin it pins it for the rest.
 *
 * The `effects` half is now closed. Effects ride on ARROWS only, so the
 * arrow-embedded row is the ordinary case; the standalone `EffectRow` that #264
 * fixed is the rare one. The `associatedTypeBindings` half is still OPEN and
 * deliberately so — see the @Disabled test below, which states the property and
 * records the measurement that stopped it landing.
 */
class FreeTypeVarAgreementTest {

    private fun varType(v: TypeVar) = MezzeType.VarType(v)

    private fun arrowRaising(errorType: MezzeType) = MezzeType.FunctionType(
        paramType = MezzeType.RecordType(mapOf("x" to MezzeType.IntType)),
        returnType = MezzeType.IntType,
        effects = MezzeType.EffectRow(
            effects = setOf("Exception"),
            effectInstances = setOf(
                MezzeType.EffectInstance("Exception", mapOf("'error" to errorType))
            )
        )
    )

    private fun arrowWithHaving(outType: MezzeType) = MezzeType.FunctionType(
        paramType = MezzeType.RecordType(mapOf("x" to MezzeType.IntType)),
        returnType = MezzeType.IntType,
        abilityConstraints = listOf(
            MezzeType.FunctionAbilityConstraint(
                abilityName = "NumAdd",
                typeArgs = listOf(MezzeType.IntType),
                associatedTypeBindings = mapOf("'out" to outType)
            )
        )
    )

    // ---- the gap ----

    @Test
    fun `a var living only in the arrow's effect row is free`() {
        val v = TypeVar.fresh()
        val arrow = arrowRaising(varType(v))
        assertTrue(
            arrow.anyChild { it is MezzeType.VarType && it.variable == v },
            "precondition: anyChild must see the effect row's associated type"
        )
        assertTrue(
            occursCheck(v, arrow),
            "precondition: occursCheck must see it too"
        )
        assertTrue(
            v in collectFreeTypeVars(arrow),
            "generalization must quantify a var that lives only in `<Exception having 'error = 'e>` — " +
                "otherwise every call site of the binding shares one variable"
        )
    }

    /**
     * The gap that is still OPEN, stated as the property it will satisfy.
     *
     * `collectFreeTypeVars` does not quantify a var that lives only in a
     * constraint's `having 'out = 'r`, so every call site of such a binding shares
     * one variable. Quantifying it today makes things WORSE, not better:
     * `TypeScheme.instantiate` freshens the binding into the obligation while
     * `applySubstitutionToType` clears the constraints off the instantiated type,
     * so the fresh `'out` reaches the call site with nothing left to re-pin it.
     *
     * MEASURED, not assumed: adding these vars turns
     * `LazyEffectsTest.test suspended value in recursive function` red — a solved
     * `Result` degrades to a bare var. Re-enable together with call-site
     * re-resolution of a quantified `having` binding.
     */
    @Test
    @Disabled("OPEN: quantifying `having 'out` vars needs call-site re-resolution first — see the KDoc")
    fun `a var living only in a constraint's associated-type binding is free`() {
        val v = TypeVar.fresh()
        val arrow = arrowWithHaving(varType(v))
        assertTrue(arrow.anyChild { it is MezzeType.VarType && it.variable == v }, "precondition: anyChild")
        assertTrue(occursCheck(v, arrow), "precondition: occursCheck")
        assertTrue(
            v in collectFreeTypeVars(arrow),
            "generalization must quantify a var that lives only in `having 'out = 'r`"
        )
    }

    /**
     * The half of the above that IS true today and must not silently regress:
     * the two walkers that DO see the binding still see it.
     */
    @Test
    fun `occursCheck and anyChild both see a constraint's associated-type binding`() {
        val v = TypeVar.fresh()
        val arrow = arrowWithHaving(varType(v))
        assertTrue(arrow.anyChild { it is MezzeType.VarType && it.variable == v })
        assertTrue(occursCheck(v, arrow))
    }

    @Test
    fun `a var nested inside an effect row's associated type is free`() {
        val v = TypeVar.fresh()
        // <Exception having 'error = [ 'e ]> — the var is one level down.
        val arrow = arrowRaising(MezzeType.TypeApplication("Seq", listOf(varType(v))))
        assertTrue(
            v in collectFreeTypeVars(arrow),
            "the effect row's associated types must be walked, not just scanned for bare vars"
        )
    }

    // ---- agreement, stated as the property ----

    @Test
    fun `whatever occursCheck finds, generalization quantifies`() {
        val v = TypeVar.fresh()
        val other = TypeVar.fresh()
        val shapes = listOf(
            "effect row" to arrowRaising(varType(v)),
            // "having binding" is the one shape that does NOT hold yet — see the
            // @Disabled test above for why quantifying it regresses a real program.
            "return" to MezzeType.FunctionType(MezzeType.IntType, varType(v)),
            "param" to MezzeType.FunctionType(varType(v), MezzeType.IntType),
            "constraint type arg" to MezzeType.FunctionType(
                MezzeType.IntType, MezzeType.IntType,
                abilityConstraints = listOf(MezzeType.FunctionAbilityConstraint("Eq", typeArgs = listOf(varType(v))))
            ),
            "nested in a record field" to MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("f" to varType(v))), MezzeType.IntType
            )
        )
        for ((label, type) in shapes) {
            assertTrue(occursCheck(v, type), "$label: occursCheck should find the var")
            assertTrue(
                v in collectFreeTypeVars(type),
                "$label: occursCheck finds the var but generalization would not quantify it"
            )
            // And neither walker invents a variable that is not there.
            assertFalse(occursCheck(other, type), "$label: occursCheck found an absent var")
            assertFalse(other in collectFreeTypeVars(type), "$label: collectFreeTypeVars found an absent var")
        }
    }

    // ---- the anti-hang property these walkers must keep ----

    @Test
    fun `a constraint binding that points back at its own arrow does not hang`() {
        // `associatedTypeBindings` can hold a value that transitively contains the
        // arrow itself (recursive effect types — Promise, Semaphore, TVar).
        // occursCheck checks these positions SHALLOWLY for exactly this reason;
        // collectFreeTypeVars must not reintroduce the loop.
        val v = TypeVar.fresh()
        val placeholder = MezzeType.TypeApplication("Promise", listOf(varType(v)))
        val selfReferential = MezzeType.FunctionType(
            paramType = MezzeType.IntType,
            returnType = placeholder,
            abilityConstraints = listOf(
                MezzeType.FunctionAbilityConstraint("Awaitable", associatedTypeBindings = mapOf("'out" to placeholder))
            )
        )
        // Must terminate, and must still see the var (via returnType).
        assertTrue(v in collectFreeTypeVars(selfReferential))
    }
}
