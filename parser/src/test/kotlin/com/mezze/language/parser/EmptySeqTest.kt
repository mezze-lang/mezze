package com.mezze.language.parser

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class EmptySeqTest {
    private val parser = MezzeParser()

    @Test
    fun testEmptySeqAlone() {
        val source = "let x: [Int] = []"
        val module = parser.parseParsedModule(source)
        assertNotNull(module)
        assertEquals(1, module.decls.size)
    }

    @Test
    fun testEmptySeqAfterNonEmpty() {
        val source = """
            let nums = [1, 2, 3]
            let empty: [Int] = []
        """.trimIndent()
        val module = parser.parseParsedModule(source)
        assertNotNull(module)
        assertEquals(2, module.decls.size)
    }
}
