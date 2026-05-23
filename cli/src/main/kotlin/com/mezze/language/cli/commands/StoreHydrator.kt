package com.mezze.language.cli.commands

import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.store.StoreSerialization

/**
 * Shared helper for `mez dev` / `mez build` / `mez check`: pre-populates
 * `ModuleTypeChecker.globalCache` from the project's `cache.sqlite` so
 * subsequent typecheck passes only re-typecheck modules whose source
 * actually changed.
 *
 * Without this, every typecheck walks every imported module from
 * scratch — wasted work when the typed result already lives in store.
 *
 * Two layers:
 *   - stdlib: served from PrecompiledStdlib (no SQLite round-trip).
 *   - user modules: walked from store, restricted to the entry's
 *     transitive import closure (modules outside the closure load on
 *     demand if they ever get referenced).
 */
object StoreHydrator {

    /**
     * Pump cached typed modules into ModuleTypeChecker's cache.
     * Idempotent and cheap (sub-100 ms typical) — safe to call on every
     * `mez build` / `mez check`.
     */
    fun hydrate(store: SQLiteMezzeStore, entryModulePath: String, timing: Boolean = false) {
        val stdlibStart = System.nanoTime()
        var stdlibHydrated = 0
        for (v2Module in com.mezze.language.typechecker.PrecompiledStdlib.stdlibTypedModules.values) {
            com.mezze.language.typechecker.module.ModuleTypeChecker
                .hydrateFromV2Module(v2Module.path, v2Module)
            stdlibHydrated++
        }
        val stdlibMs = (System.nanoTime() - stdlibStart) / 1_000_000

        val closure = com.mezze.language.codebase.StoreHydration
            .computeImportClosure(store, entryModulePath)
        val stats = com.mezze.language.codebase.StoreHydration.hydrateTypedModules(
            store,
            hydrate = { modulePath, blob ->
                try {
                    val v2Module = StoreSerialization.deserializeTypedModule(blob)
                    com.mezze.language.typechecker.module.ModuleTypeChecker
                        .hydrateFromV2Module(modulePath, v2Module)
                    true
                } catch (_: Exception) {
                    false
                }
            },
            include = { mod ->
                !mod.path.startsWith("std::") && mod.path in closure
            }
        )
        if (timing) {
            System.err.println("[${stdlibMs}ms] hydrated $stdlibHydrated stdlib modules from PrecompiledStdlib")
            System.err.println("[${stats.elapsedMs}ms] hydrated user modules: ${stats.hits} hit, ${stats.misses} miss (closure=${closure.size})")
        }
    }
}
