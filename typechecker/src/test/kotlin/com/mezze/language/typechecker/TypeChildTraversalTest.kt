package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.ast.TypeVar
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse

/**
 * `MezzeType.anyChild` / `transformChildren` are the shared structural walk the
 * typechecker leans on for substitution and predicates (`mapVarTypes`,
 * `substituteTypeParams`, `stampTypeHashes`, `isConcrete`, `containsVarType`, …).
 * A polymorphic variant carries its applied type-args in `VariantType.typeArgs`
 * (`Tree Int` == `VariantType(identity="Tree", typeArgs=[Int])`), so those args
 * ARE structural children and must be walked/rewritten like any other. This pins
 * that — a var living ONLY in `typeArgs` (not in a constructor payload) must be
 * seen by `anyChild` and rewritten by `transformChildren`, else a substitution
 * leaves `typeArgs` stale against already-substituted payloads.
 */
class TypeChildTraversalTest {

    private fun treeWithVarOnlyInTypeArgs(v: MezzeType): MezzeType.VariantType =
        MezzeType.VariantType(
            // Payloads carry NO var — the var lives only in typeArgs, so this
            // isolates the typeArgs traversal from the constructor traversal.
            constructors = mapOf("Leaf" to null),
            rowVar = null,
            typeArgs = listOf(v),
            identity = TypeIdentity.Declared(Hash("a".repeat(64)), "Tree")
        )

    @Test
    fun `anyChild sees a type var that lives only in VariantType typeArgs`() {
        val v = MezzeType.VarType(TypeVar.fresh())
        val tree = treeWithVarOnlyInTypeArgs(v)
        assertTrue(
            tree.anyChild { it is MezzeType.VarType },
            "anyChild must traverse VariantType.typeArgs (an applied polymorphic variant's args are children)"
        )
    }

    @Test
    fun `transformChildren rewrites a type var in VariantType typeArgs`() {
        val v = MezzeType.VarType(TypeVar.fresh())
        val tree = treeWithVarOnlyInTypeArgs(v)
        val subst = tree.transformChildren {
            if (it is MezzeType.VarType) MezzeType.IntType else it
        } as MezzeType.VariantType
        assertEquals(
            listOf<MezzeType>(MezzeType.IntType), subst.typeArgs,
            "transformChildren must rewrite VariantType.typeArgs, else they go stale vs substituted payloads"
        )
    }

    @Test
    fun `transformChildren still rewrites constructor payloads and keeps identity`() {
        // Guard the existing behavior isn't lost when we also walk typeArgs.
        val v = MezzeType.VarType(TypeVar.fresh())
        val tree = MezzeType.VariantType(
            constructors = mapOf("Node" to v, "Leaf" to null),
            rowVar = null,
            typeArgs = listOf(v),
            identity = TypeIdentity.Declared(Hash("b".repeat(64)), "Tree")
        )
        val subst = tree.transformChildren {
            if (it is MezzeType.VarType) MezzeType.IntType else it
        } as MezzeType.VariantType
        assertEquals(MezzeType.IntType, subst.constructors["Node"], "constructor payload still substituted")
        assertEquals(listOf<MezzeType>(MezzeType.IntType), subst.typeArgs, "typeArgs substituted in step")
        assertEquals("Tree", subst.identity.displayNameOrNull(), "nominal identity preserved")
    }

    private fun fnWithVarOnlyInEffectRow(v: MezzeType): MezzeType.FunctionType =
        MezzeType.FunctionType(
            paramType = MezzeType.RecordType(fields = emptyMap(), rowVar = null),
            returnType = MezzeType.IntType,
            // The var lives ONLY in the effect instance's associated type
            // (`Exception having 'error = 'a`), nowhere in param/return.
            effects = MezzeType.EffectRow(
                effects = setOf("Exception"),
                effectInstances = setOf(MezzeType.EffectInstance("Exception", mapOf("error" to v)))
            )
        )

    @Test
    fun `anyChild sees a type var that lives only in a function's effect row`() {
        val v = MezzeType.VarType(TypeVar.fresh())
        assertTrue(
            fnWithVarOnlyInEffectRow(v).anyChild { it is MezzeType.VarType },
            "anyChild must traverse FunctionType.effects associated types"
        )
    }

    @Test
    fun `transformChildren rewrites a type var in a function's effect row`() {
        val v = MezzeType.VarType(TypeVar.fresh())
        val subst = fnWithVarOnlyInEffectRow(v).transformChildren {
            if (it is MezzeType.VarType) MezzeType.IntType else it
        } as MezzeType.FunctionType
        val rewritten = subst.effects!!.effectInstances.first().associatedTypes["error"]
        assertEquals(
            MezzeType.IntType, rewritten,
            "transformChildren must rewrite the effect instance's associated type var"
        )
    }

    @Test
    fun `anyChild is false on a fully-concrete variant`() {
        val concrete = MezzeType.VariantType(
            constructors = mapOf("Node" to MezzeType.IntType, "Leaf" to null),
            rowVar = null,
            typeArgs = listOf(MezzeType.IntType),
            identity = TypeIdentity.Declared(Hash("c".repeat(64)), "Tree")
        )
        assertFalse(
            concrete.anyChild { it is MezzeType.VarType },
            "no vars anywhere (payloads or typeArgs) → anyChild false"
        )
    }
}
