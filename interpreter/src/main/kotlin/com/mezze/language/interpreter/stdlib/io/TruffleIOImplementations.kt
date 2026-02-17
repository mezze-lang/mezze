package com.mezze.language.interpreter.stdlib.io

import com.mezze.language.interpreter.*
import com.mezze.language.interpreter.stdlib.effects.LogImplementations
import com.mezze.language.interpreter.stdlib.effects.FilesystemImplementations

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
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        // Delegate to effects implementations
        LogImplementations.registerAll(context)
        FilesystemImplementations.registerAll(context)
    }
}
