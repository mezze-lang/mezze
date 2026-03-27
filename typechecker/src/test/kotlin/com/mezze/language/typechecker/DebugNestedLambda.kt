package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test

class DebugNestedLambda {
    @Test
    fun testNestedLambdaDebug() {
        val code = """
            newtype Promise 'a = Promise __NativePromise

            let spawn: {f: {} -> 'a} -> Promise 'a = {f} -> __native_loom_spawn {f = f}

            let f = {x} -> spawn {f = {} -> x * 2}
        """.trimIndent()

        val parser = MezzeParser()
        val typeChecker = TypeChecker()

        try {
            val program = parser.parse(code)
            println("=== PARSED PROGRAM ===")
            println(program)

            // Enable debug mode if available
            System.setProperty("mezze.typecheck.debug", "true")

            val result = typeChecker.typeCheck(program)
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
