package com.mezze.language.typechecker

import com.mezze.language.typechecker.module.ModuleImportContext
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedAbilityImpl
import com.mezze.language.typedast.TypedModule
import com.mezze.language.truffleir.FunctionIR
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToByteArray

/** Thrown when a snapshot's embedded schema fingerprint doesn't match the running
 *  code's — the snapshot was written by a build whose serializable types differ (a
 *  MezzeType/IR class added, removed, or reshaped). The caller recompiles from
 *  source; this is a clean, named signal, not a cryptic decode crash. */
class SchemaSkewException(snapshotFp: String, runtimeFp: String) : RuntimeException(
    "stdlib snapshot built for a different type schema (snapshot=$snapshotFp, runtime=$runtimeFp)")

@Serializable
data class StdlibSnapshot(
    val version: Int = CURRENT_VERSION,
    val importContext: ModuleImportContext,
    val functions: Map<Hash, FunctionIR>,
    val performers: List<List<String>>,
    val dependencyGraph: Map<Hash, Set<Hash>>,
    val abilityImpls: List<TypedAbilityImpl>,
    val declHashes: Set<Hash>,
    val moduleHashes: Set<Hash>,
    // #415: mono's declIndex needs stdlib TypedModules to specialize any
    // callsite that flows through a stdlib polymorphic decl (fold, map, …).
    // Optional so pre-v2 snapshots keep decoding via ignoreUnknownKeys.
    val typedModules: Map<Hash, TypedModule> = emptyMap(),
    /** Every stdlib ability, effect and public type by identity — see [StdlibIdentities]. */
    val identities: StdlibIdentityTable? = null,
    /**
     * Every stdlib module as an importer reads it. Loaded into the checker's
     * cache at start, so `use std::…` finds the module checked instead of
     * checking it again from source in every process.
     */
    val modules: List<com.mezze.language.typechecker.module.CachedModule> = emptyList(),
    /**
     * Which stdlib functions take dictionary parameters, by hash. Derived from
     * [typedModules] by a prescan that took 100 ms at every start; derived once,
     * here, instead.
     */
    val dictMethods: Map<Hash, Set<String>> = emptyMap(),
    /**
     * Every stdlib module as STAMPED — the parse with its identities, before the
     * checker transforms it. Served to the resolver as is, so a run never reads,
     * parses or stamps stdlib source: that was 125 ms of every `mez check`.
     */
    val stamped: List<StampedModule> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
        const val RESOURCE_PATH = "META-INF/mezze/stdlib-snapshot.cbor"

        private val cbor: Cbor = Cbor {
            ignoreUnknownKeys = true
        }

        /**
         * A deterministic hash of the SERIALIZATION SCHEMA reachable from
         * [StdlibSnapshot] — every `@Serializable` class's serial name, fields, and
         * (for sealed types like `MezzeType`) subclass set. `CURRENT_VERSION` is a
         * manual bump that silently missed structural changes — e.g. removing
         * `MezzeType.BoolType` kept version 1, so a stale snapshot decoded until it
         * hit the missing subclass and threw a cryptic `SerializationException`. This
         * fingerprint changes automatically with the schema, so skew is DETECTED.
         */
        val schemaFingerprint: String by lazy {
            // Types are written shared (TypeSharing): the descriptors below do not
            // show it, so the format says so itself.
            val sb = StringBuilder("types:shared-1;")
            val seen = HashSet<String>()
            fun walk(d: SerialDescriptor) {
                if (!seen.add(d.serialName)) return
                sb.append(d.serialName).append(':').append(d.kind).append('(')
                for (i in 0 until d.elementsCount) {
                    sb.append(d.getElementName(i)).append(',')
                    runCatching { walk(d.getElementDescriptor(i)) }
                }
                sb.append(')')
            }
            walk(serializer().descriptor)
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(sb.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        /** Sibling resource holding [schemaFingerprint] of the build that wrote the
         *  snapshot. Checked BEFORE decode so schema skew is caught without changing
         *  the cbor byte layout (other readers — e.g. the runtime's legacy fallback —
         *  decode the same cbor raw and must stay byte-compatible). */
        const val FINGERPRINT_RESOURCE_PATH = "META-INF/mezze/stdlib-snapshot.fp"

        /** True iff [storedFingerprint] is present AND differs from THIS build's
         *  [schemaFingerprint] — i.e. the snapshot was built against a different type
         *  schema and must be rejected. A null/absent fingerprint (older bundle) is
         *  NOT skew: proceed and let decode/version handle it (backward-compatible). */
        fun isSchemaSkew(storedFingerprint: String?): Boolean =
            storedFingerprint != null && storedFingerprint != schemaFingerprint

        @OptIn(ExperimentalSerializationApi::class)
        fun encode(snapshot: StdlibSnapshot): ByteArray =
            com.mezze.language.ast.TypeSharing.write { cbor.encodeToByteArray(snapshot) }

        @OptIn(ExperimentalSerializationApi::class)
        fun decode(bytes: ByteArray): StdlibSnapshot =
            com.mezze.language.ast.TypeSharing.read { cbor.decodeFromByteArray<StdlibSnapshot>(bytes) }
    }
}


/** A stdlib module as the resolver stamped it, for [StdlibSnapshot.stamped]. */
@Serializable
data class StampedModule(
    val modulePath: String,
    val filePath: String,
    val sourceHash: Hash?,
    val module: com.mezze.language.parsedast.ParsedModule,
)
