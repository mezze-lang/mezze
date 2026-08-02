package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import com.mezze.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class EffectPropagationTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun testBasicEffectPropagation() {
        val source = """
            effect Log where {
              log : {msg: Str} -> {} <Log>
            }

            effect Database where {
              query : {sql: Str} -> Str <Database>
            }

            impl Log for Console where {
              log : {msg: Str} -> {} = {msg} -> {}
            }

            impl Database for Postgres where {
              query : {sql: Str} -> Str = {sql} -> do
                Log.log {msg = "Executing: $(sql)"}
                "result"
            }

            # This function should have type: {query: Str} -> Str <Database>
            let useDatabase : {query: Str} -> Str <Database> = {query} ->
              Database.query {sql = query}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val useDatabaseType = typedProgram.getVariableType("useDatabase")
        assertNotNull(useDatabaseType)
        assertTrue(useDatabaseType is MezzeType.FunctionType)

        val functionType = useDatabaseType as MezzeType.FunctionType
        assertNotNull(functionType.effects)

        // Should have only Database effect (Log is internal to the implementation)
        val effects = functionType.effects!!.effects
        assertTrue(effects.contains("Database"), "Should contain Database effect")
        assertEquals(1, effects.size, "Should have exactly 1 effect")
    }

    @Test
    fun testNestedEffectPropagation() {
        val source = """
            effect Log where {
              log : {msg: Str} -> {} <Log>
            }

            effect Auth where {
              checkPermission : {resource: Str} -> Bool <Auth>
            }

            effect Database where {
              query : {sql: Str} -> Str <Database>
            }

            impl Log for Console where {
              log : {msg: Str} -> {} = {msg} -> {}
            }

            impl Auth for SimpleAuth where {
              checkPermission : {resource: Str} -> Bool = {resource} -> do
                Log.log {msg = "Checking permission for $(resource)"}
                True
            }

            impl Database for SecureDB where {
              query : {sql: Str} -> Str = {sql} -> do
                let hasPermission = Auth.checkPermission {resource = "database"}
                Log.log {msg = "Executing query: $(sql)"}
                "secure_result"
            }

            # This should propagate only Database effect
            let secureQuery : {sql: Str} -> Str <Database> = {sql} ->
              Database.query {sql = sql}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val secureQueryType = typedProgram.getVariableType("secureQuery")
        assertNotNull(secureQueryType)
        assertTrue(secureQueryType is MezzeType.FunctionType)

        val functionType = secureQueryType as MezzeType.FunctionType
        assertNotNull(functionType.effects)

        val effects = functionType.effects!!.effects
        assertTrue(effects.contains("Database"), "Should contain Database effect")
        assertEquals(1, effects.size, "Should have exactly 1 effect")
    }

    @Test
    fun testEffectPropagationInDoExpressions() {
        val source = """
            effect Log where {
              log : {msg: Str} -> {} <Log>
            }

            effect Random where {
              nextInt : {max: Int} -> Int <Random>
            }

            impl Log for Console where {
              log : {msg: Str} -> {} = {msg} -> {}
            }

            impl Random for MathRandom where {
              nextInt : {max: Int} -> Int = {max} -> do
                Log.log {msg = "Generating random number"}
                42
            }

            # Complex function with multiple effect calls
            let complexFunction : {seed: Int} -> Int <Random, Log> = {seed} -> do
              let first = Random.nextInt {max = seed}
              let second = Random.nextInt {max = first}
              Log.log {msg = "Generated two numbers"}
              first + second
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val complexFunctionType = typedProgram.getVariableType("complexFunction")
        assertNotNull(complexFunctionType)
        assertTrue(complexFunctionType is MezzeType.FunctionType)

        val functionType = complexFunctionType as MezzeType.FunctionType
        assertNotNull(functionType.effects)

        val effects = functionType.effects!!.effects
        assertTrue(effects.contains("Random"), "Should contain Random effect")
        assertTrue(effects.contains("Log"), "Should contain Log effect from Random operations")
    }

    @Test
    fun testEffectPropagationWithMultipleReturnTypes() {
        val source = """
            effect Log where {
              log : {msg: Str} -> {} <Log>
            }

            effect Error where {
              throw : {msg: Str} -> {} <Error>
            }

            effect FileSystem where {
              readFile : {path: Str} -> Str <FileSystem>,
              writeFile : {path: Str, content: Str} -> {} <FileSystem>
            }

            impl Log for Console where {
              log : {msg: Str} -> {} = {msg} -> {}
            }

            impl Error for SimpleError where {
              throw : {msg: Str} -> {} = {msg} -> {}
            }

            impl FileSystem for LocalFS where {
              readFile : {path: Str} -> Str = {path} ->
                if path == "missing" then do
                  Log.log {msg = "Reading file: $(path)"}
                  Error.throw {msg = "File not found"}
                  ""
                else do
                  Log.log {msg = "Reading file: $(path)"}
                  "file content",
              writeFile : {path: Str, content: Str} -> {} = {path, content} -> do
                Log.log {msg = "Writing to file: $(path)"}
                {}
            }

            # Function that uses both operations with different effect signatures
            let copyFile : {from: Str, to: Str} -> {} <FileSystem> = {from, to} -> do
              let content = FileSystem.readFile {path = from}
              FileSystem.writeFile {path = to, content = content}
              {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val copyFileType = typedProgram.getVariableType("copyFile")
        assertNotNull(copyFileType)
        assertTrue(copyFileType is MezzeType.FunctionType)

        val functionType = copyFileType as MezzeType.FunctionType
        assertNotNull(functionType.effects)

        val effects = functionType.effects!!.effects
        assertTrue(effects.contains("FileSystem"), "Should contain FileSystem effect")
        assertEquals(1, effects.size, "Should have exactly 1 effect")
    }

    @Test
    fun testEffectPropagationWithHandlers() {
        val source = """
            effect Log where {
              log : {msg: Str} -> {} <Log>
            }

            effect Database where {
              query : {sql: Str} -> Str <Database>
            }

            impl Log for Console where {
              log : {msg: Str} -> {} = {msg} -> {}
            }

            impl Database for TestDB where {
              query : {sql: Str} -> Str = {sql} -> do
                Log.log {msg = "Test query: $(sql)"}
                "test_result"
            }

            # Function that calls Database.query
            let queryWithLog : {sql: Str} -> Str <Database> = {sql} ->
              Database.query {sql = sql}

            # Function that handles Database effect but TestDB adds Log effect
            # So we still have Log effect after handling Database
            let partiallyHandledQuery : {sql: Str} -> Str <Log> = {sql} ->
              perform TestDB in queryWithLog {sql = sql}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val partiallyHandledType = typedProgram.getVariableType("partiallyHandledQuery")
        assertNotNull(partiallyHandledType)
        assertTrue(partiallyHandledType is MezzeType.FunctionType)

        val functionType = partiallyHandledType as MezzeType.FunctionType

        // After handling Database with TestDB, Database is removed but Log is added
        // because TestDB's impl body uses Log.log
        val effects = functionType.effects?.effects ?: emptySet()
        assertFalse(effects.contains("Database"), "Database effect should be handled")
        assertTrue(effects.contains("Log"), "Log effect should be propagated from TestDB impl body")
    }

    @Test
    fun testNoEffectPropagationForPureFunctions() {
        val source = """
            effect Log where {
              log : {msg: Str} -> {} <Log>
            }

            ability Math for 'a where {
              compute : {it: 'a, r: 'a} -> 'a
            }

            impl Math for Int where {
              compute : {it: Int, r: Int} -> Int = {it, r} -> it + r
            }

            # This function should have no effects since Math is an ability, not an effect
            let pureCalculation : {a: Int, b: Int} -> Int <> = {a, b} ->
              a.compute {r = b}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val pureCalculationType = typedProgram.getVariableType("pureCalculation")
        assertNotNull(pureCalculationType)
        assertTrue(pureCalculationType is MezzeType.FunctionType)

        val functionType = pureCalculationType as MezzeType.FunctionType
        // Should have no effects or empty effect row
        assertTrue(functionType.effects == null || functionType.effects!!.effects.isEmpty(),
                  "Pure function should have no effects")
    }

    @Test
    fun testHigherOrderFunctionWithPolymorphicEffects() {
        // Test that higher-order functions with polymorphic effects work correctly
        val source = """
            let twice: { f: {} -> Int <...> } -> Int <...> =
                { f } -> do
                    let x = f {}
                    let y = f {}
                    x + y

            let result = twice { f = {} -> 42 }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val twiceType = typedProgram.getVariableType("twice")
        assertNotNull(twiceType)
        assertTrue(twiceType is MezzeType.FunctionType)

        val resultType = typedProgram.getVariableType("result")
        assertNotNull(resultType)
        // result should be Int, or SuspendType<Int, empty effects> when using polymorphic effects
        val isIntOrSuspendedInt = resultType is MezzeType.IntType ||
            (resultType is MezzeType.SuspendType && resultType.valueType is MezzeType.IntType)
        assertTrue(isIntOrSuspendedInt,
            "Expected IntType or SuspendType<Int> but got $resultType")
    }

    @Test
    fun testPolymorphicEffectsUnifyWithConcreteEffects() {
        // Test that <...> unifies with <Log>
        // The logging_fn calls Log.info directly (not handled), so it has <Log> effects
        val source = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            impl Log for ConsoleLogger {} where {
                info = {msg} -> {}
            }

            let apply: { f: {} -> Int <...> } -> Int <...> =
                { f } -> f {}

            let logging_fn: {} -> Int <Log> =
                {} -> do
                    Log.info { msg = "test" }
                    42

            let result = apply { f = logging_fn }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val resultType = typedProgram.getVariableType("result")
        assertNotNull(resultType)
        // result should be Int - but might need to handle Log effect
        // depending on how effect propagation works
        assertTrue(resultType is MezzeType.IntType ||
                  resultType is MezzeType.SuspendType)
    }

    @Test
    fun testMultiplePolymorphicEffectParameters() {
        // Test that different polymorphic effect parameters are independent
        val source = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            effect DB where {
                query: {sql: Str} -> {} <DB>
            }

            impl Log for ConsoleLogger {} where {
                info = {msg} -> {}
            }

            impl DB for PostgresDB {} where {
                query = {sql} -> {}
            }

            let apply_both: { f: {} -> Int <...>, g: {} -> Int <...> } -> Int <...> =
                { f, g } -> do
                    let x = f {}
                    let y = g {}
                    x + y

            let log_fn = {} -> do
                perform ConsoleLogger in Log.info { msg = "logging" }
                10

            let db_fn = {} -> do
                perform PostgresDB in DB.query { sql = "SELECT 1" }
                20

            let result = apply_both { f = log_fn, g = db_fn }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val resultType = typedProgram.getVariableType("result")
        assertNotNull(resultType)
        // result should be Int, or SuspendType<Int, effects> when using polymorphic effects
        val isIntOrSuspendedInt = resultType is MezzeType.IntType ||
            (resultType is MezzeType.SuspendType && resultType.valueType is MezzeType.IntType)
        assertTrue(isIntOrSuspendedInt,
            "Expected IntType or SuspendType<Int> but got $resultType")
    }

    @Test
    fun testNestedPolymorphicEffects() {
        // Test polymorphic effects in nested function types
        val source = """
            let compose: { f: { x: Int } -> Int <...>, g: { x: Int } -> Int <...> } -> { x: Int } -> Int <...> =
                { f, g } -> { x } -> f { x = g { x = x } }

            let inc = { x: Int } -> x + 1
            let double = { x: Int } -> x * 2

            let composed = compose { f = inc, g = double }
            let result = composed { x = 5 }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val resultType = typedProgram.getVariableType("result")
        assertNotNull(resultType)
        // result should be Int, or SuspendType<Int, effects> when using polymorphic effects
        val isIntOrSuspendedInt = resultType is MezzeType.IntType ||
            (resultType is MezzeType.SuspendType && resultType.valueType is MezzeType.IntType)
        assertTrue(isIntOrSuspendedInt,
            "Expected IntType or SuspendType<Int> but got $resultType")
    }
}
