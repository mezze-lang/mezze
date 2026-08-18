package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * When an unresolved module path omits its stdlib grouping directory
 * (`use std::list` instead of `use std::collections::list`), the M0801
 * "module not found" error suggests the real path. Suggestions are GROUNDED —
 * every candidate is verified to resolve, so a genuinely-bogus path gets no
 * false suggestion.
 */
class ModuleSuggestionTest {
    private val parser = MezzeParser()

    /** Returns the ModuleNotFound message (whatever error type it surfaces as). */
    private fun moduleError(src: String): String? =
        try { TypeChecker().typeCheck(parser.parseParsedModule(src.trimIndent(), "p.mz"), Paths.get(".")); null }
        catch (e: Throwable) { e.message }

    @Test
    fun `std list suggests std collections list`() {
        val m = moduleError("use std::list::{ get }\nlet x = 1")
        assertNotNull(m)
        assertTrue(m!!.contains("std::collections::list"),
            "should suggest the real collections path, got: $m")
    }

    @Test
    fun `std map suggests std collections map`() {
        val m = moduleError("use std::map::{ empty }\nlet x = 1")
        assertTrue(m?.contains("std::collections::map") == true, "got: $m")
    }

    @Test
    fun `std exception suggests std effects exception`() {
        val m = moduleError("use std::exception::{ raise }\nlet x = 1")
        assertTrue(m?.contains("std::effects::exception") == true, "got: $m")
    }

    @Test
    fun `std compare suggests std abilities compare`() {
        val m = moduleError("use std::compare::{ Ord }\nlet x = 1")
        assertTrue(m?.contains("std::abilities::compare") == true, "got: $m")
    }

    @Test
    fun `a genuinely bogus module gets no suggestion`() {
        val m = moduleError("use std::totallybogus::{ x }\nlet x = 1")
        assertNotNull(m)
        assertFalse(m!!.contains("Did you mean"),
            "must not invent a suggestion for a nonexistent module, got: $m")
    }
}
