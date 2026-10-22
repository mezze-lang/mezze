package com.mezze.language.langservice.hover

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import java.nio.file.Paths

/**
 * A function's hover shows its signature, not a dump of the sample rows that
 * reach each match arm. That "Branch inputs" section was noise in both the web
 * playground and the editor; running samples belongs behind an explicit action,
 * not spelled out on every hover. This pins that the section is gone.
 */
class HoverNoBranchInputsTest {

    private fun hoverOverName(src: String, name: String): String? {
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
        val line = lines.indexOfFirst { it.contains("let $name") }
        return HoverInfo.at(ctx, line, lines[line].indexOf(name))
    }

    @Test
    fun `a matching function's hover shows its signature but no Branch inputs section`() {
        val src = """
            type Status = | Queued, Shipped { days: Int }, Cancelled { why: Str } |

            let describe = { order } -> match order.status is
              Queued -> order.id
              Shipped { days = 0 } -> order.id
              Shipped { days } -> days.to_str {}
              Cancelled { why } -> why

            let main = {} -> describe { order = { id = "A-1", status = Queued } }
        """.trimIndent()
        val hover = hoverOverName(src, "describe")
        assertNotNull(hover, "expected a hover for describe")
        assertTrue(hover!!.contains("describe :"), "hover should still show the signature:\n$hover")
        assertFalse(hover.contains("Branch inputs"), "hover must not carry the branch-inputs dump:\n$hover")
        assertFalse(hover.contains("mez sample"), "hover must not spell out `mez sample` rows:\n$hover")
    }
}
