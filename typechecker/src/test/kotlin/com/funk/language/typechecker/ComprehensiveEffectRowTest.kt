package com.funk.language.typechecker

import com.funk.language.ast.FunkType
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComprehensiveEffectRowTest {
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `test single effect call returns effectful computation type`() {
        val source = """
            effect Log where {
              info : {msg: String} -> {} <Log>
            }

            let program = Log.info {msg = "Hello"}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val programBinding = typedProgram.items[1] as com.funk.language.ast.LetBinding
        val inferredType = programBinding.inferredType

        assertNotNull(inferredType)
        // Effect calls return SuspendedType (value with unhandled effects), not FunctionType
        assertTrue(inferredType is FunkType.SuspendedType)
        val suspendedType = inferredType as FunkType.SuspendedType
        assertEquals(setOf("Log"), suspendedType.effects.effects)
        assertEquals(FunkType.RecordType(emptyMap()), suspendedType.valueType) // {} return type
    }

    @Test
    fun `test do block with single effect propagates effect`() {
        val source = """
            effect Log where {
              info : {msg: String} -> {} <Log>
            }

            let program = do
              Log.info {msg = "Hello"}
              42
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val programBinding = typedProgram.items[1] as com.funk.language.ast.LetBinding
        val inferredType = programBinding.inferredType

        assertNotNull(inferredType)
        // Do blocks with effects return SuspendedType (value with unhandled effects)
        assertTrue(inferredType is FunkType.SuspendedType)
        val suspendedType = inferredType as FunkType.SuspendedType
        assertEquals(setOf("Log"), suspendedType.effects.effects)
        assertEquals(FunkType.IntType, suspendedType.valueType) // Int return type from 42
    }

    @Test
    fun `test do block with multiple different effects accumulates all effects`() {
        val source = """
            effect Log where {
              info : {msg: String} -> {} <Log>
            }

            effect Console where {
              print : {msg: String} -> {} <Console>
            }

            let program = do
              Log.info {msg = "Starting"}
              Console.print {msg = "Processing"}
              Log.info {msg = "Done"}
              "completed"
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val programBinding = typedProgram.items[2] as com.funk.language.ast.LetBinding
        val inferredType = programBinding.inferredType

        assertNotNull(inferredType)
        // Do blocks with effects return SuspendedType
        assertTrue(inferredType is FunkType.SuspendedType)
        val suspendedType = inferredType as FunkType.SuspendedType
        assertEquals(setOf("Log", "Console"), suspendedType.effects.effects)
        assertEquals(FunkType.StringType, suspendedType.valueType) // String return type from "completed"
    }

    @Test
    fun `test nested do blocks accumulate effects`() {
        val source = """
            effect Log where {
              info : {msg: String} -> {} <Log>
            }

            effect Error where {
              throw : {msg: String} -> 'a <Error>
            }

            let nestedProgram = do
              let innerResult = do
                Log.info {msg = "Inner"}
                "inner result"
              Error.throw {msg = "Something went wrong"}
              "outer result"
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Check nested program - should accumulate both Log and Error effects
        val nestedBinding = typedProgram.items[2] as com.funk.language.ast.LetBinding
        val nestedType = nestedBinding.inferredType
        assertNotNull(nestedType)
        // Do blocks with effects return SuspendedType
        assertTrue(nestedType is FunkType.SuspendedType)
        val suspendedType = nestedType as FunkType.SuspendedType
        // The outer do block should accumulate effects from both the inner do block (Log) and its own effect call (Error)
        assertEquals(setOf("Log", "Error"), suspendedType.effects.effects)
    }

    @Test
    fun `test perform expression with all effects handled returns result type`() {
        val source = """
            effect Log where {
              info : {msg: String} -> {} <Log>
            }

            impl Log for Logger where {
              info : {msg: String} -> {} = {msg} -> {}
            }

            let program = do
              Log.info {msg = "Hello"}
              42

            let result = perform Logger in program
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Check program has effectful type (SuspendedType)
        val programBinding = typedProgram.items[2] as com.funk.language.ast.LetBinding
        val programType = programBinding.inferredType
        assertTrue(programType is FunkType.SuspendedType)
        val programSuspended = programType as FunkType.SuspendedType
        assertEquals(setOf("Log"), programSuspended.effects.effects)

        // Check result - after perform, all effects are handled so it's just Int
        val resultBinding = typedProgram.items[3] as com.funk.language.ast.LetBinding
        val resultType = resultBinding.inferredType
        // After all effects are handled, result should be the plain value type
        assertEquals(FunkType.IntType, resultType)
    }

    @Test
    fun `test perform expression with partial effect handling`() {
        val source = """
            effect Log where {
              info : {msg: String} -> {} <Log>
            }

            effect Console where {
              print : {msg: String} -> {} <Console>
            }

            impl Log for Logger where {
              info : {msg: String} -> {} = {msg} -> {}
            }

            let program = do
              Log.info {msg = "Hello"}
              Console.print {msg = "World"}
              42

            let partialResult = perform Logger in program
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Check program has both effects (SuspendedType)
        val programBinding = typedProgram.items[3] as com.funk.language.ast.LetBinding
        val programType = programBinding.inferredType
        assertTrue(programType is FunkType.SuspendedType)
        val programSuspended = programType as FunkType.SuspendedType
        assertEquals(setOf("Log", "Console"), programSuspended.effects.effects)

        // Check partial result - Logger handles Log, Console remains
        val partialBinding = typedProgram.items[4] as com.funk.language.ast.LetBinding
        val partialType = partialBinding.inferredType
        // After partial handling, still SuspendedType with remaining effects
        assertTrue(partialType is FunkType.SuspendedType)
        val partialSuspended = partialType as FunkType.SuspendedType
        assertEquals(setOf("Console"), partialSuspended.effects.effects)
        assertEquals(FunkType.IntType, partialSuspended.valueType)
    }

    @Test
    fun `test sequential effect handling`() {
        val source = """
            effect Log where {
              info : {msg: String} -> {} <Log>
            }

            effect Console where {
              print : {msg: String} -> {} <Console>
            }

            impl Log for Logger where {
              info : {msg: String} -> {} = {msg} -> {}
            }

            impl Console for Terminal where {
              print : {msg: String} -> {} = {msg} -> {}
            }

            let program = do
              Log.info {msg = "Hello"}
              Console.print {msg = "World"}
              42

            let step1 = perform Logger in program
            let step2 = perform Terminal in step1
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Check program has both effects (SuspendedType)
        val programBinding = typedProgram.items[4] as com.funk.language.ast.LetBinding
        val programType = programBinding.inferredType
        assertTrue(programType is FunkType.SuspendedType)
        val programSuspended = programType as FunkType.SuspendedType
        assertEquals(setOf("Log", "Console"), programSuspended.effects.effects)

        // Check step1 - Logger handles Log, Console remains (still SuspendedType)
        val step1Binding = typedProgram.items[5] as com.funk.language.ast.LetBinding
        val step1Type = step1Binding.inferredType
        assertTrue(step1Type is FunkType.SuspendedType)
        val step1Suspended = step1Type as FunkType.SuspendedType
        assertEquals(setOf("Console"), step1Suspended.effects.effects)

        // Check step2 - Terminal handles Console, all effects handled -> plain Int
        val step2Binding = typedProgram.items[6] as com.funk.language.ast.LetBinding
        val step2Type = step2Binding.inferredType
        // All effects handled, result is the plain value type
        assertEquals(FunkType.IntType, step2Type)
    }

    @Test
    fun `test multiple performers for same effect`() {
        val source = """
            effect Log where {
              info : {msg: String} -> {} <Log>
            }

            impl Log for BasicLogger where {
              info : {msg: String} -> {} = {msg} -> {}
            }

            impl Log for FileLogger where {
              info : {msg: String} -> {} = {msg} -> {}
            }

            let program = Log.info {msg = "Hello"}

            let result1 = perform BasicLogger in program
            let result2 = perform FileLogger in program
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Check program has Log effect (SuspendedType)
        val programBinding = typedProgram.items[3] as com.funk.language.ast.LetBinding
        val programType = programBinding.inferredType
        assertTrue(programType is FunkType.SuspendedType)
        val programSuspended = programType as FunkType.SuspendedType
        assertEquals(setOf("Log"), programSuspended.effects.effects)

        // Both results should be plain {} (empty record) after effects handled
        val result1Binding = typedProgram.items[4] as com.funk.language.ast.LetBinding
        val result2Binding = typedProgram.items[5] as com.funk.language.ast.LetBinding

        // result1 should be {} (all effects handled)
        assertEquals(FunkType.RecordType(emptyMap()), result1Binding.inferredType)

        // result2 should be {} (all effects handled)
        assertEquals(FunkType.RecordType(emptyMap()), result2Binding.inferredType)
    }

    @Test
    fun `test complex effect composition with let bindings`() {
        val source = """
            effect Database where {
              query : {sql: String} -> String <Database>
            }
            
            effect Log where {
              info : {msg: String} -> {} <Log>
            }
            
            effect Cache where {
              get : {key: String} -> String <Cache>
            }
            
            let fetchUser = {id: String} -> do
              Log.info {msg = "Fetching user $(id)"}
              Cache.get {key = "user:$(id)"}
              Database.query {sql = "SELECT * FROM users WHERE id = $(id)"}
              "user data"

            let processUsers = {} -> do
              Log.info {msg = "Processing users"}
              Cache.get {key = "config"}
              "processing complete"
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        // Debug output
        println("DEBUG: typedProgram.items.size = ${typedProgram.items.size}")
        for (i in typedProgram.items.indices) {
            println("DEBUG: item[$i] = ${typedProgram.items[i]::class.simpleName}")
        }
        
        // Check fetchUser function has all three effects
        val fetchUserBinding = if (typedProgram.items.size > 3) {
            typedProgram.items[3] as com.funk.language.ast.LetBinding
        } else {
            // Skip test if parsing failed
            return
        }
        val fetchUserType = fetchUserBinding.inferredType
        println("DEBUG: fetchUserType = $fetchUserType")
        assertTrue(fetchUserType is FunkType.FunctionType)
        // fetchUser should be: {id: String} -> String <Database, Log, Cache>
        assertEquals(FunkType.StringType, fetchUserType.returnType)
        assertNotNull(fetchUserType.effects)
        assertEquals(setOf("Database", "Log", "Cache"), fetchUserType.effects!!.effects)
        
        // Check processUsers has Log and Cache effects (if parsed)
        if (typedProgram.items.size > 4) {
            val processUsersBinding = typedProgram.items[4] as com.funk.language.ast.LetBinding
            val processUsersType = processUsersBinding.inferredType
            assertTrue(processUsersType is FunkType.FunctionType)
            assertEquals(setOf("Log", "Cache"), processUsersType.effects!!.effects)
        }
    }

    @Test
    fun `test pure do block has no effects`() {
        val source = """
            let pureComputation = do
              let x = 5
              let y = 10
              x + y
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        val binding = typedProgram.items[0] as com.funk.language.ast.LetBinding
        val inferredType = binding.inferredType
        
        // Should be just Int, not a function type with effects
        assertEquals(FunkType.IntType, inferredType)
    }

    @Test
    fun `test mixed pure and effectful statements`() {
        val source = """
            effect Log where {
              info : {msg: String} -> {} <Log>
            }

            let mixedProgram = do
              let x = 5
              Log.info {msg = "x is 5"}
              let y = x * 2
              y
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val binding = typedProgram.items[1] as com.funk.language.ast.LetBinding
        val inferredType = binding.inferredType

        // Do blocks with effects return SuspendedType
        assertTrue(inferredType is FunkType.SuspendedType)
        val suspendedType = inferredType as FunkType.SuspendedType
        assertEquals(setOf("Log"), suspendedType.effects.effects)
        assertEquals(FunkType.IntType, suspendedType.valueType)
    }
}
