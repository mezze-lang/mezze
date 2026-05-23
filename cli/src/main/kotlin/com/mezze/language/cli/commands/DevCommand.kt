package com.mezze.language.cli.commands

import com.akuleshov7.ktoml.Toml
import com.mezze.language.cli.`package`.MezzeToml
import java.io.File

/**
 * `mez dev` — spawn JVM subprocess running DevRunner.
 *
 * Native binary is a thin shell: validate Mezze.toml, find Java, spawn DevRunner.
 * DevRunner does all the work — stdlib load, compile, store, execute, watch.
 */
class DevCommand {

    fun execute(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        val inspect = args.contains("--inspect")
        val inspectBrk = args.contains("--inspect-brk")
        val noWatch = args.contains("--no-watch") || args.contains("--nw")
        val profile = args.contains("--profile")
        val coverage = args.contains("--coverage")
        val traceJit = args.contains("--trace-jit")
        val allocations = args.contains("--allocations")
        // --insight=<path> attaches a JS probe; capture the path arg.
        val insightFile = args.firstNotNullOfOrNull { arg ->
            when {
                arg.startsWith("--insight=") -> arg.substringAfter("=")
                else -> null
            }
        }
        val mezzeToml = File(currentDir, "Mezze.toml")

        if (!mezzeToml.exists()) {
            println("Error: No Mezze.toml found in current directory")
            println()
            println("Run 'mez init' to create a new project, or use 'mez exec <file>' for single-file execution.")
            return
        }

        val tomlContent = mezzeToml.readText()
        val manifest = try {
            Toml.decodeFromString(MezzeToml.serializer(), tomlContent)
        } catch (e: Exception) {
            println("Error: Failed to parse Mezze.toml: ${e.message}")
            return
        }

        if (manifest.app == null || manifest.app.isEmpty()) {
            println("Error: No [[app]] entries in Mezze.toml")
            println()
            println("Add an app:")
            println("  [[app]]")
            println("  name = \"${manifest.`package`.name}\"")
            println("  path = \"src/app.mz\"")
            return
        }

        val appName = args.find { it.startsWith("--app=") }?.substringAfter("=")
            ?: args.getOrNull(args.indexOf("--app") + 1)?.takeIf { !it.startsWith("--") }

        val appConfig = if (appName != null) {
            manifest.app.find { it.name == appName } ?: run {
                println("Error: App '$appName' not found. Available: ${manifest.app.map { it.name }}")
                return
            }
        } else {
            manifest.app.first()
        }

        val appFile = File(currentDir, appConfig.path)
        if (!appFile.exists()) {
            println("Error: App file not found: ${appFile.absolutePath}")
            return
        }

        val modulePath = manifest.`package`.name
        val entryName = appConfig.entry

        val java = com.mezze.language.cli.JvmLauncher.findJava()
        if (java == null) {
            println("Error: Java 21+ required for 'mez dev'. Install with:")
            println("  sdk install java 24.0.1-graal    # sdkman")
            println("  brew install graalvm-jdk          # macOS")
            kotlin.system.exitProcess(1)
        }

        // Wire-protocol path (#220): compile here in native mez, JVM only
        // executes. Gated by MEZZE_WIRE=1 until validated end-to-end. Once
        // stable this becomes the default and DevRunner can be removed
        // (jar slim — drops :parser, :typechecker, :store-sqlite from
        // mezze-lang.jar).
        if (System.getenv("MEZZE_WIRE") == "1") {
            val exit = if (noWatch) {
                WireRunner.execOnce(currentDir, appFile, entryName, modulePath, appConfig.name)
            } else {
                WireRunner.watch(currentDir, appFile, entryName, modulePath, appConfig.name)
            }
            kotlin.system.exitProcess(exit)
        }

        val classpath = com.mezze.language.cli.JvmLauncher.buildClasspath()
        val programArgs = mutableListOf(
            "--project-root=${currentDir.absolutePath}",
            "--entry-file=${appConfig.path}",
            "--entry-name=${entryName}",
            "--module-path=${modulePath}",
            "--app-name=${appConfig.name}"
        )
        if (inspect) programArgs.add("--inspect")
        if (inspectBrk) programArgs.add("--inspect-brk")
        if (noWatch) programArgs.add("--no-watch")
        if (profile) programArgs.add("--profile")
        if (coverage) programArgs.add("--coverage")
        if (traceJit) programArgs.add("--trace-jit")
        if (allocations) programArgs.add("--allocations")
        if (insightFile != null) programArgs.add("--insight=$insightFile")

        val exitCode = com.mezze.language.cli.JvmLauncher.spawn(
            mainClass = "com.mezze.language.cli.commands.DevRunner",
            classpath = classpath,
            programArgs = programArgs
        )
        kotlin.system.exitProcess(exitCode)
    }
}
