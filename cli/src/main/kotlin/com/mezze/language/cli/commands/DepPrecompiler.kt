package com.mezze.language.cli.commands

import com.mezze.language.cli.BuildInfo
import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.codebase.StorePopulator
import com.mezze.language.parser.MezzeParser
import com.mezze.language.store.BlobKind
import com.mezze.language.store.StoreSerialization
import com.mezze.language.store.Visibility
import com.mezze.language.typechecker.TypeCheckResult
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedLetDecl
import java.io.File
import java.security.MessageDigest

/**
 * Compile a dependency's library ONCE into a per-dep, per-compiler store that
 * lives beside its source, so consuming projects load its IR instead of
 * recompiling it (the #46 global compile memo).
 *
 * The store's blobs are content-addressed: a function's hash transitively
 * encodes its whole dependency closure, so any compile of the dep produces the
 * SAME hashes a consumer would — reuse lines up by construction, and a change
 * anywhere (dep or a stdlib it calls) yields a different hash → the old blob is
 * never requested → recompile. The file name pins the compiler
 * (`compiled-<VERSION>+<GIT_SHA>.sqlite`) so a different toolchain simply misses
 * and recompiles. See the store-aware closure in NativeBuildPath.
 *
 * Best-effort + idempotent: skips if the store already exists; on any failure it
 * leaves no store, so consumers fall back to compiling the dep from source.
 * Additive — nothing reads these stores until the resolver wiring lands.
 */
object DepPrecompiler {

    /** Where a dep's compiled store lives (beside its source, pinned to this compiler). */
    fun compiledStoreFile(depDir: File): File =
        File(depDir, "compiled-${BuildInfo.VERSION}+${BuildInfo.GIT_SHA}.sqlite")

    /**
     * Compile the dep at [depDir] (its `[lib]` entry [libRelPath], module
     * [depModulePath]) into [compiledStoreFile], storing only the dep's OWN
     * functions (its callees into other packages live in THOSE deps' stores;
     * stdlib is embedded at runtime). No-op if already present or on any error.
     */
    fun precompile(depDir: File, libRelPath: String, depModulePath: String) {
        val out = compiledStoreFile(depDir)
        if (out.isFile) return
        val libFile = File(depDir, libRelPath)
        if (!libFile.isFile) return

        try {
            // Resolve the dep's own imports against ITS OWN dir, via a FRESH
            // per-dir resolver — NOT the process-global one. checkParsedModule
            // only reaches for the shared precompiled.importResolver when
            // currentPath == the `mezze.project.root` sysprop; so we deliberately
            // leave that sysprop pointing at the consumer (never the dep dir) and
            // pass the dep dir as currentPath. currentPath != projectRoot then
            // forces ModuleLoader.fromPath(depDir), which roots at the dep and
            // finds its `.deps`. Setting the sysprop to depDir instead routed
            // every dep through one shared resolver, so a prior dep's root shadowed
            // the next dep's imports (order-dependent "module not found").
            // Still clear the companion module caches so a stale cross-dep entry
            // can't shadow either (the typechecker's clear re-preloads stdlib).
            com.mezze.language.typechecker.ModuleResolver.clearCache()
            com.mezze.language.typechecker.module.ModuleTypeChecker.clearCache()
            val source = libFile.readText()
            val parsed = MezzeParser().parseWithRecovery(source, file = libFile.name)
            if (parsed.errors.isNotEmpty()) return

            val tc = TypeChecker(strictTopLevel = true)
            val res = tc.checkParsedModule(parsed.module, depDir.toPath(), depModulePath, enablePrelude = true)
            if (res !is TypeChecker.TypeCheckResult.Success) return
            val typed = res.typedModule ?: return
            val lowered = res.loweredFunctions ?: return

            // The dep's OWN functions = the hashes of the decls it defines PLUS
            // the synthetic hashes of its ability-impl operations. Ability ops are
            // lowered as standalone functions keyed by computeOpHash(impl, op)
            // (see TypedASTCompiler); without them, an ability-heavy dep's methods
            // would be re-lowered by every consumer instead of served from here.
            val ownHashes = buildSet {
                typed.decls.filterIsInstance<TypedLetDecl>().forEach { add(it.hash) }
                typed.abilityImpls.forEach { impl ->
                    impl.operations.keys.forEach { opName -> add(Hash.computeOpHash(impl.hash, opName)) }
                }
            }

            // Store the SAME bytes a from-source build produces: canonicalize
            // iter/seq/list HOF chains into FusedChainIR, exactly as
            // StdlibPrecompiler does and as WireRunner / NativeBuildPath do on
            // every closure. The consumer re-canonicalizes its whole closure
            // (idempotent on already-canonical IR), so this only guarantees our
            // stored blob is bit-identical to what the consumer would have
            // lowered itself.
            val canonicalized = com.mezze.language.truffleir.ChainCanonicalizer
                .canonicalize(lowered.associateBy { it.hash })

            val tmp = File(out.parentFile, ".tmp-${out.name}").also { it.delete() }
            val store = SQLiteMezzeStore.forFile(tmp)
            try {
                store.transaction {
                    StorePopulator.populate(
                        store,
                        TypeCheckResult(typedModule = typed),
                        modulePath = depModulePath,
                        filePath = libFile.absolutePath,
                        sourceHash = sha256(source),
                        entrySource = source,
                        parsedModule = parsed.module,
                    )
                    for (hash in ownHashes) {
                        val fn = canonicalized[hash] ?: continue
                        val existing = store.getBlob(hash)
                        store.putBlob(
                            hash,
                            existing?.kind ?: BlobKind.FUNCTION,
                            existing?.visibility ?: Visibility.PUBLIC,
                            existing?.typed,
                            StoreSerialization.serializeFunctionIR(fn),
                        )
                    }
                }
            } finally {
                store.close()
            }
            if (out.exists()) out.delete()
            java.nio.file.Files.move(tmp.toPath(), out.toPath())
        } catch (_: Throwable) {
            // Best-effort: any failure ⇒ no store ⇒ consumer compiles from source.
            runCatching { File(out.parentFile, ".tmp-${out.name}").delete() }
        }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
