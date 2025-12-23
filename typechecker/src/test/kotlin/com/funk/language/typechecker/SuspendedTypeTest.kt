package com.funk.language.typechecker

import com.funk.language.ast.FunkType
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for SuspendedType - values with unhandled effects
 *
 * SuspendedType represents a computed value that has unhandled effects,
 * distinct from FunctionType which represents a function that needs to be called.
 */
class SuspendedTypeTest {
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        TypeChecker.clearCache()
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `perform with partial effect handling creates SuspendedType`() {
        val source = """
            effect Log where {
                info: {msg: String} -> {} <Log>
            }

            effect FileSystem where {
                write: {path: String, content: String} -> {} <FileSystem>
            }

            impl Log for Console where {
                info = {msg} -> {}
            }

            impl FileSystem for LocalDisk where {
                write = {path, content} -> {}
            }

            let demo = {} -> do
                Log.info {msg = "test"}
                FileSystem.write {path = "/tmp/test.txt", content = "hello"}

            # Handle only Log - FileSystem remains unhandled
            let result = perform Console in demo {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val resultBinding = typedProgram.items.last() as com.funk.language.ast.LetBinding
        val resultType = resultBinding.inferredType

        // Should be a SuspendedType with FileSystem effect
        assertTrue(resultType is FunkType.SuspendedType, "Expected SuspendedType but got ${resultType!!::class.simpleName}")
        val suspendedType = resultType as FunkType.SuspendedType

        // Value type should be {}
        assertTrue(suspendedType.valueType is FunkType.RecordType)
        assertEquals(emptyMap(), (suspendedType.valueType as FunkType.RecordType).fields)

        // Should have FileSystem effect
        assertEquals(setOf("FileSystem"), suspendedType.effects.effects)
    }

    @Test
    fun `perform with all effects handled returns plain value`() {
        val source = """
            effect Log where {
                info: {msg: String} -> {} <Log>
            }

            impl Log for Console where {
                info = {msg} -> {}
            }

            let demo = {} -> do
                Log.info {msg = "test"}
                42

            # Handle all effects - should get plain Int
            let result = perform Console in demo {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val resultBinding = typedProgram.items.last() as com.funk.language.ast.LetBinding
        val resultType = resultBinding.inferredType

        // Should be plain IntType, not SuspendedType
        assertTrue(resultType is FunkType.IntType, "Expected IntType but got ${resultType!!::class.simpleName}")
    }

    @Test
    fun `perform on SuspendedType handles remaining effects`() {
        val source = """
            effect Log where {
                info: {msg: String} -> {} <Log>
            }

            effect FileSystem where {
                write: {path: String, content: String} -> {} <FileSystem>
            }

            impl Log for Console where {
                info = {msg} -> {}
            }

            impl FileSystem for LocalDisk where {
                write = {path, content} -> {}
            }

            let demo = {} -> do
                Log.info {msg = "test"}
                FileSystem.write {path = "/tmp/test.txt", content = "hello"}
                42

            # Handle Log first
            let step1 = perform Console in demo {}

            # Then handle FileSystem
            let step2 = perform LocalDisk in step1
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // step1 should be SuspendedType with FileSystem
        val step1Binding = typedProgram.items[typedProgram.items.size - 2] as com.funk.language.ast.LetBinding
        val step1Type = step1Binding.inferredType
        assertTrue(step1Type is FunkType.SuspendedType)
        assertEquals(setOf("FileSystem"), (step1Type as FunkType.SuspendedType).effects.effects)

        // step2 should be plain Int (all effects handled)
        val step2Binding = typedProgram.items.last() as com.funk.language.ast.LetBinding
        val step2Type = step2Binding.inferredType
        assertTrue(step2Type is FunkType.IntType)
    }

    @Test
    fun `perform on function reference removes effects from function type`() {
        val source = """
            effect Log where {
                info: {msg: String} -> {} <Log>
            }

            impl Log for Console where {
                info = {msg} -> {}
            }

            let demo = {} -> do
                Log.info {msg = "test"}
                42

            # This handles effects IN the function, not calling it
            # Result should be a pure function {} -> Int
            let result = perform Console in demo
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val resultBinding = typedProgram.items.last() as com.funk.language.ast.LetBinding
        val resultType = resultBinding.inferredType

        // Should be FunctionType with no effects
        assertTrue(resultType is FunkType.FunctionType)
        val functionType = resultType as FunkType.FunctionType
        assertTrue(functionType.effects == null || functionType.effects!!.effects.isEmpty())
    }

    @Test
    fun `SuspendedType vs FunctionType distinction`() {
        val source = """
            effect Log where {
                info: {msg: String} -> {} <Log>
            }

            effect FileSystem where {
                write: {path: String, content: String} -> {} <FileSystem>
            }

            impl Log for Console where {
                info = {msg} -> {}
            }

            let myFunction = {} -> do
                Log.info {msg = "test"}
                FileSystem.write {path = "/tmp/test.txt", content = "hello"}

            # Function reference with effects - FunctionType
            let funcRef = perform Console in myFunction

            # Function call with unhandled effects - SuspendedType
            let suspended = perform Console in myFunction {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // funcRef should be FunctionType with FileSystem effect
        val funcRefBinding = typedProgram.items[typedProgram.items.size - 2] as com.funk.language.ast.LetBinding
        val funcRefType = funcRefBinding.inferredType
        assertTrue(funcRefType is FunkType.FunctionType)
        assertNotNull((funcRefType as FunkType.FunctionType).effects)
        assertEquals(setOf("FileSystem"), funcRefType.effects!!.effects)

        // suspended should be SuspendedType with FileSystem effect
        val suspendedBinding = typedProgram.items.last() as com.funk.language.ast.LetBinding
        val suspendedType = suspendedBinding.inferredType
        assertTrue(suspendedType is FunkType.SuspendedType)
        assertEquals(setOf("FileSystem"), (suspendedType as FunkType.SuspendedType).effects.effects)
    }

    @Test
    fun `multiple handlers can be applied to SuspendedType`() {
        val source = """
            effect A where { op: {} -> {} <A> }
            effect B where { op: {} -> {} <B> }
            effect C where { op: {} -> {} <C> }

            impl A for HandlerA where { op = {} -> {} }
            impl B for HandlerB where { op = {} -> {} }
            impl C for HandlerC where { op = {} -> {} }

            let demo = {} -> do
                A.op {}
                B.op {}
                C.op {}
                "result"

            # Handle effects one by one
            let step1 = perform HandlerA in demo {}
            let step2 = perform HandlerB in step1
            let step3 = perform HandlerC in step2
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // step1: B, C remain
        val step1Type = (typedProgram.items[typedProgram.items.size - 3] as com.funk.language.ast.LetBinding).inferredType
        assertTrue(step1Type is FunkType.SuspendedType)
        assertEquals(setOf("B", "C"), (step1Type as FunkType.SuspendedType).effects.effects)

        // step2: C remains
        val step2Type = (typedProgram.items[typedProgram.items.size - 2] as com.funk.language.ast.LetBinding).inferredType
        assertTrue(step2Type is FunkType.SuspendedType)
        assertEquals(setOf("C"), (step2Type as FunkType.SuspendedType).effects.effects)

        // step3: all handled
        val step3Type = (typedProgram.items.last() as com.funk.language.ast.LetBinding).inferredType
        assertTrue(step3Type is FunkType.StringType)
    }
}
