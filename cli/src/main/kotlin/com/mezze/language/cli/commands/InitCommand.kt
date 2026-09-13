package com.mezze.language.cli.commands

import com.mezze.language.cli.`package`.validatePackageName
import com.mezze.language.cli.`package`.validateVersion
import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.codebase.StorePopulator
import java.io.File
import java.security.MessageDigest

/**
 * Handle `mezze init` command to create a new Mezze package
 *
 * Usage:
 *   mezze init <name>              # Interactive mode - creates folder <name>
 *   mezze init .                   # Initialise the CURRENT directory in place
 *   mezze init <name> --lib        # Create library package
 *   mezze init <name> --app        # Create app package
 *   mezze init <name> --lib --app  # Create both library and app
 *
 * `init .` is for an existing repo: it writes into the current directory
 * (refusing only if a Mezze.toml is already there) and APPENDS the mez ignore
 * patterns to an existing .gitignore rather than overwriting it.
 */
class InitCommand {

    fun execute(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        val hasLibFlag = args.contains("--lib")
        val hasAppFlag = args.contains("--app")
        val nameArg = args.firstOrNull { !it.startsWith("--") }

        // Resolve type. Flags win; otherwise default to --app. Interactive
        // (type prompt) only triggers when the user ran `mez init` with no
        // args at all — see below.
        var createLib: Boolean
        var createApp: Boolean
        when {
            hasLibFlag && hasAppFlag -> { createLib = true; createApp = true }
            hasLibFlag -> { createLib = true; createApp = false }
            else -> { createLib = false; createApp = true }
        }

        // Resolve name. Positional wins; otherwise — only when no args at
        // all — drop into the two-question interactive flow (type, name).
        // Any flag (e.g. `mez init --lib`) keeps the silent path and just
        // errors on the missing name; we don't want to half-ask.
        val projectName: String = if (nameArg != null) {
            nameArg
        } else if (args.isEmpty()) {
            val (t, n) = promptInteractive() ?: return
            createLib = t.lib
            createApp = t.app
            n
        } else {
            println("Error: missing project name")
            println("Usage: mez init <name> [--lib] [--app]")
            return
        }

        // `mez init .` initialises the CURRENT directory in place (for an
        // existing repo), rather than creating a subfolder. The package name
        // then comes from the dir's own name, and an existing Mezze.toml is a
        // hard stop (don't clobber a real project) while an existing .gitignore
        // is fine — ensureGitignore appends the mez entries it's missing.
        val inPlace = projectName == "."
        val targetDir = if (inPlace) currentDir else File(currentDir, projectName)
        if (inPlace) {
            if (File(targetDir, "Mezze.toml").exists()) {
                println("Error: this directory is already a Mezze project (Mezze.toml exists)")
                return
            }
        } else {
            if (targetDir.exists()) {
                println("Error: folder '$projectName' already exists at ${targetDir.absolutePath}")
                return
            }
            if (!targetDir.mkdirs()) {
                println("Error: failed to create folder '$projectName'")
                return
            }
        }

        val projectDir = targetDir
        val mezzeToml = File(projectDir, "Mezze.toml")

        // Get package name from the project name — or, for in-place init, from
        // the current directory's own name.
        val nameForPackage = if (inPlace) projectDir.absoluteFile.name else projectName
        val packageName = nameForPackage.lowercase().replace("-", "_")

        try {
            validatePackageName(packageName)
        } catch (e: IllegalArgumentException) {
            println("Error: invalid package name '$packageName'")
            println(e.message)
            println("Rename the directory to snake_case and retry.")
            return
        }

        val version = "0.1.0"
        createMezzeToml(mezzeToml, packageName, version, createLib, createApp)

        val srcDir = File(projectDir, "src")
        if (!srcDir.exists()) srcDir.mkdirs()

        if (createLib) createLibFile(File(srcDir, "lib.mz"), packageName)
        if (createApp) createAppFile(File(srcDir, "app.mz"), packageName)

        val gitignore = File(projectDir, ".gitignore")
        ensureGitignore(gitignore)

        // Warn if the new project is nested inside an existing one — it's an
        // independent project (the nearest Mezze.toml wins for any command run
        // inside it), which is easy to create by accident. Discover from the
        // ENCLOSING dir: for in-place init the project's own Mezze.toml already
        // exists here, so searching from projectDir would spuriously find itself.
        (projectDir.parentFile ?: currentDir).let { ProjectRoot.discover(it) }?.let { outer ->
            println("${Glyphs.WARN}  '$nameForPackage' is nested inside the project at ${outer.absolutePath}")
            println("    It is an independent project — the nearest Mezze.toml wins for commands run inside it.")
        }

        // Materialise the project's `.mez/` store with stdlib seeded in.
        // openProject creates src.sqlite + stdlib.sqlite + deps.sqlite,
        // attaches them, and installs the parser-cache hook. If
        // createApp, also pre-typecheck the entry so the first
        // `mez dev` hits the warm-cache fast path.
        val store = SQLiteMezzeStore.openProject(projectDir)
        com.mezze.language.typechecker.PrecompiledStdlib.setStore(store)
        com.mezze.language.typechecker.ModuleResolver.setStore(store)
        try {
            if (createApp) {
                val appFile = File(srcDir, "app.mz")
                if (appFile.exists()) {
                    val appName = packageName  // matches what createMezzeToml writes for [[app]].name
                    runInitialCompile(store, projectDir, appFile, appName, packageName)
                }
            }
        } finally {
            store.close()
        }

        val kindLabel = when {
            createLib && createApp -> "lib + app"
            createLib -> "lib"
            else -> "app"
        }
        println("Created $packageName ($kindLabel) in ${targetDir.absolutePath}")
        println()
        println("Next steps")
        if (!inPlace) println("  cd $nameForPackage")
        when {
            createLib && !createApp -> println("  mez check       type-check your library")
            createApp && !createLib -> {
                println("  mez dev         run the app")
                println("  mez build       build a standalone JAR")
            }
            else -> {
                println("  mez dev         run the app")
                println("  mez build       build a standalone JAR")
            }
        }

        com.mezze.language.cli.EnvironmentCheck.shortReport()?.let { println(it) }
    }

