package com.mezze.language.parser

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class EmptySeqTest {
    private val parser = MezzeParser()

    @Test
    fun testEmptySeqAlone() {
        val source = "let x: [Int] = []"
        val result = parser.parse(source)
        assertNotNull(result)
        assertEquals(1, result.items.size)
    }

    @Test
    fun testEmptySeqAfterNonEmpty() {
        val source = """
            let nums = [1, 2, 3]
            let empty: [Int] = []
        """.trimIndent()
        val result = parser.parse(source)
        assertNotNull(result)
        assertEquals(2, result.items.size)
    }
}
