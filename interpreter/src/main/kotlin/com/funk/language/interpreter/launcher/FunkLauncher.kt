package com.funk.language.interpreter.launcher

import org.graalvm.launcher.AbstractLanguageLauncher
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.options.OptionCategory
import org.graalvm.polyglot.PolyglotException
import java.io.File
import java.io.IOException

/**
 * Native launcher for Funk language.
 *
 * This launcher is used by native-image builds to execute Funk programs
 * without requiring the full polyglot embedding API.
 *
 * Unlike JAR builds which use the polyglot embedding API and can embed
 * other languages (Python, JS, Ruby), this native launcher provides
 * Funk-only execution for fast startup and low memory footprint.
 *
 * Supports two modes:
 * 1. File-based execution: funk program.funk
 * 2. Embedded AST execution: Used by compiled native binaries
 */
class FunkLauncher : AbstractLanguageLauncher() {

    private var programFile: File? = null
    private var debugMode = false
    private var embeddedMode = false

    override fun getLanguageId(): String = "funk"

    override fun launch(contextBuilder: Context.Builder) {
        // Configure context for Funk execution
        contextBuilder.apply {
            allowAllAccess(true)
            allowCreateThread(true)
            option("engine.WarnInterpreterOnly", "false")
        }

        val exitCode = try {
            executeProgram(contextBuilder)
            0
        } catch (e: PolyglotException) {
            if (e.isExit) {
                e.exitStatus
            } else {
                printError(e)
                1
            }
        } catch (e: IOException) {
            System.err.println("I/O error: ${e.message}")
            if (debugMode) {
                e.printStackTrace()
            }
            1
        } catch (e: Exception) {
            System.err.println("Execution error: ${e.message}")
            if (debugMode) {
                e.printStackTrace()
            }
            1
        }

        if (exitCode != 0) {
            System.exit(exitCode)
        }
    }

    private fun executeProgram(contextBuilder: Context.Builder) {
        // Check for embedded AST mode (used by compiled native binaries)
        if (embeddedMode || System.getProperty("funk.embedded.mode") == "true") {
            executeEmbeddedProgram(contextBuilder)
            return
        }

        // File-based execution
        val file = programFile ?: throw IllegalArgumentException(
            "No program file specified. Usage: funk <file.funk>"
        )

        if (!file.exists()) {
            throw IOException("File not found: ${file.absolutePath}")
        }

        contextBuilder.build().use { context ->
            val source = Source.newBuilder(languageId, file)
                .name(file.name)
                .build()

            context.eval(source)
        }
    }

    /**
     * Execute embedded AST from system properties.
     * Used by compiled native binaries with pre-parsed AST.
     */
    private fun executeEmbeddedProgram(contextBuilder: Context.Builder) {
        val astJson = System.getProperty("funk.embedded.ast")
            ?: throw IllegalArgumentException("No embedded AST found. Set funk.embedded.ast system property.")

        val monomorphicsJson = System.getProperty("funk.embedded.monomorphics") ?: "{}"
        val preResolvedJson = System.getProperty("funk.embedded.preresolved") ?: "[]"

        if (debugMode) {
            System.err.println("Loading embedded AST (${astJson.length} bytes)")
        }

        contextBuilder.build().use { context ->
            // Import required classes
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

            // Deserialize embedded data
            val program = json.decodeFromString(
                com.funk.language.ast.Program.serializer(),
                astJson
            )

            val monomorphicInstances = json.decodeFromString<Map<String, com.funk.language.ast.LetBinding>>(
                monomorphicsJson
            )

            val preResolvedItems = json.decodeFromString<List<com.funk.language.ast.ProgramItem>>(
                preResolvedJson
            )

            // Set program for direct execution (no parsing needed!)
            com.funk.language.interpreter.FunkLanguage.setProgramForDirectExecution(
                program,
                java.nio.file.Paths.get(".")
            )
            com.funk.language.interpreter.FunkLanguage.setMonomorphicInstances(monomorphicInstances)
            com.funk.language.interpreter.FunkLanguage.setPreResolvedItems(preResolvedItems)

            // Store context in ThreadLocal for Truffle execution
            com.funk.language.interpreter.TruffleContextHolder.setPolyglotContext(context)

            try {
                // Trigger execution with special marker source
                val source = Source.newBuilder(languageId, "# DIRECT_AST_EXECUTION", "__direct_execution__.funk")
                    .buildLiteral()

                context.eval(source)
            } finally {
                com.funk.language.interpreter.TruffleContextHolder.clearEnv()
            }
        }
    }

    private fun printError(e: PolyglotException) {
        System.err.println("❌ Runtime Error:")
        if (e.isGuestException) {
            System.err.println(e.message ?: "Unknown error")
            try {
                e.polyglotStackTrace.forEach { frame ->
                    System.err.println("  at ${frame}")
                }
            } catch (_: Exception) {
                // Stack trace not available
            }
        } else {
            System.err.println(e.message ?: "Unknown error")
        }

        if (debugMode) {
            e.printStackTrace()
        }
    }

    override fun preprocessArguments(
        arguments: List<String>,
        polyglotOptions: MutableMap<String, String>
    ): List<String> {
        val remaining = mutableListOf<String>()

        var i = 0
        while (i < arguments.size) {
            val arg = arguments[i]
            when {
                arg == "--debug" -> {
                    debugMode = true
                }
                arg == "--embedded" -> {
                    embeddedMode = true
                }
                arg.startsWith("-") -> {
                    // Unknown flag, let polyglot handle it
                    remaining.add(arg)
                }
                programFile == null && !embeddedMode -> {
                    // First non-flag argument is the program file
                    programFile = File(arg)
                }
                else -> {
                    // Additional arguments (could be program args)
                    remaining.add(arg)
                }
            }
            i++
        }

        return remaining
    }

    override fun validateArguments(polyglotOptions: Map<String, String>) {
        // Check system property for embedded mode
        if (System.getProperty("funk.embedded.mode") == "true") {
            embeddedMode = true
        }

        if (programFile == null && !embeddedMode) {
            throw IllegalArgumentException(
                "No program file specified.\n\n" +
                "Usage: funk [options] <file.funk>\n\n" +
                "Options:\n" +
                "  --debug     Enable debug output\n" +
                "  --embedded  Run embedded AST (used by compiled binaries)\n"
            )
        }
    }

    override fun printHelp(maxCategory: OptionCategory) {
        println("Usage: funk [options] <file.funk>")
        println()
        println("Options:")
        println("  --debug              Enable debug output")
        println("  --help               Show this help message")
        println()
        println("Examples:")
        println("  funk program.funk    Execute a Funk program")
        println("  funk --debug app.funk   Execute with debug output")
    }

    override fun collectArguments(args: MutableSet<String>) {
        // Add Funk-specific arguments that should be collected
        args.add("--debug")
        super.collectArguments(args)
    }

    companion object {
        /**
         * Main entry point for launcher-based execution.
         * This is called by compiled native binaries.
         */
        @JvmStatic
        fun main(args: Array<String>) {
            FunkLauncher().launch(args)
        }
    }
}
