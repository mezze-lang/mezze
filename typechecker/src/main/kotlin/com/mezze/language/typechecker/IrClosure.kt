package com.mezze.language.typechecker

import com.mezze.language.stdlib.FunctionIRReachability
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.typedast.Hash

/**
 * The functions a runtime must be handed to run [seed]: [seed] and everything its
 * IR reaches.
 *
 * A stdlib function is a leaf — the runtime embeds the stdlib and resolves its
 * own calls — so it is carried but not walked. A cached dependency's function is
 * not embedded, so it is carried AND walked.
 */
object IrClosure {
    fun reachable(
        seed: List<FunctionIR>,
        fromStore: ((Hash) -> FunctionIR?)? = null,
    ): List<FunctionIR> {
        val stdlibFns = PrecompiledStdlib.preloweredFunctions
        val closureMap = HashMap<Hash, FunctionIR>()
        for (fn in seed) closureMap[fn.hash] = fn

        val queue = ArrayDeque(FunctionIRReachability.collectReferencedHashes(seed))
        while (queue.isNotEmpty()) {
            val hash = queue.removeFirst()
            if (hash in closureMap) continue
            val stdFn = stdlibFns[hash]
            if (stdFn != null) {
                closureMap[hash] = stdFn
                continue
            }
            val depFn = PrecompiledDeps.preloweredFunctions[hash] ?: fromStore?.invoke(hash) ?: continue
            closureMap[hash] = depFn
            for (h in FunctionIRReachability.collectReferencedHashes(listOf(depFn))) {
                if (h !in closureMap) queue.add(h)
            }
        }
        return closureMap.values.sortedBy { it.hash.hex }
    }
}
