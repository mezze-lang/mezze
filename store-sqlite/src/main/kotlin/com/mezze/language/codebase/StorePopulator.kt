package com.mezze.language.codebase

import com.mezze.language.store.*

import com.mezze.language.typechecker.TypeCheckResult
import com.mezze.language.typedast.*
import com.mezze.language.truffleir.FunctionIR
import java.io.File
import java.security.MessageDigest

/**
 * Scatters compilation pipeline output into a MezzeStore.
 *
 * Takes a TypeCheckResult (produced by TypeChecker.typeCheck()) and writes
 * individual definitions as blobs, registers names, records edges, etc.
 *
 * This is the bridge between the compilation pipeline and the storage layer.
 * Called by `mez dev`, `mez check`, and `mez build` after each file is compiled.
 */
/**
 * Result of a populate() call — what actually changed in the store.
 *
 * Drives:
 * - Watch mode CallTarget invalidation (only rebuild changed hashes)
 * - Surgical cache invalidation in ModuleResolver / PipelineRunner
 * - Logging / telemetry
 */
data class PopulateDelta(
    /** Hashes newly written this run. Subset of all hashes in result. */
    val newHashes: Set<Hash>,
    /** Hashes already present in the store, skipped (no IO). */
    val unchangedHashes: Set<Hash>,
)

object StorePopulator {

    /**
     * The structural hash of a type — its identity, independent of any name.
     *
     * This is the key an impl target is stored and looked up under, and the key its
     * type tree is indexed under, so the two always agree.
     */
    private fun typeStructureHash(type: com.mezze.language.ast.MezzeType): Hash {
        val b = com.mezze.language.ast.hashing.MezzeHashBuilder()
        com.mezze.language.ast.hashing.appendType(b, type)
        return b.buildHash()
    }

    /**
     * Scatter a TypeCheckResult into the store, skipping definitions whose
     * content hash already exists. Returns the delta.
     *
     * @param store The MezzeStore to write to
     * @param result The TypeCheckResult from TypeChecker.typeCheck()
     * @param modulePath The module path (e.g., "myapp::utils")
     * @param filePath The source file path (e.g., "src/utils.mz")
     * @param sourceHash The SHA-256 hash of the source file contents
     * @param entrySource Optional raw source text of the entry file. When
     *   provided, per-decl source ranges are sliced from it and written to
     *   `decls` along with their per-decl SHA-256. Without it we can't
     *   compute per-decl hashes, so we skip the decls write for the entry
     *   (transitive deps still get decls — we can read their source from disk).
     * @param parsedModule The entry ParsedModule (optional). When provided,
     *   it's persisted to `parsed_modules` so the parser can be skipped on
     *   the next cold start.
     * @param loadedParsedModules Per-import ParsedModule keyed by module path.
     *   Each entry is persisted to `parsed_modules`. Only project files —
     *   stdlib and other namespaced modules are filtered out.
     */
    fun populate(
        store: MezzeStore,
        result: TypeCheckResult,
        modulePath: String,
        filePath: String?,
        sourceHash: String,
        entrySource: String? = null,
        parsedModule: com.mezze.language.parsedast.ParsedModule? = null,
        loadedParsedModules: Map<String, com.mezze.language.parsedast.ParsedModule> = emptyMap()
    ): PopulateDelta {
        // Collect all hashes we might write so we can do one bulk existence check.
        val candidateHashes = mutableSetOf<Hash>()
        result.typedModule?.let { module ->
            module.decls.filterIsInstance<TypedLetDecl>().mapTo(candidateHashes) { it.hash }
            module.decls.filterIsInstance<TypedTypeDecl>().mapTo(candidateHashes) { it.hash }
            module.abilityDecls.mapTo(candidateHashes) { it.hash }
            module.abilityImpls.mapTo(candidateHashes) { it.hash }
            module.effectDecls.mapTo(candidateHashes) { it.hash }
            module.performerDecls.mapTo(candidateHashes) { it.hash }
        }
        result.loweredFunctions.mapTo(candidateHashes) { it.hash }

        val existing = store.blobsExist(candidateHashes)
        val newHashes = candidateHashes - existing

        store.transaction {
            // Register the module — always update (file may have new sourceHash).
            store.putModule(modulePath, filePath, sourceHash)

            // Persist the entry ParsedModule first so reads can skip the parser
            // even if typecheck/lower below fails. Comments are part of the
            // serialized blob (they're fields on ParsedDecl).
            if (parsedModule != null) {
                val parsedBlob = StoreSerialization.serializeParsedModule(parsedModule)
                store.putParsedModule(modulePath, sourceHash, parsedBlob)
                store.putModuleDoc(modulePath, parsedModule.doc)
            }

            val typedModule = result.typedModule
            if (typedModule != null) {
                scatterTypedModule(store, typedModule, modulePath, existing)

                // Cache the whole TypedModule blob keyed by (path, sourceHash).
                // Lets future JVM starts skip re-typecheck if source matches.
                // Idempotent: same (path, hash) → same bytes → INSERT OR REPLACE no-op.
                val typedBlob = StoreSerialization.serializeTypedModule(typedModule)
                store.putTypedModule(modulePath, sourceHash, typedBlob)

                // Per-decl index. Slice F will diff source_hash to skip
                // typecheck per-decl; LSP workspace symbol uses the name
                // index. We need source bytes to compute per-decl ranges,
                // so the entry's decls only land if `entrySource` was passed.
                if (entrySource != null) {
                    scatterDecls(store, typedModule, modulePath, entrySource, parsedModule)
                }

                // Cross-file find-references index. Walks every TypedExpr
                // in the entry's decls + impl/performer bodies and writes
                // one row per BindingRef.ByHash use site.
                scatterUsages(store, typedModule, filePath)
            }
            // The entry's `imports` rows are written AFTER the dep loop below
            // — the loop scatters dep `names`, which scatterImports needs to
            // resolve `target_hash`. Ordering matters here.

            // Cache typed bytes for every transitively-loaded project module
            // (skip stdlib — its modulePath starts with "std::" and lives
            // inside the install dir, never a project file).
            //
            // The LoadedModule.sourceHash from ImportResolver is a MezzeHash
            // (#-prefixed SHA3-512). Hydration uses SHA-256 of file bytes for
            // compatibility with DevRunner's keying. Re-compute SHA-256 here
            // so both tools share the same `(modulePath, sourceHash)` keys.
            for ((loadedPathTyped, loadedTyped) in result.loadedModules) {
                val loadedPath = loadedPathTyped.fullPath
                if (loadedPath == modulePath) continue
                // Skip stdlib here. Stdlib lives in its own attached
                // `.mez/store/stdlib.sqlite`, materialised once at openProject
                // from the bundled JAR resource. The stdlib precompiler
                // tool writes to a separate file (not a project store),
                // so this filter doesn't affect it.
                if (loadedPath.startsWith(com.mezze.language.parsedast.STDLIB_NAMESPACE_PREFIX)) {
                    continue
                }
                val src = result.loadedModuleSources[loadedPathTyped] ?: continue
                val file = File(src.filePath).takeIf { it.exists() } ?: continue
                val depSource = file.readText()
                val sha256 = sha256Hex(depSource)
                store.putModule(loadedPath, src.filePath, sha256)
                val blob = StoreSerialization.serializeTypedModule(loadedTyped)
                store.putTypedModule(loadedPath, sha256, blob)

                // Scatter dep decls into per-decl blobs + register names.
                // Without this, cross-file imports that resolveName() into
                // dep modules can't find a target hash, and per-decl
                // executor lookups against cache.sqlite always miss.
                scatterTypedModule(store, loadedTyped, loadedPath, existing)

                // Cache the dep's ParsedModule too, if the caller provided it.
                // Same (path, sourceHash) keying as typed_modules.
                val depParsed = loadedParsedModules[loadedPath]
                if (depParsed != null) {
                    val parsedBlob = StoreSerialization.serializeParsedModule(depParsed)
                    store.putParsedModule(loadedPath, sha256, parsedBlob)
                    store.putModuleDoc(loadedPath, depParsed.doc)
                }

                // Per-decl index for the dep too. We have its source in hand.
                scatterDecls(store, loadedTyped, loadedPath, depSource, depParsed)

                // Imports for the dep — only if we have its ParsedModule.
                // Without the ParsedModule we don't have the use-decl source
                // ranges, and the index is uninteresting without them.
                if (depParsed != null) {
                    scatterImports(store, depParsed, loadedPath)
                }

                // Usages for the dep — same walk, attributed to its file.
                scatterUsages(store, loadedTyped, src.filePath)
            }

            // Now that all deps have scattered their `names`, write the
            // entry's imports — `resolveName` lookups inside scatterImports
            // can now hit cross-module targets.
            if (parsedModule != null) {
                scatterImports(store, parsedModule, modulePath)
            }

            val functions = result.loweredFunctions
            if (functions.isNotEmpty()) {
                scatterFunctionIRs(store, functions, modulePath, existing)
                // Only when this run lowered: a check-only index has no functions,
                // and that says nothing about which ones the program still needs.
                store.replaceBlobOwnership("lowered:$modulePath", functions.mapTo(HashSet()) { it.hash })
            }
        }

        return PopulateDelta(newHashes = newHashes, unchangedHashes = existing)
    }

