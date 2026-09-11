package com.mezze.language.cli.toolchain

import com.mezze.language.cli.MezzeHome
import java.io.File
import kotlin.system.exitProcess

/**
 * The `~/.mezze/bin/mez` proxy.
 *
 * `mez` installs as two things: one binary per version under
 * `~/.mezze/toolchains/<version>/bin/mez`, and a single proxy on the user's PATH
 * at `~/.mezze/bin/mez`. The proxy is just a copy of a `mez` binary — what makes
 * it *the proxy* is only its path.
 *
 * When a binary starts and finds it is running from the proxy path, it resolves
 * which installed toolchain should handle the command — via [ToolchainResolver]'s
 * rustup-style precedence (`+ver` > `MEZ_VERSION` > project `[toolchain]` >
 * global default > latest installed) — and re-execs that toolchain's own binary,
 * forwarding args, stdio, and the exit code. Running as anything else (a
 * toolchain's own `bin/mez`, or a dev/JVM launch) it returns immediately so the
 * CLI runs in-process.
 *
 * Dispatch lives inside the binary, not in a shell script, so version selection
 * has exactly one implementation (ToolchainResolver) and behaves identically on
 * every OS. The decision is pure ([decide]); only [maybeReexecAndExit] touches
 * the process.
 */
object ProxyDispatch {

    sealed interface Action {
        /** Re-exec [target] with [args]; the proxy waits and exits with its code. */
        data class Reexec(val target: File, val args: List<String>) : Action
        /** Nothing to dispatch to — print [message] to stderr and exit [code]. */
        data class Fail(val message: String, val code: Int) : Action
        /** Not the proxy (or would self-dispatch): run the CLI in this process. */
        data object RunInProcess : Action
    }

    /** Entry point: called first thing in `main`. Dispatches and exits, or returns to run in-process. */
    fun maybeReexecAndExit(args: Array<String>) {
        val action = decide(
            self = MezzeHome.executable,
            home = MezzeHome.home,
            cwd = File(System.getProperty("user.dir")),
            env = System.getenv("MEZ_VERSION"),
            args = args.toList(),
            paths = ToolchainPaths.resolve(MezzeHome.home),
        )
        when (action) {
            is Action.RunInProcess -> return
            is Action.Fail -> { System.err.println(action.message); exitProcess(action.code) }
            is Action.Reexec -> exitProcess(exec(action.target, action.args))
        }
    }

    /**
     * Pure dispatch decision. [self] is the running executable (null ⇒ dev/JVM
     * launch ⇒ run in-process). We are the proxy iff [self] is `<home>/bin/mez`.
     */
    fun decide(self: File?, home: File, cwd: File, env: String?, args: List<String>, paths: ToolchainPaths): Action {
        val selfC = self?.let { canon(it) } ?: return Action.RunInProcess
        // We are the proxy iff we're `<home>/bin/mez` — matched by extension so a
        // Windows `mez.exe` (or a `.cmd`/`.bat` shim) counts too.
        val inProxyBin = selfC.parentFile?.let { canon(it) == canon(File(home, "bin")) } == true
        if (!inProxyBin || selfC.nameWithoutExtension != "mez") return Action.RunInProcess

        // Leading `+<channel>` is an explicit, one-shot version selector.
        val explicit = args.firstOrNull()?.takeIf { it.startsWith("+") && it.length > 1 }?.substring(1)
        val forwarded = if (explicit != null) args.drop(1) else args

        // Management commands (`mez toolchain …`) operate on the shared ~/.mezze
        // world — the toolchains dir and settings.toml — not on a program. Handle
        // them in the proxy itself so `toolchain default/pin/list/remove` keep
        // working even when the resolved default is broken or uninstalled
        // (otherwise you could pin yourself into a corner you can't unpin).
        if (forwarded.firstOrNull() == "toolchain") return Action.RunInProcess

        val resolution = ToolchainResolver.resolve(
            explicit = explicit,
            env = env,
            projectChannel = readProjectChannel(cwd),
            default = paths.defaultChannel(),
            installed = paths.installed(),
        )
        return when (resolution) {
            is ToolchainResolver.Resolution.Ok -> {
                val binDir = File(paths.dirFor(resolution.version), "bin")
                val target = toolchainBinary(binDir)
                when {
                    target == null -> Action.Fail(
                        "mez: toolchain '${resolution.version}' is installed but its binary is missing:\n" +
                            "     ${File(binDir, "mez").absolutePath}\n" +
                            "     reinstall it, or pick another with: mez toolchain default <version>",
                        1,
                    )
                    canon(target) == selfC -> Action.RunInProcess // never re-exec ourselves
                    else -> Action.Reexec(target, forwarded)
                }
            }
            is ToolchainResolver.Resolution.NotInstalled -> Action.Fail(
                "mez: toolchain '${resolution.request.channel}' " +
                    "(from ${resolution.request.source.name.lowercase()}) is not installed.\n" +
                    "     installed: ${resolution.installed.joinToString(", ").ifEmpty { "(none)" }}\n" +
                    "     install it, or pick another with: mez toolchain default <version>",
                1,
            )
            is ToolchainResolver.Resolution.None -> Action.Fail(
                "mez: no toolchains installed under ${paths.toolchainsDir.absolutePath}.\n" +
                    "     install one with: ./gradlew :cli:installNative",
                1,
            )
        }
    }

