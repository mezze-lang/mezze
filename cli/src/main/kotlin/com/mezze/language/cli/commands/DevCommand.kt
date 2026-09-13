package com.mezze.language.cli.commands

import com.akuleshov7.ktoml.Toml
import com.mezze.language.cli.`package`.MezzeToml
import java.io.File

/**
 * `mez dev` — compile here in the native binary, execute via the
 * wire-protocol RuntimeServer (a slim JVM child).
 *
 * Flow: native mez parses + typechecks + lowers user code; spawns
 * `java -cp mez-runtime.jar com.mezze.interpreter.standalone.RuntimeServer`;
 * sends LOAD frames; streams user output back; waits for EXITED.
 *
 * There is only one execution path — the wire protocol. The legacy
 * in-JVM DevRunner was deleted in commit dff800a6.
 */
class DevCommand {

    companion object {
        /**
         * Dev instrumentation flags forwarded to RuntimeServer as args.
         * It parses them via DevFlags.parse before building the Engine.
         */
        internal val DEV_FLAGS_BOOLEAN = setOf(
            "--inspect", "--inspect-brk",
            "--dap", "--dap-brk",
            "--profile", "--coverage", "--allocations", "--trace-jit",
        )
    }


    fun execute(
        args: List<String>,
        currentDir: File = File(System.getProperty("user.dir")),
        appArgs: List<String> = emptyList(),
    ) {
        val noWatch = args.contains("--no-watch") || args.contains("--nw")

        // Collect dev instrumentation flags to forward to the child.
        // Boolean flags pass through verbatim; --insight=<path> too.
        val devFlags = args.filter {
            it in DEV_FLAGS_BOOLEAN || it.startsWith("--insight=") || it.startsWith("--dap=")
        }

        val msg = CliMessages()
        // Walk up to the nearest Mezze.toml so `mez dev` works from any
        // subdirectory and uses the root's .mez/ (see ProjectRoot). Single-file
        // `mez exec <file>` never reaches here — ExecCommand runs it standalone.
        val root = ProjectRoot.discover(currentDir) ?: currentDir
        val mezzeToml = File(root, "Mezze.toml")

        if (!mezzeToml.exists()) {
            System.err.println(msg.noManifest())
            return
        }

        val tomlContent = mezzeToml.readText()
        val manifest = try {
            Toml.decodeFromString(MezzeToml.serializer(), tomlContent)
        } catch (e: Exception) {
            System.err.println(msg.badManifest(e.message))
            return
        }

        if (manifest.app == null || manifest.app.isEmpty()) {
            System.err.println(msg.noApp(manifest.`package`.name))
            return
        }

        val appName = args.find { it.startsWith("--app=") }?.substringAfter("=")
            ?: args.getOrNull(args.indexOf("--app") + 1)?.takeIf { !it.startsWith("--") }

        val appConfig = if (appName != null) {
            manifest.app.find { it.name == appName } ?: run {
                System.err.println(msg.appNotFound(appName, manifest.app.map { it.name }))
                return
            }
        } else {
            manifest.app.first()
        }

        val appFile = File(root, appConfig.path)
        if (!appFile.exists()) {
            System.err.println(msg.appFileNotFound(appFile))
            return
        }

        val modulePath = manifest.`package`.name
        val entryName = appConfig.entry

        // Resolve the JDK/GraalVM and adapt: a too-old/missing JDK is a hard,
        // graceful stop; anything runnable proceeds, warning once if degraded
        // (non-GraalVM → interpreter, or a mismatched line). --require-jit turns
        // that warning into a failure for CI.
        val resolved = com.mezze.language.cli.GraalvmResolver.resolve()
        if (!resolved.canRun) {
            throw com.mezze.language.cli.MezzeCliError(
                resolved.degradeReason ?: "no usable JDK found",
                hint = "install GraalVM ${com.mezze.language.cli.BuildInfo.GRAAL_LINE} (see `mez doctor`), " +
                    "or set MEZZE_GRAALVM_HOME",
            )
        }
        com.mezze.language.cli.GraalvmResolver.requirementFailure(
            resolved, requireJit = args.contains("--require-jit"), requireNative = false,
        )?.let { throw com.mezze.language.cli.MezzeCliError(it, hint = "see `mez doctor`") }
        com.mezze.language.cli.GraalvmResolver.warnIfDegraded(resolved)

        val exit = if (noWatch) {
            WireRunner.execOnce(root, appFile, entryName, modulePath, appConfig.name, devFlags, appArgs = appArgs)
        } else {
            WireRunner.watch(root, appFile, entryName, modulePath, appConfig.name, devFlags)
        }
        kotlin.system.exitProcess(exit)
    }
}
