package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

class PartialApplicationDebugTest {
    @Test
    fun `minimal partial application`() {
        val code = """
            let add = { x, y } -> x + y
            let add_five = add { x = 5 }
            let result = add_five { y = 10 }
            result
        """.trimIndent()

        val parser = FunkParser()
        val program = parser.parse(code)

        val typeChecker = TypeChecker()
        val result = typeChecker.typeCheck(program)

        assertNotNull(result)
    }
}
