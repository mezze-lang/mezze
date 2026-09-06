package com.mezze.language.langservice.hover

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Paths

/**
 * A handler declaration carries two things the hover header used to drop.
 *
 *     impl Http for MockHttpClient { rules: [MockRule] } where { … }
 *     impl Exception for ExceptionToResult having 'error = 'e where { … }
 *
 * Parameters are what a caller supplies at the `perform` site — a hover that
 * omits them explains nothing about the `{ rules = … }` in the code beside it.
 * The `having` bindings are what give the operation signatures their meaning;
 * without them an op reading `{ error: 'error } -> 'a` names a type variable
 * that appears nowhere else in the hover.
 */
class HoverHandlerHeaderTest {

    private fun hoverOverImpl(src: String, target: String): String {
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
        val line = lines.indexOfFirst { it.startsWith("impl ") && it.contains(target) }
        val hover = HoverInfo.at(ctx, line, lines[line].indexOf(target))
        assertNotNull(hover, "expected a hover for $target")
        return hover!!
    }

    @Test
    fun `handler parameters appear in the header`() {
        val hover = hoverOverImpl(
            """
            effect Http where { fetch: { url: Str } -> Str <Http> }

            type Rule = { url: Str, body: Str }

            impl Http for MockClient { rules: [Rule] } where {
              fetch = { url } -> "mocked",
            }
            """.trimIndent(),
            "MockClient",
        )
        assertTrue(
            hover.contains("impl Http for MockClient {") && hover.contains("rules"),
            "the parameter the caller must pass belongs in the header:\n$hover",
        )
    }

    @Test
    fun `associated type bindings appear in the header`() {
        val hover = hoverOverImpl(
            """
            effect Fail having 'error where {
              fail: { error: 'error } -> 'a <Fail>
            }

            impl Fail for FailToStr having 'error = Str where {
              fail = { error } -> error,
            }
            """.trimIndent(),
            "FailToStr",
        )
        assertTrue(
            hover.contains("having 'error = Str"),
            "`having 'error = Str` explains the 'error in the ops below:\n$hover",
        )
    }

    @Test
    fun `a plain handler keeps its plain header`() {
        val hover = hoverOverImpl(
            """
            effect Http where { fetch: { url: Str } -> Str <Http> }

            impl Http for RealClient where {
              fetch = { url } -> "real",
            }
            """.trimIndent(),
            "RealClient",
        )
        assertTrue(
            hover.contains("impl Http for RealClient where"),
            "no parameters and no bindings means neither braces nor having:\n$hover",
        )
    }

    @Test
    fun `a long operation signature wraps at the arrow, never mid-type`() {
        val hover = hoverOverImpl(
            """
            type RequestRecord = { url: Str }
            type HttpConfiguration = { timeout: Int }
            type ResponseRecord = { status: Int, body: Str }

            effect Http where {
              request: { req: RequestRecord, config: HttpConfiguration } -> ResponseRecord <Http>
            }

            impl Http for RealClient where {
              request = { req, config } -> { status = 200, body = "ok" },
            }
            """.trimIndent(),
            "RealClient",
        )
        // Every line the hover renders stays inside the tooltip's width, so
        // whoever displays it never has to invent a break of its own.
        for (line in hover.lines()) {
            assert(line.length <= 60) { "hover line too long to render unwrapped:\n$line" }
        }
        // And the break, where it happened, is at the arrow.
        assert(hover.contains("-> ")) { "the return type should still be there:\n$hover" }
    }
}