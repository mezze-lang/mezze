package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

class SuspendedArithmeticDebugTest {
    @Test
    fun `minimal suspended arithmetic`() {
        val code = """
            effect Log where {
              print: {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
              print: {msg: Str} -> {} = {msg} -> {}
            }

            let getNumber: {} -> Int <Log> = {} -> do
                Log.print {msg = "test"}
                42

            let result = getNumber {} + 10
            result
        """.trimIndent()

        val parser = MezzeParser()
        val program = parser.parse(code)

        val typeChecker = TypeChecker()
        val result = typeChecker.typeCheck(program)

        assertNotNull(result)
    }
}
