package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class DebugTest {
    
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }
    
    @Test
    fun `test simple lambda`() {
        val source = """
            let add = {a, b} -> a + b
            let multiply = {x, y} -> x * y
            let divide = {a, b} -> a / b
        """.trimIndent()
        
        try {
            val parsedModule = parser.parseParsedModule(source, "test.mz")
            val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))
            println("SUCCESS: Type checking passed")
            
            // Add the same assertions as the real test
            val addType = typedProgram.getVariableType("add")
            println("Add binding type: ${addType?.let { com.mezze.language.ast.prettyPrintType(it) } ?: "unknown"}")
            
        } catch (e: Exception) {
            println("ERROR: ${e.message}")
            e.printStackTrace()
        }
    }
}
