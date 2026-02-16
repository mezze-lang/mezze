package com.funk.language.typechecker.codec

import com.funk.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertEquals

/**
 * Unit tests for the CodecGenerator class.
 *
 * Tests the automatic derivation of Encode/Decode implementations.
 */
class CodecGeneratorTest {

    private lateinit var generator: CodecGenerator

    @BeforeEach
    fun setup() {
        generator = CodecGenerator()
    }

    // ========================================================================
    // isSerializable Tests
    // ========================================================================

    @Test
    fun `primitives should be serializable`() {
        assertTrue(generator.isSerializable(FunkType.IntType))
        assertTrue(generator.isSerializable(FunkType.FloatType))
        assertTrue(generator.isSerializable(FunkType.StringType))
        assertTrue(generator.isSerializable(FunkType.BooleanType))
        assertTrue(generator.isSerializable(FunkType.NatType))
        assertTrue(generator.isSerializable(FunkType.FracType))
        assertTrue(generator.isSerializable(FunkType.CharType))
        assertTrue(generator.isSerializable(FunkType.OrderingType))
    }

    @Test
    fun `binary types should not be serializable`() {
        assertFalse(generator.isSerializable(FunkType.BytesType))
        assertFalse(generator.isSerializable(FunkType.ByteBufferType))
    }

    @Test
    fun `function types should not be serializable`() {
        val funcType = FunkType.FunctionType(
            paramType = FunkType.RecordType(mapOf("x" to FunkType.IntType)),
            returnType = FunkType.IntType
        )
        assertFalse(generator.isSerializable(funcType))
    }

    @Test
    fun `suspended types should not be serializable`() {
        val suspendedType = FunkType.SuspendedType(
            valueType = FunkType.IntType,
            effects = FunkType.EffectRow(setOf("IO"))
        )
        assertFalse(generator.isSerializable(suspendedType))
    }

    @Test
    fun `simple record should be serializable`() {
        val recordType = FunkType.RecordType(mapOf(
            "name" to FunkType.StringType,
            "age" to FunkType.IntType
        ))
        assertTrue(generator.isSerializable(recordType))
    }

    @Test
    fun `record with function field should not be serializable`() {
        val funcType = FunkType.FunctionType(
            paramType = FunkType.RecordType(mapOf("x" to FunkType.IntType)),
            returnType = FunkType.IntType
        )
        val recordType = FunkType.RecordType(mapOf(
            "name" to FunkType.StringType,
            "callback" to funcType
        ))
        assertFalse(generator.isSerializable(recordType))
    }

    @Test
    fun `empty record should be serializable`() {
        val recordType = FunkType.RecordType(emptyMap())
        assertTrue(generator.isSerializable(recordType))
    }

    @Test
    fun `nested record should be serializable`() {
        val innerRecord = FunkType.RecordType(mapOf("city" to FunkType.StringType))
        val outerRecord = FunkType.RecordType(mapOf(
            "name" to FunkType.StringType,
            "address" to innerRecord
        ))
        assertTrue(generator.isSerializable(outerRecord))
    }

    @Test
    fun `simple variant should be serializable`() {
        val variantType = FunkType.VariantType(mapOf(
            "Red" to null,
            "Blue" to null,
            "Green" to null
        ))
        assertTrue(generator.isSerializable(variantType))
    }

    @Test
    fun `variant with payload should be serializable`() {
        val variantType = FunkType.VariantType(mapOf(
            "Some" to FunkType.IntType,
            "None" to null
        ))
        assertTrue(generator.isSerializable(variantType))
    }

    @Test
    fun `variant with function payload should not be serializable`() {
        val funcType = FunkType.FunctionType(
            paramType = FunkType.RecordType(emptyMap()),
            returnType = FunkType.IntType
        )
        val variantType = FunkType.VariantType(mapOf(
            "Callback" to funcType,
            "None" to null
        ))
        assertFalse(generator.isSerializable(variantType))
    }

    @Test
    fun `list of primitives should be serializable`() {
        val listType = FunkType.ListType(FunkType.IntType)
        assertTrue(generator.isSerializable(listType))
    }

