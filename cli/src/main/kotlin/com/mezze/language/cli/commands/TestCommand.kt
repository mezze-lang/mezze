package com.mezze.language.cli.commands

import com.akuleshov7.ktoml.Toml
import com.mezze.language.cli.`package`.MezzeToml
import com.mezze.language.codebase.SQLiteMezzeStore
import com.mezze.language.codebase.StorePopulator
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.printing.ErrorFormatter
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.ast.MezzeType
import java.io.File
import java.security.MessageDigest

/**
 * Handle `mez test` — discover and run test files.
 *
 * Convention:
 * - Test files: files in `tests/` directory, or files matching `*_test.mz`
 * - Test functions: functions starting with `test_`
 * - Test type: `{} -> Result {} Str` (returns Ok {} on pass, Err "message" on fail)
 *
 * Usage:
 *   mez test                  — run all tests
 *   mez test tests/foo.mz     — run specific test file
 */
class TestCommand {

    fun execute(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        val mezzeToml = File(currentDir, "Mezze.toml")
        if (!mezzeToml.exists()) {
            println("Error: No Mezze.toml found. Run 'mez init' to create a project.")
            return
        }

        val manifest = try {
            Toml.decodeFromString(MezzeToml.serializer(), mezzeToml.readText())
        } catch (e: Exception) {
            println("Error: Failed to parse Mezze.toml: ${e.message}")
            return
        }

        // Find test files
        val testFiles = if (args.isNotEmpty() && !args[0].startsWith("-")) {
            // Specific file
            val file = File(currentDir, args[0])
            if (!file.exists()) {
                println("Error: Test file not found: ${file.absolutePath}")
                return
            }
            listOf(file)
        } else {
            // Discover test files
            discoverTestFiles(currentDir)
        }

        if (testFiles.isEmpty()) {
            println("No test files found.")
            println("Place test files in tests/ directory or name them *_test.mz")
            return
        }

        println("Running tests...")
        println()

        val store = SQLiteMezzeStore.forProject(currentDir)
        val parser = MezzeParser()
        val typeChecker = TypeChecker()

        var totalTests = 0
        var passedTests = 0
        var failedTests = 0
        var errorFiles = 0
        val failures = mutableListOf<String>()
        val startTime = System.nanoTime()

        try {
            for (testFile in testFiles) {
                val relativePath = testFile.relativeTo(currentDir).path
                val source = testFile.readText()

                // Parse
                val ast = try {
                    parser.parseParsedModule(source, file = testFile.name)
                } catch (e: com.mezze.language.parser.ParseException) {
                    println("  PARSE ERROR  $relativePath")
                    println("    ${e.message}")
                    errorFiles++
                    continue
                }

                // Typecheck
                val typeCheckResult = try {
                    typeChecker.typeCheck(ast, currentDir.toPath())
                } catch (e: TypeCheckError) {
                    println("  TYPE ERROR   $relativePath")
                    println("    ${e.message}")
                    errorFiles++
                    continue
                }

                // Scatter into store
                val sourceHash = sha256(source)
                val modulePath = "test::${testFile.nameWithoutExtension}"
                StorePopulator.populate(store, typeCheckResult, modulePath, testFile.absolutePath, sourceHash)

                // Find test functions (functions starting with test_)
                val testFunctions = typeCheckResult.typedModule?.decls
                    ?.filterIsInstance<TypedLetDecl>()
                    ?.filter { it.name.startsWith("test_") }
                    ?: emptyList()

                if (testFunctions.isEmpty()) {
                    println("  (no tests)   $relativePath")
                    continue
                }

                println("  $relativePath")

                // Execute each test function
                val functions = typeCheckResult.loweredFunctions
                for (testFn in testFunctions) {
                    totalTests++
                    val testStart = System.nanoTime()

                    try {
                        // Execute the test function via Truffle
                        val result = executeTestFunction(testFn.name, functions, typeCheckResult.performerRecords, currentDir)
                        val testTime = (System.nanoTime() - testStart) / 1_000_000

                        // Check result — Ok {} = pass, Err "message" = fail
                        when {
                            result == null || isUnitResult(result) -> {
                                // Test returned {} (unit) — pass
                                println("    PASS  ${testFn.name} (${testTime}ms)")
                                passedTests++
                            }
                            isOkResult(result) -> {
                                println("    PASS  ${testFn.name} (${testTime}ms)")
                                passedTests++
                            }
                            isErrResult(result) -> {
                                val msg = getErrMessage(result)
                                println("    FAIL  ${testFn.name} (${testTime}ms)")
                                println("          $msg")
                                failedTests++
                                failures.add("$relativePath::${testFn.name}: $msg")
                            }
                            else -> {
                                println("    PASS  ${testFn.name} (${testTime}ms)")
                                passedTests++
                            }
                        }
                    } catch (e: Exception) {
                        val testTime = (System.nanoTime() - testStart) / 1_000_000
                        println("    FAIL  ${testFn.name} (${testTime}ms)")
                        println("          ${e.message}")
                        failedTests++
                        failures.add("$relativePath::${testFn.name}: ${e.message}")
                    }
                }
            }
        } finally {
            store.close()
        }

        val totalTime = (System.nanoTime() - startTime) / 1_000_000

        // Summary
        println()
        if (failures.isNotEmpty()) {
            println("Failures:")
            for (failure in failures) {
                println("  $failure")
            }
            println()
        }

        println("$passedTests passed, $failedTests failed, $totalTests total (${totalTime}ms)")
        if (errorFiles > 0) println("$errorFiles file(s) had errors")

        if (failedTests > 0 || errorFiles > 0) {
            kotlin.system.exitProcess(6)
        }
    }

