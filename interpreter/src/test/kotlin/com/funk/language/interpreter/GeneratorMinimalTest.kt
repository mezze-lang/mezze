package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import java.nio.file.Paths

@Disabled("Continuation/Delimited handler not yet implemented")
class GeneratorMinimalTest {
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
        val typeCheckResult = typeChecker.typeCheck(program)
        val projectRoot = Paths.get(System.getProperty("funk.project.root", "."))
        return FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
    }

    @Test
    fun testMinimalRepro() {
        val source = """
            use std::effects::continuation::{Continuation, Delimited}

            # Simple generator
            let gen = {} -> perform Delimited in do
                Continuation.suspend_with { value = 42 }

            # Inline version of next
            let inline_next = { gen } -> do
                let extracted = Continuation.extract_suspended { suspended = gen }
                match extracted is
                    Some { val } -> do
                        let next_state = Continuation.resume { cont = val.cont, value = {} }
                        { value = Some { val = val.value }, gen = next_state }
                    None -> { value = None, gen = {} }

            # Create generator
            let g = gen {}

            # Call inline_next within perform Delimited in
            let result = perform Delimited in inline_next { gen = g }

            # Try to access the value field
            result.value
        """.trimIndent()

        val result = executeProgram(source)
        println("Result: $result")
        println("Result class: ${result::class.simpleName}")
        assertTrue(result is FunkVariant)
    }

    @Test
    fun testRecursiveNext() {
        val source = """
            use std::effects::continuation::{Continuation, Delimited}

            # Generator that yields 42 then completes
            let gen = {} -> perform Delimited in do
                Continuation.suspend_with { value = 42 }

            # Inline version of next
            let inline_next = { gen } -> do
                let extracted = Continuation.extract_suspended { suspended = gen }
                match extracted is
                    Some { val } -> do
                        let next_state = Continuation.resume { cont = val.cont, value = {} }
                        { value = Some { val = val.value }, gen = next_state }
                    None -> { value = None, gen = {} }

            # Recursive helper - like to_list_helper
            let helper = { gen, acc } -> do
                let result = inline_next { gen = gen }
                match result.value is
                    Some { val } -> do
                        let new_acc = __native_arraylist_concat { xs = acc, ys = [val] }
                        helper { gen = result.gen, acc = new_acc }
                    None -> acc

            # Create generator and call helper
            let g = gen {}
            perform Delimited in helper { gen = g, acc = [] }
        """.trimIndent()

        val result = executeProgram(source)
        println("Recursive result: $result")
        println("Recursive result class: ${result::class.simpleName}")
        assertTrue(result is FunkArrayList)
        val list = result as FunkArrayList
        println("List size: ${list.elements.size}")
        println("List elements: ${list.elements}")
    }
}
