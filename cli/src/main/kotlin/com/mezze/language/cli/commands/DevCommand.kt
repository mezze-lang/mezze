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
        private val DEV_FLAGS_BOOLEAN = setOf(
            "--inspect", "--inspect-brk",
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
        val devFlags = args.filter { it in DEV_FLAGS_BOOLEAN || it.startsWith("--insight=") }

        val msg = CliMessages()
        val mezzeToml = File(currentDir, "Mezze.toml")

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

        val appFile = File(currentDir, appConfig.path)
        if (!appFile.exists()) {
            System.err.println(msg.appFileNotFound(appFile))
            return
        }

        val modulePath = manifest.`package`.name
        val entryName = appConfig.entry

        val envReport = com.mezze.language.cli.EnvironmentCheck.probe()
        if (com.mezze.language.cli.EnvironmentCheck.isCriticalFailure(envReport)) {
            println(com.mezze.language.cli.EnvironmentCheck.longReport(envReport))
            kotlin.system.exitProcess(1)
        }

        val exit = if (noWatch) {
            WireRunner.execOnce(currentDir, appFile, entryName, modulePath, appConfig.name, devFlags, appArgs = appArgs)
        } else {
            WireRunner.watch(currentDir, appFile, entryName, modulePath, appConfig.name, devFlags)
        }
        kotlin.system.exitProcess(exit)
    }
}
