package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test

class DebugNestedLambda {
    @Test
    fun testNestedLambdaDebug() {
        val code = """
            newtype Promise 'a = Promise __NativePromise

            let spawn: {f: {} -> 'a} -> Promise 'a = {f} -> __native_loom_spawn {f = f}

            let f = {x} -> spawn {f = {} -> x * 2}
        """.trimIndent()

        val parser = FunkParser()
        val typeChecker = TypeChecker()

        try {
            val program = parser.parse(code)
            println("=== PARSED PROGRAM ===")
            println(program)

            // Enable debug mode if available
            System.setProperty("funk.typecheck.debug", "true")

            val result = typeChecker.typeCheck(program)
            println("=== SUCCESS ===")
            println("Type of f: ${result.program.items.find {
                it is com.funk.language.ast.LetBinding && it.variableName == "f"
            }?.let {
                (it as com.funk.language.ast.LetBinding).inferredType
            }}")
        } catch (e: Exception) {
            println("=== ERROR ===")
            println(e.message)
            e.printStackTrace()
            throw e
        }
    }
}
