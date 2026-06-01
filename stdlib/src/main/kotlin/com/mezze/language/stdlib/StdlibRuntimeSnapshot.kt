package com.mezze.language.stdlib

import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.typedast.Hash
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * Runtime-only slice of the stdlib snapshot.
 *
 * This is intentionally a *subset* of the full StdlibSnapshot that lives
 * in :typechecker. The full one carries compile-time fields
 * (ModuleImportContext, abilityImpls, declHashes, …) that drag in the
 * entire `:typechecker/module/` graph during CBOR decode. The runtime
 * never reads those — so we ship them in a separate file consumed only
 * by the compile pipeline.
 *
 * Field set is the minimum needed by :interpreter at execution time:
 *   - `functions`: hash → FunctionIR for stdlib bodies
 *   - `performers`: stdlib's pre-built performer records (handler chain init)
 *   - `dependencyGraph`: hash → set of referenced hashes (transitive
 *     closure walks)
 *
 * The full StdlibSnapshot still exists alongside this one in builds
 * where the compile pipeline runs. User-app binaries only ever ship the
 * runtime slice.
 */
@Serializable
data class StdlibRuntimeSnapshot(
    val version: Int = CURRENT_VERSION,
    val functions: Map<Hash, FunctionIR>,
    val performers: List<List<String>>,
    val dependencyGraph: Map<Hash, Set<Hash>>,
) {
    companion object {
        const val CURRENT_VERSION = 1
        const val RESOURCE_PATH = "META-INF/mezze/stdlib-runtime.cbor"

        @OptIn(ExperimentalSerializationApi::class)
        private val cbor: Cbor = Cbor {
            ignoreUnknownKeys = true
        }

        @OptIn(ExperimentalSerializationApi::class)
        fun encode(snapshot: StdlibRuntimeSnapshot): ByteArray = cbor.encodeToByteArray(snapshot)

        @OptIn(ExperimentalSerializationApi::class)
        fun decode(bytes: ByteArray): StdlibRuntimeSnapshot = cbor.decodeFromByteArray<StdlibRuntimeSnapshot>(bytes)
    }
}
