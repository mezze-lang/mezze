package com.mezze.language.cli.commands

import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.store.StoreSerialization
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.typechecker.PrecompiledDeps
import com.mezze.language.typedast.Hash
import java.io.File

/**
 * Consumer read-side of the #46 compile-once memo. Before a compile, load every
 * dependency's prelowered IR from its shared `compiled-<compiler>.sqlite` store
 * into [PrecompiledDeps], so the typechecker serves that IR instead of
 * re-lowering the dep (see TypedModuleEmitter + computeReachableClosure).
 *
 * Walks the whole dependency tree: a project's `.deps/mezze/<name>-<ver>`
 * symlinks point at each dep's real dir (a shared content-store entry or a path
 * dep), and each dep's OWN `.deps/mezze` holds its transitive deps. Dedup is by
 * canonical path, so a diamond's shared content-store entry loads once.
 *
 * Purely additive: a dep with no compiled store (or an unreadable one) is simply
 * skipped, and the consumer compiles it from source. Because the stored IR is
 * content-hash-keyed, serving it can only ever match what a from-source compile
 * would produce (proven by scripts/pkg-reuse-e2e.sh).
 */
object DepStoreLoader {

    /** Populate [PrecompiledDeps] from [projectRoot]'s dep tree. Clears it if none. */
    fun load(projectRoot: File) {
        val merged = HashMap<Hash, FunctionIR>()
        val seen = HashSet<String>()
        val queue = ArrayDeque<File>()
        depDirsOf(projectRoot).forEach { queue.add(it) }

        while (queue.isNotEmpty()) {
            val depDir = queue.removeFirst()
            val canonical = runCatching { depDir.canonicalPath }.getOrDefault(depDir.path)
            if (!seen.add(canonical)) continue
            loadStore(DepPrecompiler.compiledStoreFile(depDir), merged)
            depDirsOf(depDir).forEach { queue.add(it) }
        }

        if (merged.isEmpty()) PrecompiledDeps.clear() else PrecompiledDeps.set(merged)
    }

    /** The dep dirs a package links under its own `.deps/mezze` (symlinks resolved). */
    private fun depDirsOf(pkgDir: File): List<File> {
        val mezze = File(pkgDir, ".deps/mezze")
        if (!mezze.isDirectory) return emptyList()
        return mezze.listFiles()?.filter { it.isDirectory }?.map { it.canonicalFile } ?: emptyList()
    }

    /** Merge every IR blob in [storeFile] into [into]. Best-effort. */
    private fun loadStore(storeFile: File, into: MutableMap<Hash, FunctionIR>) {
        if (!storeFile.isFile) return
        runCatching {
            val store = SQLiteMezzeStore.forFile(storeFile)
            try {
                for (hash in store.getAllBlobHashes()) {
                    val ir = store.getIR(hash) ?: continue
                    into[hash] = StoreSerialization.deserializeFunctionIR(ir)
                }
            } finally {
                store.close()
            }
        }
    }
}