    @Test
    fun `list of records should be serializable`() {
        val recordType = FunkType.RecordType(mapOf("x" to FunkType.IntType))
        val listType = FunkType.ListType(recordType)
        assertTrue(generator.isSerializable(listType))
    }

    @Test
    fun `list of functions should not be serializable`() {
        val funcType = FunkType.FunctionType(
            paramType = FunkType.RecordType(emptyMap()),
            returnType = FunkType.IntType
        )
        val listType = FunkType.ListType(funcType)
        assertFalse(generator.isSerializable(listType))
    }

    @Test
    fun `map with serializable key value should be serializable`() {
        val mapType = FunkType.MapType(
            keyType = FunkType.StringType,
            valueType = FunkType.IntType
        )
        assertTrue(generator.isSerializable(mapType))
    }

    @Test
    fun `set of primitives should be serializable`() {
        val setType = FunkType.SetType(FunkType.IntType)
        assertTrue(generator.isSerializable(setType))
    }

    @Test
    fun `sorted set should be serializable`() {
        val sortedSetType = FunkType.SortedSetType(FunkType.IntType)
        assertTrue(generator.isSerializable(sortedSetType))
    }

    @Test
    fun `sorted map should be serializable`() {
        val sortedMapType = FunkType.SortedMapType(
            keyType = FunkType.StringType,
            valueType = FunkType.IntType
        )
        assertTrue(generator.isSerializable(sortedMapType))
    }

    @Test
    fun `type application Option with serializable inner should be serializable`() {
        val optionType = FunkType.TypeApplication(
            constructor = "Option",
            args = listOf(FunkType.IntType)
        )
        assertTrue(generator.isSerializable(optionType))
    }

    @Test
    fun `type application Result with serializable args should be serializable`() {
        val resultType = FunkType.TypeApplication(
            constructor = "Result",
            args = listOf(FunkType.IntType, FunkType.StringType)
        )
        assertTrue(generator.isSerializable(resultType))
    }

    @Test
    fun `type variable should be assumed serializable`() {
        val varType = FunkType.VarType(TypeVar("a"))
        assertTrue(generator.isSerializable(varType))
    }

    // ========================================================================
    // generateEncodeImpl Tests
    // ========================================================================

    @Test
    fun `generateEncodeImpl for Int creates valid impl`() {
        val formatType = FunkType.TypeApplication("Json", emptyList())
        val impl = generator.generateEncodeImpl(formatType, FunkType.IntType)

        assertEquals("Encode", impl.abilityName)
        assertEquals(2, impl.targetTypes.size)
        assertEquals(1, impl.operations.size)  // encode with config
        assertEquals("encode", impl.operations[0].name)
    }

    @Test
    fun `generateEncodeImpl for simple record creates valid impl`() {
        val formatType = FunkType.TypeApplication("Json", emptyList())
        val recordType = FunkType.RecordType(mapOf(
            "name" to FunkType.StringType,
            "age" to FunkType.IntType
        ))
        val impl = generator.generateEncodeImpl(formatType, recordType)

        assertEquals("Encode", impl.abilityName)
        assertEquals(2, impl.targetTypes.size)
        assertEquals(1, impl.operations.size)  // encode with config
        assertNotNull(impl.operations[0].implementation)
    }

    @Test
    fun `generateEncodeImpl for variant creates valid impl`() {
        val formatType = FunkType.TypeApplication("Json", emptyList())
        val variantType = FunkType.VariantType(mapOf(
            "Some" to FunkType.IntType,
            "None" to null
        ))
        val impl = generator.generateEncodeImpl(formatType, variantType)

        assertEquals("Encode", impl.abilityName)
        assertEquals(1, impl.operations.size)  // encode with config
    }

    @Test
    fun `generateEncodeImpl for List creates valid impl`() {
        val formatType = FunkType.TypeApplication("Json", emptyList())
        val listType = FunkType.ListType(FunkType.IntType)
        val impl = generator.generateEncodeImpl(formatType, listType)

        assertEquals("Encode", impl.abilityName)
        assertEquals(1, impl.operations.size)  // encode with config
    }

