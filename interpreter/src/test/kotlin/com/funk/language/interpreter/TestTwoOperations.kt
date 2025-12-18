package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test

class TestTwoOperations {
    private val parser = FunkParser()
    private val typeChecker = TypeChecker()

    @Test
    fun testTwoOperationsNoConcat() {
        val source = """
            ability Show 'a where {
              show : {val: 'a} -> String
              debug : {val: 'a} -> String
            }

            impl Show String where {
              show : {val: String} -> String = {val} -> val
              debug : {val: String} -> String = {val} -> val
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        println("Result: $result")
    }
}
