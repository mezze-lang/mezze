package com.mezze.language.interpreter.stdlib.system

import com.mezze.language.interpreter.*
import com.mezze.language.interpreter.stdlib.effects.TimeImplementations
import com.mezze.language.interpreter.stdlib.effects.EnvironmentImplementations

/**
 * Truffle-optimized implementations for system effects.
 *
 * Phase 6 of Truffle migration: I/O and System operations optimized for JIT performance.
 * This file delegates to organized effect implementations.
 *
 * Delegates to:
 * - TimeImplementations: All time operations (18 functions)
 * - EnvironmentImplementations: All environment operations (12 functions)
 *
 * Note: JSON operations have been moved to JsonImplementations.kt in the codec package,
 * which provides full JSON parsing using kotlinx.serialization.
 */
object TruffleSystemImplementations {

    /**
     * Register all system effect implementations
     */
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        // Delegate to effects implementations
        TimeImplementations.registerAll(context)
        EnvironmentImplementations.registerAll(context)

        // JSON operations are now in JsonImplementations.kt (codec package)
        // which provides proper JSON parsing/serialization using kotlinx.serialization
    }
}
