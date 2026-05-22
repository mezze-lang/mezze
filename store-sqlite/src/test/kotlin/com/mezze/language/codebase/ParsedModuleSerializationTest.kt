package com.mezze.language.codebase

import com.mezze.language.store.*

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ParsedModuleSerializationTest {

    @Test
    fun `round-trips a simple module`() {
        val source = """
            pub let main = {} -> "hi"
        """.trimIndent()
        val parsed = MezzeParser().parseParsedModule(source, "x.mz")
        val blob = StoreSerialization.serializeParsedModule(parsed)
        val back = StoreSerialization.deserializeParsedModule(blob)
        assertEquals(parsed.decls.size, back.decls.size)
    }

    @Test
    fun `round-trips a module with imports and a perform expression`() {
        // Mirrors the layout of /tmp/funk-watch-test/src/app.mz that mez dev
        // exercises end-to-end. Catches any decl variant CBOR can't handle.
        val source = """
            use std::effects::log::{ Log, Console, println }
            use watchtest::utils::{ greet }

            let main = {} -> perform Console in do
              println { msg = greet { who = "stage1f" } }
        """.trimIndent()
        val parsed = MezzeParser().parseParsedModule(source, "x.mz")
        val blob = StoreSerialization.serializeParsedModule(parsed)
        val back = StoreSerialization.deserializeParsedModule(blob)
        assertEquals(parsed.decls.size, back.decls.size)
    }
}