    /**
     * Walk every decl in [module] and record one `usages` row per
     * `TypedVar` whose binding is `BindingRef.ByHash`. Each row carries
     * the source loc of the *use site* (the TypedVar.loc), not the
     * definition. LSP textDocument/references reads this in O(1) by
     * filtering on `target_hash`.
     *
     * Also records cross-decl hash references: `TypedHandlerRef`'s
     * effect/performer hashes, `TypedPerformOp.effectHash`, and the
     * `AbilityImplRef` hashes inside a `TypedCall.instantiation`. These
     * don't have their own Loc fields, so we attribute them to their
     * containing expression's loc.
     *
     * Cleared per-file before the walk — re-typechecking the file
     * reproduces all its usage rows; nothing else should ever write
     * usages for that file.
     */
    private fun scatterUsages(
        store: MezzeStore,
        module: TypedModule,
        filePath: String?
    ) {
        if (filePath == null) return
        store.clearUsagesForFile(filePath)

        // Every reference is recorded with the declaration it is written IN, so
        // the source call graph needs no guessing from line spans.
        fun from(owner: com.mezze.language.typedast.Hash): UsageSink = { h, loc, isCall ->
            store.putUsage(h, filePath, loc.startLine, loc.startCol, loc.endLine, loc.endCol, owner, isCall)
        }

        for (decl in module.decls) {
            when (decl) {
                is TypedLetDecl -> walkExpr(decl.value, from(decl.hash))
                is TypedTypeDecl -> Unit  // type defs hold MezzeType, no exprs
                else -> Unit
            }
        }
        for (impl in module.abilityImpls) {
            val collect = from(impl.hash)
            // Cross-ref: impl → ability
            collect(impl.abilityHash, impl.loc, false)
            for (body in impl.operations.values) walkExpr(body, collect)
        }
        for (decl in module.performerDecls) {
            val collect = from(decl.hash)
            collect(decl.effectHash, decl.loc, false)
            for (body in decl.operations.values) walkExpr(body, collect)
        }
        // ability/effect decls have no exec bodies in TypedAst (operations
        // are signatures only).
    }