    @Test
    fun `generated impls are cached`() {
        val formatType = FunkType.TypeApplication("Json", emptyList())

        val impl1 = generator.generateEncodeImpl(formatType, FunkType.IntType)
        val impl2 = generator.generateEncodeImpl(formatType, FunkType.IntType)

        // Should return the same instance due to caching
        assertTrue(impl1 === impl2)
    }

    // ========================================================================
    // generateDecodeImpl Tests
    // ========================================================================

    @Test
    fun `generateDecodeImpl for Int creates valid impl`() {
        val formatType = FunkType.TypeApplication("Json", emptyList())
        val impl = generator.generateDecodeImpl(formatType, FunkType.IntType)

        assertEquals("Decode", impl.abilityName)
        assertEquals(2, impl.targetTypes.size)
        assertEquals(1, impl.operations.size)  // decode with config
        assertEquals("decode", impl.operations[0].name)
    }

    @Test
    fun `generateDecodeImpl for simple record creates valid impl`() {
        val formatType = FunkType.TypeApplication("Json", emptyList())
        val recordType = FunkType.RecordType(mapOf(
            "name" to FunkType.StringType,
            "age" to FunkType.IntType
        ))
        val impl = generator.generateDecodeImpl(formatType, recordType)

        assertEquals("Decode", impl.abilityName)
        assertEquals(1, impl.operations.size)  // decode with config
        assertNotNull(impl.operations[0].implementation)
    }

    @Test
    fun `generateDecodeImpl for variant creates valid impl`() {
        val formatType = FunkType.TypeApplication("Json", emptyList())
        val variantType = FunkType.VariantType(mapOf(
            "Some" to FunkType.IntType,
            "None" to null
        ))
        val impl = generator.generateDecodeImpl(formatType, variantType)

        assertEquals("Decode", impl.abilityName)
        assertEquals(1, impl.operations.size)  // decode with config
    }

    // ========================================================================
    // Streaming Encode/Decode Tests
    // ========================================================================

    @Test
    fun `generateStreamEncodeImpl for Int creates valid impl`() {
        val formatType = FunkType.TypeApplication("JsonStream", emptyList())
        val impl = generator.generateStreamEncodeImpl(formatType, FunkType.IntType)

        assertEquals("StreamEncode", impl.abilityName)
        assertEquals(2, impl.targetTypes.size)
        assertEquals(1, impl.operations.size)
        assertEquals("stream_encode", impl.operations[0].name)
    }

    @Test
    fun `generateStreamDecodeImpl for Int creates valid impl`() {
        val formatType = FunkType.TypeApplication("JsonStream", emptyList())
        val impl = generator.generateStreamDecodeImpl(formatType, FunkType.IntType)

        assertEquals("StreamDecode", impl.abilityName)
        assertEquals(2, impl.targetTypes.size)
        assertEquals(1, impl.operations.size)
        assertEquals("stream_decode", impl.operations[0].name)
    }

    // ========================================================================
    // Cache Management Tests
    // ========================================================================

    @Test
    fun `clearCaches clears all caches`() {
        val formatType = FunkType.TypeApplication("Json", emptyList())

        // Generate some impls
        generator.generateEncodeImpl(formatType, FunkType.IntType)
        generator.generateDecodeImpl(formatType, FunkType.IntType)

        // Verify caches are populated
        val stats1 = generator.getCacheStats()
        assertTrue(stats1["encode"]!! > 0)
        assertTrue(stats1["decode"]!! > 0)

        // Clear caches
        generator.clearCaches()

        // Verify caches are empty
        val stats2 = generator.getCacheStats()
        assertEquals(0, stats2["encode"])
        assertEquals(0, stats2["decode"])
        assertEquals(0, stats2["streamEncode"])
        assertEquals(0, stats2["streamDecode"])
    }

    @Test
    fun `getCacheStats returns correct counts`() {
        val formatType = FunkType.TypeApplication("Json", emptyList())

        generator.clearCaches()

        // Generate impls for different types
        generator.generateEncodeImpl(formatType, FunkType.IntType)
        generator.generateEncodeImpl(formatType, FunkType.StringType)
        generator.generateDecodeImpl(formatType, FunkType.IntType)

        val stats = generator.getCacheStats()
        assertEquals(2, stats["encode"])
        assertEquals(1, stats["decode"])
        assertEquals(0, stats["streamEncode"])
        assertEquals(0, stats["streamDecode"])
    }

