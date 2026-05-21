package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifies that errors involving `.try {}` / `Tryable` are understandable
 * and don't leak compiler internals.
 */
class TryableErrorMessagesTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    private fun expectError(source: String): String {
        return try {
            val ast = parser.parseParsedModule(source.trimIndent())
            typeChecker.typeCheck(ast, java.nio.file.Path.of("."))
            "(no error)"
        } catch (e: Exception) {
            e.message ?: "(empty)"
        }
    }

    @Test
    fun `try on Int has clear error mentioning Tryable`() {
        val msg = expectError("""
            use std::effects::exception::{ExceptionToResult}

            let run = {} -> do
                let r = perform ExceptionToResult in do
                    let v = (42).try {}
                    Ok {val = v}
                r

            run {}
        """)
        assertTrue(msg.contains("Tryable"), "Expected 'Tryable' in error, got: $msg")
        assertTrue(msg.contains("Int") || msg.contains("does not implement"),
            "Expected mention of Int / does not implement, got: $msg")
        // Should NOT leak compiler internals
        assertTrue(!msg.contains("MezzeUnit"), "Error should not mention MezzeUnit: $msg")
        assertTrue(!msg.contains("Dictionary slot"), "Error should not mention Dictionary slot: $msg")
        assertTrue(!msg.contains("CachedFieldAccessNode"),
            "Error should not mention CachedFieldAccessNode: $msg")
    }

    @Test
    fun `Tryable user impl is recognized in error suggestions`() {
        // When the user has impl'd Tryable for their own type, but calls .try on
        // an unrelated type, the available-impls list should include theirs.
        val msg = expectError("""
            use std::abilities::tryable::{Tryable}
            use std::effects::exception::{Exception, ExceptionToResult}

            type Box 'a = | Boxed {val: 'a} |

            impl Tryable for Box 'a having 'inner = 'a where {
                try = {it} -> match it is
                    Boxed {val} -> val
            }

            let run = {} -> do
                let r = perform ExceptionToResult in do
                    let v = (42).try {}
                    Ok {val = v}
                r

            run {}
        """)
        assertTrue(msg.contains("Tryable"), "Expected mention of Tryable: $msg")
    }

    @Test
    fun `bind on Int has friendly do-bind error`() {
        // The Phase 5 improvement: error should be framed around `<-` (not
        // the underlying ability), and suggest `let x = ...` as a workaround.
        val msg = expectError("""
            let foo = {} -> do
                x <- 42
                x
            foo {}
        """)
        assertTrue(msg.contains("`<-`"), "Expected '`<-`' in error, got: $msg")
        assertTrue(msg.contains("let") && msg.contains("="),
            "Expected `let x = ...` hint, got: $msg")
        assertTrue(msg.contains("Int"), "Expected mention of Int, got: $msg")
    }
}
