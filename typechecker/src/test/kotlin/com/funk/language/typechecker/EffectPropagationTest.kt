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
            effect Log {
              log : {msg: String} -> {}
            }

            effect Database {
              query : {sql: String} -> String <Log>
            }

            impl Log for Console where {
              log : {msg: String} -> {} = {msg} -> {}
            }

            impl Database for Postgres where {
              query : {sql: String} -> String = {sql} -> do
                Log.log {msg = "Executing: $(sql)"}
                "result"
            }

            # This function should have type: {query: String} -> String <Database, Log>
            let useDatabase : {query: String} -> String <Database, Log> = {query} ->
              Database.query {sql = query}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        // The useDatabase function should be the last item (index 4: Log, Database, Console impl, Postgres impl, useDatabase)
        val useDatabaseDecl = typedProgram.items[4] as LetBinding

        assertNotNull(useDatabaseDecl.inferredType)
        assertTrue(useDatabaseDecl.inferredType is FunkType.FunctionType)

        val functionType = useDatabaseDecl.inferredType as FunkType.FunctionType
        assertNotNull(functionType.effects)

        // Should have both Database and Log effects
        val effects = functionType.effects!!.effects
        assertTrue(effects.contains("Database"), "Should contain Database effect")
        assertTrue(effects.contains("Log"), "Should contain Log effect due to propagation")
    }

    @Test
    fun testNestedEffectPropagation() {
        val source = """
            effect Log {
              log : {msg: String} -> {}
            }

            effect Auth {
              checkPermission : {resource: String} -> Boolean <Log>
            }

            effect Database {
              query : {sql: String} -> String <Auth, Log>
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

            # This should propagate all three effects: Database, Auth, Log
            let secureQuery : {sql: String} -> String <Database, Auth, Log> = {sql} ->
              Database.query {sql = sql}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        val secureQueryDecl = typedProgram.items.find {
            it is LetBinding && it.variableName == "secureQuery"
        } as LetBinding

        assertNotNull(secureQueryDecl.inferredType)
        assertTrue(secureQueryDecl.inferredType is FunkType.FunctionType)

        val functionType = secureQueryDecl.inferredType as FunkType.FunctionType
        assertNotNull(functionType.effects)

        val effects = functionType.effects!!.effects
        assertTrue(effects.contains("Database"), "Should contain Database effect")
        assertTrue(effects.contains("Auth"), "Should contain Auth effect")
        assertTrue(effects.contains("Log"), "Should contain Log effect")
        assertEquals(3, effects.size, "Should have exactly 3 effects")
    }

    @Test
    fun testEffectPropagationInDoExpressions() {
        val source = """
            effect Log {
              log : {msg: String} -> {}
            }

            effect Random {
              nextInt : {max: Int} -> Int <Log>
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
        val typedProgram = typeChecker.typeCheck(program)

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
            effect Log {
              log : {msg: String} -> {}
            }

            effect Error {
              throw : {msg: String} -> {}
            }

            effect FileSystem {
              readFile : {path: String} -> String <Log, Error>,
              writeFile : {path: String, content: String} -> {} <Log>
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
            let copyFile : {from: String, to: String} -> {} <FileSystem, Log, Error> = {from, to} -> do
              let content = FileSystem.readFile {path = from}  # <Log, Error>
              FileSystem.writeFile {path = to, content = content}  # <Log>
              {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        val copyFileDecl = typedProgram.items.find {
            it is LetBinding && it.variableName == "copyFile"
        } as LetBinding

        assertNotNull(copyFileDecl.inferredType)
        assertTrue(copyFileDecl.inferredType is FunkType.FunctionType)

        val functionType = copyFileDecl.inferredType as FunkType.FunctionType
        assertNotNull(functionType.effects)

        val effects = functionType.effects!!.effects
        assertTrue(effects.contains("FileSystem"), "Should contain FileSystem effect")
        assertTrue(effects.contains("Log"), "Should contain Log effect")
        assertTrue(effects.contains("Error"), "Should contain Error effect")
    }

    @Test
    fun testEffectPropagationWithHandlers() {
        val source = """
            effect Log {
              log : {msg: String} -> {}
            }

            effect Database {
              query : {sql: String} -> String <Log>
            }

            impl Log for Console where {
              log : {msg: String} -> {} = {msg} -> {}
            }

            impl Database for TestDB where {
              query : {sql: String} -> String = {sql} -> do
                Log.log {msg = "Test query: $(sql)"}
                "test_result"
            }

            # Function that calls Database.query which declares <Log> effect
            let queryWithLog : {sql: String} -> String <Database, Log> = {sql} ->
              Database.query {sql = sql}

            # Function that handles Database effect, but Log effect should remain
            let partiallyHandledQuery : {sql: String} -> String <Log> = {sql} ->
              perform TestDB in queryWithLog {sql = sql}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        val partiallyHandledDecl = typedProgram.items.find {
            it is LetBinding && it.variableName == "partiallyHandledQuery"
        } as LetBinding

        assertNotNull(partiallyHandledDecl.inferredType)
        assertTrue(partiallyHandledDecl.inferredType is FunkType.FunctionType)

        val functionType = partiallyHandledDecl.inferredType as FunkType.FunctionType

        // After handling Database with TestDB, Database effect should be removed
        // but Log effect should remain because Database.query declares <Log> in its signature
        val effects = functionType.effects?.effects ?: emptySet()
        assertFalse(effects.contains("Database"), "Database effect should be handled")
        assertTrue(effects.contains("Log"), "Log effect should still be present from effect signature")
    }

    @Test
    fun testNoEffectPropagationForPureFunctions() {
        val source = """
            effect Log {
              log : {msg: String} -> {}
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
        val typedProgram = typeChecker.typeCheck(program)

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
}
