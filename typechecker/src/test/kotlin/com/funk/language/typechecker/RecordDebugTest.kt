package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class RecordDebugTest {
    private lateinit var typeChecker: TypeChecker
    private lateinit var parser: FunkParser

    @BeforeEach
    fun setUp() {
        typeChecker = TypeChecker()
        parser = FunkParser()
    }

    @Test
    fun debugSimpleRecordAccess() {
        val source = """
            let test = {point} -> point.x
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast)
        
        println("Simple record access worked!")
    }
    
    @Test
    fun debugDoubleRecordAccess() {
        val source = """
            let test = {point} -> point.x + point.y
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast)
        
        println("Double record access worked!")
    }
}