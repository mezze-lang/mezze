package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import java.io.FileWriter
import java.nio.file.Paths

class ContinuationTraceTest {
    @Test
    fun `trace continuation import`() {
        val source = """
            use std::control::continuation

            let test = {} -> do
                let c = continuation::new {}
                42

            test {}
        """.trimIndent()

        val fw = FileWriter("/tmp/cont_trace.txt")
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))

        fw.write("=== START ===\n")
        fw.flush()

        val parser = MezzeParser()
        val parsed = parser.parseParsedModule(source, file = "<trace>")
        fw.write("PARSED: ${parsed.decls.size} decls\n")
        fw.flush()

        val tc = TypeChecker()
        fw.write("TYPECHECKER CREATED\n")
        fw.flush()

        try {
            val result = tc.checkParsedModule(parsed, projectRoot, enablePrelude = true)
            when (result) {
                is TypeChecker.TypeCheckResult.Failure -> {
                    fw.write("TYPECHECK FAILED: ${result.errors.map { it.message }}\n")
                }
                is TypeChecker.TypeCheckResult.Success -> {
                    fw.write("TYPECHECK SUCCESS\n")
                    fw.write("  typedModule: ${result.typedModule != null}\n")
                    fw.write("  loweredFns: ${result.loweredFunctions?.size}\n")
                }
            }
        } catch (e: Exception) {
            fw.write("EXCEPTION: ${e::class.simpleName}: ${e.message}\n")
            e.printStackTrace(java.io.PrintWriter(fw))
        } catch (e: OutOfMemoryError) {
            fw.write("OOM: ${e.message}\n")
        }
        fw.close()
        println("Trace written to /tmp/cont_trace.txt")
    }
}
