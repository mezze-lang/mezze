package com.mezze.language.typechecker.codec

import com.mezze.language.ast.BuiltinTypes
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.PrimitiveTypes
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The codec capability CHECK (`AbilityChecker.walkTier2Capabilities`) and the
 * codec EMIT (`CodecGenerator`) must route every value shape to the SAME Tier-2
 * family: if the emit calls `config.encode_mapping` but the check demanded
 * `EncodeSequence`, a format passes the check and then crashes at run time with
 * "Field 'encode_mapping' not found" (or silently encodes through the wrong
 * shape). The two sites drifted here historically — e.g. whether a `Set` is a
 * Sequence or a Mapping.
 *
 * The family decision now lives in ONE place, [Tier2Classifier]. The CHECK
 * consumes it directly (so it cannot drift from it). This test pins the EMIT to
 * the same classifier: for a representative type of each family it asserts
 * (a) the classifier's family and (b) that the generator emits that family's
 * `encode_*` / `decode_*` method. Change the emit's shape dispatch without the
 * classifier and this fails; change the classifier without the check and
 * `CodecAbilityShapeTest` fails.
 */
class CodecCheckEmitMirrorTest {

    private val jsonC = MezzeType.TypeApplication("JsonC", emptyList())
    private val jsonIn = MezzeType.TypeApplication("Json", emptyList())

    // A format with NO bulk ScalarSequence impls, so sequences take the generic
    // `encode_sequence` / `decode_sequence` walk (not the `encode_int_seq` fast
    // path, which is a Sequence-family optimisation, not a separate family).
    private val gen = CodecGenerator(implementsAbility = { _, _ -> false })

    private fun app(ctor: String, vararg args: MezzeType) =
        MezzeType.TypeApplication(ctor, args.toList())

    /** family → (representative type, emit method stem). */
    private val cases: List<Triple<Tier2Shape, MezzeType, String>> = listOf(
        Triple(Tier2Shape.SCALAR, MezzeType.IntType, "int"),
        Triple(Tier2Shape.SCALAR, MezzeType.StringType, "str"),
        Triple(Tier2Shape.SCALAR, MezzeType.BytesType, "bytes"),
        Triple(Tier2Shape.RECORD, MezzeType.RecordType(mapOf("a" to MezzeType.IntType)), "record"),
        Triple(
            Tier2Shape.VARIANT,
            MezzeType.VariantType(constructors = mapOf("Circle" to null, "Square" to null)),
            "variant",
        ),
        Triple(Tier2Shape.SEQUENCE, app(BuiltinTypes.SEQ, MezzeType.IntType), "sequence"),
        Triple(Tier2Shape.SEQUENCE, app(BuiltinTypes.LIST, MezzeType.IntType), "sequence"),
        Triple(Tier2Shape.SEQUENCE, app(BuiltinTypes.SET, MezzeType.IntType), "sequence"),
        Triple(Tier2Shape.MAPPING, app(BuiltinTypes.MAP, MezzeType.StringType, MezzeType.IntType), "mapping"),
        Triple(Tier2Shape.OPTION, app(BuiltinTypes.OPTION, MezzeType.IntType), "option"),
    )

    @Test
    fun `classifier family matches the emitted encode method for every shape`() {
        for ((family, type, stem) in cases) {
            assertEquals(family, Tier2Classifier.of(type), "classifier family for $type")
            val body = gen.generateEncodeImpl(jsonC, type).toString()
            assertTrue(
                body.contains("encode_$stem"),
                "Encode of $type (family $family) must call config.encode_$stem; got:\n$body",
            )
        }
    }

    @Test
    fun `classifier family matches the emitted decode method for every shape`() {
        for ((family, type, stem) in cases) {
            assertEquals(family, Tier2Classifier.of(type), "classifier family for $type")
            val body = gen.generateDecodeImpl(jsonC, type, jsonIn).toString()
            // Scalars decode via `decode_<prim>`; record uses the per-field
            // `decode_record_field` accessor; the rest mirror their encode stem.
            val expected = when (stem) {
                "record" -> "decode_record_field"
                else -> "decode_$stem"
            }
            assertTrue(
                body.contains(expected),
                "Decode of $type (family $family) must call config.$expected; got:\n$body",
            )
        }
    }

    @Test
    fun `builtin scalar spellings classify as SCALAR`() {
        // Bool (builtin `| True, False |`) and Nat (builtin newtype over Int)
        // fold into the scalar hooks, NOT Variant / newtype-noop.
        assertEquals(Tier2Shape.SCALAR, Tier2Classifier.of(PrimitiveTypes.BOOL_TYPE), "Bool is scalar")
        val nat = MezzeType.NewtypeType(name = "Nat", constructor = "Nat", wrappedType = MezzeType.IntType)
        assertEquals(Tier2Shape.SCALAR, Tier2Classifier.of(nat), "Nat is scalar")
        assertTrue(gen.generateEncodeImpl(jsonC, PrimitiveTypes.BOOL_TYPE).toString().contains("encode_bool"))
        assertTrue(gen.generateEncodeImpl(jsonC, nat).toString().contains("encode_nat"))
    }

    @Test
    fun `types with no Tier-2 call classify as null`() {
        // A function type / effect row has no Tier-2 family — the check skips it
        // and the emit falls through to Tier 3. (A user newtype over a non-Nat
        // base is also null: not auto-derivable.)
        assertEquals(
            null,
            Tier2Classifier.of(MezzeType.FunctionType(MezzeType.IntType, MezzeType.IntType)),
            "function type has no Tier-2 family",
        )
        val userNewtype = MezzeType.NewtypeType(name = "Money", constructor = "Money", wrappedType = MezzeType.IntType)
        assertEquals(null, Tier2Classifier.of(userNewtype), "non-Nat newtype has no top-level Tier-2 family")
    }
}