    private fun walkExpr(
        expr: com.mezze.language.typedast.TypedExpr,
        collect: UsageSink
    ) {
        when (expr) {
            is com.mezze.language.typedast.TypedVar -> {
                val ref = expr.binding
                if (ref is com.mezze.language.typedast.BindingRef.ByHash) {
                    collect(ref.hash, expr.loc, false)
                }
            }
            is com.mezze.language.typedast.TypedCall -> {
                // The function position of a call is a CALL, not a mention.
                val fn = expr.function
                val callee = (fn as? com.mezze.language.typedast.TypedVar)?.binding as? com.mezze.language.typedast.BindingRef.ByHash
                if (callee != null) collect(callee.hash, fn.loc, true) else walkExpr(fn, collect)
                walkExpr(expr.arg, collect)
                expr.instantiation?.abilityImpls?.forEach { ref ->
                    // No per-impl loc on AbilityImplRef; attribute to the call site.
                    collect(ref.implHash, expr.loc, false)
                    collect(ref.abilityHash, expr.loc, false)
                }
            }
            is com.mezze.language.typedast.TypedLambda -> {
                walkExpr(expr.body, collect)
                // Captures of ByHash bindings count as uses too.
                for (cap in expr.captures) {
                    val src = cap.source
                    if (src is com.mezze.language.typedast.BindingRef.ByHash) {
                        // No loc on a TypedCapture; attribute to the lambda.
                        collect(src.hash, expr.loc, false)
                    }
                }
            }
            is com.mezze.language.typedast.TypedRecord -> for (e in expr.entries) when (e) {
                is com.mezze.language.typedast.TypedRecordEntry.Field -> walkExpr(e.value, collect)
                is com.mezze.language.typedast.TypedRecordEntry.Spread -> walkExpr(e.expr, collect)
            }
            is com.mezze.language.typedast.TypedFieldAccess -> walkExpr(expr.record, collect)
            is com.mezze.language.typedast.TypedRecordUpdate -> {
                walkExpr(expr.record, collect)
                for (e in expr.updates) when (e) {
                    is com.mezze.language.typedast.TypedRecordEntry.Field -> walkExpr(e.value, collect)
                    is com.mezze.language.typedast.TypedRecordEntry.Spread -> walkExpr(e.expr, collect)
                }
            }
            is com.mezze.language.typedast.TypedVariantConstruct -> expr.payload?.let { walkExpr(it, collect) }
            is com.mezze.language.typedast.TypedMatch -> {
                walkExpr(expr.scrutinee, collect)
                for (case in expr.cases) {
                    case.guard?.let { walkExpr(it, collect) }
                    walkExpr(case.body, collect)
                    for (ilb in case.ifLetBindings) walkExpr(ilb.expr, collect)
                }
            }
            is com.mezze.language.typedast.TypedIf -> {
                walkExpr(expr.condition, collect)
                walkExpr(expr.thenBranch, collect)
                walkExpr(expr.elseBranch, collect)
            }
            is com.mezze.language.typedast.TypedBlock -> {
                for (stmt in expr.statements) when (stmt) {
                    is com.mezze.language.typedast.TypedStatement.Let -> walkExpr(stmt.value, collect)
                    is com.mezze.language.typedast.TypedStatement.Expr -> walkExpr(stmt.expr, collect)
                }
                walkExpr(expr.result, collect)
            }
            is com.mezze.language.typedast.TypedSeq -> for (el in expr.elements) when (el) {
                is com.mezze.language.typedast.TypedSeqElement.Regular -> walkExpr(el.expr, collect)
                is com.mezze.language.typedast.TypedSeqElement.Spread -> walkExpr(el.expr, collect)
            }
            is com.mezze.language.typedast.TypedPerform -> {
                for (h in expr.handlers) {
                    collect(h.performerHash, h.loc, false)
                    collect(h.effectHash, h.loc, false)
                    h.params?.let { walkExpr(it, collect) }
                }
                walkExpr(expr.body, collect)
            }
            is com.mezze.language.typedast.TypedPerformOp -> {
                collect(expr.effectHash, expr.loc, false)
                walkExpr(expr.arg, collect)
            }
            is com.mezze.language.typedast.TypedPipe -> {
                walkExpr(expr.left, collect)
                walkExpr(expr.right, collect)
            }
            is com.mezze.language.typedast.TypedStringLit -> for (part in expr.parts) {
                if (part is com.mezze.language.typedast.TypedStringPart.Interpolation) walkExpr(part.expr, collect)
            }
            // Leaves — no sub-expressions, no hash refs.
            is com.mezze.language.typedast.TypedIntLit,
            is com.mezze.language.typedast.TypedNatLit,
            is com.mezze.language.typedast.TypedFloatLit,
            is com.mezze.language.typedast.TypedFracLit,
            is com.mezze.language.typedast.TypedBoolLit,
            is com.mezze.language.typedast.TypedCharLit -> Unit
        }
    }

    /**
     * Write per-import rows into the `imports` index. Walks the parsed
     * `use` declarations because TypedImport has no Loc / target_module
     * path — only `moduleHash` and `declHash`.
     *
     * Only selective imports (`use std::int::{add, multiply}`) produce
     * rows. Wildcard / module-bag imports (`use std::int`) are skipped —
     * they don't bind a single local name, so they don't fit the
     * `(module_path, local_name)` primary key, and the consumers (auto-
     * import code action, unused-import check) only need named items.
     */
    private fun scatterImports(
        store: MezzeStore,
        parsed: com.mezze.language.parsedast.ParsedModule,
        modulePath: String
    ) {
        store.clearImportsForModule(modulePath)

        for (decl in parsed.decls) {
            val (path, items) = when (decl) {
                is com.mezze.language.parsedast.ParsedUseDecl ->
                    decl.path to selectiveItems(decl.path)
                is com.mezze.language.parsedast.ParsedPubUseDecl ->
                    decl.path to selectiveItems(decl.path)
                else -> continue
            }
            if (items == null) continue  // module-bag import; skip
            val targetModule = (path as com.mezze.language.parsedast.ParsedUsePath.Selective)
                .modulePath.toString()

            for (item in items) {
                val localName = item.alias ?: item.name
                // Lookup the target hash if the source module's names were
                // already scattered (deps land before the entry's imports).
                // Misses are written as null so future runs can fill them in.
                val targetHash = store.resolveName(targetModule, item.name)
                store.putImport(
                    modulePath = modulePath,
                    localName = localName,
                    targetModule = targetModule,
                    targetName = item.name,
                    targetHash = targetHash,
                    startLine = item.loc.startLine,
                    startCol = item.loc.startCol,
                    endLine = item.loc.endLine,
                    endCol = item.loc.endCol
                )
            }
        }
    }

    private fun selectiveItems(
        path: com.mezze.language.parsedast.ParsedUsePath
    ): List<com.mezze.language.parsedast.ParsedImportItem>? = when (path) {
        is com.mezze.language.parsedast.ParsedUsePath.Selective -> path.items
        is com.mezze.language.parsedast.ParsedUsePath.Module -> null
    }

