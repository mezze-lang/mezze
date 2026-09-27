package com.mezze.language.cli.commands

import com.mezze.language.codebase.StorePopulator
import com.mezze.language.parsedast.diagnostics.display
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.World
import com.mezze.language.typechecker.module.ModuleTypeChecker
import java.io.File

/**
 * A set of edits declared as ONE unit, applied as one.
 *
 * ## Why this exists
 *
 * Batching already happens, and both existing forms INFER the batch boundary from
 * TIMING: `WireRunner` coalesces whatever is in the watch queue before one
 * invalidation, and the LSP debounces at 250 ms. That is the right proxy for a
 * typist, whose intent is unknowable. It is the wrong one for an agent, which knows
 * exactly where its changeset begins and ends and has no way to say so — so twelve
 * files written over two seconds become several coalescing rounds, each a separate
 * store write, eviction, reload and set of diagnostics, for one logical change.
 *
 * A [Changeset] lets the caller say it: here are the files, apply them together,
 * give me ONE complete set of diagnostics.
 *
 * ## Content, not paths
 *
 * A changeset carries the TEXT, not a list of files already written. That is what
 * makes a dry run a flag rather than a separate subsystem: applying to a fresh
 * [World] type-checks the whole batch against a source overlay and touches no file,
 * and applying for real does the same work and then writes. Nothing has to be
 * written, checked, and rolled back.
 *
 * It is also the only way to check a batch that ADDS a module: a file that does not
 * exist yet cannot be resolved from disk, and the overlay supplies it.
 *
 * ## All-or-nothing, but diagnose everything
 *
 * Every module is checked and every diagnostic is returned, because an agent wants
 * all twelve files' errors in one round rather than the first file's. But nothing is
 * WRITTEN unless all of them pass. Applying ten of twelve would leave the project in
 * a state nobody asked for — a half-applied logical change that compiles, which is
 * the kind of result that looks like success.
 */
data class Changeset(
    /** Module path → its new full text. `app::handler`, not a file path. */
    val modules: Map<String, String>,
) {
    init {
        require(modules.isNotEmpty()) { "a changeset with no modules has nothing to apply" }
    }

    /** Where [modulePath] would live under [projectRoot]. */
    fun fileFor(projectRoot: File, modulePath: String): File =
        File(projectRoot, "src/" + modulePath.replace("::", "/") + ".mz")
}

/** What applying a changeset produced. */
data class ChangesetResult(
    /** True when every module type-checked. Only then is anything written. */
    val ok: Boolean,
    /** Module path → its diagnostics. Every module appears, even a clean one. */
    val diagnostics: Map<String, List<String>>,
    /** Files written. Empty for a dry run, and empty whenever [ok] is false. */
    val written: List<File>,
    /** True when this was a dry run, so [written] being empty is not a failure. */
    val dryRun: Boolean,
) {
    val failedModules: List<String> get() = diagnostics.filterValues { it.isNotEmpty() }.keys.toList()
}

/**
 * Apply a [Changeset]: type-check the whole batch once, then write it, or not.
 *
 * The world is where the batch is checked. A dry run gets a fresh one and discards
 * it; a real apply uses the world the project already lives in, so the result is
 * the state everything else sees.
 */
object ChangesetApply {

