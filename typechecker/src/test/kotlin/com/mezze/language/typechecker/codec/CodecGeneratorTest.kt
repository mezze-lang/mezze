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
 * Tests the automatic derivation of EncodeRepr/DecodeRepr implementations.
 */
class CodecGeneratorTest {

    private lateinit var generator: CodecGenerator

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
        assertTrue(generator.isSerializable(MezzeType.NatType))
        assertTrue(generator.isSerializable(MezzeType.FracType))
        assertTrue(generator.isSerializable(MezzeType.CharType))
        assertTrue(generator.isSerializable(MezzeType.OrderingType))
    }

    @Test
    fun `binary types should not be serializable`() {
        assertFalse(generator.isSerializable(MezzeType.BytesType))
        assertFalse(generator.isSerializable(MezzeType.ByteBufferType))
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
    fun `suspended types should not be serializable`() {
        val suspendedType = MezzeType.SuspendedType(
            valueType = MezzeType.IntType,
            effects = MezzeType.EffectRow(setOf("IO"))
        )
        assertFalse(generator.isSerializable(suspendedType))
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
        val varType = MezzeType.VarType(TypeVar("a"))
        assertTrue(generator.isSerializable(varType))
    }

    @Test
    fun `generateEncodeImpl for Int creates valid impl`() {
        val formatType = MezzeType.TypeApplication("Json", emptyList())
        val impl = generator.generateEncodeImpl(formatType, MezzeType.IntType)

        assertEquals("EncodeRepr", impl.abilityName)
        // Roc-style: single type parameter (just the data type)
        assertEquals(1, impl.targetTypes.size)
        assertEquals(1, impl.operations.size)  // encode with config
        assertEquals("encode_repr", impl.operations[0].name)
    }

    @Test
    fun `generateEncodeImpl for simple record creates valid impl`() {
        val formatType = MezzeType.TypeApplication("Json", emptyList())
        val recordType = MezzeType.RecordType(mapOf(
            "name" to MezzeType.StringType,
            "age" to MezzeType.IntType
        ))
        val impl = generator.generateEncodeImpl(formatType, recordType)

        assertEquals("EncodeRepr", impl.abilityName)
        // Roc-style: single type parameter (just the data type)
        assertEquals(1, impl.targetTypes.size)
        assertEquals(1, impl.operations.size)  // encode with config
        assertNotNull(impl.operations[0].implementation)
    }

    @Test
    fun `generateEncodeImpl for variant creates valid impl`() {
        val formatType = MezzeType.TypeApplication("Json", emptyList())
        val variantType = MezzeType.VariantType(mapOf(
            "Some" to MezzeType.IntType,
            "None" to null
        ))
        val impl = generator.generateEncodeImpl(formatType, variantType)

        assertEquals("EncodeRepr", impl.abilityName)
        assertEquals(1, impl.operations.size)  // encode with config
    }

    @Test
    fun `generateEncodeImpl for List creates valid impl`() {
        val formatType = MezzeType.TypeApplication("Json", emptyList())
        val listType = MezzeType.ListType(MezzeType.IntType)
        val impl = generator.generateEncodeImpl(formatType, listType)

        assertEquals("EncodeRepr", impl.abilityName)
        assertEquals(1, impl.operations.size)  // encode with config
    }

    @Test
    fun `generated impls are cached`() {
        val formatType = MezzeType.TypeApplication("Json", emptyList())

        val impl1 = generator.generateEncodeImpl(formatType, MezzeType.IntType)
        val impl2 = generator.generateEncodeImpl(formatType, MezzeType.IntType)

        // Should return the same instance due to caching
        assertTrue(impl1 === impl2)
    }

    @Test
    fun `generateDecodeImpl for Int creates valid impl`() {
        val formatType = MezzeType.TypeApplication("Json", emptyList())
        val impl = generator.generateDecodeImpl(formatType, MezzeType.IntType)

        assertEquals("DecodeRepr", impl.abilityName)
        // Roc-style: single type parameter (just the data type)
        assertEquals(1, impl.targetTypes.size)
        assertEquals(1, impl.operations.size)  // decode with config
        assertEquals("decode_repr", impl.operations[0].name)
    }

    @Test
    fun `generateDecodeImpl for simple record creates valid impl`() {
        val formatType = MezzeType.TypeApplication("Json", emptyList())
        val recordType = MezzeType.RecordType(mapOf(
            "name" to MezzeType.StringType,
            "age" to MezzeType.IntType
        ))
        val impl = generator.generateDecodeImpl(formatType, recordType)

        assertEquals("DecodeRepr", impl.abilityName)
        assertEquals(1, impl.operations.size)  // decode with config
        assertNotNull(impl.operations[0].implementation)
    }

    @Test
    fun `generateDecodeImpl for variant creates valid impl`() {
        val formatType = MezzeType.TypeApplication("Json", emptyList())
        val variantType = MezzeType.VariantType(mapOf(
            "Some" to MezzeType.IntType,
            "None" to null
        ))
        val impl = generator.generateDecodeImpl(formatType, variantType)

        assertEquals("DecodeRepr", impl.abilityName)
        assertEquals(1, impl.operations.size)  // decode with config
    }

    @Test
    fun `generateStreamEncodeImpl for Int creates valid impl`() {
        val formatType = MezzeType.TypeApplication("JsonStream", emptyList())
        val impl = generator.generateStreamEncodeImpl(formatType, MezzeType.IntType)

        assertEquals("StreamEncode", impl.abilityName)
        // Roc-style: single type parameter (just the data type)
        assertEquals(1, impl.targetTypes.size)
        assertEquals(1, impl.operations.size)
        assertEquals("stream_encode", impl.operations[0].name)
    }

    @Test
    fun `generateStreamDecodeImpl for Int creates valid impl`() {
        val formatType = MezzeType.TypeApplication("JsonStream", emptyList())
        val impl = generator.generateStreamDecodeImpl(formatType, MezzeType.IntType)

        assertEquals("StreamDecode", impl.abilityName)
        // Roc-style: single type parameter (just the data type)
        assertEquals(1, impl.targetTypes.size)
        assertEquals(1, impl.operations.size)
        assertEquals("stream_decode", impl.operations[0].name)
    }

    // Type Application Tests (Option, Result)

    @Test
    fun `generateEncodeImpl for Option creates valid impl`() {
        val formatType = MezzeType.TypeApplication("Json", emptyList())
        val optionType = MezzeType.TypeApplication("Option", listOf(MezzeType.IntType))
        val impl = generator.generateEncodeImpl(formatType, optionType)

        assertEquals("EncodeRepr", impl.abilityName)
        assertEquals(1, impl.operations.size)  // encode with config
    }

    @Test
    fun `generateDecodeImpl for Option creates valid impl`() {
        val formatType = MezzeType.TypeApplication("Json", emptyList())
        val optionType = MezzeType.TypeApplication("Option", listOf(MezzeType.IntType))
        val impl = generator.generateDecodeImpl(formatType, optionType)

        assertEquals("DecodeRepr", impl.abilityName)
        assertEquals(1, impl.operations.size)  // decode with config
    }

    @Test
    fun `generateEncodeImpl for Result creates valid impl`() {
        val formatType = MezzeType.TypeApplication("Json", emptyList())
        val resultType = MezzeType.TypeApplication("Result", listOf(MezzeType.IntType, MezzeType.StringType))
        val impl = generator.generateEncodeImpl(formatType, resultType)

        assertEquals("EncodeRepr", impl.abilityName)
        assertEquals(1, impl.operations.size)  // encode with config
    }

    @Test
    fun `generateDecodeImpl for Result creates valid impl`() {
        val formatType = MezzeType.TypeApplication("Json", emptyList())
        val resultType = MezzeType.TypeApplication("Result", listOf(MezzeType.IntType, MezzeType.StringType))
        val impl = generator.generateDecodeImpl(formatType, resultType)

        assertEquals("DecodeRepr", impl.abilityName)
        assertEquals(1, impl.operations.size)  // decode with config
    }

    // Normalized Cache Key Tests

    @Test
    fun `records with same fields in different order share cache key`() {
        val formatType = MezzeType.TypeApplication("Json", emptyList())

        // Record with fields in order: name, age
        val record1 = MezzeType.RecordType(linkedMapOf(
            "name" to MezzeType.StringType,
            "age" to MezzeType.IntType
        ))

        // Record with fields in order: age, name
        val record2 = MezzeType.RecordType(linkedMapOf(
            "age" to MezzeType.IntType,
            "name" to MezzeType.StringType
        ))

        val impl1 = generator.generateEncodeImpl(formatType, record1)
        val impl2 = generator.generateEncodeImpl(formatType, record2)

        // Should return the same cached instance
        assertTrue(impl1 === impl2, "Records with same fields in different order should share encoder")
    }

    @Test
    fun `nested records with different field orders share cache key`() {
        val formatType = MezzeType.TypeApplication("Json", emptyList())

        // Nested record: {address: {city, zip}, name}
        val inner1 = MezzeType.RecordType(linkedMapOf(
            "city" to MezzeType.StringType,
            "zip" to MezzeType.IntType
        ))
        val outer1 = MezzeType.RecordType(linkedMapOf(
            "address" to inner1,
            "name" to MezzeType.StringType
        ))

        // Nested record with different order: {name, address: {zip, city}}
        val inner2 = MezzeType.RecordType(linkedMapOf(
            "zip" to MezzeType.IntType,
            "city" to MezzeType.StringType
        ))
        val outer2 = MezzeType.RecordType(linkedMapOf(
            "name" to MezzeType.StringType,
            "address" to inner2
        ))

        val impl1 = generator.generateEncodeImpl(formatType, outer1)
        val impl2 = generator.generateEncodeImpl(formatType, outer2)

        // Should return the same cached instance
        assertTrue(impl1 === impl2, "Nested records with same structure should share encoder")
    }

    @Test
    fun `records with different fields do not share cache key`() {
        val formatType = MezzeType.TypeApplication("Json", emptyList())

        val record1 = MezzeType.RecordType(mapOf(
            "name" to MezzeType.StringType,
            "age" to MezzeType.IntType
        ))

        val record2 = MezzeType.RecordType(mapOf(
            "name" to MezzeType.StringType,
            "email" to MezzeType.StringType
        ))

        val impl1 = generator.generateEncodeImpl(formatType, record1)
        val impl2 = generator.generateEncodeImpl(formatType, record2)

        // Should NOT return the same instance
        assertFalse(impl1 === impl2, "Records with different fields should not share encoder")
    }

    @Test
    fun `records with same field names but different types do not share cache key`() {
        val formatType = MezzeType.TypeApplication("Json", emptyList())

        val record1 = MezzeType.RecordType(mapOf(
            "value" to MezzeType.IntType
        ))

        val record2 = MezzeType.RecordType(mapOf(
            "value" to MezzeType.StringType
        ))

        val impl1 = generator.generateEncodeImpl(formatType, record1)
        val impl2 = generator.generateEncodeImpl(formatType, record2)

        // Should NOT return the same instance
        assertFalse(impl1 === impl2, "Records with same field name but different types should not share encoder")
    }

    // Variant Type Tests

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
        val formatType = MezzeType.TypeApplication("Json", emptyList())
        val emptyVariant = MezzeType.VariantType(emptyMap())
        val impl = generator.generateEncodeImpl(formatType, emptyVariant)

        assertEquals("EncodeRepr", impl.abilityName)
        assertEquals(1, impl.operations.size)  // encode with config
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
