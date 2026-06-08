package com.mezze.language.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import java.time.Duration

/**
 * Repro for parser non-termination on `||` between two record-arg calls
 * inside a record-field lambda body. Caught during #239 OnSuccess work
 * — see memory/project_239_match_user_adt_bug.md.
 *
 * The hanging shape is:
 *
 *     foo { f = {} ->
 *       bar { x = 1 } || baz { y = 2 } }
 *
 * The parser appears to greedily extend the first `bar { ... }` past the
 * trailing `||`, never stabilising. `assertTimeoutPreemptively` runs the
 * parser in a worker thread that gets force-killed on timeout — needed
 * because the parser's tight Kotlin loop doesn't yield to Thread.interrupt
 * so JUnit's @Timeout annotation hangs the whole test process.
 *
 * Tests are marked @Disabled until the parser bug is fixed; they're kept
 * as the regression repro for whoever picks up the followup.
 */
@org.junit.jupiter.api.Disabled("Parser hangs on this input — see project_239_match_user_adt_bug.md")
class InlineOrInRecordLambdaTest {

    private val parser = MezzeParser()
    private val limit: Duration = Duration.ofSeconds(5)

    @Test
    fun `simple || between two function calls outside a lambda`() {
        // Sanity: outside a lambda, `||` between two calls parses fine.
        val src = """
            let main = {} -> bar { x = 1 } || baz { y = 2 }
        """
        assertTimeoutPreemptively(limit) {
            parser.parseParsedModule(src, "<test>")
        }
    }

    @Test
    fun `|| between two function calls inside a record-field lambda body`() {
        // The repro: lambda is the value of a record field; its body
        // is `call1 { ... } || call2 { ... }` on adjacent lines.
        val src = """
            let main = {} -> foo { f = {} ->
              bar { x = 1 } ||
              baz { y = 2 } }
        """
        assertTimeoutPreemptively(limit) {
            parser.parseParsedModule(src, "<test>")
        }
    }

    @Test
    fun `|| between two function calls inside a record-field lambda body on one line`() {
        val src = """
            let main = {} -> foo { f = {} -> bar { x = 1 } || baz { y = 2 } }
        """
        assertTimeoutPreemptively(limit) {
            parser.parseParsedModule(src, "<test>")
        }
    }
}
