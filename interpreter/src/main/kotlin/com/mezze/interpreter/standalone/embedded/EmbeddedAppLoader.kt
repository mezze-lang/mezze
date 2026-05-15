package com.mezze.interpreter.standalone.embedded

import com.mezze.language.store.StoreSerialization

/**
 * Build-time-only loader: native-image's `--initialize-at-build-time`
 * flag for this class causes its `init {}` block to run **in the build
 * JVM**, not at runtime. The deserialized object graph the init produces
 * is then snapshotted into the image heap by native-image. At runtime,
 * the executable just reads [EmbeddedApp]'s static fields as
 * already-initialized values — no resource decode, no IO.
 *
 * The CBOR resource (`META-INF/mezze/embedded-app.cbor`) only needs to
 * exist on the build classpath, not in the final binary; we don't pass
 * `-H:IncludeResources` for it, so it's discarded after build init.
 *
 * For the JVM jar build (no embedded resource present), the init is a
 * no-op and [EmbeddedApp.present] stays false — the CLI's StandaloneMain
 * falls through to the SQLite path.
 */
object EmbeddedAppLoader {
    init {
        loadIfPresent()
    }

    private fun loadIfPresent() {
        try {
            val cl = javaClass.classLoader
            val cborStream = cl.getResourceAsStream("META-INF/mezze/embedded-app.cbor")
                ?: return  // jar mode: no embedded data, nothing to do
            val descStream = cl.getResourceAsStream("META-INF/mezze/embedded-app.meta")
                ?: return  // resource present but descriptor missing — abort

            val fnBytes = cborStream.readBytes()
            cborStream.close()
            val descBytes = descStream.readBytes()
            descStream.close()

            EmbeddedApp.functions = StoreSerialization.deserializeFunctionIRList(fnBytes)

            // Descriptor format (CBOR map): {
            //   "appName": String,
            //   "entryName": String?,
            //   "performers": List<List<String>>
            // }
            val desc = StoreSerialization.deserializeEmbeddedAppMeta(descBytes)
            EmbeddedApp.appName = desc.appName
            EmbeddedApp.entryName = desc.entryName
            EmbeddedApp.performerRecords = desc.performerRecords

            EmbeddedApp.present = true
        } catch (e: Throwable) {
            // Build-time init failures should be loud — we don't want to
            // ship a binary that silently fell back to SQLite when the
            // user asked for native-embedded. Re-throw so native-image
            // aborts with a clear error.
            throw RuntimeException(
                "EmbeddedAppLoader: failed to load embedded app at build time: ${e.message}", e
            )
        }
    }

    /**
     * Public no-op entry to force class loading. Native-image's reachability
     * analysis needs SOMETHING to reference this class for `init {}` to
     * run; otherwise the class would be considered dead code and stripped.
     * [NativeMain] calls [touch] from its `main` so the linkage holds.
     */
    @JvmStatic
    fun touch() { /* init has already run by the time we get here */ }
}
