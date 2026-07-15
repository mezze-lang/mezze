package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.system.measureTimeMillis

@Tag("benchmark")
class TypeCheckerBenchmarkTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        System.setProperty("mezze.typecheck.strict", "true")
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `benchmark simple bindings`() {
        val source = buildString {
            for (i in 0 until 100) {
                appendLine("let x$i = $i")
            }
            appendLine("x99")
        }
        val parsed = parser.parseParsedModule(source, "bench_bindings.mz")

        // Warmup
        typeChecker.typeCheck(parsed, Paths.get("."))
        TypeChecker.clearCache()
        ModuleResolver.clearCache()

        val elapsed = measureTimeMillis {
            typeChecker = TypeChecker()
            typeChecker.typeCheck(parsed, Paths.get("."))
        }

        println("100 simple bindings: ${elapsed}ms")
        assert(elapsed < 5000) { "Type-checking 100 bindings took ${elapsed}ms (>5s budget)" }
    }

    @Test
    fun `benchmark polymorphic functions`() {
        val source = buildString {
            appendLine("let identity = {x} -> x")
            for (i in 0 until 50) {
                appendLine("let r$i = identity { x = $i }")
            }
            appendLine("r49")
        }
        val parsed = parser.parseParsedModule(source, "bench_poly.mz")

        // Warmup
        typeChecker.typeCheck(parsed, Paths.get("."))
        TypeChecker.clearCache()
        ModuleResolver.clearCache()

        val elapsed = measureTimeMillis {
            typeChecker = TypeChecker()
            typeChecker.typeCheck(parsed, Paths.get("."))
        }

        println("50 polymorphic instantiations: ${elapsed}ms")
        assert(elapsed < 5000) { "Type-checking 50 poly calls took ${elapsed}ms (>5s budget)" }
    }

    @Test
    fun `benchmark record types`() {
        val source = buildString {
            for (i in 0 until 50) {
                appendLine("let p$i = { x = $i, y = ${i * 2}, z = \"s$i\" }")
            }
            appendLine("p49.x")
        }
        val parsed = parser.parseParsedModule(source, "bench_records.mz")

        // Warmup
        typeChecker.typeCheck(parsed, Paths.get("."))
        TypeChecker.clearCache()
        ModuleResolver.clearCache()

        val elapsed = measureTimeMillis {
            typeChecker = TypeChecker()
            typeChecker.typeCheck(parsed, Paths.get("."))
        }

        println("50 record bindings + field access: ${elapsed}ms")
        assert(elapsed < 5000) { "Type-checking 50 records took ${elapsed}ms (>5s budget)" }
    }

    @Test
    fun `benchmark match expressions`() {
        val source = buildString {
            appendLine("type Color = | Red, Green, Blue |")
            appendLine("let classify = {c: Color} -> match c is")
            appendLine("  Red -> 1")
            appendLine("  Green -> 2")
            appendLine("  Blue -> 3")
            for (i in 0 until 30) {
                appendLine("let r$i = classify { c = Red }")
            }
            appendLine("r29")
        }
        val parsed = parser.parseParsedModule(source, "bench_match.mz")

        // Warmup
        typeChecker.typeCheck(parsed, Paths.get("."))
        TypeChecker.clearCache()
        ModuleResolver.clearCache()

        val elapsed = measureTimeMillis {
            typeChecker = TypeChecker()
            typeChecker.typeCheck(parsed, Paths.get("."))
        }

        println("Variant type + 30 match calls: ${elapsed}ms")
        assert(elapsed < 5000) { "Type-checking match expressions took ${elapsed}ms (>5s budget)" }
    }

    @Test
    fun `benchmark stdlib fixture if available`() {
        val projectRoot = System.getProperty("mezze.project.root") ?: return
        val stdlibDir = Paths.get(projectRoot, "stdlib", "std")
        if (!Files.isDirectory(stdlibDir)) return

        val mzFiles = Files.walk(stdlibDir)
            .filter { it.toString().endsWith(".mz") }
            .toList()
            .take(10)

        if (mzFiles.isEmpty()) return

        var totalMs = 0L
        var fileCount = 0

        for (file in mzFiles) {
            val source = Files.readString(file)
            val modulePath = stdlibDir.parent.relativize(file).toString()
                .replace("/", "::")
                .removeSuffix(".mz")

            try {
                TypeChecker.clearCache()
                ModuleResolver.clearCache()
                typeChecker = TypeChecker()
                val elapsed = measureTimeMillis {
                    val parsed = parser.parseParsedModule(source, file.fileName.toString())
                    typeChecker.typeCheck(parsed, Paths.get(projectRoot), modulePath = modulePath)
                }
                totalMs += elapsed
                fileCount++
                println("  ${file.fileName}: ${elapsed}ms")
            } catch (_: Exception) {
                // Some stdlib files may need dependencies — skip
            }
        }

        if (fileCount > 0) {
            println("Stdlib benchmark: $fileCount files in ${totalMs}ms (avg ${totalMs / fileCount}ms)")
        }
    }
}