    private data class PackageKind(val lib: Boolean, val app: Boolean)

    /**
     * Interactive prompt: choose package type, then enter a name.
     * Returns null if the user aborts (EOF on a required prompt).
     * Only invoked when the user runs `mez init` with no args at all.
     */
    private fun promptInteractive(): Pair<PackageKind, String>? {
        println("Package type:")
        println("  1) App (executable program)  [default]")
        println("  2) Library (reusable code)")
        println("  3) Both (library with CLI tool)")
        print("Choice [1-3]: ")
        val kind = when (readLine()?.trim().orEmpty()) {
            "", "1" -> PackageKind(lib = false, app = true)
            "2"     -> PackageKind(lib = true,  app = false)
            "3"     -> PackageKind(lib = true,  app = true)
            else -> {
                println("Invalid choice; defaulting to app.")
                PackageKind(lib = false, app = true)
            }
        }
        println()
        print("Project name: ")
        val name = readLine()?.trim().orEmpty()
        if (name.isEmpty()) {
            println("Error: project name is required.")
            return null
        }
        println()
        return kind to name
    }

    private fun createMezzeToml(file: File, packageName: String, version: String, hasLib: Boolean, hasApp: Boolean) {
        val content = buildString {
            appendLine("[package]")
            appendLine("name = \"$packageName\"")
            appendLine("version = \"$version\"")

            if (hasLib) {
                appendLine()
                appendLine("[lib]")
                appendLine("path = \"src/lib.mz\"")
            }

            if (hasApp) {
                appendLine()
                appendLine("[[app]]")
                appendLine("name = \"$packageName\"")
                appendLine("path = \"src/app.mz\"")
                appendLine("entry = \"main\"")
            }

            appendLine()
            appendLine("[dependencies]")

            appendLine()
            appendLine("[dev-dependencies]")
        }

        file.writeText(content)
    }

    private fun createLibFile(file: File, packageName: String) {
        val content = """
            pub let example = {} -> {
                message = "Hello from $packageName!"
            }
        """.trimIndent()

        file.writeText(formatScaffold(content))
    }

    private fun createAppFile(file: File, packageName: String) {
        val content = """
            use std::effects::log::{println, Console}

            let main = {} -> perform Console in do
                println { msg = "Hello from $packageName!" }
        """.trimIndent()

        file.writeText(formatScaffold(content))
    }

    /**
     * Run a scaffold template through the formatter so a fresh project starts in
     * canonical mez style (and stays `mez fmt --check`-clean). Best-effort: if the
     * template somehow fails to format, fall back to the raw source — a scaffold
     * must never block `mez init`. Ensures a trailing newline either way.
     */
    private fun formatScaffold(source: String): String = try {
        com.mezze.language.formatter.Formatter().format(source)
    } catch (_: Exception) {
        if (source.endsWith("\n")) source else source + "\n"
    }