    private fun exec(target: File, args: List<String>): Int {
        val proc = try {
            ProcessBuilder(listOf(target.absolutePath) + args).inheritIO().start()
        } catch (e: java.io.IOException) {
            // The binary exists (we checked isFile) but won't run — corrupt,
            // wrong arch, or not executable. Say so and how to fix, not a trace.
            System.err.println("mez: the toolchain binary could not be started:")
            System.err.println("     ${target.absolutePath}")
            System.err.println("     ${e.message}")
            System.err.println("     It looks corrupt or not executable — reinstall this toolchain")
            System.err.println("     (e.g. 'mez toolchain install <tarball>', or rebuild it).")
            return 126 // conventional "command found but not executable"
        }
        // Forward termination to the dispatched toolchain. An interactive Ctrl-C
        // already signals the whole foreground process group (proxy + child + its
        // JVM), but if the proxy is killed by PID alone the child would orphan —
        // so tear it down on shutdown. Long-running children (mez dev, mez lsp)
        // then always stop with the proxy.
        val hook = Thread {
            proc.destroy()
            if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) proc.destroyForcibly()
        }
        Runtime.getRuntime().addShutdownHook(hook)
        return try {
            proc.waitFor()
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        }
    }

    private fun canon(f: File): File = runCatching { f.canonicalFile }.getOrDefault(f.absoluteFile)

    /** The `mez` executable in [binDir], platform-agnostic (`mez` or `mez.exe`). */
    private fun toolchainBinary(binDir: File): File? =
        listOf("mez", "mez.exe").map { File(binDir, it) }.firstOrNull { it.isFile }

    /** Nearest `Mezze.toml` walking up from [start]; its `[toolchain] channel = "…"`, or null. */
    private fun readProjectChannel(start: File): String? {
        var d: File? = start.absoluteFile
        while (d != null) {
            val toml = File(d, "Mezze.toml")
            if (toml.isFile) return channelIn(toml) // nearest manifest is the boundary
            d = d.parentFile
        }
        return null
    }

    private val SECTION = Regex("""^\s*\[([^]]+)]\s*$""")
    private val CHANNEL = Regex("""^\s*channel\s*=\s*"([^"]+)"\s*$""")

    /** Line-scan `[toolchain] channel` — deliberately dependency-free so the proxy stays fast to start. */
    private fun channelIn(toml: File): String? {
        var inToolchain = false
        for (raw in runCatching { toml.readLines() }.getOrDefault(emptyList())) {
            val sec = SECTION.find(raw)
            if (sec != null) { inToolchain = sec.groupValues[1].trim() == "toolchain"; continue }
            if (inToolchain) CHANNEL.find(raw)?.let { return it.groupValues[1] }
        }
        return null
    }
}
