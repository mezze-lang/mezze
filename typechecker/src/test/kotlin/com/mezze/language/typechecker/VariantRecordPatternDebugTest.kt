package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
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

        val parser = MezzeParser()
        val parsedModule = parser.parseParsedModule(code, "test.mz")

        val typeChecker = TypeChecker()
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(result)
    }
}
