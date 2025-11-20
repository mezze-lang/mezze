package com.funk.language.interpreter.stdlib.io

import com.funk.language.interpreter.*
import com.funk.language.interpreter.stdlib.effects.LogImplementations
import com.funk.language.interpreter.stdlib.effects.FilesystemImplementations

/**
 * Truffle runtime implementations for IO abilities
 *
 * This file now delegates to the organized effects implementations:
 * - LogImplementations: All logging operations
 * - FilesystemImplementations: All filesystem operations
 */
object TruffleIOImplementations {

    /**
     * Register all IO runtime implementations
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        // Delegate to effects implementations
        LogImplementations.registerAll(context)
        FilesystemImplementations.registerAll(context)
    }
}
