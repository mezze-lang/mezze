package com.mezze.language.typechecker.codec

import com.mezze.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertEquals

/**
 * Unit tests for the CodecGenerator class.
 *
 * Tests the automatic derivation of Tier-3 `Encode` / `Decode` implementations
 * per (config, data) / (config, data, in) pair. Users of the new codec surface
 * do not interact with these directly — the Tier-4 sugar (`.json{}` /
 * `Type.from_json{}`) in each format module wraps them. See
 * `docs/codec/redesign-2026-09.md` §"Tier 3 — Encode / Decode primitive".
 */
class CodecGeneratorTest {

    private lateinit var generator: CodecGenerator

    private val jsonC = MezzeType.TypeApplication("JsonC", emptyList())
    private val jsonInType = MezzeType.TypeApplication("Json", emptyList())

    @BeforeEach
    fun setup() {
        generator = CodecGenerator()
    }

    @Test
    fun `primitives should be serializable`() {
        assertTrue(generator.isSerializable(MezzeType.IntType))
        assertTrue(generator.isSerializable(MezzeType.FloatType))
        assertTrue(generator.isSerializable(MezzeType.StringType))
        assertTrue(generator.isSerializable(MezzeType.BoolType))
        // Nat is now a newtype (`newtype Nat = Int` in std/nat.mz), not a primitive.
        // Newtypes are not auto-serializable — the impls live in std/nat.mz.
        assertTrue(!generator.isSerializable(MezzeType.NewtypeType(
            name = "Nat",
            constructor = "Nat",
            wrappedType = MezzeType.IntType,
        )))
        assertTrue(generator.isSerializable(MezzeType.FracType))
        assertTrue(generator.isSerializable(MezzeType.CharType))
    }

    @Test
    fun `Bytes is serializable`() {
        // Bytes maps to Tier-2 `encode_bytes` / `decode_bytes` — needed so
        // polymorphic helpers (`'a with Encode 'c 'a`) dispatch when `'a`
        // instantiates to Bytes.
        assertTrue(generator.isSerializable(MezzeType.BytesType))
    }

