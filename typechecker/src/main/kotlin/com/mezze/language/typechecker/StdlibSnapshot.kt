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
