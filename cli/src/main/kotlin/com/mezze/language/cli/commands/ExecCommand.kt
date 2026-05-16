package com.mezze.language.cli.commands

import java.io.File

/**
 * `mez exec [app_name]` — one-shot execution of the project's app.
 *
 * Same fast execution path as `mez dev` (JIT-on Truffle, in-process),
 * but exits after a single run instead of starting the file watcher.
 * No `dist/` artifacts are produced.
 *
 * App selection mirrors `mez dev`:
 * - default: first `[[app]]` entry in `Mezze.toml`
 * - positional: `mez exec foo`
 * - flag form: `mez exec --app foo` or `mez exec --app=foo`
 *
 * Implementation note: we forward to `DevCommand` with `--no-watch`
 * forced on. The shared `DevRunner.runOnce` path already does exactly
 * what `exec` needs (load → typecheck → lower → execute → exit), so
 * `ExecCommand` is a thin shell that strips dev-only flags
 * (`--inspect`, `--profile`, …) and normalises the app-name argument.
 *
 * #213
 */
class ExecCommand {

    fun execute(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        // Strip dev-only flags. Anything that's specific to interactive
        // development (debuggers, watchers, profilers) doesn't belong on
        // a one-shot run and we deliberately drop it rather than silently
        // accept it to avoid users thinking `mez exec --inspect` works.
        val rejected = setOf("--inspect", "--inspect-brk", "--profile", "--coverage", "--allocations", "--trace-jit")
        val bad = args.firstOrNull { it in rejected || it.startsWith("--insight=") }
        if (bad != null) {
            println("Error: '$bad' is not supported by 'mez exec' — use 'mez dev' for interactive runs.")
            kotlin.system.exitProcess(1)
        }

        // Normalise the bare positional `mez exec foo` into the `--app=foo`
        // form DevCommand already understands. A positional is anything
        // that isn't a flag and isn't already consumed by `--app`.
        val hasAppFlag = args.any { it == "--app" || it.startsWith("--app=") }
        val positional = if (!hasAppFlag) args.firstOrNull { !it.startsWith("-") } else null

        val forwarded = buildList {
            if (positional != null) add("--app=$positional")
            for (a in args) {
                if (a == positional) continue
                add(a)
            }
            add("--no-watch")
        }

        DevCommand().execute(forwarded, currentDir)
    }
}
