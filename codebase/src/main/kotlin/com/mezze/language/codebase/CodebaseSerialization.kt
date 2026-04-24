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

    // ====================================================================
    // Individual TypedDecl serializers (for per-definition blob storage)
    // ====================================================================

    fun serializeTypedLetDecl(decl: com.mezze.language.typedast.TypedLetDecl): ByteArray {
        return cbor.encodeToByteArray(decl)
    }

    fun deserializeTypedLetDecl(bytes: ByteArray): com.mezze.language.typedast.TypedLetDecl {
        return cbor.decodeFromByteArray(bytes)
    }

    fun serializeTypedTypeDecl(decl: com.mezze.language.typedast.TypedTypeDecl): ByteArray {
        return cbor.encodeToByteArray(decl)
    }

    fun deserializeTypedTypeDecl(bytes: ByteArray): com.mezze.language.typedast.TypedTypeDecl {
        return cbor.decodeFromByteArray(bytes)
    }

    fun serializeTypedAbilityDecl(decl: com.mezze.language.typedast.TypedAbilityDecl): ByteArray {
        return cbor.encodeToByteArray(decl)
    }

    fun deserializeTypedAbilityDecl(bytes: ByteArray): com.mezze.language.typedast.TypedAbilityDecl {
        return cbor.decodeFromByteArray(bytes)
    }

    fun serializeTypedAbilityImpl(impl: com.mezze.language.typedast.TypedAbilityImpl): ByteArray {
        return cbor.encodeToByteArray(impl)
    }

    fun deserializeTypedAbilityImpl(bytes: ByteArray): com.mezze.language.typedast.TypedAbilityImpl {
        return cbor.decodeFromByteArray(bytes)
    }

    fun serializeTypedEffectDecl(decl: com.mezze.language.typedast.TypedEffectDecl): ByteArray {
        return cbor.encodeToByteArray(decl)
    }

    fun deserializeTypedEffectDecl(bytes: ByteArray): com.mezze.language.typedast.TypedEffectDecl {
        return cbor.decodeFromByteArray(bytes)
    }

    fun serializeTypedPerformerDecl(decl: com.mezze.language.typedast.TypedPerformerDecl): ByteArray {
        return cbor.encodeToByteArray(decl)
    }

    fun deserializeTypedPerformerDecl(bytes: ByteArray): com.mezze.language.typedast.TypedPerformerDecl {
        return cbor.decodeFromByteArray(bytes)
    }
}