    /**
     * Write per-decl rows into the `decls` index. Replaces any prior rows for
     * this module first — ranges and names move when code is edited, and we'd
     * rather not chase a partial overlap.
     *
     * Per-decl `source_hash` is SHA-256 of the source bytes inside the decl's
     * Loc range. Slice F diffs these on recompile to find which decls actually
     * changed; today's slice just lays the rows down.
     */
    private fun scatterDecls(
        store: MezzeStore,
        module: TypedModule,
        modulePath: String,
        source: String,
        parsedModule: com.mezze.language.parsedast.ParsedModule? = null
    ) {
        store.clearDeclsForModule(modulePath)
        val lineStarts = LineIndex(source)

        // Build lookup from parsed decls for doc/metadata extraction
        val parsedDeclsByName = buildParsedDeclLookup(parsedModule)

        for (decl in module.decls.filterIsInstance<TypedLetDecl>()) {
            // A bare top-level expression is lowered as a synthetic `let`, but
            // nobody declared it and no program can name it, so it is not a
            // declaration of the codebase. Its references are still recorded, from
            // its own hash, by scatterUsages.
            if (decl.name.startsWith(com.mezze.language.ast.MagicParams.TOPLEVEL_EXPR_PREFIX)) continue
            val parsed = parsedDeclsByName[decl.name]
            writeDecl(store, modulePath, decl.name, DeclKind.LET, decl.hash, decl.loc, source, lineStarts, parsed,
                type = decl.scheme.type)
        }
        // A type, ability or effect has no signature but a DEFINITION — its
        // fields, constructors or operations — indexed beside the decl row.
        for (decl in module.decls.filterIsInstance<TypedTypeDecl>()) {
            val parsed = parsedDeclsByName[decl.name]
            writeDecl(store, modulePath, decl.name, DeclKind.TYPE, decl.hash, decl.loc, source, lineStarts, parsed)
            store.putDefinition(com.mezze.language.store.DefinitionIndex.flatten(decl))
        }
        for (decl in module.abilityDecls) {
            val parsed = parsedDeclsByName[decl.name]
            writeDecl(store, modulePath, decl.name, DeclKind.ABILITY, decl.hash, decl.loc, source, lineStarts, parsed)
            store.putDefinition(com.mezze.language.store.DefinitionIndex.flatten(decl))
        }
        for (decl in module.effectDecls) {
            val parsed = parsedDeclsByName[decl.name]
            writeDecl(store, modulePath, decl.name, DeclKind.EFFECT, decl.hash, decl.loc, source, lineStarts, parsed)
            store.putDefinition(com.mezze.language.store.DefinitionIndex.flatten(decl))
        }
        for (decl in module.performerDecls) {
            val parsed = parsedDeclsByName[decl.name]
            writeDecl(store, modulePath, decl.name, DeclKind.PERFORMER, decl.hash, decl.loc, source, lineStarts, parsed)
        }
        // Ability impls have no user-facing name; synthesize one from the
        // hash so the (module, kind, name) primary key stays unique.
        for (impl in module.abilityImpls) {
            val synthName = "impl_${impl.hash.hex.take(12)}"
            writeDecl(store, modulePath, synthName, DeclKind.IMPL, impl.hash, impl.loc, source, lineStarts, null)
        }
    }

    private fun buildParsedDeclLookup(
        parsedModule: com.mezze.language.parsedast.ParsedModule?
    ): Map<String, com.mezze.language.parsedast.ParsedDecl> {
        if (parsedModule == null) return emptyMap()
        val result = mutableMapOf<String, com.mezze.language.parsedast.ParsedDecl>()
        for (decl in parsedModule.decls) {
            val name = when (decl) {
                is com.mezze.language.parsedast.ParsedLetDecl -> if (decl.isSimpleBinding) decl.variableName else null
                is com.mezze.language.parsedast.ParsedTypeDecl -> decl.name
                is com.mezze.language.parsedast.ParsedAbilityDecl -> decl.name
                is com.mezze.language.parsedast.ParsedEffectDecl -> decl.name
                is com.mezze.language.parsedast.ParsedPerformerDecl -> decl.performerName
                else -> null
            }
            if (name != null) result[name] = decl
        }
        return result
    }

    /**
     * A declaration's `#{ … }` metadata as JSON text — what `std::codebase` hands
     * a program, which decodes it into whatever type it declares. Built with a
     * JSON encoder, not by concatenation: a hand-built string escaped only `"`,
     * so a note holding a backslash or a newline was not JSON at all.
     */
    private fun serializeMetadata(metadata: com.mezze.language.parsedast.ParsedMetadata?): String? {
        if (metadata == null || metadata.fields.isEmpty()) return null
        return kotlinx.serialization.json.JsonObject(metadata.fields.mapValues { (_, v) -> metadataJson(v) }).toString()
    }

    private fun metadataJson(value: com.mezze.language.parsedast.ParsedMetadataValue): kotlinx.serialization.json.JsonElement =
        when (value) {
            is com.mezze.language.parsedast.ParsedMetadataValue.StringVal -> kotlinx.serialization.json.JsonPrimitive(value.value)
            is com.mezze.language.parsedast.ParsedMetadataValue.IntVal -> kotlinx.serialization.json.JsonPrimitive(value.value)
            is com.mezze.language.parsedast.ParsedMetadataValue.BoolVal -> kotlinx.serialization.json.JsonPrimitive(value.value)
            is com.mezze.language.parsedast.ParsedMetadataValue.FloatVal -> kotlinx.serialization.json.JsonPrimitive(value.value)
            is com.mezze.language.parsedast.ParsedMetadataValue.ListVal -> kotlinx.serialization.json.JsonArray(value.values.map { metadataJson(it) })
            is com.mezze.language.parsedast.ParsedMetadataValue.RecordVal ->
                kotlinx.serialization.json.JsonObject(value.fields.mapValues { (_, v) -> metadataJson(v) })
        }

