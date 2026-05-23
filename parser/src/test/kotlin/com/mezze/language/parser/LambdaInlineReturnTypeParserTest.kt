package com.mezze.language.parser

import com.mezze.language.parsedast.diagnostics.ErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class LambdaInlineReturnTypeParserTest {

    private val parser = MezzeParser()

    private fun parseExpectingFailure(source: String): ParseException =
        assertThrows(ParseException::class.java) {
            parser.parseParsedModule(source, "inline_ret.mz")
        }

    @Test
    fun `inline return type with single ident is rejected with P0123`() {
        val source = """
            let f = { n: Int } -> Int do
              n
        """.trimIndent()
        val ex = parseExpectingFailure(source)
        assertEquals(ErrorCode.P0123_LAMBDA_INLINE_RETURN_TYPE, ex.code)
        val msg = ex.message ?: ""
        assertTrue(msg.contains("Int"), "Message should mention the offending type: $msg")
    }

    @Test
    fun `inline return type with parametrized type is rejected`() {
        val source = """
            let f = { xs: List Int } -> List Int do
              xs
        """.trimIndent()
        val ex = parseExpectingFailure(source)
        assertEquals(ErrorCode.P0123_LAMBDA_INLINE_RETURN_TYPE, ex.code)
        val msg = ex.message ?: ""
        assertTrue(msg.contains("List Int"), "Message should mention the full type 'List Int': $msg")
    }

    @Test
    fun `inline return type with multi-arg parametrized type is rejected`() {
        val source = """
            let f = { x: Int } -> Result Int Str do
              Ok { val = x }
        """.trimIndent()
        val ex = parseExpectingFailure(source)
        assertEquals(ErrorCode.P0123_LAMBDA_INLINE_RETURN_TYPE, ex.code)
        val msg = ex.message ?: ""
        assertTrue(msg.contains("Result Int Str"), "Message should mention 'Result Int Str': $msg")
    }

    @Test
    fun `hint includes binding-level annotation rewrite`() {
        val source = """
            let f = { n: Int } -> Int do
              n
        """.trimIndent()
        val ex = parseExpectingFailure(source)
        val hintBody = ex.hints.joinToString("\n")
        assertTrue(
            hintBody.contains("let f:") || hintBody.contains("annotate the whole"),
            "Hint should suggest the binding-level annotation form, got: ${ex.hints}",
        )
    }

    @Test
    fun `lambda returning a constructor still parses`() {
        // `-> Some { val = x }` is a legitimate body — must not trigger P0123.
        val source = """
            let f = { x: Int } -> Some { val = x }
        """.trimIndent()
        val module = parser.parseParsedModule(source, "ok_ctor.mz")
        assertNotNull(module)
    }

    @Test
    fun `do-bodied lambda without inline type still parses`() {
        val source = """
            let f = { n: Int } -> do
              n
        """.trimIndent()
        val module = parser.parseParsedModule(source, "ok_do.mz")
        assertNotNull(module)
    }
}