    /**
     * Parse + typecheck the freshly-created template entry and populate
     * the project's `.mez/store/src.sqlite`. The first `mez dev` is then
     * warm: COLD_START's fast path matches because the cached typed
     * modules + IR are already there.
     *
     * Failures are swallowed (template typecheck error → just skip the
     * pre-warm; project is still usable, first `mez dev` will surface
     * the error). We don't want a flaky template stack to block init.
     */
    private fun runInitialCompile(
        store: SQLiteMezzeStore,
        projectDir: File,
        appFile: File,
        appName: String,
        modulePath: String
    ) {
        try {
            val source = appFile.readText()
            val ast = com.mezze.language.parser.MezzeParser()
                .parseParsedModule(source, file = appFile.name)

            val typeChecker = com.mezze.language.typechecker.TypeChecker()
            val result = typeChecker.typeCheck(ast, projectDir.toPath(), enablePrelude = true)
            if (result.loweredFunctions.isEmpty()) return  // template didn't compile cleanly; skip silently

            val depParsed = com.mezze.language.typechecker.ModuleResolver
                .getAllParsedModules()
            val sourceHash = sha256(source)
            StorePopulator.populate(
                store, result, modulePath, appFile.absolutePath, sourceHash,
                entrySource = source,
                parsedModule = ast,
                loadedParsedModules = depParsed
            )
            // Front-door name→hash: templates always use `main` as the entry.
            // Persist the entry's content hash as identity; keep the name for
            // display. If the template lacks `main`, skip app registration
            // (the pre-typecheck is best-effort; see the catch below).
            val entryHash = result.loweredFunctions.find { it.name == "main" }?.hash ?: return
            store.transaction {
                store.putApp(appName, entryHash, "main", modulePath, appFile.absolutePath)
                store.setAppFunctionHashes(appName, result.loweredFunctions.map { it.hash })
                store.setAppPerformerRecords(appName, result.performerRecords)
            }
            // cache populated silently — surface only on MEZZE_DEBUG (see catch below)
        } catch (e: Exception) {
            // Don't fail `mez init` on a template type error; the user
            // can still edit and re-run. But surface in MEZZE_DEBUG so
            // template breakage doesn't go silent.
            if (System.getenv("MEZZE_DEBUG") != null) {
                System.err.println("[init] pre-typecheck skipped: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun createGitignore(file: File) {
        file.writeText(GITIGNORE_CONTENT)
    }

    /**
     * Create `.gitignore` if absent; if it already exists (common — the repo
     * predates `mez init`, or the user ran `git init` first), APPEND only the
     * mez-specific entries it's missing rather than skip it entirely. Skipping
     * was the old behaviour and left `.deps/`, `.mez/`, `.build/` untracked-but-
     * not-ignored in any pre-existing repo — the exact footgun (`.deps` full of
     * symlinks committed by accident) this avoids.
     */
    private fun ensureGitignore(file: File) {
        if (!file.exists()) {
            createGitignore(file)
            return
        }
        val existing = file.readText()
        val addition = gitignoreAdditions(existing) ?: return
        file.appendText(addition)
    }

    companion object {
        /** The mez-specific ignore patterns, checked line-by-line when appending. */
        private val MEZ_IGNORE_PATTERNS = listOf(".build/", "*.jar", "Mezze.lock", ".deps/", ".mez/")

        /**
         * The text to append to an EXISTING `.gitignore` so it ignores the
         * mez-generated files it's missing — or null if it already covers them
         * all. Pure (no disk) so it's unit-testable. A pattern counts as present
         * when it appears as a whole trimmed line, so we neither re-add `.mez/`
         * the user already ignores nor get fooled by it inside a comment.
         */
        fun gitignoreAdditions(existing: String): String? {
            val existingLines = existing.lineSequence().map { it.trim() }.toSet()
            val missing = MEZ_IGNORE_PATTERNS.filter { it !in existingLines }
            if (missing.isEmpty()) return null
            return buildString {
                if (existing.isNotEmpty() && !existing.endsWith("\n")) append('\n')
                append("\n# Added by `mez init` — Mezze generated files (safe to delete, rebuilt).\n")
                missing.forEach { append(it).append('\n') }
            }
        }

        private val GITIGNORE_CONTENT = """
            # Mezze build artifacts (default — change `out` in Mezze.toml's
            # [build] section if you use a different output directory).
            .build/
            *.jar
            Mezze.lock

            # Resolved dependencies (generated by `mez sync`; symlinks / fetched
            # packages — rebuilt from Mezze.toml, never commit).
            .deps/

            # Mezze project cache (generated; safe to delete, will be rebuilt).
            .mez/

            # IDE
            .idea/
            .vscode/
            *.iml

            # OS
            .DS_Store
            Thumbs.db
        """.trimIndent()
    }
}
