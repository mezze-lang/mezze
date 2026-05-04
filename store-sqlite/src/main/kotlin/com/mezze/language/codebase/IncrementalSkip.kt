package com.mezze.language.codebase

import com.mezze.language.parsedast.ParsedDecl
import com.mezze.language.parsedast.ParsedLetDecl
import com.mezze.language.parsedast.ParsedModule
import com.mezze.language.parsedast.ParsedTypeDecl
import com.mezze.language.parsedast.ParsedAbilityDecl
import com.mezze.language.parsedast.ParsedImplAbilityDecl
import com.mezze.language.parsedast.ParsedEffectDecl
import com.mezze.language.parsedast.ParsedPerformerDecl
import com.mezze.language.parsedast.STDLIB_NAMESPACE_PREFIX
import java.io.File
import java.security.MessageDigest

/**
 * Module-level typecheck-skip gate.
 *
 * Slice F's pragmatic v1: the typechecker still works on whole modules, but
 * before invoking it on a watch reload we check the cache to see whether
 * anything in the entry module *or* its dependency closure actually changed.
 * If nothing did, we hydrate the prior `TypeCheckResult` straight from the
 * cached typed_modules blob + the app's recorded function/performer hashes
 * and skip both typecheck and lowering.
 *
 * ## The five gates
 *
 * 1. The entry's parsed-file hash matches `parsed_modules.source_hash`.
 *    Catches edits to imports, orphan comments, or any byte outside a decl
 *    that would otherwise slip past per-decl source_hash.
 * 2. The current parse's set of decl names equals the cached `decls` rows'
 *    set. Catches added or removed top-level decls.
 * 3. Every decl's per-decl `source_hash` from the new parse equals the row
 *    in `decls`. Catches body edits.
 * 4. Every dep listed in `imports` for this module is fresh: the imported
 *    module's `modules.source_hash` equals SHA-256 of its file on disk.
 *    This is the load-bearing soundness check — without it, a cached entry
 *    typed against an out-of-date dep would be reused.
 * 5. Stdlib version match: `metadata.stdlib_module_hashes` in the project
 *    cache matches the seed file's value. A `mez` upgrade bumps stdlib;
 *    we must not reuse typed bytes built against the old stdlib.
 *
 * If ANY gate fails, return null and let the caller run the full typecheck.
 *
 * ## What the skip does NOT do
 *
 * - It doesn't typecheck a subset of decls. Modules remain the typecheck
 *   unit; this just decides "skip the whole call" vs "run it."
 * - It doesn't repair the in-memory `ModuleTypeChecker.globalCache`. The
 *   caller still owns invalidation of dep modules; this gate only covers
 *   the entry.
 */
object IncrementalSkip {

    /**
     * Result of a successful skip. Carries everything DevRunner would have
     * needed from a fresh typecheck — the cached typed module, the app's
     * function manifest, and its performer records. Lowering is not re-run;
     * we trust the cached IR bytes that StorePopulator wrote on the previous
     * compile.
     */
    data class Hit(
        val typedModule: com.mezze.language.typedast.TypedModule,
        val loweredFunctions: List<com.mezze.language.truffleir.FunctionIR>,
        val performerRecords: List<Array<String>>,
        /**
         * The decls-source-hash diff that the gate computed. Always empty
         * when this is a Hit — but the field exists so the same data type
         * can describe a "near miss" in future telemetry work.
         */
        val changedDecls: List<String> = emptyList()
    )

    /**
     * The reason a skip was denied. `null` when [maybeSkip] returns a Hit;
     * otherwise one of these tags so DevRunner can log a one-line "what
     * changed" for the user.
     */
    enum class MissReason {
        NO_CACHED_TYPED_MODULE,
        FILE_HASH_MISMATCH,
        DECL_SET_CHANGED,
        DECL_BODY_CHANGED,
        DEP_FILE_STALE,
        STDLIB_VERSION_MISMATCH,
        APP_NOT_REGISTERED,
        FUNCTION_IR_MISSING
    }

    sealed class Result {
        data class Skip(val hit: Hit) : Result()
        data class Miss(val reason: MissReason, val detail: String? = null) : Result()
    }

