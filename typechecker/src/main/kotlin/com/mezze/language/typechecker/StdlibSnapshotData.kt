package com.mezze.language.typechecker

import com.mezze.language.typechecker.module.ModuleImportContext
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedAbilityImpl
import com.mezze.language.truffleir.FunctionIR

/**
 * Build-time-initialized holder for the deserialized stdlib snapshot.
 *
 * Native-image is told to initialize this object at build time via
 *   --initialize-at-build-time=com.mezze.language.typechecker.StdlibSnapshotData
 *
 * The `init {}` block runs once during native-image's build JVM —
 * reads the CBOR resource, decodes it into the typed fields below, and
 * those fields land in the binary's `.data` section. At runtime the
 * binary just reads them as static fields (zero decode cost).
 *
 * Crucially this object contains ONLY pure data (Maps, Lists, Hash, IR
 * nodes). No file handles, no thread state, no paths captured from the
 * build CWD. That's a hard requirement for `--initialize-at-build-time`
 * — anything that references build-time state would either fail
 * native-image's heap validator or, worse, freeze a wrong value into
 * the binary.
 *
 * On the JVM (e.g. `mez run`, `mez dev`), the `init {}` still runs
 * normally — same cost as before, just relocated from PrecompiledStdlib
 * to here. JDK-mode users don't lose anything.
 *
 * `present` is false when the resource is missing — older builds, or
 * environments where stdlib is being recompiled from sources. Callers
 * (see PrecompiledStdlib.tryLoadFromSnapshotData) check that before
 * reading the other fields.
 */
object StdlibSnapshotData {
    val present: Boolean
    val importContext: ModuleImportContext?
    val functions: Map<Hash, FunctionIR>
    val performers: List<Array<String>>
    val dependencyGraph: Map<Hash, Set<Hash>>
    val abilityImpls: List<TypedAbilityImpl>
    val declHashes: Set<Hash>
    val moduleHashes: Set<Hash>

    init {
        // Read + decode the bundled CBOR resource. Wrapped in try/catch
        // because missing resources are a normal "older build" state —
        // we fall back to the SQLite path in PrecompiledStdlib.
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
            if (System.getenv("MEZZE_DEBUG") != null) {
                System.err.println(
                    "[StdlibSnapshotData] decode failed (${e.javaClass.simpleName}): ${e.message}"
                )
            }
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
        } else {
            present = false
            importContext = null
            functions = emptyMap()
            performers = emptyList()
            dependencyGraph = emptyMap()
            abilityImpls = emptyList()
            declHashes = emptySet()
            moduleHashes = emptySet()
        }
    }
}
