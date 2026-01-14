package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class DebugSetupTest {

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        println("SETUP: About to clear caches...")
        System.out.flush()
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        println("SETUP: Caches cleared. About to create parser...")
        System.out.flush()
        parser = FunkParser()
        println("SETUP: Parser created. About to create typechecker...")
        System.out.flush()
        typeChecker = TypeChecker()
        println("SETUP: TypeChecker created. Setup complete!")
        System.out.flush()
    }

    @Test
    fun `test setup works`() {
        println("TEST: Test method started")
        System.out.flush()
        val code = "let x = 42"
        println("TEST: About to parse simple code...")
        System.out.flush()
        val program = parser.parse(code)
        println("TEST: Parsed. About to typecheck...")
        System.out.flush()
        val result = typeChecker.typeCheck(program)
        println("TEST: Success!")
        System.out.flush()
    }
}
