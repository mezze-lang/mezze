package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

class VariantRecordPatternDebugTest {
    @Test
    fun `minimal variant with record pattern`() {
        val code = """
            type Point = | Point2D { x: Int, y: Int } |

            let test = {point} -> match point is
              Point2D { x, y } -> x + y
        """.trimIndent()

        val parser = FunkParser()
        val program = parser.parse(code)

        val typeChecker = TypeChecker()
        val result = typeChecker.typeCheck(program)

        assertNotNull(result)
    }
}
