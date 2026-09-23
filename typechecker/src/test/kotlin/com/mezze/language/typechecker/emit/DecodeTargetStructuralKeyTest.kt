package com.mezze.language.typechecker.emit

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.ast.TypeVar
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `DecodeTargetResolver.structural` is the key that decides WHICH derived decoder
 * a call site is wired to. Two targets must share a key exactly when they are the
 * same shape, and the failure is silent in both directions:
 *
 *  - two shapes that wrongly spell the SAME let one target select the other's
 *    decoder;
 *  - one shape that wrongly spells TWO ways matches no impl at all, leaving the
 *    call site unwired so it runs the first decoder in scope.
 *
 * Both have happened. A `VariantType` used to fall through to
 * `javaClass.simpleName`, so a Bool in variant form spelled "VariantType" while a
 * Bool in primitive form spelled "Bool" — `{"flag":true}` decoded as `False` —
 * and simultaneously every user variant spelled "VariantType", so `{ x: Color }`
 * and `{ x: Status }` compared equal.
 *
 * The Bool half is covered end-to-end by
 * `JsonDecodeExhaustiveTest.threeDifferentShapes`. The variant half cannot be
 * reached through `from_json` today (the derived codec does not decode a user
 * variant from a JSON string), so it is pinned here directly.
 */
class DecodeTargetStructuralKeyTest {

    private fun key(t: MezzeType) = DecodeTargetResolver.structural(t)

    private fun declared(name: String, hash: String) =
        TypeIdentity.derive(name, Hash(hash), allowBuiltin = false)

    // ---- the collision that had no test ----

    @Test
    fun `two different user variants do not share a key`() {
        val colour = MezzeType.VariantType(
            mapOf("Red" to null, "Green" to null),
            identity = declared("Colour", "c0c0c0c0")
        )
        val status = MezzeType.VariantType(
            mapOf("Open" to null, "Closed" to null),
            identity = declared("Status", "50505050")
        )
        assertNotEquals(
            key(colour), key(status),
            "`{ x: Colour }` and `{ x: Status }` share a decoder key, so one can select " +
                "the other's decoder"
        )
    }

    @Test
    fun `variants differing only in a payload type do not share a key`() {
        fun wrap(payload: MezzeType) =
            MezzeType.VariantType(mapOf("Wrap" to payload), identity = declared("W", "a0a0a0a0"))
        assertNotEquals(key(wrap(MezzeType.IntType)), key(wrap(MezzeType.StringType)))
    }

    @Test
    fun `an open variant does not share a key with its closed twin`() {
        val arms = mapOf("A" to null, "B" to null)
        assertNotEquals(
            key(MezzeType.VariantType(arms)),
            key(MezzeType.VariantType(arms, rowVar = com.mezze.language.ast.RowVar.fresh()))
        )
    }

    @Test
    fun `arm ORDER does not change the key`() {
        // The key sorts, so the same variant written two ways is one shape.
        assertEquals(
            key(MezzeType.VariantType(mapOf("A" to null, "B" to null))),
            key(MezzeType.VariantType(mapOf("B" to null, "A" to null)))
        )
    }

    // ---- the half that DOES have an end-to-end test, pinned here too ----

    @Test
    fun `Bool's two representations share one key`() {
        val boolVariant = MezzeType.VariantType(mapOf("True" to null, "False" to null))
        assertEquals(
            "Bool", key(boolVariant),
            "a Bool in variant form must spell the same as MezzeType.BoolType, or a " +
                "`{ flag: Bool }` target matches no derived impl"
        )
        assertEquals(key(MezzeType.BoolType), key(boolVariant))
    }

    // ---- a newtype is nominal, not its shape ----

    @Test
    fun `two newtypes over the same wrapped type do not share a key`() {
        val email = MezzeType.NewtypeType("Email", "Email", MezzeType.StringType, identity = declared("Email", "e0e0e0e0"))
        val userId = MezzeType.NewtypeType("UserId", "UserId", MezzeType.StringType, identity = declared("UserId", "0d0d0d0d"))
        assertNotEquals(key(email), key(userId))
        assertNotEquals(key(email), key(MezzeType.StringType))
    }

    // ---- no type may spell as a Kotlin class ----

    @Test
    fun `no type spells as its Kotlin class name`() {
        val all = listOf(
            MezzeType.IntType, MezzeType.FloatType, MezzeType.StringType, MezzeType.BoolType,
            MezzeType.CharType, MezzeType.FracType, MezzeType.BytesType,
            MezzeType.VarType(TypeVar.freshWithName("'a")),
            MezzeType.FunctionType(MezzeType.IntType, MezzeType.IntType),
            MezzeType.RecordType(mapOf("x" to MezzeType.IntType)),
            MezzeType.VariantType(mapOf("A" to null)),
            MezzeType.TypeApplication("Option", listOf(MezzeType.IntType)),
            MezzeType.NewtypeType("N", "N", MezzeType.IntType),
            MezzeType.EffectRow(effects = setOf("Exception")),
        )
        for (t in all) {
            val k = key(t)
            assertTrue(
                k != t::class.simpleName,
                "${t::class.simpleName} spells as its own Kotlin class name — that is the " +
                    "fallthrough that made every variant compare equal"
            )
        }
        // And every one of them is distinct from every other.
        val keys = all.map { key(it) }
        assertEquals(keys.size, keys.toSet().size, "two distinct types share a key: $keys")
    }
}
