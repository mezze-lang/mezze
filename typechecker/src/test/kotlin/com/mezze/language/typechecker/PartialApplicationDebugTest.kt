package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
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

        val parser = MezzeParser()
        val parsedModule = parser.parseParsedModule(code, "test.mz")

        val typeChecker = TypeChecker()
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(result)
    }
}
