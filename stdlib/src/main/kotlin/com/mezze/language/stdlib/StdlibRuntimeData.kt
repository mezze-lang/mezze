package com.mezze.language.stdlib

import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.typedast.Hash

/**
 * Runtime-only view of the precompiled stdlib.
 *
 * Lives in :stdlib (depends only on :ast) so :interpreter can read it
 * without dragging :typechecker onto the runtime classpath.
 *
 * # Loading
 *
 * `StdlibRuntimeData` is populated in one of two ways:
 *
 * 1. **Self-load from the CBOR snapshot.** Triggered by the `init {}`
 *    block on first class access. Reads
 *    `META-INF/mezze/stdlib-runtime.cbor` (or the legacy
 *    `stdlib-snapshot.cbor` for older bundles) and decodes only the
 *    runtime-relevant fields. This is the path user binaries take
 *    (native + JVM jar) and the warm-cache path for the CLI.
 *
 * 2. **Push from PrecompiledStdlib.** When :typechecker runs the full
 *    compile pipeline (no snapshot available — first `mez init`,
 *    snapshot rebuild, stdlib dev work), it calls [install] after
 *    computing the data. This is how non-snapshot CLI/LSP runs
 *    populate the runtime view.
 *
 * Native-image: this object is initialized at build time via
 *   --initialize-at-build-time=com.mezze.language.stdlib.StdlibRuntimeData
 * so the snapshot decode happens during AOT and the maps land in the
 * binary's `.data` section. Zero runtime decode cost.
 */
object StdlibRuntimeData {

    private const val RESOURCE_PATH = "META-INF/mezze/stdlib-runtime.cbor"
    private const val LEGACY_RESOURCE_PATH = "META-INF/mezze/stdlib-snapshot.cbor"

    @Volatile private var _preloweredFunctions: Map<Hash, FunctionIR> = emptyMap()
    @Volatile private var _preloweredPerformerRecords: List<Array<String>> = emptyList()
    @Volatile private var _dependencyGraph: Map<Hash, Set<Hash>> = emptyMap()
    @Volatile private var _present: Boolean = false

    init {
        tryLoadFromResource()
    }

    val preloweredFunctions: Map<Hash, FunctionIR>
        get() = _preloweredFunctions

    val preloweredPerformerRecords: List<Array<String>>
        get() = _preloweredPerformerRecords

    val dependencyGraph: Map<Hash, Set<Hash>>
        get() = _dependencyGraph

    /** True once data is available (either from snapshot or install()). */
    val present: Boolean
        get() = _present

    /**
     * Push runtime data into this object.
     *
     * Called by :typechecker's PrecompiledStdlib when it compiles the
     * stdlib from sources (no snapshot path). Overwrites any prior load
     * — last writer wins, which matches the existing PrecompiledStdlib
     * semantics (one process = one stdlib snapshot).
     */
    @JvmStatic
    fun install(
        preloweredFunctions: Map<Hash, FunctionIR>,
        preloweredPerformerRecords: List<Array<String>>,
        dependencyGraph: Map<Hash, Set<Hash>>,
    ) {
        _preloweredFunctions = preloweredFunctions
        _preloweredPerformerRecords = preloweredPerformerRecords
        _dependencyGraph = dependencyGraph
        _present = true
    }

    private fun tryLoadFromResource() {
        try {
            val cl = StdlibRuntimeData::class.java.classLoader
            val stream = cl.getResourceAsStream(RESOURCE_PATH)
                ?: cl.getResourceAsStream(LEGACY_RESOURCE_PATH)
                ?: return  // No snapshot bundled — caller will install() later.
            val bytes = stream.use { it.readBytes() }
            val snapshot = StdlibRuntimeSnapshot.decode(bytes)
            if (snapshot.version != StdlibRuntimeSnapshot.CURRENT_VERSION) return
            _preloweredFunctions = snapshot.functions
            _preloweredPerformerRecords = snapshot.performers.map { it.toTypedArray() }
            _dependencyGraph = snapshot.dependencyGraph
            _present = true
        } catch (e: Exception) {
            System.err.println(
                "[mezze] stdlib runtime snapshot decode failed (${e.javaClass.simpleName}): " +
                "${e.message ?: "<no message>"} — falling back to source compile " +
                "(slower boot). Set MEZZE_DEBUG=1 for full stacktrace."
            )
            if (System.getenv("MEZZE_DEBUG") != null) {
                e.printStackTrace(System.err)
            }
        }
    }
}
