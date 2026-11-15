package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.Loc
import com.mezze.language.typechecker.mono.Monomorphizer
import com.mezze.language.typedast.BindingRef
import com.mezze.language.typedast.TypedCapture
import com.mezze.language.typedast.TypedIntLit
import com.mezze.language.typedast.TypedLambda
import com.mezze.language.typedast.TypedParam
import com.mezze.language.typedast.TypedVar
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which closure parameters a specialization may strip, decided by BINDING
 * IDENTITY rather than by matching a name.
 *
 * `Monomorphizer` Piece 5 strips a specialization's dead closure parameters and
 * `referencedClosureParams` decides which are dead. Two things were wrong with
 * it, and only one of them showed up as a crash.
 *
 * **It could not see a nested lambda's capture list.** A lambda records what it
 * closes over in `captures: List<TypedCapture>` — a structural field, not an
 * expression child — and after Piece 3 splices, that list is the ONLY remaining
 * record: nothing in the body mentions the parameter any more. So the parameter
 * was counted dead, removed from the outer parameter list, and lowering hit
 *
 *     lambda captures 'f', which is neither a local, a parameter nor a capture
 *     of the enclosing function. Enclosing params=[xs]
 *
 * reproduced on `bench/cases/defunc` from a clean cache. A/B: remove the
 * capture-list read and the crash returns unchanged; restore it and the build
 * succeeds.
 *
 * **And it compared NAMES.** `TypedCapture` carries `source: BindingRef`, so a
 * capture already says which binding it came from. Matching names instead is the
 * hazard the third test pins: an inner lambda with its own parameter of the same
 * name made the outer one look live, which silently disables the strip.
 *
 * These build the AST by hand, because the shape that matters is one the
 * typechecker never emits — a source program that calls the parameter leaves a
 * `TypedVar` behind, and the splice is precisely what removes that while leaving
 * the capture. A version written from source passed with AND without the fix,
 * verified, so it pinned nothing. Nothing here depends on a particular NAME:
 * each case reads the name back off the params it constructed.
 *
 * WHAT THIS DOES NOT CLAIM. `MEZZE_MONO_STRIP=1` is still off by default and
 * still wrong on a second, independent count: from a clean cache,
 * `bench/cases/defunc` kernel 6 (`seq::fold` over a range) answers
 * `sum=<function:>` instead of 400000040000000 — the fold returns its folder.
 * That is a call-site arity problem across `stripMap`, `stripCallArgFields` and
 * `stripBySiteLoc`, not this scan. These tests pin the scan.
 */
class ClosureParamLivenessTest {

    private val loc = Loc("p.mz", 0, 0, 0, 0)
    private val int = MezzeType.IntType
    private val fnType = MezzeType.FunctionType(MezzeType.RecordType(emptyMap()), int)

    /** An outer lambda of two params, with [inner] as its body. The candidate is
     *  always its SECOND parameter, read back off the AST rather than written as
     *  a literal anywhere below. */
    private fun outer(inner: TypedExprLambdaAlias): TypedLambda = TypedLambda(
        params = listOf(TypedParam("xs", int, loc), TypedParam("cb", fnType, loc)),
        body = inner,
        captures = emptyList(),
        type = fnType,
        loc = loc,
    )

    private fun live(o: TypedLambda) =
        Monomorphizer.referencedClosureParams(o, setOf(o.params[1].name))

    @Test
    fun `a parameter held only by a nested lambda's capture list is live`() {
        // The post-splice shape: the inner lambda closes over the outer param and
        // nothing MENTIONS it. `source = Param(1)` is the outer param's identity.
        val o = outer(TypedLambda(
            params = emptyList(),
            body = TypedIntLit(1L, int, loc),
            captures = listOf(TypedCapture("cb", fnType, BindingRef.Param(1))),
            type = fnType,
            loc = loc,
        ))
        val name = o.params[1].name
        assertTrue(name in live(o),
            "`$name` was reported DEAD. Nothing in the body mentions it — it is " +
                "held only by the inner lambda's `captures` list, a structural " +
                "field no walk over expressions reaches, and exactly what " +
                "TypedASTCompiler consumes when it places the inner lambda's " +
                "captures. live=${live(o)}")
    }

    @Test
    fun `a parameter referenced directly is live`() {
        // The case the original scan did handle; kept so a regression in the simple
        // path cannot hide behind the new one passing.
        val direct = TypedLambda(
            params = listOf(TypedParam("xs", int, loc), TypedParam("cb", fnType, loc)),
            body = TypedVar("cb", BindingRef.Param(1), fnType, loc),
            captures = emptyList(),
            type = fnType,
            loc = loc,
        )
        val name = direct.params[1].name
        assertTrue(name in Monomorphizer.referencedClosureParams(direct, setOf(name)),
            "a directly-referenced parameter was reported dead")
    }

    @Test
    fun `an inner parameter of the SAME NAME does not keep the outer one alive`() {
        // THE SHADOWING CASE, and the reason this is keyed on identity. The inner
        // lambda declares its own parameter with the outer one's name and refers to
        // THAT — `Param(0)` of the inner frame. A scan comparing names sees the
        // name and reports the outer parameter live, so the strip never fires and
        // every specialization keeps a dead parameter forever.
        val o = outer(TypedLambda(
            params = listOf(TypedParam("cb", fnType, loc)),
            body = TypedVar("cb", BindingRef.Param(0), fnType, loc),
            captures = emptyList(),
            type = fnType,
            loc = loc,
        ))
        val name = o.params[1].name
        assertTrue(name !in live(o),
            "`$name` was reported live, but the only reference to that NAME is the " +
                "inner lambda's own parameter — a different binding. The outer one " +
                "is closed over by nothing and must be strippable. live=${live(o)}")
    }
}

/** Alias so [ClosureParamLivenessTest.outer] reads as taking a lambda. */
private typealias TypedExprLambdaAlias = TypedLambda
