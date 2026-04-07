package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class OpenRecordDebugTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        System.setProperty("mezze.debug.unification", "true")
        parser = MezzeParser()
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

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        println("Parsed program successfully")
        println()

        try {
            val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))
            println("Type checking succeeded!")
        } catch (e: Exception) {
            println("Type checking failed: ${e.message}")
            e.printStackTrace()
        }
    }
}
