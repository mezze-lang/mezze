package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

class SuspendedArithmeticDebugTest {
    @Test
    fun `minimal suspended arithmetic`() {
        val code = """
            effect Log where {
              print: {msg: String} -> {} <Log>
            }

            impl Log for Console where {
              print: {msg: String} -> {} = {msg} -> {}
            }

            let getNumber: {} -> Int <Log> = {} -> do
                Log.print {msg = "test"}
                42

            let result = getNumber {} + 10
            result
        """.trimIndent()

        val parser = FunkParser()
        val program = parser.parse(code)

        val typeChecker = TypeChecker()
        val result = typeChecker.typeCheck(program)

        assertNotNull(result)
    }
}
