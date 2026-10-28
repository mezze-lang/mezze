package com.mezze.language.typechecker

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Thread pools that degrade to inline execution where platform threads are not
 * available — the single-threaded GraalVM web image (the WASM playground), where
 * `Thread.start()` throws `IllegalThreadStateException`.
 *
 * The typechecker starts threads in several places (parse prefetch, the checked-
 * module writer, the parallel module checker). Each one crashes the browser the
 * moment it is reached. Routing them all through [newPool] means they get a real
 * pool on the JVM/LSP and a same-thread executor in the browser, with no change
 * to the call sites' logic — a submitted task simply runs synchronously there.
 *
 * The single-threaded host is identified by a system property the WASM entry
 * sets ([platformThreadsAvailable]), not by a runtime probe: the web image lets a
 * small probe thread start yet still throws on the real pool's large-stack
 * worker, so a probe lies. Parsing/checking sequentially there costs nothing.
 */
object MezzeExecutors {
    /** True everywhere a platform thread can be started — the JVM, native, the LSP.
     *  False only in the single-threaded WASM web image, where [MezzeWeb] sets
     *  `mezze.wasm.singleThreaded=true` at startup. A runtime probe is NOT used:
     *  the web image lets a small probe thread start yet throws
     *  IllegalThreadStateException on the real pool's large-stack worker, so the
     *  probe gives a false positive. The build knows what it is; the property is
     *  the honest signal. */
    val platformThreadsAvailable: Boolean
        get() = System.getProperty("mezze.wasm.singleThreaded") != "true"

    /** A fixed pool with daemon threads of [stackBytes], or a same-thread executor
     *  when platform threads cannot be started. */
    fun newPool(name: String, size: Int, stackBytes: Long): ExecutorService =
        if (platformThreadsAvailable)
            Executors.newFixedThreadPool(maxOf(1, size)) { r ->
                Thread(null, r, name, stackBytes).apply { isDaemon = true }
            }
        else
            SameThreadExecutor()

    /** Runs every submitted task in the calling thread. `submit {…}.get()` therefore
     *  returns the result synchronously; `execute {…}` runs before it returns. */
    private class SameThreadExecutor : AbstractExecutorService() {
        @Volatile private var stopped = false
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf() }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }
}
