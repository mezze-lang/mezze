package com.mezze.language.store

import java.io.File

/**
 * Single source of truth for the on-disk layout of a Mezze project's
 * tooling metadata directory.
 *
 * Every consumer that reads or writes anywhere under `.mez/` MUST route
 * through here. Hard-coded path literals in callers are bugs: they bypass
 * the [CACHE_DIR_ENV] override and they decouple the layout from this
 * file, so future restructures end up missing call sites.
 *
 * ## Layout
 *
 * ```
 * <projectRoot>/.mez/
 *   store/
 *     src.sqlite        user-authored code (apps, libs, internal modules)
 *     stdlib.sqlite     bundled stdlib content, seeded per project (read-only)
 *     deps.sqlite       external deps via `mez sync` (read-only)
 *   native-shell/       cached native-image shells keyed by classpath hash
 *   run/                CBOR scratch passed from native mez to JVM child
 *   profile/            GraalVM profiler / coverage / allocation reports
 * ```
 *
 * ## Override
 *
 * Setting [CACHE_DIR_ENV] (`MEZZE_CACHE_DIR`) redirects the entire `.mez/`
 * tree to that path. Useful for CI shared mounts, Docker volumes, or
 * sandboxing tests so they don't fight over the same per-project files.
 *
 * ## Why `:store` lives this low
 *
 * The path resolvers are needed by `:interpreter` (RuntimeServer.profile
 * output), `:cli` (WireRunner.run scratch + NativeBuildPath shell cache)
 * and `:store-sqlite` (SQLiteMezzeStore.openProject). `:store` is the
 * only module all three depend on without creating cycles.
 */
object ProjectLayout {

    // ───── env override ─────

    /** When set + non-blank, redirects `.mez/` to that absolute path. */
    const val CACHE_DIR_ENV = "MEZZE_CACHE_DIR"

    // ───── top-level layout ─────

    /** The per-project metadata dir. Sits next to `Mezze.toml`. */
    const val PROJECT_DIR = ".mez"

    // ───── subdirs under PROJECT_DIR ─────

    /** Subdir holding the three SQLite content stores. */
    const val STORE_DIR = "store"

    /**
     * Subdir holding cached native-image shells (one entry per
     * `(runtime jar + polyglot deps + optimized flag)` triple). Each
     * shell is a fully-built native binary with the runtime + stdlib
     * snapshot baked in but no user app — user app CBOR is appended at
     * build time.
     */
    const val NATIVE_SHELL_DIR = "native-shell"

    /**
     * Subdir holding CBOR scratch files the native `mez` writes for the
     * JVM child (RuntimeServer) to consume per invocation. Overwritten
     * every `mez dev` / `mez exec` run.
     */
    const val RUN_DIR = "run"

    /**
     * Subdir holding GraalVM profiler / coverage / allocation output.
     * Created on demand by RuntimeServer when the user passes
     * `--profile` / `--coverage` / `--allocations` flags.
     */
    const val PROFILE_DIR = "profile"

    // ───── files under STORE_DIR ─────

    /** User-authored content store. ATTACHed as SQLite's `main`. */
    const val SRC_FILE = "src.sqlite"

    /** Bundled stdlib content store. ATTACHed as `stdlib`. */
    const val STDLIB_FILE = "stdlib.sqlite"

    /** External-deps content store, populated by `mez sync`. ATTACHed as `deps`. */
    const val DEPS_FILE = "deps.sqlite"

    // ───── resolvers ─────

    /**
     * Resolve `<projectRoot>/.mez/`, honoring [CACHE_DIR_ENV]. Used by
     * every other resolver here — do not bypass it.
     */
    fun projectDir(projectRoot: File): File {
        val override = System.getenv(CACHE_DIR_ENV)?.takeIf { it.isNotBlank() }
        return if (override != null) File(override) else File(projectRoot, PROJECT_DIR)
    }

    fun storeDir(projectRoot: File): File =
        File(projectDir(projectRoot), STORE_DIR)

    fun srcFile(projectRoot: File): File =
        File(storeDir(projectRoot), SRC_FILE)

    fun stdlibFile(projectRoot: File): File =
        File(storeDir(projectRoot), STDLIB_FILE)

    fun depsFile(projectRoot: File): File =
        File(storeDir(projectRoot), DEPS_FILE)

    fun nativeShellDir(projectRoot: File): File =
        File(projectDir(projectRoot), NATIVE_SHELL_DIR)

    fun runDir(projectRoot: File): File =
        File(projectDir(projectRoot), RUN_DIR)

    fun profileDir(projectRoot: File): File =
        File(projectDir(projectRoot), PROFILE_DIR)
}