    // ========================================================================
    // Type Application Tests (Option, Result)
    // ========================================================================

    @Test
    fun `generateEncodeImpl for Option creates valid impl`() {
        val formatType = FunkType.TypeApplication("Json", emptyList())
        val optionType = FunkType.TypeApplication("Option", listOf(FunkType.IntType))
        val impl = generator.generateEncodeImpl(formatType, optionType)

        assertEquals("Encode", impl.abilityName)
        assertEquals(1, impl.operations.size)  // encode with config
    }

    @Test
    fun `generateDecodeImpl for Option creates valid impl`() {
        val formatType = FunkType.TypeApplication("Json", emptyList())
        val optionType = FunkType.TypeApplication("Option", listOf(FunkType.IntType))
        val impl = generator.generateDecodeImpl(formatType, optionType)

        assertEquals("Decode", impl.abilityName)
        assertEquals(1, impl.operations.size)  // decode with config
    }

    @Test
    fun `generateEncodeImpl for Result creates valid impl`() {
        val formatType = FunkType.TypeApplication("Json", emptyList())
        val resultType = FunkType.TypeApplication("Result", listOf(FunkType.IntType, FunkType.StringType))
        val impl = generator.generateEncodeImpl(formatType, resultType)

        assertEquals("Encode", impl.abilityName)
        assertEquals(1, impl.operations.size)  // encode with config
    }

    @Test
    fun `generateDecodeImpl for Result creates valid impl`() {
        val formatType = FunkType.TypeApplication("Json", emptyList())
        val resultType = FunkType.TypeApplication("Result", listOf(FunkType.IntType, FunkType.StringType))
        val impl = generator.generateDecodeImpl(formatType, resultType)

        assertEquals("Decode", impl.abilityName)
        assertEquals(1, impl.operations.size)  // decode with config
    }

    // ========================================================================
    // Normalized Cache Key Tests
    // ========================================================================

    @Test
    fun `records with same fields in different order share cache key`() {
        val formatType = FunkType.TypeApplication("Json", emptyList())

        // Record with fields in order: name, age
        val record1 = FunkType.RecordType(linkedMapOf(
            "name" to FunkType.StringType,
            "age" to FunkType.IntType
        ))

        // Record with fields in order: age, name
        val record2 = FunkType.RecordType(linkedMapOf(
            "age" to FunkType.IntType,
            "name" to FunkType.StringType
        ))

        generator.clearCaches()

        val impl1 = generator.generateEncodeImpl(formatType, record1)
        val impl2 = generator.generateEncodeImpl(formatType, record2)

        // Should return the same cached instance
        assertTrue(impl1 === impl2, "Records with same fields in different order should share encoder")

        // Cache should have only one entry
        val stats = generator.getCacheStats()
        assertEquals(1, stats["encode"], "Only one encoder should be cached for same-structure records")
    }

    @Test
    fun `nested records with different field orders share cache key`() {
        val formatType = FunkType.TypeApplication("Json", emptyList())

        // Nested record: {address: {city, zip}, name}
        val inner1 = FunkType.RecordType(linkedMapOf(
            "city" to FunkType.StringType,
            "zip" to FunkType.IntType
        ))
        val outer1 = FunkType.RecordType(linkedMapOf(
            "address" to inner1,
            "name" to FunkType.StringType
        ))

        // Nested record with different order: {name, address: {zip, city}}
        val inner2 = FunkType.RecordType(linkedMapOf(
            "zip" to FunkType.IntType,
            "city" to FunkType.StringType
        ))
        val outer2 = FunkType.RecordType(linkedMapOf(
            "name" to FunkType.StringType,
            "address" to inner2
        ))

        generator.clearCaches()

        val impl1 = generator.generateEncodeImpl(formatType, outer1)
        val impl2 = generator.generateEncodeImpl(formatType, outer2)

        // Should return the same cached instance
        assertTrue(impl1 === impl2, "Nested records with same structure should share encoder")
    }

