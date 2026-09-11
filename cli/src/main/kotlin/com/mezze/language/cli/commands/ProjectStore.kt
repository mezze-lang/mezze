package com.mezze.language.cli.commands

import com.mezze.language.cli.BuildInfo
import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.codebase.StoreHydration
import java.io.File

/**
 * Open a project's `.mez/store` for a compile, first invalidating any
 * typecheck/IR cache that a **different** mez binary wrote.
 *
 * The per-project store caches typed modules + lowered IR keyed by content
 * hash. Those hashes — and the IR shape behind them — are only meaningful for
 * the compiler that produced them. With versioned toolchains this matters: the
 * proxy dispatches by the project's pin, so `mez check` / `exec` / `dev` /
 * `build` can each run under a *different* compiler over a project's life, and a
 * newer toolchain may lower the same source to different IR. Reusing the old
 * entries would type-check or run against stale IR (historically:
 * `expected MezzeFunction got null` at runtime).
 *
 * [StoreHydration.invalidateOnVersionShift] wipes the typecheck-derived tables
 * when the store's recorded `mez_version` differs from this binary's identity
 * (VERSION+GIT_SHA). Routing every compile entry point through here means one
 * guard covers them all, instead of each command remembering to call it (only
 * `build` did — see #44). It is a cheap metadata read on the common path (same
 * version → no-op).
 */
object ProjectStore {

    /** VERSION+GIT_SHA — the binary-identity stamp the cache is keyed against. */
    val binaryIdentity: String get() = "${BuildInfo.VERSION}+${BuildInfo.GIT_SHA}"

    /** Open [projectRoot]'s store, wiping cache left by another mez version. */
    fun open(projectRoot: File): SQLiteMezzeStore {
        val store = SQLiteMezzeStore.openProject(projectRoot)
        store.transaction {
            StoreHydration.invalidateOnVersionShift(store, binaryIdentity)
        }
        return store
    }
}
