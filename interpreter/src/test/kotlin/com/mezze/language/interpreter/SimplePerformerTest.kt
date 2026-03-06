package com.mezze.language.interpreter

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class SimplePerformerTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker
    private lateinit var interpreter: MezzeInterpreter

    @BeforeEach
    fun setup() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
        interpreter = MezzeInterpreter()
    }

    @Test
    fun testVerySimplePerformer() {
        // This is the exact same code that works in CLI
        val source = """
            effect Console where {
              print : {msg: String} -> {} <Console>
            }

            impl Console for Terminal where {
              print : {msg: String} -> {} = {msg} -> {}
            }

            perform Terminal in Console.print {msg = "Hello"}
        """.trimIndent()

        println("Parsing...")
        val program = parser.parse(source)
        
        println("Type checking...")
        val typeCheckResult = typeChecker.typeCheck(program)
        
        println("Executing...")
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances)

        assertTrue(result is MezzeRecord)
        assertEquals(0, (result as MezzeRecord).fields.size) // Unit type
    }
}
