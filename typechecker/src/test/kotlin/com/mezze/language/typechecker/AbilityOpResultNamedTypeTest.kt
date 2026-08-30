package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * #334: a call whose declared return is a named TypeApplication with free vars
 * (`await : {..} -> Result 'a AsyncError`) used to collapse to a bare type var at
 * generation time. Inherent-method dispatch runs during generation, so a receiver
 * with two candidate methods (`get_or_else` lives on both Result and Option) could
 * not be dispatched — the call fell through to a structural field access and failed
 * with "Cannot access field 'get_or_else' on variant type: | Ok ..., Err ... |".
 * Preserving the named Result identity at the call site fixes dispatch.
 */
class AbilityOpResultNamedTypeTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun check(src: String) {
        tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
    }

    @Test
    fun `inherent method on an ability-op Result result resolves without annotation`() {
        // `a.await {}` returns Result Str AsyncError; `.get_or_else` (2 candidates:
        // Result + Option) must dispatch on the preserved Result identity.
        check(
            """
            use std::effects::async::{ Async, LoomAsync }
            use std::result::{ get_or_else }
            let main = {} -> perform LoomAsync in do
              let a = Async.spawn { f = {} -> "hi" }
              let r = a.await {}
              r.get_or_else { def = "?" }
            """
        )
    }
}
