package com.mezze.language.typechecker

import com.mezze.language.typechecker.module.ModuleImportContext
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedAbilityImpl
import com.mezze.language.truffleir.FunctionIR
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * On-disk snapshot of PrecompiledStdlib's runtime state, used to bootstrap
 * the stdlib in environments where SQLite isn't available — primarily the
 * native binary built by `mez build --native`. Built once at gradle time
 * by the `serializeStdlibSnapshot` task from PrecompiledStdlib's in-memory
 * caches; deserialized on every CLI / native binary boot via
 * [PrecompiledStdlib.tryLoadFromSnapshot].
 *
 * Everything that the existing SQLite-backed `FullCacheData` path provides
 * is captured here, in the same field order so the load site can swap
 * either source in.
 *
 * Versioned in case the on-disk format ever changes — bumping
 * [CURRENT_VERSION] invalidates older snapshots and falls back to the
 * slow path. Don't reuse old version numbers across format changes; the
 * loader explicitly refuses anything other than the current one.
 *
 * Performers are stored as `List<List<String>>` rather than
 * `List<Array<String>>` because kotlinx-serialization handles List
 * cleanly without a custom serializer; callers convert back to
 * `Array<String>` at read time.
 */
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
) {
    companion object {
        const val CURRENT_VERSION = 1
        const val RESOURCE_PATH = "META-INF/mezze/stdlib-snapshot.cbor"

        private val cbor: Cbor = Cbor {
            ignoreUnknownKeys = true
        }

        @OptIn(ExperimentalSerializationApi::class)
        fun encode(snapshot: StdlibSnapshot): ByteArray = cbor.encodeToByteArray(snapshot)

        @OptIn(ExperimentalSerializationApi::class)
        fun decode(bytes: ByteArray): StdlibSnapshot = cbor.decodeFromByteArray<StdlibSnapshot>(bytes)
    }
}
