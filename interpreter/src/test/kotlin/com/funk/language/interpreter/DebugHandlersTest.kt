package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import java.nio.file.Paths

class DebugHandlersTest {
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker
    private lateinit var interpreter: FunkInterpreter

    @BeforeEach
    fun setup() {
        parser = FunkParser()
        typeChecker = TypeChecker()
        interpreter = FunkInterpreter()
        System.setProperty("funk.project.root", "/Users/shriyans/Projects/funk")
    }

    private fun executeProgram(source: String): FunkValue {
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val projectRoot = Paths.get(System.getProperty("funk.project.root", "."))
        return interpreter.execute(typedProgram, projectRoot)
    }

    @Test
    fun testNestedFunctionCalls() {
        val source = """
            use std::effects::continuation::{Continuation, Delimited}

            # Test what happens when calling an imported function that uses Continuation
            let inner = { x } -> do
                # This should work because we have __handlers from perform Delimited in
                let extracted = Continuation.extract_suspended { suspended = x }
                extracted

            let outer = { x } -> do
                # Call inner, which needs Continuation
                inner { x = x }

            # Now test it
            let gen = {} -> perform Delimited in do
                Continuation.suspend_with { value = 42 }

            let g = gen {}

            # Call outer (which calls inner) within perform Delimited in
            perform Delimited in outer { x = g }
        """.trimIndent()

        val result = executeProgram(source)
        println("Result: $result")
        println("Result class: ${result::class.simpleName}")
        assertTrue(result is FunkVariant)
    }
}