    @Test
    fun `function types should not be serializable`() {
        val funcType = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(mapOf("x" to MezzeType.IntType)),
            returnType = MezzeType.IntType
        )
        assertFalse(generator.isSerializable(funcType))
    }

    @Test
    fun `simple record should be serializable`() {
        val recordType = MezzeType.RecordType(mapOf(
            "name" to MezzeType.StringType,
            "age" to MezzeType.IntType
        ))
        assertTrue(generator.isSerializable(recordType))
    }

    @Test
    fun `record with function field should not be serializable`() {
        val funcType = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(mapOf("x" to MezzeType.IntType)),
            returnType = MezzeType.IntType
        )
        val recordType = MezzeType.RecordType(mapOf(
            "name" to MezzeType.StringType,
            "callback" to funcType
        ))
        assertFalse(generator.isSerializable(recordType))
    }

    @Test
    fun `empty record should be serializable`() {
        val recordType = MezzeType.RecordType(emptyMap())
        assertTrue(generator.isSerializable(recordType))
    }

    @Test
    fun `nested record should be serializable`() {
        val innerRecord = MezzeType.RecordType(mapOf("city" to MezzeType.StringType))
        val outerRecord = MezzeType.RecordType(mapOf(
            "name" to MezzeType.StringType,
            "address" to innerRecord
        ))
        assertTrue(generator.isSerializable(outerRecord))
    }

    @Test
    fun `simple variant should be serializable`() {
        val variantType = MezzeType.VariantType(mapOf(
            "Red" to null,
            "Blue" to null,
            "Green" to null
        ))
        assertTrue(generator.isSerializable(variantType))
    }

    @Test
    fun `variant with payload should be serializable`() {
        val variantType = MezzeType.VariantType(mapOf(
            "Some" to MezzeType.IntType,
            "None" to null
        ))
        assertTrue(generator.isSerializable(variantType))
    }

    @Test
    fun `variant with function payload should not be serializable`() {
        val funcType = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(emptyMap()),
            returnType = MezzeType.IntType
        )
        val variantType = MezzeType.VariantType(mapOf(
            "Callback" to funcType,
            "None" to null
        ))
        assertFalse(generator.isSerializable(variantType))
    }

    @Test
    fun `list of primitives should be serializable`() {
        val listType = MezzeType.ListType(MezzeType.IntType)
        assertTrue(generator.isSerializable(listType))
    }

    @Test
    fun `list of records should be serializable`() {
        val recordType = MezzeType.RecordType(mapOf("x" to MezzeType.IntType))
        val listType = MezzeType.ListType(recordType)
        assertTrue(generator.isSerializable(listType))
    }

    @Test
    fun `list of functions should not be serializable`() {
        val funcType = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(emptyMap()),
            returnType = MezzeType.IntType
        )
        val listType = MezzeType.ListType(funcType)
        assertFalse(generator.isSerializable(listType))
    }

    @Test
    fun `map with serializable key value should be serializable`() {
        val mapType = MezzeType.MapType(
            keyType = MezzeType.StringType,
            valueType = MezzeType.IntType
        )
        assertTrue(generator.isSerializable(mapType))
    }

    @Test
    fun `set of primitives should be serializable`() {
        val setType = MezzeType.SetType(MezzeType.IntType)
        assertTrue(generator.isSerializable(setType))
    }

    @Test
    fun `sorted set should be serializable`() {
        val sortedSetType = MezzeType.SortedSetType(MezzeType.IntType)
        assertTrue(generator.isSerializable(sortedSetType))
    }

    @Test
    fun `sorted map should be serializable`() {
        val sortedMapType = MezzeType.SortedMapType(
            keyType = MezzeType.StringType,
            valueType = MezzeType.IntType
        )
        assertTrue(generator.isSerializable(sortedMapType))
    }

    @Test
    fun `type application Option with serializable inner should be serializable`() {
        val optionType = MezzeType.TypeApplication(
            constructor = "Option",
            args = listOf(MezzeType.IntType)
        )
        assertTrue(generator.isSerializable(optionType))
    }

    @Test
    fun `type application Result with serializable args should be serializable`() {
        val resultType = MezzeType.TypeApplication(
            constructor = "Result",
            args = listOf(MezzeType.IntType, MezzeType.StringType)
        )
        assertTrue(generator.isSerializable(resultType))
    }

    @Test
    fun `type variable should be assumed serializable`() {
        val varType = MezzeType.VarType(TypeVar.freshWithName("a"))
        assertTrue(generator.isSerializable(varType))
    }

    // ---- generateEncodeImpl: 2-slot shape (formatType, dataType) ---------

    @Test
    fun `generateEncodeImpl for Int creates valid impl`() {
        val impl = generator.generateEncodeImpl(jsonC, MezzeType.IntType)

        assertEquals("Encode", impl.abilityName)
        // Tier-3 shape: ability param is the config; target is the data type.
        assertEquals(1, impl.abilityTypeArgs.size)
        assertEquals(1, impl.targetTypes.size)
        assertEquals(1, impl.operations.size)
        assertEquals("encode", impl.operations[0].name)
    }

    @Test
    fun `generateEncodeImpl for simple record creates valid impl`() {
        val recordType = MezzeType.RecordType(mapOf(
            "name" to MezzeType.StringType,
            "age" to MezzeType.IntType
        ))
        val impl = generator.generateEncodeImpl(jsonC, recordType)

        assertEquals("Encode", impl.abilityName)
        assertEquals(1, impl.abilityTypeArgs.size)
        assertEquals(1, impl.targetTypes.size)
        assertEquals(1, impl.operations.size)
        assertNotNull(impl.operations[0].implementation)
    }

    @Test
    fun `generateEncodeImpl for variant creates valid impl`() {
        val variantType = MezzeType.VariantType(mapOf(
            "Some" to MezzeType.IntType,
            "None" to null
        ))
        val impl = generator.generateEncodeImpl(jsonC, variantType)

        assertEquals("Encode", impl.abilityName)
        assertEquals(1, impl.operations.size)
    }

    @Test
    fun `generateEncodeImpl for List creates valid impl`() {
        val listType = MezzeType.ListType(MezzeType.IntType)
        val impl = generator.generateEncodeImpl(jsonC, listType)

        assertEquals("Encode", impl.abilityName)
        assertEquals(1, impl.operations.size)
    }

    @Test
    fun `generated impls are cached`() {
        val impl1 = generator.generateEncodeImpl(jsonC, MezzeType.IntType)
        val impl2 = generator.generateEncodeImpl(jsonC, MezzeType.IntType)

        assertTrue(impl1 === impl2)
    }

    // ---- generateDecodeImpl: 3-slot shape (formatType, dataType, inType) --

    @Test
    fun `generateDecodeImpl for Int creates valid impl`() {
        val impl = generator.generateDecodeImpl(jsonC, MezzeType.IntType, jsonInType)

        assertEquals("Decode", impl.abilityName)
        // Tier-3 decode shape: two ability params (config, data), one target (input).
        assertEquals(2, impl.abilityTypeArgs.size)
        assertEquals(1, impl.targetTypes.size)
        assertEquals(1, impl.operations.size)
        assertEquals("decode", impl.operations[0].name)
    }

    @Test
    fun `generateDecodeImpl for simple record creates valid impl`() {
        val recordType = MezzeType.RecordType(mapOf(
            "name" to MezzeType.StringType,
            "age" to MezzeType.IntType
        ))
        val impl = generator.generateDecodeImpl(jsonC, recordType, jsonInType)

        assertEquals("Decode", impl.abilityName)
        assertEquals(1, impl.operations.size)
        assertNotNull(impl.operations[0].implementation)
    }

    @Test
    fun `generateDecodeImpl for variant creates valid impl`() {
        val variantType = MezzeType.VariantType(mapOf(
            "Some" to MezzeType.IntType,
            "None" to null
        ))
        val impl = generator.generateDecodeImpl(jsonC, variantType, jsonInType)

        assertEquals("Decode", impl.abilityName)
        assertEquals(1, impl.operations.size)
    }

    // ---- Type application: Option / Result ------------------------------

    @Test
    fun `generateEncodeImpl for Option creates valid impl`() {
        val optionType = MezzeType.TypeApplication("Option", listOf(MezzeType.IntType))
        val impl = generator.generateEncodeImpl(jsonC, optionType)

        assertEquals("Encode", impl.abilityName)
        assertEquals(1, impl.operations.size)
    }

    @Test
    fun `generateDecodeImpl for Option creates valid impl`() {
        val optionType = MezzeType.TypeApplication("Option", listOf(MezzeType.IntType))
        val impl = generator.generateDecodeImpl(jsonC, optionType, jsonInType)

        assertEquals("Decode", impl.abilityName)
        assertEquals(1, impl.operations.size)
    }

    @Test
    fun `generateEncodeImpl for Result creates valid impl`() {
        val resultType = MezzeType.TypeApplication("Result", listOf(MezzeType.IntType, MezzeType.StringType))
        val impl = generator.generateEncodeImpl(jsonC, resultType)

        assertEquals("Encode", impl.abilityName)
        assertEquals(1, impl.operations.size)
    }

    @Test
    fun `generateDecodeImpl for Result creates valid impl`() {
        val resultType = MezzeType.TypeApplication("Result", listOf(MezzeType.IntType, MezzeType.StringType))
        val impl = generator.generateDecodeImpl(jsonC, resultType, jsonInType)

        assertEquals("Decode", impl.abilityName)
        assertEquals(1, impl.operations.size)
    }

    // ---- Cache-key distinctness ----------------------------------------
    // (Order-invariant cache-key normalisation was a perf-hint of the old
    // generator that the new implementation does not promise — the walker
    // sorts fields at emit time, so order-differing keys still produce
    // identical impl bodies, they just miss the cache. The tests below only
    // probe distinctness, which IS a correctness invariant.)

    @Test
    fun `records with different fields do not share cache key`() {
        val record1 = MezzeType.RecordType(mapOf(
            "name" to MezzeType.StringType,
            "age" to MezzeType.IntType
        ))

        val record2 = MezzeType.RecordType(mapOf(
            "name" to MezzeType.StringType,
            "email" to MezzeType.StringType
        ))

        val impl1 = generator.generateEncodeImpl(jsonC, record1)
        val impl2 = generator.generateEncodeImpl(jsonC, record2)

        assertFalse(impl1 === impl2, "Records with different fields should not share encoder")
    }

    @Test
    fun `records with same field names but different types do not share cache key`() {
        val record1 = MezzeType.RecordType(mapOf(
            "value" to MezzeType.IntType
        ))

        val record2 = MezzeType.RecordType(mapOf(
            "value" to MezzeType.StringType
        ))

        val impl1 = generator.generateEncodeImpl(jsonC, record1)
        val impl2 = generator.generateEncodeImpl(jsonC, record2)

        assertFalse(impl1 === impl2, "Records with same field name but different types should not share encoder")
    }

    // ---- Variant coverage ----------------------------------------------

    @Test
    fun `variant with record payload should be serializable`() {
        val variantType = MezzeType.VariantType(mapOf(
            "Person" to MezzeType.RecordType(mapOf("name" to MezzeType.StringType)),
            "Unknown" to null
        ))
        assertTrue(generator.isSerializable(variantType))
    }

    @Test
    fun `variant with nested variant payload should be serializable`() {
        val innerVariant = MezzeType.VariantType(mapOf(
            "Yes" to null,
            "No" to null
        ))
        val outerVariant = MezzeType.VariantType(mapOf(
            "Answer" to innerVariant,
            "None" to null
        ))
        assertTrue(generator.isSerializable(outerVariant))
    }

    @Test
    fun `empty variant should be serializable`() {
        val emptyVariant = MezzeType.VariantType(emptyMap())
        assertTrue(generator.isSerializable(emptyVariant))
    }

    @Test
    fun `generateEncodeImpl for empty variant creates valid impl`() {
        val emptyVariant = MezzeType.VariantType(emptyMap())
        val impl = generator.generateEncodeImpl(jsonC, emptyVariant)

        assertEquals("Encode", impl.abilityName)
        assertEquals(1, impl.operations.size)
    }

    @Test
    fun `list of Option types should be serializable`() {
        val optionType = MezzeType.TypeApplication("Option", listOf(MezzeType.IntType))
        val listType = MezzeType.ListType(optionType)
        assertTrue(generator.isSerializable(listType))
    }

    @Test
    fun `map with record values should be serializable`() {
        val recordType = MezzeType.RecordType(mapOf("x" to MezzeType.IntType))
        val mapType = MezzeType.MapType(
            keyType = MezzeType.StringType,
            valueType = recordType
        )
        assertTrue(generator.isSerializable(mapType))
    }

    @Test
    fun `Result containing record types should be serializable`() {
        val okType = MezzeType.RecordType(mapOf("data" to MezzeType.StringType))
        val errType = MezzeType.RecordType(mapOf("message" to MezzeType.StringType))
        val resultType = MezzeType.TypeApplication("Result", listOf(okType, errType))
        assertTrue(generator.isSerializable(resultType))
    }
}
