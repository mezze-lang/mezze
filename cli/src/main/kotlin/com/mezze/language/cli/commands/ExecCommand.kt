package com.mezze.language.cli.commands

import java.io.File

/**
 * `mez exec [app_name | script.mz]` — one-shot execution.
 *
 * Two modes:
 *
 * 1. **Project mode** — `Mezze.toml` present in cwd. Same fast execution
 *    path as `mez dev` (JIT-on Truffle, in-process), but exits after a
 *    single run instead of starting the file watcher. No `dist/` output.
 *    App selection:
 *      - default: first `[[app]]` entry in `Mezze.toml`
 *      - positional: `mez exec foo`
 *      - flag form: `mez exec --app foo` or `mez exec --app=foo`
 *
 * 2. **Standalone script mode** (#276) — positional is a path to a
 *    `.mz` file. Runs that file directly with no project setup: entry
 *    `main`, module name derived from the file stem. Works from any
 *    directory, including with an absolute path. Skips manifest loading
 *    entirely.
 *
 * The project-mode path forwards to `DevCommand` with `--no-watch`.
 * Standalone-script mode calls `WireRunner.execOnce` directly since
 * `DevCommand` requires a `Mezze.toml`.
 *
 * #213, #276
 */
class ExecCommand {

    fun execute(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        // Split user-program arguments off at `--`. Everything before is
        // mez/dev flags; everything after is forwarded verbatim into the
        // user program's `Environment.process_args`. Mirrors the conventional
        // shape of `cargo run -- foo bar`, `npm run script -- --flag`.
        val sepIdx = args.indexOf("--")
        val mezArgs = if (sepIdx >= 0) args.subList(0, sepIdx) else args
        val userArgs = if (sepIdx >= 0) args.subList(sepIdx + 1, args.size) else emptyList()

        val hasAppFlag = mezArgs.any { it == "--app" || it.startsWith("--app=") }
        val positional = if (!hasAppFlag) mezArgs.firstOrNull { !it.startsWith("-") } else null

        // Standalone-script mode: positional refers to an existing .mz
        // file. Resolve relative to cwd. This wins over project mode
        // even if a Mezze.toml is present — an explicit script path is
        // an explicit intent to run that file.
        if (positional != null) {
            val scriptFile = resolveScript(positional, currentDir)
            if (scriptFile != null) {
                runStandalone(scriptFile, mezArgs, userArgs)
                return
            }
        }

        // Project mode: forward to DevCommand.
        val forwarded = buildList {
            if (positional != null) add("--app=$positional")
            for (a in mezArgs) {
                if (a == positional) continue
                add(a)
            }
            add("--no-watch")
        }

        DevCommand().execute(forwarded, currentDir, appArgs = userArgs)
    }

    /** Return the file iff [arg] names an existing `.mz` file we can run
     *  as a standalone script. Absolute paths pass through unchanged;
     *  bare paths resolve against [currentDir]. */
    private fun resolveScript(arg: String, currentDir: File): File? {
        if (!arg.endsWith(".mz")) return null
        val f = if (File(arg).isAbsolute) File(arg) else File(currentDir, arg)
        return if (f.isFile) f.canonicalFile else null
    }

    private fun runStandalone(scriptFile: File, mezArgs: List<String>, userArgs: List<String>) {
        val devFlags = mezArgs.filter {
            it in DevCommand.DEV_FLAGS_BOOLEAN
                || it.startsWith("--insight=")
                || it.startsWith("--dap=")
        }
        // Synthesize project fields: use the parent dir as project root
        // (that's where any `.mz/` cache directory will live) and the
        // file stem as both module path and app name. `entry` is always
        // `main` — a standalone script has no way to declare otherwise.
        val projectDir = scriptFile.parentFile ?: File(".")
        val stem = scriptFile.nameWithoutExtension
        val exit = WireRunner.execOnce(
            currentDir = projectDir,
            appFile = scriptFile,
            entryName = "main",
            modulePath = stem,
            appName = stem,
            devFlags = devFlags,
            appArgs = userArgs,
        )
        kotlin.system.exitProcess(exit)
    }
}
