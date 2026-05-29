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
 * forced on. The wire-protocol path inside `DevCommand` does exactly
 * what `exec` needs (load → typecheck → lower → execute → exit), so
 * `ExecCommand` is a thin shell that normalises the app-name argument.
 *
 * #213
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

        // Dev-only flags (--inspect, --profile, etc.) are rejected by
        // DevCommand; no need to filter them here.
        //
        // Normalise the bare positional `mez exec foo` into the `--app=foo`
        // form DevCommand already understands. A positional is anything
        // that isn't a flag and isn't already consumed by `--app`.
        val hasAppFlag = mezArgs.any { it == "--app" || it.startsWith("--app=") }
        val positional = if (!hasAppFlag) mezArgs.firstOrNull { !it.startsWith("-") } else null

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
}
