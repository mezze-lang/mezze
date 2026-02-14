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
        assertEquals(1, impl.operations.size)
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
        assertEquals(1, impl.operations.size)
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
        assertEquals(1, impl.operations.size)
    }

    @Test
    fun `generateEncodeImpl for List creates valid impl`() {
        val formatType = FunkType.TypeApplication("Json", emptyList())
        val listType = FunkType.ListType(FunkType.IntType)
        val impl = generator.generateEncodeImpl(formatType, listType)

        assertEquals("Encode", impl.abilityName)
        assertEquals(1, impl.operations.size)
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
        assertEquals(1, impl.operations.size)
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
        assertEquals(1, impl.operations.size)
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
        assertEquals(1, impl.operations.size)
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
        assertEquals(1, impl.operations.size)
    }

    @Test
    fun `generateDecodeImpl for Option creates valid impl`() {
        val formatType = FunkType.TypeApplication("Json", emptyList())
        val optionType = FunkType.TypeApplication("Option", listOf(FunkType.IntType))
        val impl = generator.generateDecodeImpl(formatType, optionType)

        assertEquals("Decode", impl.abilityName)
        assertEquals(1, impl.operations.size)
    }

    @Test
    fun `generateEncodeImpl for Result creates valid impl`() {
        val formatType = FunkType.TypeApplication("Json", emptyList())
        val resultType = FunkType.TypeApplication("Result", listOf(FunkType.IntType, FunkType.StringType))
        val impl = generator.generateEncodeImpl(formatType, resultType)

        assertEquals("Encode", impl.abilityName)
        assertEquals(1, impl.operations.size)
    }

    @Test
    fun `generateDecodeImpl for Result creates valid impl`() {
        val formatType = FunkType.TypeApplication("Json", emptyList())
        val resultType = FunkType.TypeApplication("Result", listOf(FunkType.IntType, FunkType.StringType))
        val impl = generator.generateDecodeImpl(formatType, resultType)

        assertEquals("Decode", impl.abilityName)
        assertEquals(1, impl.operations.size)
    }
}