    /**
     * @param store the project store
     * @param entryParsed the freshly parsed entry module
     * @param entrySource the entry file's current bytes
     * @param entrySourceHash sha256 of [entrySource] (caller already
     *   computed this; passed in to avoid a redundant hash)
     * @param modulePath canonical entry module path (e.g. "myapp")
     * @param entryFilePath absolute path to the entry .mz file
     * @param appName the app this skip serves (for loadFunctions /
     *   loadPerformerRecords)
     */
    fun maybeSkip(
        store: SQLiteMezzeStore,
        entryParsed: ParsedModule,
        entrySource: String,
        entrySourceHash: String,
        modulePath: String,
        entryFilePath: String,
        appName: String,
        seedStdlibHashes: String? = null
    ): Result {
        // Gate 1: parsed-file hash must match. If not, something changed
        // outside any decl (imports, top-level use clauses, orphan
        // comments) — full typecheck.
        val cachedModule = store.getModule(modulePath)
            ?: return Result.Miss(MissReason.NO_CACHED_TYPED_MODULE)
        if (cachedModule.sourceHash != entrySourceHash) {
            return Result.Miss(MissReason.FILE_HASH_MISMATCH)
        }
        store.getTypedModule(modulePath, entrySourceHash)
            ?: return Result.Miss(MissReason.NO_CACHED_TYPED_MODULE)

        // Gate 2 + 3: decl set + per-decl source hashes match. Walk current
        // parse's decls, compute source_hash for each, compare against the
        // `decls` rows for this module.
        val cachedDecls = store.getDeclsForModule(modulePath)
            .associateBy { it.declKey() }
        val newDeclHashes = perDeclSourceHashes(entryParsed, entrySource)

        if (cachedDecls.keys != newDeclHashes.keys) {
            return Result.Miss(
                MissReason.DECL_SET_CHANGED,
                detail = "added=${newDeclHashes.keys - cachedDecls.keys} " +
                         "removed=${cachedDecls.keys - newDeclHashes.keys}"
            )
        }
        val changed = newDeclHashes.entries.filter { (k, h) ->
            cachedDecls[k]?.sourceHash != h
        }.map { it.key }
        if (changed.isNotEmpty()) {
            return Result.Miss(MissReason.DECL_BODY_CHANGED, detail = changed.joinToString(","))
        }

        // Gate 4: every dep is current on disk. Walk imports for this
        // module; for each unique target_module, verify the cached
        // modules.source_hash matches the file on disk. Stdlib targets
        // are skipped — slice C handles their version invariant via
        // gate 5.
        val depTargets = store.getImportsForModule(modulePath)
            .map { it.targetModule }
            .toSet()
        for (depPath in depTargets) {
            if (depPath == modulePath) continue
            if (depPath.startsWith(STDLIB_NAMESPACE_PREFIX)) continue
            val depMod = store.getModule(depPath) ?: return Result.Miss(
                MissReason.DEP_FILE_STALE, detail = "$depPath: no module row"
            )
            val depFile = depMod.filePath?.let { File(it) } ?: continue
            if (!depFile.exists()) {
                return Result.Miss(MissReason.DEP_FILE_STALE, detail = "$depPath: file missing")
            }
            val onDiskHash = sha256(depFile.readText())
            if (onDiskHash != depMod.sourceHash) {
                return Result.Miss(MissReason.DEP_FILE_STALE, detail = depPath)
            }
        }

        // Gate 5: stdlib version. The seed records `stdlib_module_hashes`
        // when the project cache was last seeded; compare to whatever the
        // currently bundled seed reports. If they differ, the user
        // upgraded `mez` (or we re-ran `compileStdlib`) and any cached
        // typed module built against the old stdlib is suspect.
        if (seedStdlibHashes != null) {
            val cachedStdlib = store.getMetadata("stdlib_module_hashes")
            if (cachedStdlib != null && cachedStdlib != seedStdlibHashes) {
                return Result.Miss(MissReason.STDLIB_VERSION_MISMATCH)
            }
        }

        // All gates passed — hydrate from cache.
        val typedBlob = store.getTypedModule(modulePath, entrySourceHash)
            ?: return Result.Miss(MissReason.NO_CACHED_TYPED_MODULE)
        val typedModule = try {
            CodebaseSerialization.deserializeTypedModule(typedBlob)
        } catch (_: Exception) {
            return Result.Miss(MissReason.NO_CACHED_TYPED_MODULE)
        }

        val app = store.getApp(appName) ?: return Result.Miss(MissReason.APP_NOT_REGISTERED)
        val functions = store.getAppFunctionHashes(appName).mapNotNull { hash ->
            val irBytes = store.getIR(hash) ?: return@mapNotNull null
            try {
                CodebaseSerialization.deserializeFunctionIR(irBytes)
            } catch (_: Exception) {
                null
            }
        }
        if (functions.size != store.getAppFunctionHashes(appName).size) {
            return Result.Miss(MissReason.FUNCTION_IR_MISSING)
        }
        val performers = store.getAppPerformerRecords(appName)

        // Reference unused field so the compiler doesn't warn; also lets
        // future telemetry surface that the app row was checked.
        @Suppress("UNUSED_VARIABLE") val _appName = app.name

        return Result.Skip(Hit(typedModule, functions, performers))
    }

