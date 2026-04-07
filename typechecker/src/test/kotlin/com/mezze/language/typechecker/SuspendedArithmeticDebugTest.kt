package com.mezze.language.typechecker

import java.nio.file.Paths
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
        val parsedModule = parser.parseParsedModule(code, "test.mz")

        val typeChecker = TypeChecker()
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(result)
    }
}
