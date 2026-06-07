package com.mezze.interpreter.nodes.builtin.dsl

import com.mezze.language.truffleir.NativeSignatures
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Forces the registration init blocks of every `*Ops.kt` file to run.
 *
 * Kotlin top-level `val` initializers fire lazily when their class first
 * loads, which means [NativeSignatures] could appear empty if no one ever
 * touched `FloatOps`. The block below references each ops file once at
 * first call to [ensureLoaded], guaranteeing every op has been registered
 * before the IR-builder or interpreter queries either registry.
 *
 * Self-installs as the bootstrap hook on [NativeSignatures] at class
 * load. The typechecker calls `NativeSignatures.signatureOf(...)` which
 * triggers `ensureLoaded` exactly once, populating both registries.
 *
 * Idempotent and lock-free after the first call.
 */
object NativeBootstrap {
    private val loaded = AtomicBoolean(false)

    init {
        NativeSignatures.setBootstrap(Runnable { ensureLoaded() })
    }

    fun ensureLoaded() {
        if (loaded.compareAndSet(false, true)) {
            // Touching each Ops object's class forces top-level NativeOp
            // val initializers to run. Add a line per new *Ops.kt file.
            FloatOps.MARKER  // FloatOps.MARKER is `Unit`; only side-effect is class-load
        }
    }
}
