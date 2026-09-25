package com.mezze.language.typechecker.solving


import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.ast.AbilityConstraint
import com.mezze.language.ast.solving.occursCheck
import com.mezze.language.typechecker.context.TypeScheme
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
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
 * a constraint's `associatedTypeBindings`). Those are where an effect's or an
 * ability's associated type lives — `'error` on a raising arrow, `'out` on a
 * `NumAdd` obligation. Neither has a surface spelling in a `with` clause:
 * `having` appears only on an `ability`, `impl` or `effect` declaration, and
 * these are the solver's own tables.
 *
 * BOTH halves are now closed, by different means, and the difference is the
 * point:
 *
 *  - `effects`: generalization QUANTIFIES the variable (#264 closed the
 *    standalone row; the arrow-embedded one, the ordinary case, followed).
 *  - `associatedTypeBindings`: generalization deliberately does NOT quantify it.
 *    `TypeScheme.instantiate` freshens it per instantiation instead, which is
 *    what stops a declaration and its call sites sharing one variable. Widening
 *    this walk to quantify was tried twice and is the wrong lever — it changes
 *    the scheme's shape, which mono and evidence selection read, and produced a
 *    wrong answer. See the two tests below.
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
     * A `having` binding's variable is not SHARED between a declaration and its
     * call sites. FIXED — but not where this test used to demand.
     *
     * The property is that two call sites cannot collide on one variable. The
     * obvious implementation is to quantify it in [collectFreeTypeVars], and this
     * test asserted exactly that for a long time. It is the WRONG lever, measured:
     * quantifying changes the scheme's shape, which mono and evidence selection
     * read, and it produced a wrong answer (`sum_to {n=5,acc=0}` then `3 + 4`
     * returned 15).
     *
     * `TypeScheme.instantiate` freshens the binding per instantiation instead, so
     * `collectFreeTypeVars` still does not collect it — correctly, since nothing
     * needs quantifying for the property to hold. What matters is asserted below
     * and, as a program, in the last test of this file.
     */
    @Test
    fun `a having binding is freshened per instantiation, not shared`() {
        val shared = TypeVar.fresh()
        val scheme = TypeScheme(
            quantifiedVars = listOf(TypeVar.fresh()),
            constraints = listOf(
                AbilityConstraint(
                    abilityName = "NumAdd",
                    typeParam = "'l",
                    associatedTypeBindings = mapOf("'out" to varType(shared)),
                ),
            ),
            type = MezzeType.FunctionType(MezzeType.IntType, MezzeType.IntType),
        )

        val first = scheme.instantiate().freshConstraints.single().associatedTypeBindings["'out"]
        val second = scheme.instantiate().freshConstraints.single().associatedTypeBindings["'out"]

        assertTrue(first is MezzeType.VarType && second is MezzeType.VarType)
        assertTrue(
            (first as MezzeType.VarType).variable != (second as MezzeType.VarType).variable,
            "two instantiations handed back the SAME `having` variable ($first), so the first " +
                "call site to discharge the obligation pins it for every other one",
        )
        assertTrue(
            first.variable != shared && second.variable != shared,
            "an instantiation handed back the DECLARATION's own variable, which is the same " +
                "sharing seen from the other side",
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
            // "having binding" is deliberately ABSENT from this list: it is the one
            // shape generalization does not quantify, and must not — the sharing it
            // would otherwise cause is stopped at instantiation instead. See
            // `a having binding is freshened per instantiation, not shared`.
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

    /**
     * The same gap as a PROGRAM, because the walker-level property above does not
     * say what a user loses.
     *
     * Both call sites are ordinary uses of an ordinary polymorphic function. The
     * first pins the shared `having 'out` and the second is refused. Reverse them
     * and the refusal reverses too.
     */
    @Test
    fun `two call sites of a fn whose out is not observable both typecheck`() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        val src = """
            let ignore = { x } -> 0
            let f = { a, b } -> ignore { x = a + b }
            let i = f { a = 1, b = 2 }
            let g = f { a = 1.5, b = 2.5 }
            i
        """.trimIndent()
        // Compiling at all is the assertion: today this throws
        // "Type mismatch for associated type 'out': expected Float but got Int".
        TypeChecker().typeCheck(
            com.mezze.language.parser.MezzeParser().parseParsedModule(src, "having.mz"),
            java.nio.file.Paths.get("."),
        )
    }
}
