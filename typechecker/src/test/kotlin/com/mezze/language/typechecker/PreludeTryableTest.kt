package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests that the `Tryable` ability — added to the prelude — is available
 * without an explicit `use std::abilities::tryable::{Tryable}` import in
 * the user's module.
 *
 * `Exception` (effect) and `ExceptionToResult` (performer) are NOT in the
 * prelude — a module that handles `.try {}`'s `<Exception>` row must import
 * them explicitly via `use std::effects::exception`.
 */
class PreludeTryableTest {
    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    private fun typeCheck(source: String): String? {
        return try {
            val ast = parser.parseParsedModule(source.trimIndent())
            typeChecker.typeCheck(ast, java.nio.file.Path.of("."))
            null
        } catch (e: Exception) {
            e.message
        }
    }

    @Test
    fun `Tryable in prelude — dispatch on Result without explicit import`() {
        // User does NOT import `Tryable` — it's in the prelude, so `.try {}`
        // dispatches on Result anyway. ExceptionToResult IS imported
        // explicitly to handle the `<Exception>` row.
        val error = typeCheck("""
            use std::effects::exception::{ExceptionToResult}

            let make_ok : {} -> Result Int Str = {} -> Ok {val = 42}

            let run = {} -> do
                let r = perform ExceptionToResult in do
                    let v = (make_ok {}).try {}
                    Ok {val = v}
                r
        """)
        assertNull(error, "Expected no error, got: $error")
    }

    @Test
    fun `Tryable in prelude — dispatch on Option without explicit import`() {
        val error = typeCheck("""
            use std::effects::exception::{ExceptionToResult}

            let make_some : {} -> Option Int = {} -> Some {val = 7}

            let run = {} -> do
                let r = perform ExceptionToResult in do
                    let v = (make_some {}).try {}
                    Ok {val = v}
                r
        """)
        assertNull(error, "Expected no error, got: $error")
    }
}
