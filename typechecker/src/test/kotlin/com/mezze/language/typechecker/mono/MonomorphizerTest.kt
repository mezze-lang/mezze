package com.mezze.language.typechecker.mono

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.parsedast.Loc
import com.mezze.language.typedast.AbilityImplRef
import com.mezze.language.typedast.BindingRef
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypeInstantiation
import com.mezze.language.typedast.TypeParam
import com.mezze.language.typedast.TypeKind
import com.mezze.language.typedast.TypeScheme
import com.mezze.language.typedast.TypedAbilityConstraint
import com.mezze.language.typedast.TypedCall
import com.mezze.language.typedast.TypedIntLit
import com.mezze.language.typedast.TypedLambda
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedParam
import com.mezze.language.typedast.TypedVar
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MonomorphizerTest {

    private val loc = Loc("test.mz", 1, 1, 1, 1)
    private val a = TypeVar("'a", 0, 1)
    private val b = TypeVar("'b", 0, 2)
    private val varA = MezzeType.VarType(a)
    private val varB = MezzeType.VarType(b)
    private val intT = MezzeType.IntType
    private val strT = MezzeType.StringType

    /** Identity 'a → 'a generic decl: `let id = {x: 'a} -> x` */
    private fun makeIdentityDecl(hash: Hash, typeParamName: String = "'a"): TypedLetDecl {
        val tv = TypeVar(typeParamName, 0, 1)
        val varT = MezzeType.VarType(tv)
        val body = TypedLambda(
            params = listOf(TypedParam("x", varT, loc)),
            body = TypedVar("x", BindingRef.Param(0), varT, loc),
            captures = emptyList(),
            type = MezzeType.FunctionType(varT, varT),
            loc = loc
        )
        return TypedLetDecl(
            hash = hash,
            name = "id",
            typeParams = listOf(TypeParam(typeParamName, TypeKind.Star)),
            value = body,
            scheme = TypeScheme(
                typeParams = listOf(TypeParam(typeParamName, TypeKind.Star)),
                abilityConstraints = emptyList(),
                type = MezzeType.FunctionType(varT, varT)
            ),
            isPublic = true,
            loc = loc
        )
    }

    // ---------- specializedHash ----------

    @Test
    fun `specializedHash deterministic across runs`() {
        val gh = Hash("a".repeat(64))
        val h1 = Monomorphizer.specializedHash(gh, listOf(intT), emptyList())
        val h2 = Monomorphizer.specializedHash(gh, listOf(intT), emptyList())
        assertEquals(h1, h2)
    }

    @Test
    fun `specializedHash differs by generic hash`() {
        val gh1 = Hash("a".repeat(64))
        val gh2 = Hash("b".repeat(64))
        val h1 = Monomorphizer.specializedHash(gh1, listOf(intT), emptyList())
        val h2 = Monomorphizer.specializedHash(gh2, listOf(intT), emptyList())
        assertNotEquals(h1, h2)
    }

    @Test
    fun `specializedHash differs by type args`() {
        val gh = Hash("a".repeat(64))
        val h1 = Monomorphizer.specializedHash(gh, listOf(intT), emptyList())
        val h2 = Monomorphizer.specializedHash(gh, listOf(strT), emptyList())
        assertNotEquals(h1, h2)
    }

    @Test
    fun `specializedHash differs by impl bindings`() {
        val gh = Hash("a".repeat(64))
        val ah = Hash("c".repeat(64))
        val h1 = Monomorphizer.specializedHash(gh, listOf(intT), emptyList())
        val h2 = Monomorphizer.specializedHash(
            gh, listOf(intT),
            listOf(AbilityImplRef(ah, Hash("d".repeat(64)), intT))
        )
        assertNotEquals(h1, h2)
    }

    @Test
    fun `specializedHash differs from generic hash itself`() {
        val gh = Hash("a".repeat(64))
        val sh = Monomorphizer.specializedHash(gh, listOf(intT), emptyList())
        assertNotEquals(gh, sh)
    }

    // ---------- run ----------

    @Test
    fun `run specializes a single seed`() {
        val gh = Hash("a".repeat(64))
        val decl = makeIdentityDecl(gh)
        val mono = Monomorphizer(resolveDecl = { if (it == gh) decl else null })

        val req = Monomorphizer.SpecRequest(gh, listOf(intT), emptyList())
        val result = mono.run(listOf(req))

        assertEquals(1, result.specializations.size)
        val sh = result.callSiteRewrites[req]
        assertTrue(sh != null)
        val spec = result.specializations[sh]!!
        assertEquals(gh, spec.genericHash)
        assertEquals(listOf(intT), spec.typeArgs)
        // The specialized lambda should have its 'a → Int substituted.
        assertEquals(MezzeType.FunctionType(intT, intT), spec.specializedType)
    }

    @Test
    fun `run dedupes identical requests`() {
        val gh = Hash("a".repeat(64))
        val decl = makeIdentityDecl(gh)
        val mono = Monomorphizer(resolveDecl = { if (it == gh) decl else null })

        val req1 = Monomorphizer.SpecRequest(gh, listOf(intT), emptyList())
        val req2 = Monomorphizer.SpecRequest(gh, listOf(intT), emptyList())
        val result = mono.run(listOf(req1, req2))

        assertEquals(1, result.specializations.size)
        assertEquals(result.callSiteRewrites[req1], result.callSiteRewrites[req2])
    }

    @Test
    fun `run produces distinct specializations for distinct type args`() {
        val gh = Hash("a".repeat(64))
        val decl = makeIdentityDecl(gh)
        val mono = Monomorphizer(resolveDecl = { if (it == gh) decl else null })

        val rInt = Monomorphizer.SpecRequest(gh, listOf(intT), emptyList())
        val rStr = Monomorphizer.SpecRequest(gh, listOf(strT), emptyList())
        val result = mono.run(listOf(rInt, rStr))

        assertEquals(2, result.specializations.size)
        assertNotEquals(result.callSiteRewrites[rInt], result.callSiteRewrites[rStr])
    }

    @Test
    fun `run skips non-resolvable hashes silently`() {
        val gh = Hash("a".repeat(64))
        val mono = Monomorphizer(resolveDecl = { null })

        val req = Monomorphizer.SpecRequest(gh, listOf(intT), emptyList())
        val result = mono.run(listOf(req))

        assertEquals(0, result.specializations.size)
        assertNull(result.callSiteRewrites[req])
        assertTrue(result.fallbacks.isEmpty()) // Not a fallback — just not specializable
    }

    @Test
    fun `run skips non-generic decls (no type params)`() {
        val gh = Hash("a".repeat(64))
        val monoDecl = TypedLetDecl(
            hash = gh,
            name = "x",
            typeParams = emptyList(),
            value = TypedIntLit(42, intT, loc),
            scheme = TypeScheme(emptyList(), emptyList(), intT),
            isPublic = true,
            loc = loc
        )
        val mono = Monomorphizer(resolveDecl = { if (it == gh) monoDecl else null })

        val req = Monomorphizer.SpecRequest(gh, emptyList(), emptyList())
        val result = mono.run(listOf(req))

        assertEquals(0, result.specializations.size)
    }

    @Test
    fun `run drops to fallback on arity mismatch`() {
        val gh = Hash("a".repeat(64))
        val decl = makeIdentityDecl(gh) // 1 type param
        val mono = Monomorphizer(resolveDecl = { if (it == gh) decl else null })

        val req = Monomorphizer.SpecRequest(gh, listOf(intT, strT), emptyList()) // 2 args
        val result = mono.run(listOf(req))

        assertEquals(0, result.specializations.size)
        assertEquals(listOf(req), result.fallbacks)
    }

    @Test
    fun `run enqueues transitive generic calls`() {
        // outer 'a → calls inner with 'a
        val innerHash = Hash("a".repeat(64))
        val outerHash = Hash("b".repeat(64))
        val innerDecl = makeIdentityDecl(innerHash)
        val tv = TypeVar("'a", 0, 1)
        val varT = MezzeType.VarType(tv)
        // outer body: { x: 'a } -> inner(x) with instantiation typeArgs=['a]
        val outerLambda = TypedLambda(
            params = listOf(TypedParam("x", varT, loc)),
            body = TypedCall(
                function = TypedVar("inner", BindingRef.ByHash(innerHash), MezzeType.FunctionType(varT, varT), loc),
                arg = TypedVar("x", BindingRef.Param(0), varT, loc),
                instantiation = TypeInstantiation(typeArgs = listOf(varT), abilityImpls = emptyList()),
                isTailCall = false,
                type = varT,
                loc = loc
            ),
            captures = emptyList(),
            type = MezzeType.FunctionType(varT, varT),
            loc = loc
        )
        val outerDecl = TypedLetDecl(
            hash = outerHash,
            name = "outer",
            typeParams = listOf(TypeParam("'a", TypeKind.Star)),
            value = outerLambda,
            scheme = TypeScheme(
                typeParams = listOf(TypeParam("'a", TypeKind.Star)),
                abilityConstraints = emptyList(),
                type = MezzeType.FunctionType(varT, varT)
            ),
            isPublic = true,
            loc = loc
        )
        val mono = Monomorphizer(resolveDecl = {
            when (it) { innerHash -> innerDecl; outerHash -> outerDecl; else -> null }
        })

        val req = Monomorphizer.SpecRequest(outerHash, listOf(intT), emptyList())
        val result = mono.run(listOf(req))

        // Two specializations: outer<Int> and inner<Int> (transitively
        // discovered after substitution rewrote the call instantiation).
        assertEquals(2, result.specializations.size)
        assertTrue(result.callSiteRewrites[req] != null)
    }

    @Test
    fun `run hits depth limit and drops to fallback`() {
        // A self-recursive decl that always re-instantiates itself with
        // its own type variable (artificial polymorphic recursion).
        val gh = Hash("a".repeat(64))
        val tv = TypeVar("'a", 0, 1)
        val varT = MezzeType.VarType(tv)
        val recDecl = TypedLetDecl(
            hash = gh,
            name = "rec",
            typeParams = listOf(TypeParam("'a", TypeKind.Star)),
            // body: { x: 'a } -> rec[List 'a](x)  — i.e. call self with a strictly larger type
            value = TypedLambda(
                params = listOf(TypedParam("x", varT, loc)),
                body = TypedCall(
                    function = TypedVar("rec", BindingRef.ByHash(gh), MezzeType.FunctionType(varT, varT), loc),
                    arg = TypedVar("x", BindingRef.Param(0), varT, loc),
                    instantiation = TypeInstantiation(
                        typeArgs = listOf(MezzeType.ListType(varT)),
                        abilityImpls = emptyList()
                    ),
                    isTailCall = false,
                    type = varT,
                    loc = loc
                ),
                captures = emptyList(),
                type = MezzeType.FunctionType(varT, varT),
                loc = loc
            ),
            scheme = TypeScheme(
                typeParams = listOf(TypeParam("'a", TypeKind.Star)),
                abilityConstraints = emptyList(),
                type = MezzeType.FunctionType(varT, varT)
            ),
            isPublic = true,
            loc = loc
        )
        // depth limit 3 so the test runs fast.
        val mono = Monomorphizer(resolveDecl = { if (it == gh) recDecl else null }, maxDepth = 3)

        val req = Monomorphizer.SpecRequest(gh, listOf(intT), emptyList())
        val result = mono.run(listOf(req))

        // Should specialize for Int, List Int, List(List Int), List(List(List Int))
        // (depths 0..3, 4 specializations) and fallback at depth 4.
        assertTrue(result.specializations.size <= 4)
        assertTrue(result.fallbacks.isNotEmpty())
    }
}
