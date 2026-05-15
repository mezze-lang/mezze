package com.mezze.interpreter.standalone

import com.mezze.language.truffleir.FunctionIR

/**
 * In-image holder for an AOT-compiled Mezze app's FunctionIRs +
 * metadata. Populated at native-image build time by [EmbeddedAppLoader]'s
 * static initializer (which native-image runs in the build JVM, then
 * snapshots the resulting heap into the binary's `.data` section).
 *
 * Runtime semantics — when [present] is true, [StandaloneMain] reads
 * the fields directly and skips the SQLite path entirely. No JDBC, no
 * resource decode, no CBOR deserialize at runtime. The deserialized
 * object graph is already laid out in memory.
 *
 * For the JVM jar build (no `mez build --native`), [EmbeddedAppLoader]
 * never runs its build-time init, [present] stays false, and the
 * existing SQLite path handles everything as before.
 */
object EmbeddedApp {
    @JvmStatic
    @Volatile
    var present: Boolean = false

    @JvmStatic
    var functions: List<FunctionIR> = emptyList()

    @JvmStatic
    var performerRecords: List<Array<String>> = emptyList()

    @JvmStatic
    var entryName: String? = null

    @JvmStatic
    var appName: String = ""
}