    /**
     * SHA-256 hex, matching what the store uses for `source_hash`.
     *
     * The same scheme ModuleResolver computes with. A second convention here would
     * write rows no lookup could ever find — the #317 failure.
     */
    private fun sha256Hex(source: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun apply(
        projectRoot: File,
        changeset: Changeset,
        dryRun: Boolean,
        /**
         * The world to check in. Defaults to a fresh one for a dry run — the
         * speculative case — and to the project's own world for a real apply.
         */
        world: World? = null,
        /**
         * Where to persist the batch once it commits. Null skips persistence, which
         * is what a dry run wants and what a caller with no project store has.
         *
         * A parameter rather than something opened here: opening a store is the
         * caller's decision — `WireRunner` and the LSP each already have one — and
         * this must stay callable without one so the check-and-write path is
         * testable on its own.
         */
        store: com.mezze.language.store.MezzeStore? = null,
    ): ChangesetResult {
        val target = world ?: if (dryRun) World.fresh("changeset") else World.DEFAULT
        val ownsWorld = world == null && dryRun

        try {
            // One overlay for the WHOLE batch before anything is checked. Checking
            // module by module as each is overlaid would let an earlier module
            // resolve a later one from its OLD text on disk — the batch would be
            // type-checked against a state that never exists.
            for ((modulePath, source) in changeset.modules) {
                ModuleResolver.overlaySource(target, modulePath, source)
            }
            // Anything already checked in this world was checked against the old
            // text, so it cannot be reused.
            ModuleTypeChecker.invalidateModulePaths(changeset.modules.keys, target)

            val checked = LinkedHashMap<String, Checked>()
            for ((modulePath, source) in changeset.modules) {
                checked[modulePath] = check(projectRoot, target, modulePath, source)
            }
            val diagnostics = checked.mapValues { (_, c) -> c.diagnostics }

            val ok = diagnostics.values.all { it.isEmpty() }
            if (!ok || dryRun) {
                return ChangesetResult(ok, diagnostics, emptyList(), dryRun)
            }

            val written = changeset.modules.map { (modulePath, source) ->
                val file = changeset.fileFor(projectRoot, modulePath)
                file.parentFile?.mkdirs()
                file.writeText(source)
                // Recorded BEFORE the watcher can see the write, so the event this
                // very line produces is recognised as ours and dropped. Without it
                // the watcher re-runs the whole cycle for a change the system just
                // made — the duplicated work a declared changeset exists to remove,
                // arriving by another route.
                SelfWrites.record(file, source)
                file
            }

            // The store, in ONE pass over the batch, using the results the check
            // phase already produced. Persisting per module as each was checked
            // would put the store through N rounds for one logical change — the
            // thing this task exists to stop — and would leave earlier modules
            // persisted if a later one failed.
            if (store != null) {
                for ((modulePath, c) in checked) {
                    val result = c.result ?: continue
                    val source = changeset.modules.getValue(modulePath)
                    StorePopulator.populate(
                        store,
                        result,
                        modulePath = modulePath,
                        filePath = changeset.fileFor(projectRoot, modulePath).absolutePath,
                        sourceHash = sha256Hex(source),
                        entrySource = source,
                        parsedModule = c.parsed,
                    )
                }
            }
            // The files now match the overlay, so the overlay has nothing left to
            // say. Leaving it would make this world permanently prefer text that is
            // no longer distinguishable from the file — and mask the next real edit.
            ModuleResolver.clearOverlay(target)
            return ChangesetResult(ok = true, diagnostics, written, dryRun = false)
        } finally {
            if (ownsWorld) {
                // A speculative world is scaffolding: it holds caches and an overlay
                // for text that was never written.
                ModuleResolver.forgetWorld(target)
                ModuleTypeChecker.forgetWorld(target)
            }
        }
    }

    /**
     * One module's outcome: its diagnostics, and — when it passed — the checked
     * result and parsed module, which is what the store needs.
     *
     * Kept rather than re-derived: persisting otherwise means type-checking the
     * batch a SECOND time, which is exactly the duplicated work this exists to
     * remove.
     */
    private data class Checked(
        val diagnostics: List<String>,
        val result: com.mezze.language.typechecker.TypeCheckResult? = null,
        val parsed: com.mezze.language.parsedast.ParsedModule? = null,
    )

    /** Parse and type-check one module of the batch. */
    private fun check(
        projectRoot: File,
        world: World,
        modulePath: String,
        source: String,
    ): Checked {
        val fileName = modulePath.substringAfterLast("::") + ".mz"
        val parsed = MezzeParser().parseWithRecovery(source, file = fileName)
        if (parsed.errors.isNotEmpty()) {
            // A module that does not parse is reported and NOT type-checked: every
            // type error after a parse failure is downstream of it, and reporting
            // both buries the one the reader can act on.
            return Checked(parsed.errors.map { "${it.code.display()}: ${it.errorMessage}" })
        }

        return try {
            val result = TypeChecker(world = world).checkParsedModule(
                parsed.module, projectRoot.toPath(), currentModulePath = modulePath,
            )
            when (result) {
                is TypeChecker.TypeCheckResult.Failure ->
                    Checked(result.errors.map { "${it.code.display()}: ${it.message}" })
                is TypeChecker.TypeCheckResult.Success ->
                    Checked(emptyList(), TypeCheckRunner.wrapSuccess(result), parsed.module)
            }
        } catch (e: com.mezze.language.typechecker.TypeCheckError) {
            Checked(listOf("${e.code.display()}: ${e.message}"))
        } catch (e: com.mezze.language.typechecker.ModuleError) {
            // An unresolvable import is a diagnostic about this changeset, not a
            // crash: it usually means the batch is missing a module it refers to.
            Checked(listOf(e.message ?: "module error"))
        } catch (e: com.mezze.language.typechecker.module.ModuleError) {
            // The SECOND ModuleError. There are two — the resolver's
            // (`typechecker.ModuleError`) and the module layer's
            // (`typechecker.module.ModuleError`) — and ModuleLoader converts one into
            // the other, so which one escapes depends on how far the failure got.
            // Catching only the resolver's let a missing import escape as a crash.
            Checked(listOf(e.message ?: "module error"))
        }
    }
}
