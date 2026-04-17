package com.mezze.language.codebase

import com.mezze.language.ast.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.decodeFromByteArray

/**
 * Serialization utilities for codebase storage.
 *
 * Uses CBOR (Concise Binary Object Representation) for compact storage.
 * CBOR is ~50% smaller than JSON and faster to parse.
 */
@OptIn(ExperimentalSerializationApi::class)
object CodebaseSerialization {

    private val cbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = false  // Skip null/default values for smaller output
    }

    // ========================================================================
    // TYPE SERIALIZATION
    // ========================================================================

    /**
     * Serialize a MezzeType to CBOR bytes.
     */
    fun serializeType(type: MezzeType): ByteArray {
        return cbor.encodeToByteArray(MezzeType.serializer(), type)
    }

    /**
     * Deserialize a MezzeType from CBOR bytes.
     */
    fun deserializeType(bytes: ByteArray): MezzeType {
        return cbor.decodeFromByteArray(MezzeType.serializer(), bytes)
    }

    // ========================================================================
    // V2 TYPED MODULE SERIALIZATION
    // ========================================================================

    /**
     * Serialize a v2 TypedModule to CBOR bytes.
     */
    fun serializeTypedModule(module: com.mezze.language.typedast.TypedModule): ByteArray {
        return cbor.encodeToByteArray(module)
    }

    /**
     * Deserialize a v2 TypedModule from CBOR bytes.
     */
    fun deserializeTypedModule(bytes: ByteArray): com.mezze.language.typedast.TypedModule {
        return cbor.decodeFromByteArray(bytes)
    }

    // ========================================================================
    // TRUFFLE IR SERIALIZATION
    // ========================================================================

    /**
     * Serialize a FunctionIR to CBOR bytes.
     */
    fun serializeFunctionIR(fn: com.mezze.language.truffleir.FunctionIR): ByteArray {
        return cbor.encodeToByteArray(fn)
    }

    /**
     * Deserialize a FunctionIR from CBOR bytes.
     */
    fun deserializeFunctionIR(bytes: ByteArray): com.mezze.language.truffleir.FunctionIR {
        return cbor.decodeFromByteArray(bytes)
    }

    /**
     * Serialize a list of FunctionIR to CBOR bytes.
     */
    fun serializeFunctionIRList(fns: List<com.mezze.language.truffleir.FunctionIR>): ByteArray {
        return cbor.encodeToByteArray(fns)
    }

    /**
     * Deserialize a list of FunctionIR from CBOR bytes.
     */
    fun deserializeFunctionIRList(bytes: ByteArray): List<com.mezze.language.truffleir.FunctionIR> {
        return cbor.decodeFromByteArray(bytes)
    }
}
