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
    // AST SERIALIZATION
    // ========================================================================

    /**
     * Serialize a Program to CBOR bytes.
     */
    fun serializeProgram(program: Program): ByteArray {
        return cbor.encodeToByteArray(program)
    }

    /**
     * Deserialize a Program from CBOR bytes.
     */
    fun deserializeProgram(bytes: ByteArray): Program {
        return cbor.decodeFromByteArray(bytes)
    }

    /**
     * Serialize a LetBinding to CBOR bytes.
     */
    fun serializeLetBinding(binding: LetBinding): ByteArray {
        return cbor.encodeToByteArray(binding)
    }

    /**
     * Deserialize a LetBinding from CBOR bytes.
     */
    fun deserializeLetBinding(bytes: ByteArray): LetBinding {
        return cbor.decodeFromByteArray(bytes)
    }

    /**
     * Serialize a TypeDeclaration to CBOR bytes.
     */
    fun serializeTypeDeclaration(decl: TypeDeclaration): ByteArray {
        return cbor.encodeToByteArray(decl)
    }

    /**
     * Deserialize a TypeDeclaration from CBOR bytes.
     */
    fun deserializeTypeDeclaration(bytes: ByteArray): TypeDeclaration {
        return cbor.decodeFromByteArray(bytes)
    }

    /**
     * Serialize an AbilityDeclaration to CBOR bytes.
     */
    fun serializeAbilityDeclaration(decl: AbilityDeclaration): ByteArray {
        return cbor.encodeToByteArray(decl)
    }

    /**
     * Deserialize an AbilityDeclaration from CBOR bytes.
     */
    fun deserializeAbilityDeclaration(bytes: ByteArray): AbilityDeclaration {
        return cbor.decodeFromByteArray(bytes)
    }

    /**
     * Serialize an EffectDeclaration to CBOR bytes.
     */
    fun serializeEffectDeclaration(decl: EffectDeclaration): ByteArray {
        return cbor.encodeToByteArray(decl)
    }

    /**
     * Deserialize an EffectDeclaration from CBOR bytes.
     */
    fun deserializeEffectDeclaration(bytes: ByteArray): EffectDeclaration {
        return cbor.decodeFromByteArray(bytes)
    }

    /**
     * Serialize an ImplDeclaration to CBOR bytes.
     */
    fun serializeImplDeclaration(decl: ImplDeclaration): ByteArray {
        return cbor.encodeToByteArray(decl)
    }

    /**
     * Deserialize an ImplDeclaration from CBOR bytes.
     */
    fun deserializeImplDeclaration(bytes: ByteArray): ImplDeclaration {
        return cbor.decodeFromByteArray(bytes)
    }

    /**
     * Serialize an ImplAbilityDeclaration to CBOR bytes.
     */
    fun serializeImplAbilityDeclaration(decl: ImplAbilityDeclaration): ByteArray {
        return cbor.encodeToByteArray(decl)
    }

    /**
     * Deserialize an ImplAbilityDeclaration from CBOR bytes.
     */
    fun deserializeImplAbilityDeclaration(bytes: ByteArray): ImplAbilityDeclaration {
        return cbor.decodeFromByteArray(bytes)
    }

    /**
     * Serialize any ProgramItem to CBOR bytes.
     */
    fun serializeProgramItem(item: ProgramItem): ByteArray {
        return cbor.encodeToByteArray(ProgramItem.serializer(), item)
    }

    /**
     * Deserialize any ProgramItem from CBOR bytes.
     */
    fun deserializeProgramItem(bytes: ByteArray): ProgramItem {
        return cbor.decodeFromByteArray(ProgramItem.serializer(), bytes)
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
    // MODULE MAP SERIALIZATION
    // ========================================================================

    /**
     * Serialize a map of module path to Program.
     */
    fun serializeModuleMap(modules: Map<String, Program>): ByteArray {
        return cbor.encodeToByteArray(modules)
    }

    /**
     * Deserialize a map of module path to Program.
     */
    fun deserializeModuleMap(bytes: ByteArray): Map<String, Program> {
        return cbor.decodeFromByteArray(bytes)
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