    @Test
    fun `records with different fields do not share cache key`() {
        val formatType = FunkType.TypeApplication("Json", emptyList())

        val record1 = FunkType.RecordType(mapOf(
            "name" to FunkType.StringType,
            "age" to FunkType.IntType
        ))

        val record2 = FunkType.RecordType(mapOf(
            "name" to FunkType.StringType,
            "email" to FunkType.StringType
        ))

        generator.clearCaches()

        val impl1 = generator.generateEncodeImpl(formatType, record1)
        val impl2 = generator.generateEncodeImpl(formatType, record2)

        // Should NOT return the same instance
        assertFalse(impl1 === impl2, "Records with different fields should not share encoder")

        // Cache should have two entries
        val stats = generator.getCacheStats()
        assertEquals(2, stats["encode"], "Two different record types should have two encoders")
    }

    @Test
    fun `records with same field names but different types do not share cache key`() {
        val formatType = FunkType.TypeApplication("Json", emptyList())

        val record1 = FunkType.RecordType(mapOf(
            "value" to FunkType.IntType
        ))

        val record2 = FunkType.RecordType(mapOf(
            "value" to FunkType.StringType
        ))

        generator.clearCaches()

        val impl1 = generator.generateEncodeImpl(formatType, record1)
        val impl2 = generator.generateEncodeImpl(formatType, record2)

        // Should NOT return the same instance
        assertFalse(impl1 === impl2, "Records with same field name but different types should not share encoder")

        // Cache should have two entries
        val stats = generator.getCacheStats()
        assertEquals(2, stats["encode"])
    }

    // ========================================================================
    // Variant Type Tests
    // ========================================================================

    @Test
    fun `variant with record payload should be serializable`() {
        val variantType = FunkType.VariantType(mapOf(
            "Person" to FunkType.RecordType(mapOf("name" to FunkType.StringType)),
            "Unknown" to null
        ))
        assertTrue(generator.isSerializable(variantType))
    }

    @Test
    fun `variant with nested variant payload should be serializable`() {
        val innerVariant = FunkType.VariantType(mapOf(
            "Yes" to null,
            "No" to null
        ))
        val outerVariant = FunkType.VariantType(mapOf(
            "Answer" to innerVariant,
            "None" to null
        ))
        assertTrue(generator.isSerializable(outerVariant))
    }

    // ========================================================================
    // Empty Collection Tests
    // ========================================================================

    @Test
    fun `empty variant should be serializable`() {
        val emptyVariant = FunkType.VariantType(emptyMap())
        assertTrue(generator.isSerializable(emptyVariant))
    }

    @Test
    fun `generateEncodeImpl for empty variant creates valid impl`() {
        val formatType = FunkType.TypeApplication("Json", emptyList())
        val emptyVariant = FunkType.VariantType(emptyMap())
        val impl = generator.generateEncodeImpl(formatType, emptyVariant)

        assertEquals("Encode", impl.abilityName)
        assertEquals(1, impl.operations.size)  // encode with config
    }

    // ========================================================================
    // Complex Type Tests
    // ========================================================================

    @Test
    fun `list of Option types should be serializable`() {
        val optionType = FunkType.TypeApplication("Option", listOf(FunkType.IntType))
        val listType = FunkType.ListType(optionType)
        assertTrue(generator.isSerializable(listType))
    }

    @Test
    fun `map with record values should be serializable`() {
        val recordType = FunkType.RecordType(mapOf("x" to FunkType.IntType))
        val mapType = FunkType.MapType(
            keyType = FunkType.StringType,
            valueType = recordType
        )
        assertTrue(generator.isSerializable(mapType))
    }

    @Test
    fun `Result containing record types should be serializable`() {
        val okType = FunkType.RecordType(mapOf("data" to FunkType.StringType))
        val errType = FunkType.RecordType(mapOf("message" to FunkType.StringType))
        val resultType = FunkType.TypeApplication("Result", listOf(okType, errType))
        assertTrue(generator.isSerializable(resultType))
    }
}