    private fun discoverTestFiles(projectDir: File): List<File> {
        val files = mutableListOf<File>()

        // tests/ directory
        val testsDir = File(projectDir, "tests")
        if (testsDir.exists()) {
            collectMezzeFiles(testsDir, files)
        }

        // *_test.mz files in src/
        val srcDir = File(projectDir, "src")
        if (srcDir.exists()) {
            srcDir.walkTopDown().filter { it.extension == "mz" && it.nameWithoutExtension.endsWith("_test") }.forEach {
                files.add(it)
            }
        }

        return files.sortedBy { it.path }
    }

    private fun collectMezzeFiles(dir: File, files: MutableList<File>) {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory && !file.name.startsWith(".")) {
                collectMezzeFiles(file, files)
            } else if (file.extension == "mz") {
                files.add(file)
            }
        }
    }

    private fun executeTestFunction(
        testName: String,
        functions: List<com.mezze.language.truffleir.FunctionIR>,
        performerRecords: List<Array<String>>,
        projectDir: File
    ): Any? {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false")
        System.setProperty("polyglot.log.level", "OFF")

        val context = org.graalvm.polyglot.Context.newBuilder(com.mezze.interpreter.v2.runtime.MezzeLanguage.ID)
            .allowAllAccess(true)
            .allowCreateThread(true)
            .build()

        return try {
            com.mezze.interpreter.v2.runtime.MezzeLanguage.setFunctionsForDirectExecution(functions, performerRecords, projectDir.toPath())

            val source = org.graalvm.polyglot.Source.newBuilder(
                com.mezze.interpreter.v2.runtime.MezzeLanguage.ID,
                "# Test execution",
                "__test_execution__.mz"
            ).buildLiteral()

            context.eval(source)
            com.mezze.interpreter.v2.runtime.MezzeLanguage.getLastExecutionResult()
        } finally {
            context.close(true)
        }
    }

    private fun isUnitResult(result: Any): Boolean {
        return result is com.mezze.interpreter.v2.values.MezzeRecord && result.fieldCount == 0
    }

    private fun isOkResult(result: Any): Boolean {
        return result is com.mezze.interpreter.v2.values.MezzeVariant && result.constructorName == "Ok"
    }

    private fun isErrResult(result: Any): Boolean {
        return result is com.mezze.interpreter.v2.values.MezzeVariant && result.constructorName == "Err"
    }

    private fun getErrMessage(result: Any): String {
        if (result is com.mezze.interpreter.v2.values.MezzeVariant) {
            val payload = result.getPayloadValue()
            if (payload is com.mezze.interpreter.v2.values.MezzeRecord) {
                val msg = payload.getField("error") ?: payload.getField("val") ?: payload.getField("msg")
                return msg?.toString() ?: result.toString()
            }
            return payload?.toString() ?: result.toString()
        }
        return result.toString()
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
