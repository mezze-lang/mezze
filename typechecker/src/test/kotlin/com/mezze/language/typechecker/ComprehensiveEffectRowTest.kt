package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComprehensiveEffectRowTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `test single effect call puts effect on the enclosing function arrow`() {
        val source = """
            effect Log where {
              info : {msg: Str} -> {} <Log>
            }

            let program = {} -> Log.info {msg = "Hello"}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val inferredType = result.getVariableType("program")

        assertNotNull(inferredType)
        // NEW MODEL: effects live on function arrows. Wrapping the effect call in a
        // function makes the Log effect ride the arrow; the return value is bare {}.
        assertTrue(inferredType is MezzeType.FunctionType)
        val fnType = inferredType as MezzeType.FunctionType
        assertEquals(setOf("Log"), fnType.effects!!.effects)
        assertEquals(MezzeType.RecordType(emptyMap()), fnType.returnType) // {} return type
    }

    @Test
    fun `test do block with single effect propagates effect`() {
        val source = """
            effect Log where {
              info : {msg: Str} -> {} <Log>
            }

            let program = {} -> do
              Log.info {msg = "Hello"}
              42
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val inferredType = result.getVariableType("program")

        assertNotNull(inferredType)
        // NEW MODEL: the do block's Log effect propagates to the enclosing function
        // arrow; the return value is bare Int.
        assertTrue(inferredType is MezzeType.FunctionType)
        val fnType = inferredType as MezzeType.FunctionType
        assertEquals(setOf("Log"), fnType.effects!!.effects)
        assertEquals(MezzeType.IntType, fnType.returnType) // Int return type from 42
    }

    @Test
    fun `test do block with multiple different effects accumulates all effects`() {
        val source = """
            effect Log where {
              info : {msg: Str} -> {} <Log>
            }

            effect Console where {
              print : {msg: Str} -> {} <Console>
            }

            let program = {} -> do
              Log.info {msg = "Starting"}
              Console.print {msg = "Processing"}
              Log.info {msg = "Done"}
              "completed"
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val inferredType = result.getVariableType("program")

        assertNotNull(inferredType)
        // NEW MODEL: the do block's effects accumulate on the enclosing function arrow.
        assertTrue(inferredType is MezzeType.FunctionType)
        val fnType = inferredType as MezzeType.FunctionType
        assertEquals(setOf("Log", "Console"), fnType.effects!!.effects)
        assertEquals(MezzeType.StringType, fnType.returnType) // String return type from "completed"
    }

    @Test
    fun `test nested do blocks accumulate effects`() {
        val source = """
            effect Log where {
              info : {msg: Str} -> {} <Log>
            }

            effect Error where {
              throw : {msg: Str} -> 'a <Error>
            }

            let nestedProgram = {} -> do
              let innerResult = do
                Log.info {msg = "Inner"}
                "inner result"
              Error.throw {msg = "Something went wrong"}
              "outer result"
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Check nested program - should accumulate both Log and Error effects
        val nestedType = result.getVariableType("nestedProgram")
        assertNotNull(nestedType)
        // NEW MODEL: nested do blocks' effects accumulate on the enclosing function arrow.
        assertTrue(nestedType is MezzeType.FunctionType)
        val fnType = nestedType as MezzeType.FunctionType
        // The arrow should accumulate effects from both the inner do block (Log) and the outer effect call (Error)
        assertEquals(setOf("Log", "Error"), fnType.effects!!.effects)
    }

    @Test
    fun `test perform expression with all effects handled returns result type`() {
        // Wrap in function to avoid top-level <*> restriction
        val source = """
            effect Log where {
              info : {msg: Str} -> {} <Log>
            }

            impl Log for Logger where {
              info : {msg: Str} -> {} = {msg} -> {}
            }

            let program = {} -> do
              Log.info {msg = "Hello"}
              42

            let getResult = {} -> perform Logger in (program {})
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // NEW MODEL: program is a function whose arrow carries the Log effect.
        val programType = result.getVariableType("program")
        assertTrue(programType is MezzeType.FunctionType)
        val programFn = programType as MezzeType.FunctionType
        assertEquals(setOf("Log"), programFn.effects!!.effects)

        // Check getResult - after perform, all effects are handled
        // The function returns Int with <*> marker
        val getResultType = result.getVariableType("getResult")
        assertTrue(getResultType is MezzeType.FunctionType)
        val fnType = getResultType as MezzeType.FunctionType
        assertEquals(MezzeType.IntType, fnType.returnType)
        assertNotNull(fnType.effects)
        assertTrue(fnType.effects!!.performed)
        assertTrue(fnType.effects!!.effects.isEmpty())
    }

    @Test
    fun `test perform expression with partial effect handling`() {
        val source = """
            effect Log where {
              info : {msg: Str} -> {} <Log>
            }

            effect Console where {
              print : {msg: Str} -> {} <Console>
            }

            impl Log for Logger where {
              info : {msg: Str} -> {} = {msg} -> {}
            }

            # Define program as a function
            let program = {} -> do
              Log.info {msg = "Hello"}
              Console.print {msg = "World"}
              42

            # Partially handle Log on the FUNCTION (not calling it)
            # Result is a function with Console remaining + <*> marker
            let partialResult = perform Logger in program
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Check program has both effects
        val programType = result.getVariableType("program")
        assertTrue(programType is MezzeType.FunctionType)
        val programFnType = programType as MezzeType.FunctionType
        assertEquals(setOf("Log", "Console"), programFnType.effects!!.effects)

        // Check partialResult - Logger handles Log, Console remains, <*> marker
        val partialType = result.getVariableType("partialResult")
        assertTrue(partialType is MezzeType.FunctionType)
        val partialFnType = partialType as MezzeType.FunctionType
        assertEquals(setOf("Console"), partialFnType.effects!!.effects)
        assertTrue(partialFnType.effects!!.performed)  // <*> marker
        assertEquals(MezzeType.IntType, partialFnType.returnType)
    }

    @Test
    fun `test sequential effect handling`() {
        val source = """
            effect Log where {
              info : {msg: Str} -> {} <Log>
            }

            effect Console where {
              print : {msg: Str} -> {} <Console>
            }

            impl Log for Logger where {
              info : {msg: Str} -> {} = {msg} -> {}
            }

            impl Console for Terminal where {
              print : {msg: Str} -> {} = {msg} -> {}
            }

            # Define program as a function
            let program = {} -> do
              Log.info {msg = "Hello"}
              Console.print {msg = "World"}
              42

            # Handle effects sequentially on the FUNCTION (not calling it)
            let step1 = perform Logger in program
            let step2 = perform Terminal in step1
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Check program has both effects
        val programType = result.getVariableType("program")
        assertTrue(programType is MezzeType.FunctionType)
        val programFnType = programType as MezzeType.FunctionType
        assertEquals(setOf("Log", "Console"), programFnType.effects!!.effects)

        // Check step1 - Logger handles Log, Console remains
        val step1Type = result.getVariableType("step1")
        assertTrue(step1Type is MezzeType.FunctionType)
        val step1FnType = step1Type as MezzeType.FunctionType
        assertEquals(setOf("Console"), step1FnType.effects!!.effects)
        assertTrue(step1FnType.effects!!.performed)

        // Check step2 - Terminal handles Console, all effects handled
        val step2Type = result.getVariableType("step2")
        assertTrue(step2Type is MezzeType.FunctionType)
        val step2FnType = step2Type as MezzeType.FunctionType
        assertTrue(step2FnType.effects!!.effects.isEmpty())
        assertTrue(step2FnType.effects!!.performed)
    }

    @Test
    fun `test multiple performers for same effect`() {
        // Wrap in functions to avoid top-level <*> restriction
        val source = """
            effect Log where {
              info : {msg: Str} -> {} <Log>
            }

            impl Log for BasicLogger where {
              info : {msg: Str} -> {} = {msg} -> {}
            }

            impl Log for FileLogger where {
              info : {msg: Str} -> {} = {msg} -> {}
            }

            let program = {} -> Log.info {msg = "Hello"}

            let getResult1 = {} -> perform BasicLogger in (program {})
            let getResult2 = {} -> perform FileLogger in (program {})
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // NEW MODEL: program is a function whose arrow carries the Log effect.
        val programType = result.getVariableType("program")
        assertTrue(programType is MezzeType.FunctionType)
        val programFn = programType as MezzeType.FunctionType
        assertEquals(setOf("Log"), programFn.effects!!.effects)

        // Both results should be functions returning {} with <*> marker
        // getResult1 should be {} -> {} <*>
        val getResult1Type = result.getVariableType("getResult1") as MezzeType.FunctionType
        assertEquals(MezzeType.RecordType(emptyMap()), getResult1Type.returnType)
        assertNotNull(getResult1Type.effects)
        assertTrue(getResult1Type.effects!!.performed)

        // getResult2 should be {} -> {} <*>
        val getResult2Type = result.getVariableType("getResult2") as MezzeType.FunctionType
        assertEquals(MezzeType.RecordType(emptyMap()), getResult2Type.returnType)
        assertNotNull(getResult2Type.effects)
        assertTrue(getResult2Type.effects!!.performed)
    }

    @Test
    fun `test complex effect composition with let bindings`() {
        val source = """
            effect Database where {
              query : {sql: Str} -> Str <Database>
            }

            effect Log where {
              info : {msg: Str} -> {} <Log>
            }

            effect Cache where {
              get : {key: Str} -> Str <Cache>
            }

            let fetchUser = {id: Str} -> do
              Log.info {msg = "Fetching user $(id)"}
              Cache.get {key = "user:$(id)"}
              Database.query {sql = "SELECT * FROM users WHERE id = $(id)"}
              "user data"

            let processUsers = {} -> do
              Log.info {msg = "Processing users"}
              Cache.get {key = "config"}
              "processing complete"
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Check fetchUser function has all three effects
        val fetchUserType = result.getVariableType("fetchUser")
        assertNotNull(fetchUserType)
        assertTrue(fetchUserType is MezzeType.FunctionType)
        val fetchUserFnType = fetchUserType as MezzeType.FunctionType
        // fetchUser should be: {id: Str} -> Str <Database, Log, Cache>
        assertEquals(MezzeType.StringType, fetchUserFnType.returnType)
        assertNotNull(fetchUserFnType.effects)
        assertEquals(setOf("Database", "Log", "Cache"), fetchUserFnType.effects!!.effects)

        // Check processUsers has Log and Cache effects
        val processUsersType = result.getVariableType("processUsers")
        assertNotNull(processUsersType)
        assertTrue(processUsersType is MezzeType.FunctionType)
        val processUsersFnType = processUsersType as MezzeType.FunctionType
        assertEquals(setOf("Log", "Cache"), processUsersFnType.effects!!.effects)
    }

    @Test
    fun `test pure do block has no effects`() {
        val source = """
            let pureComputation = do
              let x = 5
              let y = 10
              x + y
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val inferredType = result.getVariableType("pureComputation")

        // Should be just Int, not a function type with effects
        assertEquals(MezzeType.IntType, inferredType)
    }

    @Test
    fun `test mixed pure and effectful statements`() {
        val source = """
            effect Log where {
              info : {msg: Str} -> {} <Log>
            }

            let mixedProgram = {} -> do
              let x = 5
              Log.info {msg = "x is 5"}
              let y = x * 2
              y
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val inferredType = result.getVariableType("mixedProgram")

        // NEW MODEL: the mixed do block's Log effect rides the enclosing function arrow;
        // the return value is bare Int.
        assertTrue(inferredType is MezzeType.FunctionType)
        val fnType = inferredType as MezzeType.FunctionType
        assertEquals(setOf("Log"), fnType.effects!!.effects)
        assertEquals(MezzeType.IntType, fnType.returnType)
    }
}
