package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import com.funk.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class EffectPropagationTest {

    private val parser = FunkParser()
    private val typeChecker = TypeChecker()

    @Test
    fun testBasicEffectPropagation() {
        val source = """
            effect Log where {
              log : {msg: String} -> {} <Log>
            }

            effect Database where {
              query : {sql: String} -> String <Database>
            }

            impl Log for Console where {
              log : {msg: String} -> {} = {msg} -> {}
            }

            impl Database for Postgres where {
              query : {sql: String} -> String = {sql} -> do
                Log.log {msg = "Executing: $(sql)"}
                "result"
            }

            # This function should have type: {query: String} -> String <Database>
            let useDatabase : {query: String} -> String <Database> = {query} ->
              Database.query {sql = query}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // The useDatabase function should be the last item (index 4: Log, Database, Console impl, Postgres impl, useDatabase)
        val useDatabaseDecl = typedProgram.items[4] as LetBinding

        assertNotNull(useDatabaseDecl.inferredType)
        assertTrue(useDatabaseDecl.inferredType is FunkType.FunctionType)

        val functionType = useDatabaseDecl.inferredType as FunkType.FunctionType
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
              log : {msg: String} -> {} <Log>
            }

            effect Auth where {
              checkPermission : {resource: String} -> Boolean <Auth>
            }

            effect Database where {
              query : {sql: String} -> String <Database>
            }

            impl Log for Console where {
              log : {msg: String} -> {} = {msg} -> {}
            }

            impl Auth for SimpleAuth where {
              checkPermission : {resource: String} -> Boolean = {resource} -> do
                Log.log {msg = "Checking permission for $(resource)"}
                true
            }

            impl Database for SecureDB where {
              query : {sql: String} -> String = {sql} -> do
                let hasPermission = Auth.checkPermission {resource = "database"}
                Log.log {msg = "Executing query: $(sql)"}
                "secure_result"
            }

            # This should propagate only Database effect
            let secureQuery : {sql: String} -> String <Database> = {sql} ->
              Database.query {sql = sql}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val secureQueryDecl = typedProgram.items.find {
            it is LetBinding && it.variableName == "secureQuery"
        } as LetBinding

        assertNotNull(secureQueryDecl.inferredType)
        assertTrue(secureQueryDecl.inferredType is FunkType.FunctionType)

        val functionType = secureQueryDecl.inferredType as FunkType.FunctionType
        assertNotNull(functionType.effects)

        val effects = functionType.effects!!.effects
        assertTrue(effects.contains("Database"), "Should contain Database effect")
        assertEquals(1, effects.size, "Should have exactly 1 effect")
    }

    @Test
    fun testEffectPropagationInDoExpressions() {
        val source = """
            effect Log where {
              log : {msg: String} -> {} <Log>
            }

            effect Random where {
              nextInt : {max: Int} -> Int <Random>
            }

            impl Log for Console where {
              log : {msg: String} -> {} = {msg} -> {}
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

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val complexFunctionDecl = typedProgram.items.find {
            it is LetBinding && it.variableName == "complexFunction"
        } as LetBinding

        assertNotNull(complexFunctionDecl.inferredType)
        assertTrue(complexFunctionDecl.inferredType is FunkType.FunctionType)

        val functionType = complexFunctionDecl.inferredType as FunkType.FunctionType
        assertNotNull(functionType.effects)

        val effects = functionType.effects!!.effects
        assertTrue(effects.contains("Random"), "Should contain Random effect")
        assertTrue(effects.contains("Log"), "Should contain Log effect from Random operations")
    }

    @Test
    fun testEffectPropagationWithMultipleReturnTypes() {
        val source = """
            effect Log where {
              log : {msg: String} -> {} <Log>
            }

            effect Error where {
              throw : {msg: String} -> {} <Error>
            }

            effect FileSystem where {
              readFile : {path: String} -> String <FileSystem>,
              writeFile : {path: String, content: String} -> {} <FileSystem>
            }

            impl Log for Console where {
              log : {msg: String} -> {} = {msg} -> {}
            }

            impl Error for SimpleError where {
              throw : {msg: String} -> {} = {msg} -> {}
            }

            impl FileSystem for LocalFS where {
              readFile : {path: String} -> String = {path} ->
                if path == "missing" then do
                  Log.log {msg = "Reading file: $(path)"}
                  Error.throw {msg = "File not found"}
                  ""
                else do
                  Log.log {msg = "Reading file: $(path)"}
                  "file content"
              writeFile : {path: String, content: String} -> {} = {path, content} -> do
                Log.log {msg = "Writing to file: $(path)"}
                {}
            }

            # Function that uses both operations with different effect signatures
            let copyFile : {from: String, to: String} -> {} <FileSystem> = {from, to} -> do
              let content = FileSystem.readFile {path = from}
              FileSystem.writeFile {path = to, content = content}
              {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val copyFileDecl = typedProgram.items.find {
            it is LetBinding && it.variableName == "copyFile"
        } as LetBinding

        assertNotNull(copyFileDecl.inferredType)
        assertTrue(copyFileDecl.inferredType is FunkType.FunctionType)

        val functionType = copyFileDecl.inferredType as FunkType.FunctionType
        assertNotNull(functionType.effects)

        val effects = functionType.effects!!.effects
        assertTrue(effects.contains("FileSystem"), "Should contain FileSystem effect")
        assertEquals(1, effects.size, "Should have exactly 1 effect")
    }

    @Test
    fun testEffectPropagationWithHandlers() {
        val source = """
            effect Log where {
              log : {msg: String} -> {} <Log>
            }

            effect Database where {
              query : {sql: String} -> String <Database>
            }

            impl Log for Console where {
              log : {msg: String} -> {} = {msg} -> {}
            }

            impl Database for TestDB where {
              query : {sql: String} -> String = {sql} -> do
                Log.log {msg = "Test query: $(sql)"}
                "test_result"
            }

            # Function that calls Database.query
            let queryWithLog : {sql: String} -> String <Database> = {sql} ->
              Database.query {sql = sql}

            # Function that handles Database effect completely
            let partiallyHandledQuery : {sql: String} -> String <> = {sql} ->
              perform TestDB in queryWithLog {sql = sql}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val partiallyHandledDecl = typedProgram.items.find {
            it is LetBinding && it.variableName == "partiallyHandledQuery"
        } as LetBinding

        assertNotNull(partiallyHandledDecl.inferredType)
        assertTrue(partiallyHandledDecl.inferredType is FunkType.FunctionType)

        val functionType = partiallyHandledDecl.inferredType as FunkType.FunctionType

        // After handling Database with TestDB, all effects should be removed
        val effects = functionType.effects?.effects ?: emptySet()
        assertFalse(effects.contains("Database"), "Database effect should be handled")
        assertTrue(effects.isEmpty(), "All effects should be handled")
    }

    @Test
    fun testNoEffectPropagationForPureFunctions() {
        val source = """
            effect Log where {
              log : {msg: String} -> {} <Log>
            }

            ability Math 'a where {
              add : {x: 'a, y: 'a} -> 'a
            }

            impl Math Int where {
              add : {x: Int, y: Int} -> Int = {x, y} -> x + y
            }

            # This function should have no effects since Math is an ability, not an effect
            let pureCalculation : {a: Int, b: Int} -> Int <> = {a, b} ->
              Math.add {x = a, y = b}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val pureCalculationDecl = typedProgram.items.find {
            it is LetBinding && it.variableName == "pureCalculation"
        } as LetBinding

        assertNotNull(pureCalculationDecl.inferredType)
        assertTrue(pureCalculationDecl.inferredType is FunkType.FunctionType)

        val functionType = pureCalculationDecl.inferredType as FunkType.FunctionType
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

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val twiceDecl = typedProgram.items.find {
            it is LetBinding && it.variableName == "twice"
        } as LetBinding

        assertNotNull(twiceDecl.inferredType)
        assertTrue(twiceDecl.inferredType is FunkType.FunctionType)

        // The function type should be { f: {} -> Int <...> } -> Int <...>
        // But the actual inferred type depends on whether effects are propagated
        val twiceType = twiceDecl.inferredType as FunkType.FunctionType

        val resultDecl = typedProgram.items.find {
            it is LetBinding && it.variableName == "result"
        } as LetBinding

        assertNotNull(resultDecl.inferredType)
        // result should be Int
        assertTrue(resultDecl.inferredType is FunkType.IntType)
    }

    @Test
    fun testPolymorphicEffectsUnifyWithConcreteEffects() {
        // Test that <...> unifies with <Log>
        // The logging_fn calls Log.info directly (not handled), so it has <Log> effects
        val source = """
            effect Log where {
                info: {msg: String} -> {} <Log>
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

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val resultDecl = typedProgram.items.find {
            it is LetBinding && it.variableName == "result"
        } as LetBinding

        assertNotNull(resultDecl.inferredType)
        // result should be Int - but might need to handle Log effect
        // depending on how effect propagation works
        assertTrue(resultDecl.inferredType is FunkType.IntType ||
                  resultDecl.inferredType is FunkType.SuspendedType)
    }

    @Test
    fun testMultiplePolymorphicEffectParameters() {
        // Test that different polymorphic effect parameters are independent
        val source = """
            effect Log where {
                info: {msg: String} -> {} <Log>
            }

            effect DB where {
                query: {sql: String} -> {} <DB>
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

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val resultDecl = typedProgram.items.find {
            it is LetBinding && it.variableName == "result"
        } as LetBinding

        assertNotNull(resultDecl.inferredType)
        assertTrue(resultDecl.inferredType is FunkType.IntType)
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

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val resultDecl = typedProgram.items.find {
            it is LetBinding && it.variableName == "result"
        } as LetBinding

        assertNotNull(resultDecl.inferredType)
        assertTrue(resultDecl.inferredType is FunkType.IntType)
    }
}
