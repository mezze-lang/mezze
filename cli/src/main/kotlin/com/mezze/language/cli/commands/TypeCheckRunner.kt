package com.mezze.language.cli.commands

import com.mezze.language.parsedast.diagnostics.display
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.TypeCheckResult
import com.mezze.language.typechecker.TypeCheckWarning
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.metadataInReach
import com.mezze.language.langservice.printing.ErrorFormatter

/**
 * Shared typecheck-result handling for `mez check` and `mez dev`.
 *
 * Both commands use the same lower-level `checkParsedModule` entry point
 * (so they collect ALL errors, not just the first), then either render
 * them or wrap the Success into the public `TypeCheckResult` shape that
 * `StorePopulator` and downstream lowering expect.
 */
object TypeCheckRunner {

    /**
     * Format every error in [failure] into a single user-facing string.
     *
     * Rust-style: each error is a self-contained block separated by a
     * blank line. No file-grouping banner — each error's `--> file:line:col`
     * carries the file path. The footer mirrors `rustc`'s
     * "aborting due to N previous errors" line.
     */
    fun formatErrors(failure: TypeChecker.TypeCheckResult.Failure, source: String): String = buildString {
        for ((idx, err) in failure.errors.withIndex()) {
            if (idx > 0) append("\n\n")
            val formatted = try {
                ErrorFormatter.format(
                    TypeCheckError(err.message, err.loc, err.errorType, err.metadata, err.code, err.hints),
                    source,
                )
            } catch (_: Exception) {
                err.message
            }
            append(formatted)
        }
        if (failure.errors.isNotEmpty()) {
            val n = failure.errors.size
            append("\n\nerror: aborting due to $n ")
            append(if (n == 1) "previous error" else "previous errors")

            val explainableCode = failure.errors
                .map { it.code }
                .firstOrNull { ExplainDocs.hasDoc(it) }
            if (explainableCode != null) {
                append("\n\nFor more information about this error, try `mez --explain ")
                append(explainableCode.display())
                append("`.")
            }
        }
    }

    /**
     * Wrap a raw `TypeCheckResult.Success` from `checkParsedModule(...)`
     * into the public `TypeCheckResult` shape used by `StorePopulator`
     * and downstream lowering.
     */
    fun wrapSuccess(success: TypeChecker.TypeCheckResult.Success): TypeCheckResult {
        return TypeCheckResult(
            resolvedImports = emptyList(),
            warnings = success.warnings.map { TypeCheckWarning(it.message, it.loc, it.code) },
            metadataByHash = success.metadataInReach(),
            importedAbilities = success.importContext?.importedAbilities ?: emptyMap(),
            importedEffects = success.importContext?.importedEffects ?: emptyMap(),
            loweredFunctions = success.loweredFunctions ?: emptyList(),
            typedModule = success.typedModule,
            performerRecords = success.loweredPerformers,
            // Built when first read: a module loaded from the checked-module
            // cache reads its body only when asked, and `check` never asks.
            loadedModules = LazyMap {
                success.loadedModules
                    .mapValues { (_, m) -> m.typedModule }
                    .filterValues { it != null }
                    .mapValues { (_, v) -> v!! }
            },
            loadedModuleSources = success.loadedModules
                .mapNotNull { (path, m) ->
                    val fp = m.filePath ?: return@mapNotNull null
                    val sh = m.sourceHash ?: return@mapNotNull null
                    path to com.mezze.language.typechecker.LoadedModuleSource(fp, sh)
                }.toMap(),
            expectedTypeAt = success.expectedTypeAt,
        )
    }
}

/** A map computed by [compute] when first read. */
private class LazyMap<K, V>(compute: () -> Map<K, V>) : AbstractMap<K, V>() {
    private val map by lazy(compute)
    override val entries: Set<Map.Entry<K, V>> get() = map.entries
    override fun get(key: K): V? = map[key]
    override fun containsKey(key: K): Boolean = map.containsKey(key)
    override val size: Int get() = map.size
}
