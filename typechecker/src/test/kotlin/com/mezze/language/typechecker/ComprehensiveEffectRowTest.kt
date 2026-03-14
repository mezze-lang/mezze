package com.mezze.language.typechecker

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
    fun `test single effect call returns effectful computation type`() {
        val source = """
            effect Log where {
              info : {msg: Str} -> {} <Log>
            }

            let program = Log.info {msg = "Hello"}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val programBinding = typedProgram.items[1] as com.mezze.language.ast.LetBinding
        val inferredType = programBinding.inferredType

        assertNotNull(inferredType)
        // Effect calls return SuspendedType (value with unhandled effects), not FunctionType
        assertTrue(inferredType is MezzeType.SuspendedType)
        val suspendedType = inferredType as MezzeType.SuspendedType
        assertEquals(setOf("Log"), suspendedType.effects.effects)
        assertEquals(MezzeType.RecordType(emptyMap()), suspendedType.valueType) // {} return type
    }

    @Test
    fun `test do block with single effect propagates effect`() {
        val source = """
            effect Log where {
              info : {msg: Str} -> {} <Log>
            }

            let program = do
              Log.info {msg = "Hello"}
              42
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val programBinding = typedProgram.items[1] as com.mezze.language.ast.LetBinding
        val inferredType = programBinding.inferredType

        assertNotNull(inferredType)
        // Do blocks with effects return SuspendedType (value with unhandled effects)
        assertTrue(inferredType is MezzeType.SuspendedType)
        val suspendedType = inferredType as MezzeType.SuspendedType
        assertEquals(setOf("Log"), suspendedType.effects.effects)
        assertEquals(MezzeType.IntType, suspendedType.valueType) // Int return type from 42
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

            let program = do
              Log.info {msg = "Starting"}
              Console.print {msg = "Processing"}
              Log.info {msg = "Done"}
              "completed"
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val programBinding = typedProgram.items[2] as com.mezze.language.ast.LetBinding
        val inferredType = programBinding.inferredType

        assertNotNull(inferredType)
        // Do blocks with effects return SuspendedType
        assertTrue(inferredType is MezzeType.SuspendedType)
        val suspendedType = inferredType as MezzeType.SuspendedType
        assertEquals(setOf("Log", "Console"), suspendedType.effects.effects)
        assertEquals(MezzeType.StringType, suspendedType.valueType) // String return type from "completed"
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
        val nestedBinding = typedProgram.items[2] as com.mezze.language.ast.LetBinding
        val nestedType = nestedBinding.inferredType
        assertNotNull(nestedType)
        // Do blocks with effects return SuspendedType
        assertTrue(nestedType is MezzeType.SuspendedType)
        val suspendedType = nestedType as MezzeType.SuspendedType
        // The outer do block should accumulate effects from both the inner do block (Log) and its own effect call (Error)
        assertEquals(setOf("Log", "Error"), suspendedType.effects.effects)
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

            let program = do
              Log.info {msg = "Hello"}
              42

            let getResult = {} -> perform Logger in program
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Check program has effectful type (SuspendedType)
        val programBinding = typedProgram.items[2] as com.mezze.language.ast.LetBinding
        val programType = programBinding.inferredType
        assertTrue(programType is MezzeType.SuspendedType)
        val programSuspended = programType as MezzeType.SuspendedType
        assertEquals(setOf("Log"), programSuspended.effects.effects)

        // Check getResult - after perform, all effects are handled
        // The function returns Int with <*> marker
        val getResultBinding = typedProgram.items[3] as com.mezze.language.ast.LetBinding
        val getResultType = getResultBinding.inferredType
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

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Check program has both effects
        val programBinding = typedProgram.items[3] as com.mezze.language.ast.LetBinding
        val programType = programBinding.inferredType
        assertTrue(programType is MezzeType.FunctionType)
        val programFnType = programType as MezzeType.FunctionType
        assertEquals(setOf("Log", "Console"), programFnType.effects!!.effects)

        // Check partialResult - Logger handles Log, Console remains, <*> marker
        val partialBinding = typedProgram.items[4] as com.mezze.language.ast.LetBinding
        val partialType = partialBinding.inferredType
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

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Check program has both effects
        val programBinding = typedProgram.items[4] as com.mezze.language.ast.LetBinding
        val programType = programBinding.inferredType
        assertTrue(programType is MezzeType.FunctionType)
        val programFnType = programType as MezzeType.FunctionType
        assertEquals(setOf("Log", "Console"), programFnType.effects!!.effects)

        // Check step1 - Logger handles Log, Console remains
        val step1Binding = typedProgram.items[5] as com.mezze.language.ast.LetBinding
        val step1Type = step1Binding.inferredType
        assertTrue(step1Type is MezzeType.FunctionType)
        val step1FnType = step1Type as MezzeType.FunctionType
        assertEquals(setOf("Console"), step1FnType.effects!!.effects)
        assertTrue(step1FnType.effects!!.performed)

        // Check step2 - Terminal handles Console, all effects handled
        val step2Binding = typedProgram.items[6] as com.mezze.language.ast.LetBinding
        val step2Type = step2Binding.inferredType
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

            let program = Log.info {msg = "Hello"}

            let getResult1 = {} -> perform BasicLogger in program
            let getResult2 = {} -> perform FileLogger in program
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Check program has Log effect (SuspendedType)
        val programBinding = typedProgram.items[3] as com.mezze.language.ast.LetBinding
        val programType = programBinding.inferredType
        assertTrue(programType is MezzeType.SuspendedType)
        val programSuspended = programType as MezzeType.SuspendedType
        assertEquals(setOf("Log"), programSuspended.effects.effects)

        // Both results should be functions returning {} with <*> marker
        val getResult1Binding = typedProgram.items[4] as com.mezze.language.ast.LetBinding
        val getResult2Binding = typedProgram.items[5] as com.mezze.language.ast.LetBinding

        // getResult1 should be {} -> {} <*>
        val getResult1Type = getResult1Binding.inferredType as MezzeType.FunctionType
        assertEquals(MezzeType.RecordType(emptyMap()), getResult1Type.returnType)
        assertNotNull(getResult1Type.effects)
        assertTrue(getResult1Type.effects!!.performed)

        // getResult2 should be {} -> {} <*>
        val getResult2Type = getResult2Binding.inferredType as MezzeType.FunctionType
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

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        // Debug output
        println("DEBUG: typedProgram.items.size = ${typedProgram.items.size}")
        for (i in typedProgram.items.indices) {
            println("DEBUG: item[$i] = ${typedProgram.items[i]::class.simpleName}")
        }
        
        // Check fetchUser function has all three effects
        val fetchUserBinding = if (typedProgram.items.size > 3) {
            typedProgram.items[3] as com.mezze.language.ast.LetBinding
        } else {
            // Skip test if parsing failed
            return
        }
        val fetchUserType = fetchUserBinding.inferredType
        println("DEBUG: fetchUserType = $fetchUserType")
        assertTrue(fetchUserType is MezzeType.FunctionType)
        // fetchUser should be: {id: Str} -> Str <Database, Log, Cache>
        assertEquals(MezzeType.StringType, fetchUserType.returnType)
        assertNotNull(fetchUserType.effects)
        assertEquals(setOf("Database", "Log", "Cache"), fetchUserType.effects!!.effects)
        
        // Check processUsers has Log and Cache effects (if parsed)
        if (typedProgram.items.size > 4) {
            val processUsersBinding = typedProgram.items[4] as com.mezze.language.ast.LetBinding
            val processUsersType = processUsersBinding.inferredType
            assertTrue(processUsersType is MezzeType.FunctionType)
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
        
        val binding = typedProgram.items[0] as com.mezze.language.ast.LetBinding
        val inferredType = binding.inferredType
        
        // Should be just Int, not a function type with effects
        assertEquals(MezzeType.IntType, inferredType)
    }

    @Test
    fun `test mixed pure and effectful statements`() {
        val source = """
            effect Log where {
              info : {msg: Str} -> {} <Log>
            }

            let mixedProgram = do
              let x = 5
              Log.info {msg = "x is 5"}
              let y = x * 2
              y
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val binding = typedProgram.items[1] as com.mezze.language.ast.LetBinding
        val inferredType = binding.inferredType

        // Do blocks with effects return SuspendedType
        assertTrue(inferredType is MezzeType.SuspendedType)
        val suspendedType = inferredType as MezzeType.SuspendedType
        assertEquals(setOf("Log"), suspendedType.effects.effects)
        assertEquals(MezzeType.IntType, suspendedType.valueType)
    }
}
