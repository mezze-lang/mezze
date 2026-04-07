package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class RecordDebugTest {
    private lateinit var typeChecker: TypeChecker
    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setUp() {
        typeChecker = TypeChecker()
        parser = MezzeParser()
    }

    @Test
    fun debugSimpleRecordAccess() {
        val source = """
            let test = {point} -> point.x
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))
        
        println("Simple record access worked!")
    }
    
    @Test
    fun debugDoubleRecordAccess() {
        val source = """
            let test = {point} -> point.x + point.y
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))
        
        println("Double record access worked!")
    }
}
