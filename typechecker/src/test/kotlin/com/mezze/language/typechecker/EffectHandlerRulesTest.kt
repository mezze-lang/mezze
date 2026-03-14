package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertNotNull

/**
 * Tests for effect handler rules validation.
 *
 * These tests verify that the type checker correctly enforces rules for:
 * 1. Exact type matching between effects and handlers
 * 2. Handlers can add effects that propagate
 * 3. All methods must be implemented
 * 4. Handlers can use other effects
 * 5. Partial effect handling
 * 6. Prevention of incorrect handler usage
 */
class EffectHandlerRulesTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    // ========== PASSING TESTS ==========

    @Test
    fun `test handler types must match effect declaration exactly`() {
        val source = """
            effect Database where {
              query: { sql: Str } -> [Str] <Database>
            }

            impl Database for PostgresDB where {
              query: { sql: Str } -> [Str] = { sql } -> ["result1", "result2"]
            }

            let runQuery = { sql } -> Database.query { sql = sql }

            perform PostgresDB in runQuery { sql = "SELECT * FROM users" }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Handler with exact type match should pass")
    }

    @Test
    fun `test handler can add effects that propagate`() {
        val source = """
            effect Log where {
              info: { msg: Str } -> {} <Log>
            }

            effect FileSystem where {
              write: { path: Str, content: Str } -> {} <FileSystem>
            }

            impl FileSystem for DiskFS where {
              write: { path: Str, content: Str } -> {} = { path, content } -> {}
            }

            impl Log for FileLogger where {
              info: { msg: Str } -> {} <FileSystem> = { msg } ->
                FileSystem.write { path = "/var/log/app.log", content = msg }
            }

            let logMessage = { text } -> Log.info { msg = text }

            # Wrap in function to avoid top-level <*> restriction
            let test = {} -> perform DiskFS in (perform FileLogger in logMessage { text = "test" })
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Handler adding effects should pass")
    }

    @Test
    fun `test all methods must be implemented`() {
        val source = """
            effect Cache where {
              get: { key: Str } -> Str <Cache>,
              set: { key: Str, value: Str } -> {} <Cache>,
              delete: { key: Str } -> {} <Cache>
            }

            impl Cache for MemCache where {
              get: { key: Str } -> Str = { key } -> "cached_value",
              set: { key: Str, value: Str } -> {} = { key, value } -> {},
              delete: { key: Str } -> {} = { key } -> {}
            }

            let useCache = { key } -> do
              Cache.set { key = key, value = "test" }
              let val = Cache.get { key = key }
              Cache.delete { key = key }
              val

            perform MemCache in useCache { key = "mykey" }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Handler implementing all methods should pass")
    }

    @Test
    fun `test handler can use other effects`() {
        val source = """
            use std::collections::seq

            effect Database where {
              query: { sql: Str } -> [Str] <Database>
            }

            effect Auth where {
              check: { token: Str } -> Bool <Auth>
            }

            impl Database for PostgresDB where {
              query: { sql: Str } -> [Str] = { sql } -> ["valid_token"]
            }

            impl Auth for DatabaseAuth where {
              check: { token: Str } -> Bool <Database> = { token } -> do
                let results = Database.query { sql = "SELECT * FROM tokens" }
                seq::length {xs = results} > 0
            }

            let authenticate = { token } -> Auth.check { token = token }

            perform PostgresDB in (perform DatabaseAuth in authenticate { token = "abc123" })
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Handler using other effects should pass")
    }

    @Test
    fun `test partial effect handling`() {
        val source = """
            effect Log where {
              info: { msg: Str } -> {} <Log>
            }

            effect Cache where {
              get: { key: Str } -> Str <Cache>
            }

            impl Log for Console where {
              info: { msg: Str } -> {} = { msg } -> {}
            }

            impl Cache for MemCache where {
              get: { key: Str } -> Str = { key } -> "cached"
            }

            let fetchData = { id } -> do
              Log.info { msg = "Fetching data" }
              Cache.get { key = id }

            let withLogHandled = { id } -> perform Console in fetchData { id = id }
            let fullyHandled = { id } -> perform MemCache in withLogHandled { id = id }

            fullyHandled { id = "user123" }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Partial effect handling should pass")
    }

    @Test
    fun `test handler adds multiple effects`() {
        val source = """
            effect Log where {
              info: { msg: Str } -> {} <Log>
            }

            effect FileSystem where {
              write: { path: Str, content: Str } -> {} <FileSystem>
            }

            effect Network where {
              send: { endpoint: Str, data: Str } -> {} <Network>
            }

            impl FileSystem for DiskFS where {
              write: { path: Str, content: Str } -> {} = { path, content } -> {}
            }

            impl Network for HttpClient where {
              send: { endpoint: Str, data: Str } -> {} = { endpoint, data } -> {}
            }

            impl Log for CompositeLogger where {
              info: { msg: Str } -> {} <FileSystem, Network> = { msg } -> do
                FileSystem.write { path = "/var/log/app.log", content = msg }
                Network.send { endpoint = "log-server", data = msg }
            }

            let logMessage = { text } -> Log.info { msg = text }

            perform HttpClient, DiskFS, CompositeLogger in logMessage { text = "test" }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Handler adding multiple effects should pass")
    }

    // ========== FAILING TESTS ==========

    @Test
    fun `test output type mismatch should fail`() {
        val source = """
            effect Database where {
              query: { sql: Str } -> [Str] <Database>
            }

            impl Database for BadDB where {
              query: { sql: Str } -> Str = { sql } -> "wrong type"
            }

            let runQuery = { sql } -> Database.query { sql = sql }

            perform BadDB in runQuery { sql = "SELECT * FROM users" }
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test perform on pure function should fail`() {
        // Define effect and impl inline instead of using stdlib imports
        val source = """
            effect Log where {
              info: { msg: Str } -> {} <Log>
            }

            impl Log for Console where {
              info: { msg: Str } -> {} = { msg } -> {}
            }

            let pureFunction = { x } -> x + 1

            perform Console in pureFunction { x = 5 }
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: Console handles Log effect, but pureFunction doesn't use Log") {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test wrong effect handler should fail`() {
        val source = """
            effect Log where {
              info: { msg: Str } -> {} <Log>
            }

            effect Database where {
              query: { sql: Str } -> [Str] <Database>
            }

            impl Log for Logger where {
              info: { msg: Str } -> {} = { msg } -> {}
            }

            impl Database for PostgresDB where {
              query: { sql: Str } -> [Str] = { sql } -> ["result"]
            }

            let logMessage = { text } -> Log.info { msg = text }

            perform PostgresDB in logMessage { text = "test" }
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: PostgresDB handles Database, not Log") {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test double handler for same effect should fail`() {
        val source = """
            effect Log where {
              info: { msg: Str } -> {} <Log>
            }

            impl Log for BasicLogger where {
              info: { msg: Str } -> {} = { msg } -> {}
            }

            impl Log for EnhancedLogger where {
              info: { msg: Str } -> {} = { msg } -> {}
            }

            let logMessage = { text } -> Log.info { msg = text }

            perform EnhancedLogger, BasicLogger in logMessage { text = "test" }
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: Can't apply two handlers to the same single effect usage") {
            typeChecker.typeCheck(program).program
        }
    }
}
