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
    const val IR_VERSION: Int = 2

    private val cbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    // ------------------------------------------------------------------
    // Stored declarations: what one IS, then where it was written.
    //
    // A blob is addressed by the hash of what its declaration means. Its file
    // and line numbers are not part of that, so they are written after the
    // declaration rather than through it ([Positions]): [contentOf] is the same
    // bytes for the same declaration in any checkout, at any line.
    // ------------------------------------------------------------------

    private const val BLOB_MAGIC_0: Byte = 0x4D // 'M'
    private const val BLOB_MAGIC_1: Byte = 0x5A // 'Z'
    private const val BLOB_FORMAT: Byte = 1
    private const val BLOB_HEADER = 7           // magic, format, content length

    private fun <T> writeBlob(serializer: kotlinx.serialization.SerializationStrategy<T>, value: T): ByteArray {
        val (content, positions) = Positions.write { cbor.encodeToByteArray(serializer, value) }
        val out = ByteArray(BLOB_HEADER + content.size + positions.size)
        out[0] = BLOB_MAGIC_0; out[1] = BLOB_MAGIC_1; out[2] = BLOB_FORMAT
        val n = content.size
        out[3] = (n ushr 24).toByte(); out[4] = (n ushr 16).toByte(); out[5] = (n ushr 8).toByte(); out[6] = n.toByte()
        System.arraycopy(content, 0, out, BLOB_HEADER, n)
        System.arraycopy(positions, 0, out, BLOB_HEADER + n, positions.size)
        return out
    }

    /** Where a blob's content ends, having checked it is a blob this build can read. */
    private fun contentEnd(blob: ByteArray): Int {
        require(blob.size >= BLOB_HEADER && blob[0] == BLOB_MAGIC_0 && blob[1] == BLOB_MAGIC_1) {
            "not a stored Mezze declaration (${blob.size} bytes) — written by another mez, or not through StoreSerialization"
        }
        require(blob[2] == BLOB_FORMAT) {
            "a stored declaration in format ${blob[2]}, and this mez reads format $BLOB_FORMAT — rebuild the store"
        }
        val n = ((blob[3].toInt() and 0xff) shl 24) or ((blob[4].toInt() and 0xff) shl 16) or
            ((blob[5].toInt() and 0xff) shl 8) or (blob[6].toInt() and 0xff)
        require(n >= 0 && BLOB_HEADER + n <= blob.size) { "a stored declaration's content runs past its ${blob.size} bytes" }
        return BLOB_HEADER + n
    }

    private fun <T> readBlob(deserializer: kotlinx.serialization.DeserializationStrategy<T>, blob: ByteArray): T {
        val end = contentEnd(blob)
        return Positions.read(blob.copyOfRange(end, blob.size)) {
            cbor.decodeFromByteArray(deserializer, blob.copyOfRange(BLOB_HEADER, end))
        }
    }

    /**
     * What a stored declaration IS, without where it was written: equal for the
     * same declaration whatever file it came from and whatever line it is on.
     */
    fun contentOf(blob: ByteArray): ByteArray = blob.copyOfRange(BLOB_HEADER, contentEnd(blob))

    private fun isStoredDeclaration(bytes: ByteArray): Boolean =
        bytes.size >= BLOB_HEADER && bytes[0] == BLOB_MAGIC_0 && bytes[1] == BLOB_MAGIC_1

    /**
     * Whether two stored forms are the same declaration, wherever and whenever
     * each was written. Bytes that are not a stored declaration at all are
     * compared whole.
     */
    fun sameContent(a: ByteArray, b: ByteArray): Boolean {
        if (!isStoredDeclaration(a) || !isStoredDeclaration(b)) return a.contentEquals(b)
        if (a[2] != BLOB_FORMAT || b[2] != BLOB_FORMAT) return a.contentEquals(b)
        val endA = contentEnd(a)
        val endB = contentEnd(b)
        if (endA != endB) return false
        for (i in BLOB_HEADER until endA) if (a[i] != b[i]) return false
        return true
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

    fun serializeTypedModule(module: com.mezze.language.typedast.TypedModule): ByteArray =
        // Types written once each (TypeSharing): a module's are mostly repeats.
        com.mezze.language.ast.TypeSharing.write { cbor.encodeToByteArray(module) }

    fun deserializeTypedModule(bytes: ByteArray): com.mezze.language.typedast.TypedModule =
        com.mezze.language.ast.TypeSharing.read { cbor.decodeFromByteArray(bytes) }

    fun serializeParsedModule(module: com.mezze.language.parsedast.ParsedModule): ByteArray {
        return cbor.encodeToByteArray(module)
    }

    fun deserializeParsedModule(bytes: ByteArray): com.mezze.language.parsedast.ParsedModule {
        return cbor.decodeFromByteArray(bytes)
    }

    fun serializeFunctionIR(fn: com.mezze.language.truffleir.FunctionIR): ByteArray =
        writeBlob(com.mezze.language.truffleir.FunctionIR.serializer(), fn)

    fun deserializeFunctionIR(bytes: ByteArray): com.mezze.language.truffleir.FunctionIR =
        readBlob(com.mezze.language.truffleir.FunctionIR.serializer(), bytes)

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

    fun serializeTypedLetDecl(decl: com.mezze.language.typedast.TypedLetDecl): ByteArray =
        writeBlob(com.mezze.language.typedast.TypedLetDecl.serializer(), decl)

    fun deserializeTypedLetDecl(bytes: ByteArray): com.mezze.language.typedast.TypedLetDecl =
        readBlob(com.mezze.language.typedast.TypedLetDecl.serializer(), bytes)

    fun serializeTypedTypeDecl(decl: com.mezze.language.typedast.TypedTypeDecl): ByteArray =
        writeBlob(com.mezze.language.typedast.TypedTypeDecl.serializer(), decl)

    fun deserializeTypedTypeDecl(bytes: ByteArray): com.mezze.language.typedast.TypedTypeDecl =
        readBlob(com.mezze.language.typedast.TypedTypeDecl.serializer(), bytes)

    fun serializeTypedAbilityDecl(decl: com.mezze.language.typedast.TypedAbilityDecl): ByteArray =
        writeBlob(com.mezze.language.typedast.TypedAbilityDecl.serializer(), decl)

    fun deserializeTypedAbilityDecl(bytes: ByteArray): com.mezze.language.typedast.TypedAbilityDecl =
        readBlob(com.mezze.language.typedast.TypedAbilityDecl.serializer(), bytes)

    fun serializeTypedAbilityImpl(impl: com.mezze.language.typedast.TypedAbilityImpl): ByteArray =
        writeBlob(com.mezze.language.typedast.TypedAbilityImpl.serializer(), impl)

    fun deserializeTypedAbilityImpl(bytes: ByteArray): com.mezze.language.typedast.TypedAbilityImpl =
        readBlob(com.mezze.language.typedast.TypedAbilityImpl.serializer(), bytes)

    fun serializeTypedEffectDecl(decl: com.mezze.language.typedast.TypedEffectDecl): ByteArray =
        writeBlob(com.mezze.language.typedast.TypedEffectDecl.serializer(), decl)

    fun deserializeTypedEffectDecl(bytes: ByteArray): com.mezze.language.typedast.TypedEffectDecl =
        readBlob(com.mezze.language.typedast.TypedEffectDecl.serializer(), bytes)

    fun serializeTypedPerformerDecl(decl: com.mezze.language.typedast.TypedPerformerDecl): ByteArray =
        writeBlob(com.mezze.language.typedast.TypedPerformerDecl.serializer(), decl)

    fun deserializeTypedPerformerDecl(bytes: ByteArray): com.mezze.language.typedast.TypedPerformerDecl =
        readBlob(com.mezze.language.typedast.TypedPerformerDecl.serializer(), bytes)
}
