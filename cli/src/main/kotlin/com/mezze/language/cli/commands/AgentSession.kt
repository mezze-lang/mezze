package com.mezze.language.cli.commands

import com.mezze.language.store.MezzeStore
import com.mezze.language.typechecker.World
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What one apply produced, as data.
 *
 * `@Serializable`, so the JSON is DERIVED from this type rather than written by hand.
 * There are already four hand-rolled encoders in this module (see API-1) and a server
 * exposing any of this would be the fifth, free to drift from the other four. New
 * code does not add to that.
 *
 * Paths are strings, not `File`: this crosses a wire, and a `File` has no meaning on
 * the other side of one.
 */
@Serializable
data class AgentApplyResult(
    /** True when every module type-checked. Only then is anything written. */
    val ok: Boolean,
    /** Module path → its diagnostics. Every module appears, even a clean one. */
    val diagnostics: Map<String, List<String>>,
    /** Repo-relative paths written. Empty for a dry run and for a failed batch. */
    val written: List<String>,
    val dryRun: Boolean,
    /**
     * Whether the runtime was reloaded.
     *
     * Reported rather than inferred from [written] being non-empty: a session with no
     * reload configured writes files and reloads nothing, and a caller should not have
     * to guess which happened.
     */
    val reloaded: Boolean,
    val elapsedMillis: Long,
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)
}

/**
 * One agent's session against a project: warm, batched, machine-readable.
 *
 * The three properties the agentic case needs, and where each comes from:
 *
 *  - BATCHED. A [Changeset] is declared, not inferred from timing. One parse and
 *    type-check pass, one store write, ONE reload, one complete diagnostic set — where
 *    the file watcher would have produced several coalescing rounds for the same
 *    logical change.
 *  - WARM. The world and store are held for the session's lifetime, so the second
 *    request does not pay the first one's startup. Per-world caches are what make this
 *    safe for more than one caller at a time.
 *  - MACHINE-READABLE. [AgentApplyResult] is a serializable type, not a formatted
 *    string a caller would have to parse back.
 *
 * ## Exactly one reload
 *
 * A committed changeset writes its files, and `SelfWrites` stops the watcher reacting
 * to those writes — so nothing reloads unless this asks for it. That is deliberate:
 * the reload becomes an explicit step that happens once, instead of an emergent
 * consequence of however many filesystem events the OS chose to deliver.
 *
 * [reload] is a function rather than a runtime handle so that "called exactly once"
 * is observable. The claim being made is about this class's behaviour, and a test
 * should be able to check it without booting a Truffle context.
 */
class AgentSession(
    private val projectRoot: File,
    /** Where to persist a committed batch. Null skips persistence. */
    private val store: MezzeStore? = null,
    /**
     * The world this session checks in.
     *
     * Its own by default, so two sessions cannot make each other look like an import
     * cycle — a world serves one check at a time, and sharing one is the mistake
     * `ModuleTypeChecker` refuses outright.
     */
    private val world: World = World.fresh("agent"),
    /**
     * Reload the runtime for the module paths a committed changeset touched.
     *
     * Null means "no runtime attached": files and the store are still written, and
     * [AgentApplyResult.reloaded] says false. Called AT MOST ONCE per apply, and never
     * for a dry run or a failed batch.
     */
    private val reload: ((Set<String>) -> Unit)? = null,
) {

    /**
     * Apply [changeset] as one unit.
     *
     * [dryRun] checks and reports without writing anything, reloading anything, or
     * touching the store — the speculative case, which costs a world and nothing else.
     */
    fun apply(changeset: Changeset, dryRun: Boolean = false): AgentApplyResult {
        val started = System.nanoTime()

        val result = ChangesetApply.apply(
            projectRoot = projectRoot,
            changeset = changeset,
            dryRun = dryRun,
            world = world,
            // A dry run must not reach the store even though this session has one.
            store = if (dryRun) null else store,
        )

        // Reload only for a batch that actually committed. A failed batch wrote
        // nothing, so reloading would re-run whatever was already loaded — work for no
        // change, reported as if something had happened.
        var reloaded = false
        if (result.ok && !dryRun && reload != null) {
            reload.invoke(changeset.modules.keys)
            reloaded = true
        }

        return AgentApplyResult(
            ok = result.ok,
            diagnostics = result.diagnostics,
            written = result.written.map { relative(it) },
            dryRun = dryRun,
            reloaded = reloaded,
            elapsedMillis = (System.nanoTime() - started) / 1_000_000,
        )
    }

    /** Release this session's caches. A world holds them until it is dropped. */
    fun close() {
        com.mezze.language.typechecker.ModuleResolver.forgetWorld(world)
        com.mezze.language.typechecker.module.ModuleTypeChecker.forgetWorld(world)
    }

    /**
     * A path relative to the project, so a caller on the other side of a wire is not
     * handed this machine's directory layout.
     */
    private fun relative(file: File): String = try {
        projectRoot.toPath().toAbsolutePath().normalize()
            .relativize(file.toPath().toAbsolutePath().normalize())
            .toString()
    } catch (_: Exception) {
        // Different filesystem roots cannot be relativized; the absolute path is
        // still more useful than nothing.
        file.path
    }
}
