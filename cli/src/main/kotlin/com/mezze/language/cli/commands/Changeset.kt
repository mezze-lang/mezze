package com.mezze.language.cli.commands

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

    fun apply(
        projectRoot: File,
        changeset: Changeset,
        dryRun: Boolean,
        /**
         * The world to check in. Defaults to a fresh one for a dry run — the
         * speculative case — and to the project's own world for a real apply.
         */
        world: World? = null,
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

            val diagnostics = LinkedHashMap<String, List<String>>()
            for ((modulePath, source) in changeset.modules) {
                diagnostics[modulePath] = check(projectRoot, target, modulePath, source)
            }

            val ok = diagnostics.values.all { it.isEmpty() }
            if (!ok || dryRun) {
                return ChangesetResult(ok, diagnostics, emptyList(), dryRun)
            }

            val written = changeset.modules.map { (modulePath, source) ->
                val file = changeset.fileFor(projectRoot, modulePath)
                file.parentFile?.mkdirs()
                file.writeText(source)
                file
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

    /** Parse and type-check one module of the batch, returning its diagnostics. */
    private fun check(
        projectRoot: File,
        world: World,
        modulePath: String,
        source: String,
    ): List<String> {
        val fileName = modulePath.substringAfterLast("::") + ".mz"
        val parsed = MezzeParser().parseWithRecovery(source, file = fileName)
        if (parsed.errors.isNotEmpty()) {
            // A module that does not parse is reported and NOT type-checked: every
            // type error after a parse failure is downstream of it, and reporting
            // both buries the one the reader can act on.
            return parsed.errors.map { "${it.code.display()}: ${it.errorMessage}" }
        }

        return try {
            val result = TypeChecker(world = world).checkParsedModule(
                parsed.module, projectRoot.toPath(), currentModulePath = modulePath,
            )
            when (result) {
                is TypeChecker.TypeCheckResult.Failure ->
                    result.errors.map { "${it.code.display()}: ${it.message}" }
                is TypeChecker.TypeCheckResult.Success -> emptyList()
            }
        } catch (e: com.mezze.language.typechecker.TypeCheckError) {
            listOf("${e.code.display()}: ${e.message}")
        } catch (e: com.mezze.language.typechecker.ModuleError) {
            // An unresolvable import is a diagnostic about this changeset, not a
            // crash: it usually means the batch is missing a module it refers to.
            listOf(e.message ?: "module error")
        } catch (e: com.mezze.language.typechecker.module.ModuleError) {
            // The SECOND ModuleError. There are two — the resolver's
            // (`typechecker.ModuleError`) and the module layer's
            // (`typechecker.module.ModuleError`) — and ModuleLoader converts one into
            // the other, so which one escapes depends on how far the failure got.
            // Catching only the resolver's let a missing import escape as a crash.
            listOf(e.message ?: "module error")
        }
    }
}
