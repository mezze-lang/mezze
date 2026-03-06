package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertNotNull

class PerformerConflictTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `test performer conflict - right to left consumption should fail`() {
        // This should FAIL: BasicLogger (rightmost) expects to handle Log, but EnhancedLogger (leftmost) already consumed it
        val source = """
            effect Log where {
              info : {msg: String} -> {} <Log>
            }

            impl Log for BasicLogger where {
              info : {msg: String} -> {} = {msg} -> {}
            }

            impl Log for EnhancedLogger where {
              info : {msg: String} -> {} = {msg} -> {}
            }

            perform BasicLogger, EnhancedLogger in Log.info {msg = "Test"}
        """.trimIndent()

        val program = parser.parse(source)

        // This should now correctly fail: we can't apply two handlers to the same single effect usage
        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test performer no conflict - different effects should pass`() {
        // This should PASS: Different effects, no conflict
        val source = """
            effect Log where {
              info : {msg: String} -> {} <Log>
            }

            effect Database where {
              save : {data: String} -> {} <Database>
            }

            impl Log for Logger where {
              info : {msg: String} -> {} = {msg} -> {}
            }

            impl Database for DbHandler where {
              save : {data: String} -> {} = {data} -> {}
            }

            perform DbHandler, Logger in do
              Log.info {msg = "Starting"}
              Database.save {data = "test"}
        """.trimIndent()

        val program = parser.parse(source)

        // This should succeed - different effects, no conflict
        typeChecker.typeCheck(program).program
    }

    @Test
    fun `test single performer should always pass`() {
        // Single performer should always work
        val source = """
            effect Log where {
              info : {msg: String} -> {} <Log>
            }

            impl Log for BasicLogger where {
              info : {msg: String} -> {} = {msg} -> {}
            }

            perform BasicLogger in Log.info {msg = "Test"}
        """.trimIndent()

        val program = parser.parse(source)

        // This should succeed
        typeChecker.typeCheck(program).program
    }

    @Test
    fun `test multiple performers different effects should pass`() {
        // Multiple performers handling different effects should work
        val source = """
            effect Log where {
              info : {msg: String} -> {} <Log>
            }

            effect Database where {
              save : {data: String} -> {} <Database>
            }

            impl Log for Logger where {
              info : {msg: String} -> {} = {msg} -> {}
            }

            impl Database for DbHandler where {
              save : {data: String} -> {} = {data} -> {}
            }

            perform DbHandler, Logger in do
              Log.info {msg = "Starting"}
              Database.save {data = "test"}
        """.trimIndent()

        val program = parser.parse(source)

        // This should succeed - different effects, no conflict
        typeChecker.typeCheck(program).program
    }
}
