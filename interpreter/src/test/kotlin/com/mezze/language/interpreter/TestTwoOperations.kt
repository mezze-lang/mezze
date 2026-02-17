package com.mezze.language.interpreter

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test

class TestTwoOperations {
    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @Test
    fun testTwoOperationsNoConcat() {
        val source = """
            ability Show 'a where {
              show : {val: 'a} -> String,
              debug : {val: 'a} -> String
            }

            impl Show String where {
              show : {val: String} -> String = {val} -> val,
              debug : {val: String} -> String = {val} -> val
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        println("Result: $result")
    }
}
