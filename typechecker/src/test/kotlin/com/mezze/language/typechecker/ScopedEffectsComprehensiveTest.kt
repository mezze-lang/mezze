package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Comprehensive tests for scoped effects type checking.
 *
 * Tests cover:
 * 1. Effect propagation from initialize_scope
 * 2. Effect propagation from finalize_scope
 * 3. Combined effects from initialize_scope + finalize_scope + operations
 * 4. Effect handling (DB effect removed, IO effect remains)
 * 5. Multiple different effects in lifecycle hooks
 * 6. Scope variable availability and types
 * 7. Validation errors for incorrect usage
 */
class ScopedEffectsComprehensiveTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        ModuleResolver.clearCache()
        typeChecker = TypeChecker()
    }

    private fun typeCheck(source: String): TypeCheckResult {
        val ast = parser.parse(source)
        return typeChecker.typeCheck(ast)
    }

    private fun typeCheckFails(source: String): Exception {
        val ast = parser.parse(source)
        return assertThrows(Exception::class.java) {
            typeChecker.typeCheck(ast)
        }
    }

    private fun getVariableType(program: Program, variableName: String): MezzeType? {
        return program.items
            .filterIsInstance<LetBinding>()
            .find { it.variableName == variableName }
            ?.inferredType
    }

    private fun getFunctionEffects(type: MezzeType?): Set<String>? {
        return when (type) {
            is MezzeType.FunctionType -> type.effects?.effects
            else -> null
        }
    }

    // ========================================================================
    // EFFECT PROPAGATION TESTS
    // ========================================================================

    @Test
    fun `effects from initialize_scope propagate to perform site`() {
        val source = """
            effect IO where {
                print: { msg: String } -> {} <IO>
            }

            impl IO for Console where {
                print = { msg } -> __native_print { msg = msg }
            }

            effect DB where {
                query: { sql: String } -> String <DB>
            }

            impl DB for PostgresDB where {
                initialize_scope = {} ->
                    do
                        IO.print { msg = "Opening connection" }
                        { conn_id = 42 },
                finalize_scope = {} -> {},
                query = { sql } -> "result"
            }

            # x calls DB.query, has <DB> effect
            let x = {} -> DB.query { sql = "SELECT 1" }

            # y performs DB with PostgresDB
            # - DB effect is HANDLED (removed)
            # - IO effect is ADDED from initialize_scope
            let y = {} -> perform PostgresDB in x {}
        """.trimIndent()

        val result = typeCheck(source)
        val program = result.program

        // x should have DB effect
        val xType = getVariableType(program, "x") as MezzeType.FunctionType
        assertNotNull(xType.effects)
        assertTrue(xType.effects!!.effects.contains("DB"), "x should have DB effect")

        // y should have IO effect (from initialize_scope) but NOT DB (handled)
        val yType = getVariableType(program, "y") as MezzeType.FunctionType
        assertNotNull(yType.effects)
        assertTrue(yType.effects!!.effects.contains("IO"), "y should have IO effect from initialize_scope")
        assertFalse(yType.effects!!.effects.contains("DB"), "y should NOT have DB effect (handled)")
    }

    @Test
    fun `effects from finalize_scope propagate to perform site`() {
        val source = """
            effect IO where {
                print: { msg: String } -> {} <IO>
            }

            impl IO for Console where {
                print = { msg } -> __native_print { msg = msg }
            }

            effect DB where {
                query: { sql: String } -> String <DB>
            }

            impl DB for PostgresDB where {
                initialize_scope = {} -> { conn_id = 42 },
                finalize_scope = {} ->
                    do
                        IO.print { msg = "Closing connection" }
                        {},
                query = { sql } -> "result"
            }

            let x = {} -> DB.query { sql = "SELECT 1" }
            let y = {} -> perform PostgresDB in x {}
        """.trimIndent()

        val result = typeCheck(source)
        val program = result.program

        // y should have IO effect (from finalize_scope) but NOT DB (handled)
        val yType = getVariableType(program, "y") as MezzeType.FunctionType
        assertNotNull(yType.effects)
        assertTrue(yType.effects!!.effects.contains("IO"), "y should have IO effect from finalize_scope")
        assertFalse(yType.effects!!.effects.contains("DB"), "y should NOT have DB effect (handled)")
    }

    @Test
    fun `effects from both initialize_scope and finalize_scope combine`() {
        val source = """
            effect Log where {
                info: { msg: String } -> {} <Log>
            }

            impl Log for Console where {
                info = { msg } -> __native_print { msg = msg }
            }

            effect Metrics where {
                increment: { counter: String } -> {} <Metrics>
            }

            impl Metrics for StatsD where {
                increment = { counter } -> __native_increment { counter = counter }
            }

            effect DB where {
                query: { sql: String } -> String <DB>
            }

            impl DB for PostgresDB where {
                initialize_scope = {} ->
                    do
                        Log.info { msg = "Opening connection" }
                        { conn_id = 42 },
                finalize_scope = {} ->
                    do
                        Metrics.increment { counter = "db.connections.closed" }
                        {},
                query = { sql } -> "result"
            }

            let x = {} -> DB.query { sql = "SELECT 1" }
            let y = {} -> perform PostgresDB in x {}
        """.trimIndent()

        val result = typeCheck(source)
        val program = result.program

        // y should have both Log (from initialize_scope) and Metrics (from finalize_scope)
        val yType = getVariableType(program, "y") as MezzeType.FunctionType
        assertNotNull(yType.effects)
        assertTrue(yType.effects!!.effects.contains("Log"), "y should have Log effect from initialize_scope")
        assertTrue(yType.effects!!.effects.contains("Metrics"), "y should have Metrics effect from finalize_scope")
        assertFalse(yType.effects!!.effects.contains("DB"), "y should NOT have DB effect (handled)")
    }

    @Test
    fun `effects from operations also propagate`() {
        val source = """
            effect IO where {
                print: { msg: String } -> {} <IO>
            }

            impl IO for Console where {
                print = { msg } -> __native_print { msg = msg }
            }

            effect DB where {
                query: { sql: String } -> String <DB>
            }

            impl DB for PostgresDB where {
                initialize_scope = {} -> { conn_id = 42 },
                finalize_scope = {} -> {},
                query = { sql } ->
                    do
                        IO.print { msg = sql }
                        "result"
            }

            let x = {} -> DB.query { sql = "SELECT 1" }
            let y = {} -> perform PostgresDB in x {}
        """.trimIndent()

        val result = typeCheck(source)
        val program = result.program

        // y should have IO effect (from query operation)
        val yType = getVariableType(program, "y") as MezzeType.FunctionType
        assertNotNull(yType.effects)
        assertTrue(yType.effects!!.effects.contains("IO"), "y should have IO effect from query operation")
    }

    @Test
    fun `all three sources of effects combine correctly`() {
        val source = """
            effect Log where {
                info: { msg: String } -> {} <Log>
            }

            impl Log for Console where {
                info = { msg } -> __native_print { msg = msg }
            }

            effect Metrics where {
                increment: { counter: String } -> {} <Metrics>
            }

            impl Metrics for StatsD where {
                increment = { counter } -> __native_increment { counter = counter }
            }

            effect IO where {
                write: { data: String } -> {} <IO>
            }

            impl IO for FileIO where {
                write = { data } -> __native_write { data = data }
            }

            effect DB where {
                query: { sql: String } -> String <DB>
            }

            impl DB for PostgresDB where {
                initialize_scope = {} ->
                    do
                        Log.info { msg = "Opening connection" }
                        { conn_id = 42 },
                finalize_scope = {} ->
                    do
                        Metrics.increment { counter = "db.close" }
                        {},
                query = { sql } ->
                    do
                        IO.write { data = sql }
                        "result"
            }

            let x = {} -> DB.query { sql = "SELECT 1" }
            let y = {} -> perform PostgresDB in x {}
        """.trimIndent()

        val result = typeCheck(source)
        val program = result.program

        // y should have Log, Metrics, and IO - but NOT DB
        val yType = getVariableType(program, "y") as MezzeType.FunctionType
        assertNotNull(yType.effects)
        val effects = yType.effects!!.effects
        assertTrue(effects.contains("Log"), "y should have Log effect from initialize_scope")
        assertTrue(effects.contains("Metrics"), "y should have Metrics effect from finalize_scope")
        assertTrue(effects.contains("IO"), "y should have IO effect from query operation")
        assertFalse(effects.contains("DB"), "y should NOT have DB effect (handled)")
    }

    // ========================================================================
    // SCOPE VARIABLE TESTS
    // ========================================================================

    @Test
    fun `scope variable is available in operations`() {
        val source = """
            effect DB where {
                query: { sql: String } -> Int <DB>
            }

            impl DB for PostgresDB where {
                initialize_scope = {} -> { connection_id = 42 },
                finalize_scope = {} -> {},
                query = { sql } -> scope.connection_id
            }
        """.trimIndent()

        // Should not throw - scope.connection_id is accessible
        val result = typeCheck(source)
        assertNotNull(result.program)
    }

    @Test
    fun `scope variable is available in finalize_scope`() {
        val source = """
            effect DB where {
                query: { sql: String } -> String <DB>
            }

            impl DB for PostgresDB where {
                initialize_scope = {} -> { connection_id = 42 },
                finalize_scope = {} ->
                    do
                        let id = scope.connection_id
                        {},
                query = { sql } -> "result"
            }
        """.trimIndent()

        // Should not throw - scope.connection_id is accessible in finalize_scope
        val result = typeCheck(source)
        assertNotNull(result.program)
    }

    @Test
    fun `scope variable is NOT available in initialize_scope`() {
        val source = """
            effect DB where {
                query: { sql: String } -> String <DB>
            }

            impl DB for PostgresDB where {
                initialize_scope = {} ->
                    do
                        let x = scope.something
                        { connection_id = 42 },
                finalize_scope = {} -> {},
                query = { sql } -> "result"
            }
        """.trimIndent()

        // Should fail - scope is not available in initialize_scope
        val error = typeCheckFails(source)
        assertTrue(
            error.message?.contains("scope") == true,
            "Expected error about 'scope' not being available: ${error.message}"
        )
    }

    @Test
    fun `scope variable is NOT available without initialize_scope`() {
        val source = """
            effect Log where {
                info: { msg: String } -> {} <Log>
            }

            impl Log for Console where {
                info = { msg } ->
                    do
                        let x = scope.something
                        {}
            }
        """.trimIndent()

        // Should fail - no initialize_scope means no scope
        val error = typeCheckFails(source)
        assertTrue(
            error.message?.contains("scope") == true ||
            error.message?.contains("initialize_scope") == true,
            "Expected error about 'scope' not being available: ${error.message}"
        )
    }

    @Test
    fun `scope variable has correct record type from initialize_scope`() {
        val source = """
            effect DB where {
                get_id: {} -> Int <DB>,
                get_name: {} -> String <DB>
            }

            impl DB for PostgresDB where {
                initialize_scope = {} -> { id = 42, name = "postgres" },
                finalize_scope = {} -> {},
                get_id = {} -> scope.id,
                get_name = {} -> scope.name
            }
        """.trimIndent()

        // Should not throw - scope.id is Int, scope.name is String
        val result = typeCheck(source)
        assertNotNull(result.program)
    }

    // ========================================================================
    // VALIDATION ERROR TESTS
    // ========================================================================

    @Test
    fun `initialize_scope requires finalize_scope`() {
        val source = """
            effect DB where {
                query: { sql: String } -> String <DB>
            }

            impl DB for PostgresDB where {
                initialize_scope = {} -> { conn_id = 42 },
                query = { sql } -> "result"
            }
        """.trimIndent()

        // Should fail - initialize_scope requires finalize_scope
        val error = typeCheckFails(source)
        assertTrue(
            error.message?.contains("finalize_scope") == true,
            "Expected error about missing finalize_scope: ${error.message}"
        )
    }

    @Test
    fun `finalize_scope requires initialize_scope`() {
        val source = """
            effect DB where {
                query: { sql: String } -> String <DB>
            }

            impl DB for PostgresDB where {
                finalize_scope = {} -> {},
                query = { sql } -> "result"
            }
        """.trimIndent()

        // Should fail - finalize_scope requires initialize_scope
        val error = typeCheckFails(source)
        assertTrue(
            error.message?.contains("initialize_scope") == true,
            "Expected error about missing initialize_scope: ${error.message}"
        )
    }

    @Test
    fun `initialize_scope must return record type - with explicit annotation`() {
        val source = """
            effect DB where {
                query: { sql: String } -> String <DB>
            }

            impl DB for PostgresDB where {
                initialize_scope: {} -> Int = {} -> 42,
                finalize_scope = {} -> {},
                query = { sql } -> "result"
            }
        """.trimIndent()

        // Should fail - initialize_scope must return a record, not Int
        val error = typeCheckFails(source)
        assertTrue(
            error.message?.contains("record") == true ||
            error.message?.contains("must return") == true,
            "Expected error about return type: ${error.message}"
        )
    }

    @Test
    fun `finalize_scope must return unit - with explicit annotation`() {
        val source = """
            effect DB where {
                query: { sql: String } -> String <DB>
            }

            impl DB for PostgresDB where {
                initialize_scope = {} -> { conn_id = 42 },
                finalize_scope: {} -> Int = {} -> 42,
                query = { sql } -> "result"
            }
        """.trimIndent()

        // Should fail - finalize_scope must return {}, not Int
        val error = typeCheckFails(source)
        assertTrue(
            error.message?.contains("{}") == true ||
            error.message?.contains("unit") == true ||
            error.message?.contains("must return") == true,
            "Expected error about return type: ${error.message}"
        )
    }

    @Test
    fun `scope is reserved word - cannot be used as variable name`() {
        val source = """
            let scope = 42
        """.trimIndent()

        // Should fail at parse time
        val error = assertThrows(Exception::class.java) {
            parser.parse(source)
        }
        assertTrue(
            error.message?.contains("scope") == true &&
            error.message?.contains("reserved") == true,
            "Expected 'scope is reserved' error: ${error.message}"
        )
    }

    @Test
    fun `scope is reserved word - cannot be used as lambda parameter`() {
        val source = """
            let f = { scope } -> scope + 1
        """.trimIndent()

        // Should fail at parse time
        val error = assertThrows(Exception::class.java) {
            parser.parse(source)
        }
        assertTrue(
            error.message?.contains("scope") == true &&
            error.message?.contains("reserved") == true,
            "Expected 'scope is reserved' error: ${error.message}"
        )
    }

    // ========================================================================
    // COMPLEX SCENARIOS
    // ========================================================================

    @Test
    fun `single perform with scoped effect handles main effect and adds lifecycle effects`() {
        // This tests that when you perform with a scoped effect impl:
        // - The main effect (DB) is handled/removed
        // - The lifecycle effects (IO from initialize/finalize) are added
        val source = """
            effect IO where {
                print: { msg: String } -> {} <IO>
            }

            impl IO for Console where {
                print = { msg } -> __native_print { msg = msg }
            }

            effect DB where {
                query: { sql: String } -> String <DB>
            }

            impl DB for PostgresDB where {
                initialize_scope = {} ->
                    do
                        IO.print { msg = "Opening" }
                        { conn_id = 42 },
                finalize_scope = {} ->
                    do
                        IO.print { msg = "Closing" }
                        {},
                query = { sql } -> "result"
            }

            let x = {} -> DB.query { sql = "SELECT 1" }

            # perform PostgresDB handles DB, adds IO
            let y = {} -> perform PostgresDB in x {}
        """.trimIndent()

        val result = typeCheck(source)
        val program = result.program

        // x should have DB effect
        val xType = getVariableType(program, "x") as MezzeType.FunctionType
        assertNotNull(xType.effects)
        assertTrue(xType.effects!!.effects.contains("DB"), "x should have DB effect")

        // y should have IO effect (from scoped lifecycle) but NOT DB (handled)
        val yType = getVariableType(program, "y") as MezzeType.FunctionType
        assertNotNull(yType.effects)
        assertTrue(yType.effects!!.effects.contains("IO"), "y should have IO effect from lifecycle")
        assertFalse(yType.effects!!.effects.contains("DB"), "y should NOT have DB effect (handled)")
    }

    @Test
    fun `scoped effect with state parameters`() {
        val source = """
            effect DB where {
                query: { sql: String } -> String <DB>
            }

            impl DB for PostgresDB { connection_string: String } where {
                initialize_scope = {} -> { url = connection_string },
                finalize_scope = {} -> {},
                query = { sql } -> scope.url
            }

            let x = {} -> DB.query { sql = "SELECT 1" }
            let y = {} -> perform PostgresDB { connection_string = "postgres://localhost" } in x {}
        """.trimIndent()

        val result = typeCheck(source)
        assertNotNull(result.program)
    }

    @Test
    fun `scoped effect impl with single operation is valid`() {
        val source = """
            effect Resource where {
                acquire: {} -> {} <Resource>
            }

            impl Resource for ManagedResource where {
                initialize_scope = {} -> { handle = 42 },
                finalize_scope = {} -> {},
                acquire = {} -> {}
            }
        """.trimIndent()

        val result = typeCheck(source)
        assertNotNull(result.program)
    }

    @Test
    fun `multiple operations can access scope`() {
        val source = """
            effect Cache where {
                get: { key: String } -> Int <Cache>,
                set: { key: String, value: Int } -> {} <Cache>,
                clear: {} -> {} <Cache>
            }

            impl Cache for MemoryCache where {
                initialize_scope = {} -> { cache_id = 1, max_size = 100 },
                finalize_scope = {} -> {},
                get = { key } -> scope.cache_id,
                set = { key, value } ->
                    do
                        let id = scope.cache_id
                        let max = scope.max_size
                        {},
                clear = {} ->
                    do
                        let id = scope.cache_id
                        {}
            }
        """.trimIndent()

        val result = typeCheck(source)
        assertNotNull(result.program)
    }

    // ========================================================================
    // SCOPE FIELD NOT FOUND WITH SUGGESTION TESTS
    // ========================================================================

    @Test
    fun `scope field typo produces error with suggestion - poool vs pool`() {
        // Simple test: directly use scope.poool without do block
        val source = """
            effect Pool where {
                query: {} -> Int <Pool>
            }

            impl Pool for PgPool where {
                initialize_scope = {} -> { pool = 42 },
                finalize_scope = {} -> {},
                query = {} -> scope.poool
            }
        """.trimIndent()

        val error = typeCheckFails(source)
        assertTrue(
            error.message?.contains("poool") == true &&
            error.message?.contains("not found") == true,
            "Expected 'field not found' error for 'poool': ${error.message}"
        )
        assertTrue(
            error.message?.contains("pool") == true ||
            error.message?.contains("Did you mean") == true,
            "Expected suggestion for 'pool': ${error.message}"
        )
    }

    @Test
    fun `scope field typo produces error with suggestion - cnt vs count`() {
        val source = """
            effect Counter where {
                increment: {} -> Int <Counter>
            }

            impl Counter for SimpleCounter where {
                initialize_scope = {} -> { count = 0, name = "counter" },
                finalize_scope = {} -> {},
                increment = {} -> scope.cnt
            }
        """.trimIndent()

        val error = typeCheckFails(source)
        assertTrue(
            error.message?.contains("cnt") == true &&
            error.message?.contains("not found") == true,
            "Expected 'field not found' error for 'cnt': ${error.message}"
        )
        assertTrue(
            error.message?.contains("count") == true ||
            error.message?.contains("Did you mean") == true,
            "Expected suggestion for 'count': ${error.message}"
        )
    }

    @Test
    fun `scope field typo in finalize_scope produces error with suggestion`() {
        val source = """
            effect Pool where {
                query: { sql: String } -> String <Pool>
            }

            impl Pool for PgPool where {
                initialize_scope = {} -> { pool_id = 42, connection = "conn" },
                finalize_scope = {} ->
                    do
                        let id = scope.pool_idd
                        {},
                query = { sql } -> "result"
            }
        """.trimIndent()

        val error = typeCheckFails(source)
        assertTrue(
            error.message?.contains("pool_idd") == true &&
            error.message?.contains("not found") == true,
            "Expected 'field not found' error for 'pool_idd': ${error.message}"
        )
        assertTrue(
            error.message?.contains("pool_id") == true ||
            error.message?.contains("Did you mean") == true,
            "Expected suggestion for 'pool_id': ${error.message}"
        )
    }

    @Test
    fun `scope field completely wrong name shows available fields`() {
        val source = """
            effect Cache where {
                get: { key: String } -> Int <Cache>
            }

            impl Cache for MemoryCache where {
                initialize_scope = {} -> { cache_id = 1, max_size = 100 },
                finalize_scope = {} -> {},
                get = { key } -> scope.xyz
            }
        """.trimIndent()

        val error = typeCheckFails(source)
        assertTrue(
            error.message?.contains("xyz") == true &&
            error.message?.contains("not found") == true,
            "Expected 'field not found' error for 'xyz': ${error.message}"
        )
        // Should show available fields even when no close suggestion
        assertTrue(
            error.message?.contains("cache_id") == true ||
            error.message?.contains("max_size") == true ||
            error.message?.contains("Available") == true,
            "Expected available fields to be shown: ${error.message}"
        )
    }

    @Test
    fun `correct scope field access works without error`() {
        val source = """
            effect DB where {
                get_pool: {} -> Int <DB>,
                get_connection: {} -> String <DB>
            }

            impl DB for PostgresDB where {
                initialize_scope = {} -> { pool = 42, connection = "postgres://localhost" },
                finalize_scope = {} -> {},
                get_pool = {} -> scope.pool,
                get_connection = {} -> scope.connection
            }
        """.trimIndent()

        // Should NOT throw - scope.pool and scope.connection are valid
        val result = typeCheck(source)
        assertNotNull(result.program)
    }
}
