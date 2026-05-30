package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test

class DebugNestedLambda {
    @Test
    fun testNestedLambdaDebug() {
        val code = """
            newtype Promise 'a = Promise __NativePromise

            let spawn: {f: {} -> 'a} -> Promise 'a = {f} -> __native_async_spawn {f = f}

            let f = {x} -> spawn {f = {} -> x * 2}
        """.trimIndent()

        val parser = MezzeParser()
        val typeChecker = TypeChecker()

        try {
            val parsedModule = parser.parseParsedModule(code, "test.mz")
            println("=== PARSED PROGRAM ===")
            println(parsedModule)

            // Enable debug mode if available
            System.setProperty("mezze.typecheck.debug", "true")

            val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
            println("=== SUCCESS ===")
            println("Type of f: ${result.getVariableType("f")}")
        } catch (e: Exception) {
            println("=== ERROR ===")
            println(e.message)
            e.printStackTrace()
            throw e
        }
    }
}
