package com.mezze.language.cli.commands

import com.akuleshov7.ktoml.Toml
import com.mezze.language.cli.`package`.MezzeToml
import com.mezze.language.cli.`package`.packageNameToModule
import com.mezze.language.codebase.StorePopulator
import com.mezze.language.parser.MezzeParser
import com.mezze.language.store.MezzeStore
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
import java.io.File
import java.security.MessageDigest

/**
 * Brings the store up to date with EVERY module of a project, for codebase
 * questions (introspection §9, T5).
 *
 * A build indexes what its entry reaches. That is the program, but not the
 * codebase: a module nothing imports — a lint target, a set of handlers listed
 * nowhere yet — was invisible to `Codebase.decls`. So the first question a build
 * asks brings every `src/` module in, and a build that asks nothing pays nothing.
 *
 * Module names follow the build's own rule: an entry file (`[[app]]`, `[lib]`) is
 * the package's module; any other file is its path under `src/`. A module whose
 * source has not changed since it was indexed is left alone.
 */
internal object ProjectIndex {

    fun ensure(store: MezzeStore, projectRoot: File) {
        val src = File(projectRoot, "src")
        if (!src.isDirectory) return
        val manifest = File(projectRoot, "Mezze.toml").takeIf { it.isFile }?.let {
            Toml.decodeFromString(MezzeToml.serializer(), it.readText())
        }
        val packageModule = manifest?.let { packageNameToModule(it.`package`.name) }
        val entryFiles = buildList {
            manifest?.app.orEmpty().forEach { add(File(projectRoot, it.path).canonicalFile) }
            manifest?.lib?.let { add(File(projectRoot, it.path).canonicalFile) }
        }.toSet()

        val files = src.walkTopDown().filter { it.isFile && it.extension == "mz" }.sortedBy { it.path }.toList()
        for (file in files) {
            val module = if (packageModule != null && file.canonicalFile in entryFiles) packageModule
                else file.relativeTo(src).path.removeSuffix(".mz").replace(File.separatorChar, '/').replace("/", "::")
            val text = file.readText()
            val sha = sha256(text)
            if (store.getModule(module)?.sourceHash == sha) continue
            index(store, projectRoot, module, file, text, sha)
        }
    }

    private fun index(store: MezzeStore, projectRoot: File, module: String, file: File, text: String, sha: String) {
        val where = file.relativeTo(projectRoot).path
        val parsed = try {
            MezzeParser().parseParsedModule(text, file.name)
        } catch (e: Exception) {
            // Answering from the rest would read as "the codebase has none of
            // these", which is false; say which module is in the way instead.
            throw IllegalStateException("cannot answer a codebase question: $where does not parse (${e.message})")
        }
        val raw = TypeChecker().checkParsedModule(parsed, projectRoot.toPath(), currentModulePath = module, enablePrelude = true)
        val ok = raw as? TypeChecker.TypeCheckResult.Success
            ?: throw IllegalStateException("cannot answer a codebase question: $where does not type-check")
        StorePopulator.populate(
            store,
            TypeCheckRunner.wrapSuccess(ok),
            module, file.absolutePath, sha,
            entrySource = text,
            parsedModule = parsed,
            loadedParsedModules = ModuleResolver.getAllParsedModules(),
        )
    }

    private fun sha256(content: String): String =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
}
