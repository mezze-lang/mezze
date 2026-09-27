package com.mezze.language.store

import com.mezze.language.ast.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.decodeFromByteArray

/**
 * Serialization utilities for store storage.
 *
 * Uses CBOR (Concise Binary Object Representation) for compact storage.
 * CBOR is ~50% smaller than JSON and faster to parse.
 */
@OptIn(ExperimentalSerializationApi::class)
object StoreSerialization {

    /**
     * Compatibility version of the compiled-IR + interface format that crosses a
     * package boundary. A consumer loads a dependency's shipped compiled IR only
     * when its stamp equals this; otherwise it recompiles from the shipped source
     * (the never-fail fallback). Bump per `planned_features/package-management/
     * versioning-governance.md`: any change to lowered-IR shape/encoding, the
     * interface format, or the hash-closure inputs — never for a pure optimization
     * that leaves the bytes identical. Distinct from `SQLiteMezzeStore.SCHEMA_VERSION`
     * (on-disk table layout) and `BuildInfo.VERSION` (toolchain release identity).
     */
    const val IR_VERSION: Int = 1

    private val cbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun serializeType(type: MezzeType): ByteArray {
        return cbor.encodeToByteArray(MezzeType.serializer(), type)
    }

    fun deserializeType(bytes: ByteArray): MezzeType {
        return cbor.decodeFromByteArray(MezzeType.serializer(), bytes)
    }

    fun serializeTypeList(types: List<MezzeType>): ByteArray {
        return cbor.encodeToByteArray(kotlinx.serialization.builtins.ListSerializer(MezzeType.serializer()), types)
    }

    fun deserializeTypeList(bytes: ByteArray): List<MezzeType> {
        return cbor.decodeFromByteArray(kotlinx.serialization.builtins.ListSerializer(MezzeType.serializer()), bytes)
    }

    fun serializeTypedModule(module: com.mezze.language.typedast.TypedModule): ByteArray {
        return cbor.encodeToByteArray(module)
    }

    fun deserializeTypedModule(bytes: ByteArray): com.mezze.language.typedast.TypedModule {
        return cbor.decodeFromByteArray(bytes)
    }

    fun serializeParsedModule(module: com.mezze.language.parsedast.ParsedModule): ByteArray {
        return cbor.encodeToByteArray(module)
    }

    fun deserializeParsedModule(bytes: ByteArray): com.mezze.language.parsedast.ParsedModule {
        return cbor.decodeFromByteArray(bytes)
    }

    fun serializeFunctionIR(fn: com.mezze.language.truffleir.FunctionIR): ByteArray {
        return cbor.encodeToByteArray(fn)
    }

    fun deserializeFunctionIR(bytes: ByteArray): com.mezze.language.truffleir.FunctionIR {
        return cbor.decodeFromByteArray(bytes)
    }

    fun serializeFunctionIRList(fns: List<com.mezze.language.truffleir.FunctionIR>): ByteArray {
        return cbor.encodeToByteArray(fns)
    }

    fun deserializeFunctionIRList(bytes: ByteArray): List<com.mezze.language.truffleir.FunctionIR> {
        return cbor.decodeFromByteArray(bytes)
    }

    /**
     * App metadata that travels alongside the FunctionIR list when an
     * app is AOT-embedded into a native binary. Captures the entry-point
     * + performer-record info that the SQLite path normally reads from
     * the `metadata` / `app_performers` tables.
     */
    @kotlinx.serialization.Serializable
    data class EmbeddedAppMeta(
        val appName: String,
        // Entry-point identity: the entry function's content hash (hex). The
        // runtime selects the entry by `find { it.hash == entryHash }`, never
        // by name. Wire type is String to match the DB's hash-TEXT storage and
        // avoid Java/CBOR interop friction with the Hash value type.
        val entryHash: String,
        // Display-only entry name; NEVER a selection key.
        val entryName: String?,
        // List<List<String>> instead of List<Array<String>> because kotlinx
        // serializes arrays awkwardly; the standalone code converts back
        // to Array<String> after deserialization.
        val performerRecords: List<List<String>>,
        /**
         * Static shape of the entry's RESULT, for an EVAL closure.
         *
         * Null for an ordinary app, whose entry's result is a program's exit
         * value and is never rendered. Set when this closure is one expression
         * the client wants a VALUE back from: rendering needs the static shape,
         * because a runtime value alone cannot tell `Nat` from `Int` (both are
         * `Long`), cannot see a newtype (erased to its payload), and cannot tell
         * a one-character `Str` from a `Char`.
         *
         * It travels HERE rather than in the `Eval` wire message on purpose.
         * This sidecar is already the channel for compiler-computed facts about
         * a closure — `entryHash` and `performerRecords` are the same kind of
         * thing — and `PrintType` is a compiler-internal type, so putting it in
         * the protocol would freeze it as a published schema and make every
         * compiler refactor a protocol break.
         */
        val resultPrintType: com.mezze.language.truffleir.PrintType? = null,
    )

    fun serializeEmbeddedAppMeta(meta: EmbeddedAppMeta): ByteArray =
        cbor.encodeToByteArray(EmbeddedAppMeta.serializer(), meta)

    /**
     * Convenience wrapper used by EmbeddedAppLoader's build-time init —
     * returns a small shape with already-rehydrated `Array<String>`
     * performer records to match what `MezzeLanguage.setFunctionsForDirectExecution`
     * expects.
     */
    fun deserializeEmbeddedAppMeta(bytes: ByteArray): DeserializedEmbeddedAppMeta {
        val raw = cbor.decodeFromByteArray(EmbeddedAppMeta.serializer(), bytes)
        return DeserializedEmbeddedAppMeta(
            appName = raw.appName,
            entryHash = raw.entryHash,
            entryName = raw.entryName,
            performerRecords = raw.performerRecords.map { it.toTypedArray() },
            resultPrintType = raw.resultPrintType,
        )
    }

    data class DeserializedEmbeddedAppMeta(
        val appName: String,
        /** Entry-point identity: entry function content-hash hex. */
        val entryHash: String,
        /** Display-only entry name; NEVER a selection key. */
        val entryName: String?,
        val performerRecords: List<Array<String>>,
        /** See [EmbeddedAppMeta.resultPrintType]. Null for an ordinary app. */
        val resultPrintType: com.mezze.language.truffleir.PrintType? = null,
    )

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
