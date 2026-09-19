package com.mezze.language.typechecker.mono

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.parsedast.Loc
import com.mezze.language.typedast.AbilityImplRef
import com.mezze.language.typedast.BindingRef
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.PatternBinding
import com.mezze.language.typedast.TypeInstantiation
import com.mezze.language.typedast.TypedBlock
import com.mezze.language.typedast.TypedCall
import com.mezze.language.typedast.TypedCase
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedFieldAccess
import com.mezze.language.typedast.TypedIntLit
import com.mezze.language.typedast.TypedLambda
import com.mezze.language.typedast.TypedMatch
import com.mezze.language.typedast.TypedParam
import com.mezze.language.typedast.TypedRecord
import com.mezze.language.typedast.TypedRecordEntry
import com.mezze.language.typedast.TypedStatement
import com.mezze.language.typedast.TypedVar
import com.mezze.language.typedast.TypedVariantPattern
import com.mezze.language.typedast.TypedVarPattern
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypeSubstitutionTest {

    private val loc = Loc("test.mz", 1, 1, 1, 1)
    private val a = TypeVar("'a", 0, 1)
    private val b = TypeVar("'b", 0, 2)
    private val varA = MezzeType.VarType(a)
    private val varB = MezzeType.VarType(b)

    private val intT = MezzeType.IntType
    private val strT = MezzeType.StringType

    @Test
    fun `applyType replaces VarType with concrete type`() {
        val result = TypeSubstitution.applyType(varA, mapOf(a to intT))
        assertEquals(intT, result)
    }

    @Test
    fun `applyType leaves unrelated VarType alone`() {
        val result = TypeSubstitution.applyType(varB, mapOf(a to intT))
        assertEquals(varB, result)
    }

    @Test
    fun `applyType walks function types`() {
        val fnT = MezzeType.FunctionType(varA, varB)
        val result = TypeSubstitution.applyType(fnT, mapOf(a to intT, b to strT))
        assertEquals(MezzeType.FunctionType(intT, strT), result)
    }

    @Test
    fun `applyType walks list and map types`() {
        val listT = MezzeType.TypeApplication("List", listOf(varA))
        val mapT = MezzeType.TypeApplication("Map", listOf(varA, varB))
        assertEquals(MezzeType.TypeApplication("List", listOf(intT)), TypeSubstitution.applyType(listT, mapOf(a to intT)))
        assertEquals(
            MezzeType.TypeApplication("Map", listOf(intT, strT)),
            TypeSubstitution.applyType(mapT, mapOf(a to intT, b to strT))
        )
    }

    @Test
    fun `applyType walks record types`() {
        val recT = MezzeType.RecordType(mapOf("x" to varA, "y" to varB))
        val result = TypeSubstitution.applyType(recT, mapOf(a to intT, b to strT))
        assertEquals(MezzeType.RecordType(mapOf("x" to intT, "y" to strT)), result)
    }

    @Test
    fun `applyType empty substitution is identity`() {
        val complex = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("x" to varA)),
            MezzeType.TypeApplication("List", listOf(varB))
        )
        assertEquals(complex, TypeSubstitution.applyType(complex, emptyMap()))
    }

    @Test
    fun `applyType primitives are unchanged`() {
        assertEquals(intT, TypeSubstitution.applyType(intT, mapOf(a to strT)))
        assertEquals(strT, TypeSubstitution.applyType(strT, mapOf(a to intT)))
    }

    @Test
    fun `applyType is idempotent under empty sub after one application`() {
        // substitute then re-substitute with empty = identity
        val complex = MezzeType.FunctionType(varA, MezzeType.TypeApplication("List", listOf(varB)))
        val once = TypeSubstitution.applyType(complex, mapOf(a to intT, b to strT))
        val twice = TypeSubstitution.applyType(once, emptyMap())
        assertEquals(once, twice)
    }

    // ---------- expression-level tests ----------

    @Test
    fun `applyExpr rewrites IntLit type`() {
        val lit = TypedIntLit(42, varA, loc)
        val result = TypeSubstitution.applyExpr(lit, mapOf(a to intT))
        assertEquals(intT, result.type)
    }

    @Test
    fun `applyExpr rewrites Var type`() {
        val v = TypedVar("x", BindingRef.Param(0), varA, loc)
        val result = TypeSubstitution.applyExpr(v, mapOf(a to intT))
        assertTrue(result is TypedVar)
        assertEquals(intT, result.type)
        // Binding shape preserved.
        assertEquals(BindingRef.Param(0), result.binding)
    }

    @Test
    fun `applyExpr rewrites Lambda params, captures, body, and type`() {
        val lambdaT = MezzeType.FunctionType(varA, varA)
        val body = TypedVar("x", BindingRef.Param(0), varA, loc)
        val lambda = TypedLambda(
            params = listOf(TypedParam("x", varA, loc)),
            body = body,
            captures = emptyList(),
            type = lambdaT,
            loc = loc
        )
        val result = TypeSubstitution.applyExpr(lambda, mapOf(a to intT)) as TypedLambda
        assertEquals(MezzeType.FunctionType(intT, intT), result.type)
        assertEquals(intT, result.params[0].type)
        assertEquals(intT, result.body.type)
    }

    @Test
    fun `applyExpr rewrites Call instantiation typeArgs`() {
        val call = TypedCall(
            function = TypedVar("f", BindingRef.ByHash(Hash("a".repeat(64))), MezzeType.FunctionType(varA, varA), loc),
            arg = TypedIntLit(1, varA, loc),
            instantiation = TypeInstantiation(typeArgs = listOf(varA), abilityImpls = emptyList()),
            isTailCall = false,
            type = varA,
            loc = loc
        )
        val result = TypeSubstitution.applyExpr(call, mapOf(a to intT)) as TypedCall
        assertEquals(intT, result.type)
        assertEquals(listOf<MezzeType>(intT), result.instantiation?.typeArgs)
    }

    @Test
    fun `applyExpr rewrites Call instantiation abilityImpls targetType`() {
        val abilityHash = Hash("a".repeat(64))
        val implHash = Hash("b".repeat(64))
        val call = TypedCall(
            function = TypedVar("f", BindingRef.ByHash(implHash), MezzeType.FunctionType(varA, varA), loc),
            arg = TypedIntLit(1, varA, loc),
            instantiation = TypeInstantiation(
                typeArgs = listOf(varA),
                abilityImpls = listOf(AbilityImplRef(abilityHash, implHash, varA))
            ),
            isTailCall = false,
            type = varA,
            loc = loc
        )
        val result = TypeSubstitution.applyExpr(call, mapOf(a to intT)) as TypedCall
        assertEquals(intT, result.instantiation?.abilityImpls?.first()?.targetType)
        // Hashes are preserved as-is — Monomorphizer rewrites them in a separate pass.
        assertEquals(abilityHash, result.instantiation?.abilityImpls?.first()?.abilityHash)
        assertEquals(implHash, result.instantiation?.abilityImpls?.first()?.implHash)
    }

    @Test
    fun `applyExpr walks Record fields`() {
        val rec = TypedRecord(
            entries = listOf(
                TypedRecordEntry.Field("x", TypedIntLit(1, varA, loc), loc),
                TypedRecordEntry.Field("y", TypedIntLit(2, varB, loc), loc)
            ),
            type = MezzeType.RecordType(mapOf("x" to varA, "y" to varB)),
            loc = loc
        )
        val result = TypeSubstitution.applyExpr(rec, mapOf(a to intT, b to strT)) as TypedRecord
        val fields = result.entries.filterIsInstance<TypedRecordEntry.Field>()
        assertEquals(intT, fields[0].value.type)
        assertEquals(strT, fields[1].value.type)
    }

    @Test
    fun `applyExpr walks Block statements and result`() {
        val block = TypedBlock(
            statements = listOf(
                TypedStatement.Let("x", Hash("a".repeat(64)), varA, TypedIntLit(1, varA, loc), loc)
            ),
            result = TypedVar("x", BindingRef.Local(0, 0), varA, loc),
            type = varA,
            loc = loc
        )
        val result = TypeSubstitution.applyExpr(block, mapOf(a to intT)) as TypedBlock
        assertEquals(intT, result.type)
        assertEquals(intT, result.result.type)
        val let = result.statements[0] as TypedStatement.Let
        assertEquals(intT, let.type)
    }

    @Test
    fun `applyExpr walks FieldAccess`() {
        val fa = TypedFieldAccess(
            record = TypedVar("r", BindingRef.Param(0), MezzeType.RecordType(mapOf("x" to varA)), loc),
            field = "x",
            fieldIndex = 0,
            type = varA,
            loc = loc
        )
        val result = TypeSubstitution.applyExpr(fa, mapOf(a to intT)) as TypedFieldAccess
        assertEquals(intT, result.type)
        assertEquals(MezzeType.RecordType(mapOf("x" to intT)), result.record.type)
    }

    @Test
    fun `applyExpr walks Match cases`() {
        val match = TypedMatch(
            scrutinee = TypedVar("x", BindingRef.Param(0), varA, loc),
            cases = listOf(
                TypedCase(
                    patterns = listOf(TypedVarPattern("y", varA, loc)),
                    bindings = listOf(PatternBinding("y", varA, loc)),
                    guard = null,
                    body = TypedVar("y", BindingRef.Local(0, 0), varA, loc),
                    loc = loc
                )
            ),
            type = varA,
            loc = loc
        )
        val result = TypeSubstitution.applyExpr(match, mapOf(a to intT)) as TypedMatch
        assertEquals(intT, result.type)
        assertEquals(intT, result.cases[0].body.type)
        assertEquals(intT, result.cases[0].bindings[0].type)
        assertEquals(intT, result.cases[0].patterns[0].type)
    }

    // ---------- collectByHashRefs ----------

    @Test
    fun `collectByHashRefs picks up direct call`() {
        val targetHash = Hash("c".repeat(64))
        val call = TypedCall(
            function = TypedVar("f", BindingRef.ByHash(targetHash), MezzeType.FunctionType(varA, varA), loc),
            arg = TypedIntLit(1, intT, loc),
            instantiation = TypeInstantiation(typeArgs = listOf(intT), abilityImpls = emptyList()),
            isTailCall = false,
            type = intT,
            loc = loc
        )
        val refs = mutableListOf<Pair<BindingRef.ByHash, TypeInstantiation?>>()
        TypeSubstitution.collectByHashRefs(call, refs)
        assertEquals(1, refs.size)
        assertEquals(targetHash, refs[0].first.hash)
        assertEquals(listOf<MezzeType>(intT), refs[0].second?.typeArgs)
    }

    @Test
    fun `collectByHashRefs empty for literal`() {
        val refs = mutableListOf<Pair<BindingRef.ByHash, TypeInstantiation?>>()
        TypeSubstitution.collectByHashRefs(TypedIntLit(42, intT, loc), refs)
        assertTrue(refs.isEmpty())
    }
}
