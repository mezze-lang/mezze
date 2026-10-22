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
    val identities: StdlibIdentityTable?
    val modules: List<com.mezze.language.typechecker.module.CachedModule>
    val dictMethods: Map<com.mezze.language.typedast.Hash, Set<String>>
    /** [StdlibSnapshot.stamped], by module path. */
    val stamped: Map<String, StampedModule>

    init {
        //     stacktrace stays gated. See task #203 + #201.
        val decoded: StdlibSnapshot? = try {
            val cl = StdlibSnapshotData::class.java.classLoader
            val stream = cl.getResourceAsStream(StdlibSnapshot.RESOURCE_PATH)
            if (stream == null) {
                null
            } else {
                // Schema-skew guard: the sibling fingerprint records the type schema
                // the snapshot was BUILT against. If it differs from THIS build's, a
                // serializable class changed (added/removed/reshaped) — reject cleanly
                // BEFORE the cbor decode hits the mismatch and throws something cryptic
                // (what happened when MezzeType.BoolType was removed). Absent fingerprint
                // (older bundle) ⇒ proceed, staying backward-compatible.
                val storedFp = cl.getResourceAsStream(StdlibSnapshot.FINGERPRINT_RESOURCE_PATH)
                    ?.use { String(it.readBytes(), Charsets.UTF_8).trim() }
                if (StdlibSnapshot.isSchemaSkew(storedFp)) {
                    throw SchemaSkewException(storedFp!!, StdlibSnapshot.schemaFingerprint)
                }
                val bytes = stream.use { it.readBytes() }
                val snapshot = StdlibSnapshot.decode(bytes)
                if (snapshot.version != StdlibSnapshot.CURRENT_VERSION) null else snapshot
            }
        } catch (e: SchemaSkewException) {
            // EXPECTED, not corruption: the bundled snapshot was built against a
            // different type schema (a serializable class changed). Recompiling from
            // source is the correct, self-healing recovery; a clean rebuild
            // regenerates the snapshot. Named + loud so it is never mistaken for the
            // cryptic SerializationException it replaces.
            System.err.println(
                "[mezze] stdlib snapshot is STALE — ${e.message}. Recompiling from " +
                "source (slower boot); rebuild (installDist) regenerates the snapshot."
            )
            null
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
            identities = decoded.identities
            modules = decoded.modules
            dictMethods = decoded.dictMethods
            stamped = decoded.stamped.associateBy { it.modulePath }
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
            identities = null
            modules = emptyList()
            dictMethods = emptyMap()
            stamped = emptyMap()
        }
    }
}
