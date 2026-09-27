package com.mezze.language.typechecker

import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.module.ModuleImportContext
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedAbilityImpl
import com.mezze.language.typedast.TypedModule
import com.mezze.language.truffleir.FunctionIR

object StdlibSnapshotData {
    val present: Boolean
    val importContext: ModuleImportContext?
    val functions: Map<Hash, FunctionIR>
    val performers: List<Array<String>>
    val dependencyGraph: Map<Hash, Set<Hash>>
    val abilityImpls: List<TypedAbilityImpl>
    val declHashes: Set<Hash>
    val moduleHashes: Set<Hash>
    val typedModules: Map<Hash, TypedModule>

    init {
        //     stacktrace stays gated. See task #203 + #201.
        val decoded: StdlibSnapshot? = try {
            val cl = StdlibSnapshotData::class.java.classLoader
            val stream = cl.getResourceAsStream(StdlibSnapshot.RESOURCE_PATH)
            if (stream == null) {
                null
            } else {
                val bytes = stream.use { it.readBytes() }
                val snapshot = StdlibSnapshot.decode(bytes)
                if (snapshot.version != StdlibSnapshot.CURRENT_VERSION) null else snapshot
            }
        } catch (e: Exception) {
            System.err.println(
                "[mezze] stdlib snapshot decode failed (${e.javaClass.simpleName}): " +
                "${e.message ?: "<no message>"} — falling back to source/SQLite compile " +
                "(slower boot). Set MEZZE_TYPECHECK_DEBUG=true for full stacktrace."
            )
            DebugLogger.debug(DebugLogger.Category.Stdlib) { e.stackTraceToString() }
            null
        }

        if (decoded != null) {
            present = true
            importContext = decoded.importContext
            functions = decoded.functions
            performers = decoded.performers.map { it.toTypedArray() }
            dependencyGraph = decoded.dependencyGraph
            abilityImpls = decoded.abilityImpls
            declHashes = decoded.declHashes
            moduleHashes = decoded.moduleHashes
            typedModules = decoded.typedModules
        } else {
            present = false
            importContext = null
            functions = emptyMap()
            performers = emptyList()
            dependencyGraph = emptyMap()
            abilityImpls = emptyList()
            declHashes = emptySet()
            moduleHashes = emptySet()
            typedModules = emptyMap()
        }
    }
}
