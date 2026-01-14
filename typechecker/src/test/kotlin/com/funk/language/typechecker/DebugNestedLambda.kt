package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test

class DebugNestedLambda {
    @Test
    fun testNestedLambdaDebug() {
        val code = """
            newtype Promise = Promise __NativePromise

            let spawn: {computation: {} -> 'a} -> Promise = {computation} -> __native_loom_spawn {computation = computation}

            let f = {x} -> spawn {computation = {} -> x * 2}
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