    private fun writeDecl(
        store: MezzeStore,
        modulePath: String,
        name: String,
        kind: DeclKind,
        hash: Hash,
        loc: com.mezze.language.parsedast.Loc,
        source: String,
        lineStarts: LineIndex,
        parsedDecl: com.mezze.language.parsedast.ParsedDecl? = null,
        // Only a `let` has a type worth indexing; type/ability/effect/performer
        // decls pass null and their signature columns stay empty rather than
        // holding something that is not a signature.
        type: com.mezze.language.ast.MezzeType? = null
    ) {
        val sliced = lineStarts.slice(source, loc)
        val perDeclSourceHash = sha256Hex(sliced)
        store.putDecl(
            modulePath = modulePath,
            declName = name,
            declKind = kind,
            sourceHash = perDeclSourceHash,
            hash = hash,
            startLine = loc.startLine,
            startCol = loc.startCol,
            endLine = loc.endLine,
            endCol = loc.endCol,
            doc = parsedDecl?.docComment,
            metadata = serializeMetadata(parsedDecl?.metadata),
        )
        // The signature is a separate value with its own tables, written after
        // the decl so both land in one transaction. A decl with no type indexes
        // no signature at all, which is distinguishable from indexing an empty
        // one: a `type`/`ability`/`effect` declaration has no signature, and
        // saying so is different from claiming it has a trivial one.
        if (type != null) {
            store.putSignature(com.mezze.language.store.SignatureIndex.flatten(hash, type))
        }
    }

