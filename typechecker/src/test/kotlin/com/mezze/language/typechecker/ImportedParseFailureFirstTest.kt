package com.mezze.language.typechecker

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.fail

/**
 * When a module a file imports from does not PARSE, that parse error is what gets
 * reported — not a consequence of it. Before, `codec.mz` importing `Handle` from a
 * `core.mz` that failed to parse said only "an ability impl must target a named type,
 * but … 'Handle' … is not a declared type": an early validation pass returned its own
 * errors and dropped the recorded import failure. A provider's CI failed exactly so.
 */
class ImportedParseFailureFirstTest : VariantSpecBase() {

    @Test fun `the parse error of an imported module comes first`() {
        writeModule("core", """
            pub newtype Handle = Int

            pub let open_it = {} -> do
              let _ = 1
              Handle.(1)
        """)
        writeModule("codec", """
            use core::{ Handle }

            ability Show for 'a where { show: { it: 'a } -> Str }

            impl Show for Handle where { show = { it } -> "handle" }
        """)
        val r = checkMain("""
            use codec::{ Show }
            $MAIN
        """)
        val msg = r.exceptionOrNull()?.message ?: fail("must be refused: core.mz does not parse")
        val parse = msg.indexOf("Failed to parse module core")
        assertTrue(parse >= 0, "the cause — core.mz's parse error — must be reported; got:\n$msg")
        val consequence = msg.indexOf("not a declared type")
        assertTrue(consequence < 0 || parse < consequence, "the cause comes before its consequence; got:\n$msg")
    }
}
