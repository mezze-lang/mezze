package com.mezze.language.typechecker

import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.typedast.Hash

/**
 * Prelowered IR for the project's dependencies — the consumer read-side of the
 * #46 compile-once memo. Populated by the CLI from each dep's shared
 * `compiled-<compiler>.sqlite` store before a compile, then read by
 * [com.mezze.language.typechecker.phase.compileModule] exactly the way
 * [PrecompiledStdlib] is: a dep function whose hash is here is treated as an
 * already-lowered leaf, so the consumer serves its IR instead of re-lowering it.
 *
 * Empty by default → identical to the old behavior (deps compiled from source).
 * A miss (hash absent) also falls back to source, so this can only ever save
 * work, never change results — the stored IR is content-hash-keyed, so it is by
 * construction what a from-source compile would have produced (see
 * scripts/pkg-reuse-e2e.sh).
 *
 * Process-global mutable state, mirroring [PrecompiledStdlib]. The CLI [set]s it
 * once before compiling and [clear]s it when there are no cached deps.
 */
object PrecompiledDeps {

    @Volatile
    var preloweredFunctions: Map<Hash, FunctionIR> = emptyMap()
        private set

    /** Hashes of the dep decls served from [preloweredFunctions] (the leaf-set). */
    val declHashes: Set<Hash> get() = preloweredFunctions.keys

    fun set(functions: Map<Hash, FunctionIR>) {
        preloweredFunctions = functions
    }

    fun clear() {
        preloweredFunctions = emptyMap()
    }
}
