package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class OpenRecordDebugTest {

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        System.setProperty("funk.debug.unification", "true")
        parser = FunkParser()
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
    }

    @Test
    fun `debug open record unification`() {
        val source = """
            let my_func: {x: Int, y: Int, ...} -> Int = {x, y} -> x + y

            my_func {x = 1, y = 0, z = ""}
        """.trimIndent()

        println("=== STARTING OPEN RECORD DEBUG ===")
        println("Source: $source")
        println()

        val program = parser.parse(source)
        println("Parsed program successfully")
        println()

        try {
            val typedProgram = typeChecker.typeCheck(program).program
            println("Type checking succeeded!")
        } catch (e: Exception) {
            println("Type checking failed: ${e.message}")
            e.printStackTrace()
        }
    }
}
