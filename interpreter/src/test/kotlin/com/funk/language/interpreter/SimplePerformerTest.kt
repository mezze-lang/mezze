package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class SimplePerformerTest {

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker
    private lateinit var interpreter: FunkInterpreter

    @BeforeEach
    fun setup() {
        parser = FunkParser()
        typeChecker = TypeChecker()
        interpreter = FunkInterpreter()
    }

    @Test
    fun testVerySimplePerformer() {
        // This is the exact same code that works in CLI
        val source = """
            effect Console where {
              print : {msg: String} -> {} <Console>
            }

            impl Console for Terminal where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }

            perform Terminal in Console.print {msg = "Hello"}
        """.trimIndent()

        println("Parsing...")
        val program = parser.parse(source)
        
        println("Type checking...")
        val typeCheckResult = typeChecker.typeCheck(program)
        
        println("Executing...")
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances)

        assertTrue(result is FunkRecord)
        assertEquals(0, (result as FunkRecord).fields.size) // Unit type
    }
}