    /**
     * Cheap line-start index. Built once per source string; lookups are O(1)
     * because `lineStarts[line - 1]` is the offset of that line's first byte.
     * Loc is 1-indexed for both line and column; we convert at the boundary.
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

        /**
         * Slice the source covered by [loc]. End is inclusive (matches Loc
         * docs). Defensive against off-by-one: clamps to source bounds so
         * a one-past-the-end col doesn't crash if the parser ever drifts.
         */
        fun slice(source: String, loc: com.mezze.language.parsedast.Loc): String {
            val startLineIdx = (loc.startLine - 1).coerceIn(0, starts.size - 1)
            val endLineIdx = (loc.endLine - 1).coerceIn(0, starts.size - 1)
            val startOffset = (starts[startLineIdx] + (loc.startCol - 1)).coerceIn(0, source.length)
            // endCol is inclusive of the last character of the decl, so add 1
            // to land on the half-open offset for substring().
            val endOffset = (starts[endLineIdx] + loc.endCol).coerceIn(startOffset, source.length)
            return source.substring(startOffset, endOffset)
        }
    }

    /**
     * Scatter a TypedModule's declarations into individual blobs.
     * Skips putBlob for hashes already in the store; always re-registers names
     * and edges (cheap; cover module-level rebinding).
     */
    private fun scatterTypedModule(
        store: MezzeStore,
        module: TypedModule,
        modulePath: String,
        existing: Set<Hash>
    ) {
        // The names below are the module's names NOW; one deleted from the source
        // must stop resolving.
        store.clearNamesForModule(modulePath)
        for (decl in module.decls.filterIsInstance<TypedLetDecl>()) {
            if (decl.hash !in existing) {
                val visibility = if (decl.isPublic) Visibility.PUBLIC else Visibility.PRIVATE
                store.putBlob(decl.hash, BlobKind.FUNCTION, visibility,
                    StoreSerialization.serializeTypedLetDecl(decl), null)
            }
            store.registerName(modulePath, decl.name, decl.hash)
        }

        for (decl in module.decls.filterIsInstance<TypedTypeDecl>()) {
            if (decl.hash !in existing) {
                val visibility = if (decl.isPublic) Visibility.PUBLIC else Visibility.PRIVATE
                store.putBlob(decl.hash, BlobKind.TYPE, visibility,
                    StoreSerialization.serializeTypedTypeDecl(decl), null)
            }
            store.registerName(modulePath, decl.name, decl.hash)
        }

        for (decl in module.abilityDecls) {
            if (decl.hash !in existing) {
                store.putBlob(decl.hash, BlobKind.ABILITY, Visibility.PUBLIC,
                    StoreSerialization.serializeTypedAbilityDecl(decl), null)
            }
            store.registerName(modulePath, decl.name, decl.hash)
        }

        for (impl in module.abilityImpls) {
            if (impl.hash !in existing) {
                store.putBlob(impl.hash, BlobKind.ABILITY_IMPL, Visibility.PUBLIC,
                    StoreSerialization.serializeTypedAbilityImpl(impl), null)
                store.addEdge(impl.hash, impl.abilityHash, EdgeKind.IMPLEMENTS)
            }
            // #280/F11: key the target-type index cell on the CONTENT HASH of the
            // target type(s), NOT `MezzeType.toString()`. toString() is
            // depth-truncated & lossy (two distinct types can print identically —
            // see project_mezzetype_tostring_lossy), so a toString-keyed cell
            // silently collided distinct targets (INSERT OR REPLACE shadowing) and
            // dropped all but `firstOrNull()` of a multi-param impl's targets. The
            // content hash is stable and collision-free; joining ALL target hashes
            // preserves multi-param identity. Column stays a String, now holding a
            // hash-hex key (like the ability_hash/hash columns beside it).
            // One row per target, keyed by the target's structural hash — no longer
            // one comma-joined string. 80 of the stdlib's 310 impls have more than
            // one target, so joining them meant every reader had to split, and none
            // could join.
            val targetHashes = impl.targetTypes.map { typeStructureHash(it) }
            store.putAbilityImpl(impl.hash, impl.abilityHash, targetHashes, modulePath)

            // And index each target's STRUCTURE under that same hash. Without this an
            // impl can be counted but not described: `ability_impls` alone could say
            // "Ord has 16 impls" and name none of them, because a structural type
            // hash joins to no declaration — `Int` has no decl at all.
            //
            // Structure rather than a rendered name, so "does `Deque 'a` implement
            // Ord?" is answered by UNIFICATION. A generic impl (`impl Ord for
            // Deque 'a`) cannot be matched by name comparison.
            impl.targetTypes.forEachIndexed { i, target ->
                store.putSignature(
                    com.mezze.language.store.SignatureIndex.flatten(targetHashes[i], target)
                )
            }
        }

        for (decl in module.effectDecls) {
            if (decl.hash !in existing) {
                store.putBlob(decl.hash, BlobKind.EFFECT, Visibility.PUBLIC,
                    StoreSerialization.serializeTypedEffectDecl(decl), null)
            }
            store.registerName(modulePath, decl.name, decl.hash)
        }

        for (decl in module.performerDecls) {
            if (decl.hash !in existing) {
                store.putBlob(decl.hash, BlobKind.PERFORMER, Visibility.PUBLIC,
                    StoreSerialization.serializeTypedPerformerDecl(decl), null)
                store.addEdge(decl.hash, decl.effectHash, EdgeKind.IMPLEMENTS)
            }
            store.registerName(modulePath, decl.name, decl.hash)
            store.putEffectPerformer(decl.hash, decl.effectHash, decl.name, modulePath)
        }

        store.replaceBlobOwnership(
            "module:$modulePath",
            buildSet {
                module.decls.mapTo(this) { it.hash }
                module.abilityDecls.mapTo(this) { it.hash }
                module.abilityImpls.mapTo(this) { it.hash }
                module.effectDecls.mapTo(this) { it.hash }
                module.performerDecls.mapTo(this) { it.hash }
            },
        )
    }

    /**
     * Store lowered FunctionIRs and record dependency edges.
     *
     * - If hash existed in store BEFORE this populate run AND its blob already
     *   has IR: skip entirely (content-addressed: same hash → same bytes).
     * - Otherwise: read current blob row (might have been written by
     *   scatterTypedModule earlier in this same transaction with typed-only)
     *   and merge in IR.
     */
    private fun scatterFunctionIRs(
        store: MezzeStore,
        functions: List<FunctionIR>,
        modulePath: String,
        preExistingWithIR: Set<Hash>
    ) {
        for (fn in functions) {
            // Read current blob — covers both cases:
            //   (a) blob written by scatterTypedModule moments ago (typed set, ir=null)
            //   (b) blob existed before this run (nothing to write)
            val row = store.getBlob(fn.hash)
            if (row?.ir == null) {
                val irBytes = StoreSerialization.serializeFunctionIR(fn)
                store.putBlob(
                    fn.hash,
                    row?.kind ?: BlobKind.FUNCTION,
                    row?.visibility ?: Visibility.PRIVATE,
                    row?.typed,
                    irBytes
                )
            }

            // Edges UNCONDITIONALLY, and after the blob exists — `edges.from_hash`
            // has a foreign key to `blobs`, so this ordering is required.
            //
            // It used to sit behind a `continue` that skipped the whole iteration when
            // the blob already carried IR. Edges are a SEPARATE fact, and "the blob is
            // already there" says nothing about whether they were recorded — so a
            // store whose blobs came from an earlier pass, which is exactly how the
            // stdlib is precompiled, ended up with IR for 1,882 functions and ZERO
            // call edges. Not an error: `--callers` answered "nothing calls this" for
            // the entire stdlib, and a dead-code report named every private helper.
            //
            // Safe to repeat: an edge is a set member, so re-recording is a no-op.
            //
            // But only for a blob THIS store owns. A user compile also lowers some
            // stdlib functions (performers, impl lambdas) whose blobs, IR and all,
            // already live in the attached stdlib tier — so nothing is written above,
            // and `edges.from_hash` references `main.blobs`: recording them here
            // failed the foreign key and with it every `mez build` that reached one.
            // Their edges are the stdlib tier's, recorded when it was built.
            if (store.ownsBlob(fn.hash)) recordEdges(store, fn)
        }
    }

    /**
     * Walk a FunctionIR's body and record dependency edges in the store.
     * These edges power:
     * - `mez build` transitive closure (which blobs go into the JAR)
     * - LSP find-references (reverse edge lookup)
     * - Incremental invalidation (what to recompile when X changes)
     */
    private fun recordEdges(store: MezzeStore, fn: FunctionIR) {
        val fromHash = fn.hash
        collectEdgesFromNode(fn.body) { toHex, kind ->
            // Only a non-hex TARGET is skipped — `Hash`'s init rejects one, and a
            // native function's name is not a hash. The insert itself is deliberately
            // NOT guarded: wrapping it swallowed a foreign-key violation and turned
            // "every edge was dropped" into silence, which is how the empty call
            // graph went unnoticed.
            val to = try {
                Hash(toHex)
            } catch (_: IllegalArgumentException) {
                null
            }
            if (to != null) store.addEdge(fromHash, to, kind)
        }
    }

    /**
     * Walk a NodeIR tree and report all hash references as edges.
     */
    private fun collectEdgesFromNode(
        node: com.mezze.language.truffleir.NodeIR,
        report: (toHex: String, kind: EdgeKind) -> Unit
    ) {
        when (node) {
            is com.mezze.language.truffleir.CallDirectIR -> {
                report(node.targetHash.hex, EdgeKind.CALLS)
                node.args.forEach { collectEdgesFromNode(it, report) }
            }
            is com.mezze.language.truffleir.PartialApplyDirectIR -> {
                report(node.targetHash.hex, EdgeKind.CALLS)
                node.partialFields.forEach { collectEdgesFromNode(it.value, report) }
            }
            is com.mezze.language.truffleir.MakeLambdaIR -> {
                report(node.functionHash.hex, EdgeKind.CALLS)
            }
            is com.mezze.language.truffleir.HandleIR -> {
                collectEdgesFromNode(node.body, report)
                for (h in node.handlers) {
                    report(h.handlerHash.hex, EdgeKind.REFERENCES)
                    report(h.effectHash.hex, EdgeKind.REFERENCES)
                    h.params?.let { collectEdgesFromNode(it, report) }
                }
            }
            is com.mezze.language.truffleir.PerformOpIR -> {
                report(node.effectHash.hex, EdgeKind.REFERENCES)
                collectEdgesFromNode(node.arg, report)
            }
            is com.mezze.language.truffleir.BlockIR -> {
                for (stmt in node.stmts) {
                    when (stmt) {
                        is com.mezze.language.truffleir.StmtIR.Let -> collectEdgesFromNode(stmt.value, report)
                        is com.mezze.language.truffleir.StmtIR.Expr -> collectEdgesFromNode(stmt.node, report)
                    }
                }
                collectEdgesFromNode(node.result, report)
            }
            is com.mezze.language.truffleir.CallLocalIR -> collectEdgesFromNode(node.arg, report)
            is com.mezze.language.truffleir.CallNativeIR -> {
                collectEdgesFromNode(node.arg, report)
                node.args.forEach { collectEdgesFromNode(it, report) }
            }
            is com.mezze.language.truffleir.CallIndirectIR -> {
                collectEdgesFromNode(node.function, report)
                collectEdgesFromNode(node.arg, report)
            }
            is com.mezze.language.truffleir.InspectIR -> collectEdgesFromNode(node.value, report)
            is com.mezze.language.truffleir.CallIndirectScalarIR -> {
                collectEdgesFromNode(node.function, report)
                node.args.forEach { collectEdgesFromNode(it, report) }
            }
            is com.mezze.language.truffleir.MakeRecordIR -> node.fields.forEach { collectEdgesFromNode(it.value, report) }
            is com.mezze.language.truffleir.ReadFieldIR -> collectEdgesFromNode(node.record, report)
            is com.mezze.language.truffleir.RecordUpdateIR -> {
                collectEdgesFromNode(node.record, report)
                node.updates.forEach { collectEdgesFromNode(it.value, report) }
            }
            is com.mezze.language.truffleir.MakeVariantIR -> node.payload?.let { collectEdgesFromNode(it, report) }
            is com.mezze.language.truffleir.MatchIR -> {
                collectEdgesFromNode(node.scrutinee, report)
                for (c in node.cases) {
                    collectEdgesFromNode(c.body, report)
                    c.guard?.let { collectEdgesFromNode(it, report) }
                    for (ilb in c.ifLetBindings) collectEdgesFromNode(ilb.expr, report)
                }
            }
            is com.mezze.language.truffleir.IfIR -> {
                collectEdgesFromNode(node.cond, report)
                collectEdgesFromNode(node.thenBr, report)
                collectEdgesFromNode(node.elseBr, report)
            }
            is com.mezze.language.truffleir.WriteLocalIR -> collectEdgesFromNode(node.value, report)
            is com.mezze.language.truffleir.InlineBinaryOpIR -> {
                collectEdgesFromNode(node.left, report)
                collectEdgesFromNode(node.right, report)
            }
            is com.mezze.language.truffleir.SpecializedCallIR -> {
                node.args.forEach { collectEdgesFromNode(it, report) }
            }
            is com.mezze.language.truffleir.InterpolatedStringIR -> {
                for (part in node.parts) {
                    if (part is com.mezze.language.truffleir.StringPartIR.Expr) collectEdgesFromNode(part.node, report)
                }
            }
            is com.mezze.language.truffleir.MakeSeqIR -> {
                for (elem in node.elements) {
                    when (elem) {
                        is com.mezze.language.truffleir.SeqElementIR.Regular -> collectEdgesFromNode(elem.node, report)
                        is com.mezze.language.truffleir.SeqElementIR.Spread -> collectEdgesFromNode(elem.node, report)
                    }
                }
            }
            is com.mezze.language.truffleir.SeqGetIR -> {
                collectEdgesFromNode(node.seq, report)
                collectEdgesFromNode(node.index, report)
            }
            is com.mezze.language.truffleir.DictMethodCallIR -> collectEdgesFromNode(node.arg, report)
            is com.mezze.language.truffleir.ReadGlobalByHashIR -> report(node.hash.hex, EdgeKind.REFERENCES)
            is com.mezze.language.truffleir.ReadGlobalFnByHashIR -> report(node.hash.hex, EdgeKind.REFERENCES)
            is com.mezze.language.truffleir.EffectBindingIR -> {}
            // Leaf nodes — no edges
            is com.mezze.language.truffleir.ReadLocalIR -> {}
            is com.mezze.language.truffleir.ReadCaptureIR -> {}
            is com.mezze.language.truffleir.ReadArgIR -> {}
            is com.mezze.language.truffleir.LongLitIR -> {}
            is com.mezze.language.truffleir.DoubleLitIR -> {}
            is com.mezze.language.truffleir.BoolLitIR -> {}
            is com.mezze.language.truffleir.StringLitIR -> {}
            is com.mezze.language.truffleir.UnitLitIR -> {}
            is com.mezze.language.truffleir.CharLitIR -> {}
            is com.mezze.language.truffleir.FracLitIR -> {}
            is com.mezze.language.truffleir.FusedChainIR -> {
                // #446 — descend into source expression + sink init; report
                // stage/sink lambda hashes as CALLS edges (same as MakeLambda).
                when (val s = node.source) {
                    is com.mezze.language.truffleir.SourceIR.RangeSeq -> {
                        collectEdgesFromNode(s.start, report)
                        collectEdgesFromNode(s.end, report)
                    }
                    is com.mezze.language.truffleir.SourceIR.RangeStepSeq -> {
                        collectEdgesFromNode(s.start, report)
                        collectEdgesFromNode(s.end, report)
                        collectEdgesFromNode(s.step, report)
                    }
                    is com.mezze.language.truffleir.SourceIR.RawSeq -> collectEdgesFromNode(s.xs, report)
                    is com.mezze.language.truffleir.SourceIR.RawList -> collectEdgesFromNode(s.xs, report)
                    is com.mezze.language.truffleir.SourceIR.Opaque -> collectEdgesFromNode(s.expr, report)
                }
                for (stage in node.stages) {
                    when (stage) {
                        is com.mezze.language.truffleir.StageIR.Map -> report(stage.f.functionHash.hex, EdgeKind.CALLS)
                        is com.mezze.language.truffleir.StageIR.Filter -> report(stage.p.functionHash.hex, EdgeKind.CALLS)
                        is com.mezze.language.truffleir.StageIR.Take -> collectEdgesFromNode(stage.n, report)
                        is com.mezze.language.truffleir.StageIR.Drop -> collectEdgesFromNode(stage.n, report)
                        is com.mezze.language.truffleir.StageIR.TakeWhile -> report(stage.p.functionHash.hex, EdgeKind.CALLS)
                        is com.mezze.language.truffleir.StageIR.DropWhile -> report(stage.p.functionHash.hex, EdgeKind.CALLS)
                    }
                }
                when (val sk = node.sink) {
                    is com.mezze.language.truffleir.SinkIR.Fold -> {
                        collectEdgesFromNode(sk.init, report)
                        report(sk.f.functionHash.hex, EdgeKind.CALLS)
                    }
                    is com.mezze.language.truffleir.SinkIR.ForEach -> report(sk.f.functionHash.hex, EdgeKind.CALLS)
                    is com.mezze.language.truffleir.SinkIR.Reduce -> report(sk.f.functionHash.hex, EdgeKind.CALLS)
                    is com.mezze.language.truffleir.SinkIR.Any -> report(sk.p.functionHash.hex, EdgeKind.CALLS)
                    is com.mezze.language.truffleir.SinkIR.All -> report(sk.p.functionHash.hex, EdgeKind.CALLS)
                    is com.mezze.language.truffleir.SinkIR.Find -> report(sk.p.functionHash.hex, EdgeKind.CALLS)
                    is com.mezze.language.truffleir.SinkIR.FindIndex -> report(sk.p.functionHash.hex, EdgeKind.CALLS)
                    is com.mezze.language.truffleir.SinkIR.None -> report(sk.p.functionHash.hex, EdgeKind.CALLS)
                    is com.mezze.language.truffleir.SinkIR.MinBy -> report(sk.f.functionHash.hex, EdgeKind.CALLS)
                    is com.mezze.language.truffleir.SinkIR.MaxBy -> report(sk.f.functionHash.hex, EdgeKind.CALLS)
                    is com.mezze.language.truffleir.SinkIR.Partition -> report(sk.p.functionHash.hex, EdgeKind.CALLS)
                    is com.mezze.language.truffleir.SinkIR.Scan -> {
                        collectEdgesFromNode(sk.init, report)
                        report(sk.f.functionHash.hex, EdgeKind.CALLS)
                    }
                    com.mezze.language.truffleir.SinkIR.Sum,
                    com.mezze.language.truffleir.SinkIR.SumFloat,
                    com.mezze.language.truffleir.SinkIR.Product,
                    com.mezze.language.truffleir.SinkIR.ProductFloat,
                    com.mezze.language.truffleir.SinkIR.Count,
                    com.mezze.language.truffleir.SinkIR.ToSeq,
                    com.mezze.language.truffleir.SinkIR.ToList,
                    com.mezze.language.truffleir.SinkIR.First,
                    com.mezze.language.truffleir.SinkIR.Last,
                    com.mezze.language.truffleir.SinkIR.Min,
                    com.mezze.language.truffleir.SinkIR.Max -> {}
                }
            }
        }
    }

    /** SHA-256 of source bytes; matches DevRunner / LSP keying convention
     *  for the typed_modules table. */
    private fun sha256Hex(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * Persist a [com.mezze.language.typechecker.mono.Monomorphizer.Result]
     * to the store: each specialization becomes
     *
     *   1. a `blobs` row of kind `FUNCTION` (typed = serialized
     *      [TypedLetDecl], ir = null — IR comes from the compiler in #257),
     *   2. a `mono_specializations` row mapping specialized → generic.
     *
     * Idempotent — `INSERT OR REPLACE` on both tables. Skipped entirely
     * when the result has no specializations (the caller uses this in the
     * default code path; mono-off runs pass an empty result and pay one
     * Map size check).
     *
     * @param store target store (project cache, NOT stdlib).
     * @param mono the monomorphizer's output.
     * @param genericNameByHash optional `genericHash → name` map. When
     *   provided, the wrapped specialization decl gets the generic's name
     *   (purely for reading-the-blob ergonomics; specializations are still
     *   addressed by hash, never by name). Pass `emptyMap()` if unknown.
     */
    fun populateMonoSpecializations(
        store: MezzeStore,
        mono: com.mezze.language.typechecker.mono.Monomorphizer.Result,
        genericNameByHash: Map<Hash, String> = emptyMap()
    ) {
        if (mono.specializations.isEmpty()) return
        store.transaction {
            for ((specHash, spec) in mono.specializations) {
                // The specialized blob is a TypedLetDecl wrapping the
                // substituted body — same shape as the original generic
                // but with concrete types. typeParams are empty (every
                // bound variable was substituted away). The scheme.type
                // mirrors that.
                val name = genericNameByHash[spec.genericHash] ?: ""
                val specializedDecl = TypedLetDecl(
                    hash = specHash,
                    name = name,
                    typeParams = emptyList(),
                    value = spec.specializedBody,
                    scheme = TypeScheme(
                        typeParams = emptyList(),
                        abilityConstraints = emptyList(),
                        type = spec.specializedType
                    ),
                    isPublic = false,
                    loc = spec.specializedBody.loc
                )
                val typedBytes = StoreSerialization.serializeTypedLetDecl(specializedDecl)
                store.putBlob(
                    hash = specHash,
                    kind = BlobKind.FUNCTION,
                    visibility = Visibility.PRIVATE,
                    typed = typedBytes,
                    ir = null
                )
                val typeArgsCbor = StoreSerialization.serializeTypeList(spec.typeArgs)
                store.putMonoSpecialization(specHash, spec.genericHash, typeArgsCbor)

                // No `edges` row from generic → spec: `edges.from_hash` has
                // an FK to `blobs(hash)`, and the generic may not be in
                // `blobs` at the moment this runs (e.g., when stdlib
                // generics are referenced). The provenance row in
                // `mono_specializations` already encodes the relationship —
                // tools that need to walk generic→spec query
                // `getMonoSpecializationsForGeneric(genericHash)` directly.
            }
        }
    }
}

/** Records one reference: its target, where it is written, and whether it is a call. */
private typealias UsageSink = (com.mezze.language.typedast.Hash, com.mezze.language.parsedast.Loc, Boolean) -> Unit
