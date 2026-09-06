package com.mezze.language.langservice.hover

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Paths

/**
 * A handler may take parameters of its own:
 *
 *     impl Http for MockHttpClient { rules: [MockRule] } where { … }
 *
 * They are what a caller has to supply at the `perform` site, so the hover has
 * to show them. It used to print the header as `impl Http for MockHttpClient`
 * with the parameters dropped, which left the reader looking at a hover that
 * said nothing about the `{ rules = … }` sitting in the code beside it.
 */
class HoverHandlerParamsTest {

    private fun hoverOver(src: String, target: String): String? {
        val parser = MezzeParser()
        val module = parser.parseParsedModule(src, "t.mz")
        val tc = TypeChecker()
        val result = tc.typeCheck(module, Paths.get("."))
        val ctx = HoverInfo.Context(
            content = src,
            typeCheckResult = result,
            typeAliases = tc.getTypeAliases(),
            parsedModule = module,
        )
        val lines = src.lines()
        val line = lines.indexOfFirst { it.contains("impl ") && it.contains(target) }
        return HoverInfo.at(ctx, line, lines[line].indexOf(target))
    }

    @Test
    fun `handler parameters appear in the hover header`() {
        val src = """
            effect Http where { fetch: { url: Str } -> Str <Http> }

            type Rule = { url: Str, body: Str }

            impl Http for MockClient { rules: [Rule] } where {
              fetch = { url } -> "mocked",
            }
        """.trimIndent()

        val hover = hoverOver(src, "MockClient")
        assertNotNull(hover, "expected a hover for the MockClient handler")
        assertTrue(
            hover!!.contains("rules"),
            "hover must name the handler's parameter, which the caller has to pass:\n$hover",
        )
        assertTrue(
            hover.contains("impl Http for MockClient {"),
            "the parameters belong in the header, beside the handler name:\n$hover",
        )
    }

    @Test
    fun `a handler with no parameters keeps its plain header`() {
        val src = """
            effect Http where { fetch: { url: Str } -> Str <Http> }

            impl Http for RealClient where {
              fetch = { url } -> "real",
            }
        """.trimIndent()

        val hover = hoverOver(src, "RealClient")
        assertNotNull(hover, "expected a hover for the RealClient handler")
        assertTrue(
            hover!!.contains("impl Http for RealClient where"),
            "no parameters means no braces after the name:\n$hover",
        )
    }
}