    private fun DeclRow.declKey() = "${declKind.dbValue}:$declName"

    /**
     * Build the same `(kind:name) → source_hash` map that StorePopulator
     * writes to the `decls` table. Stays parallel with that writer's
     * iteration order so the comparison is apples-to-apples.
     */
    private fun perDeclSourceHashes(
        module: ParsedModule,
        source: String
    ): Map<String, String> {
        val lineStarts = LineIndex(source)
        val out = HashMap<String, String>(module.decls.size)
        for (decl in module.decls) {
            val (kind, name) = declKindAndName(decl) ?: continue
            val sliced = lineStarts.slice(source, decl.loc)
            out["${kind.dbValue}:$name"] = sha256(sliced)
        }
        return out
    }

    private fun declKindAndName(decl: ParsedDecl): Pair<DeclKind, String>? = when (decl) {
        is ParsedLetDecl -> if (decl.isSimpleBinding) DeclKind.LET to decl.variableName else null
        is ParsedTypeDecl -> DeclKind.TYPE to decl.name
        is ParsedAbilityDecl -> DeclKind.ABILITY to decl.name
        is ParsedImplAbilityDecl -> DeclKind.IMPL to syntheticImplName(decl)
        is ParsedEffectDecl -> DeclKind.EFFECT to decl.name
        is ParsedPerformerDecl -> DeclKind.PERFORMER to decl.performerName
        else -> null
    }

    /**
     * Stay-in-sync with StorePopulator's `impl_<12-hash>` synthesis: at the
     * parse level we don't yet have the typed-content hash, so derive a
     * stable name from the impl's source range. Any byte change to the
     * impl flips the synthetic name AND the source_hash — either suffices
     * to deny the skip.
     */
    private fun syntheticImplName(decl: ParsedImplAbilityDecl): String {
        val key = "${decl.loc.startLine}:${decl.loc.startCol}-${decl.loc.endLine}:${decl.loc.endCol}"
        return "impl_${sha256(key).take(12)}"
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * Tiny line-start index. Mirror of the same helper in StorePopulator —
     * duplicated here on purpose so the two computations don't diverge as
     * one of them gets edited.
     */
    private class LineIndex(source: String) {
        private val starts: IntArray = run {
            val list = ArrayList<Int>(source.length / 40 + 1)
            list.add(0)
            for (i in source.indices) {
                if (source[i] == '\n') list.add(i + 1)
            }
            list.toIntArray()
        }

        fun slice(source: String, loc: com.mezze.language.parsedast.Loc): String {
            val startLineIdx = (loc.startLine - 1).coerceIn(0, starts.size - 1)
            val endLineIdx = (loc.endLine - 1).coerceIn(0, starts.size - 1)
            val startOffset = (starts[startLineIdx] + (loc.startCol - 1)).coerceIn(0, source.length)
            val endOffset = (starts[endLineIdx] + loc.endCol).coerceIn(startOffset, source.length)
            return source.substring(startOffset, endOffset)
        }
    }
}
