package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the RESULT of an effectful call.
 *
 * Effects live on function arrows: calling an effectful function yields a BARE
 * value, and the effect rides the enclosing function's arrow (not the value).
 */
class EffectfulCallResultTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `perform with partial effect handling creates function with remaining effects`() {
        val source = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            effect FileSystem where {
                write: {path: Str, content: Str} -> {} <FileSystem>
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

            # Wrap in function to avoid top-level <*> restriction
            let testFn = {} -> do
                perform Console in demo {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val testFnType = result.getVariableType("testFn")

        // Should be a FunctionType with FileSystem effect and performed marker
        assertTrue(testFnType is MezzeType.FunctionType, "Expected FunctionType but got ${testFnType!!::class.simpleName}")
        val fnType = testFnType as MezzeType.FunctionType

        // Return type should be {}
        assertTrue(fnType.returnType is MezzeType.RecordType)
        assertEquals(emptyMap(), (fnType.returnType as MezzeType.RecordType).fields)

        // Should have FileSystem effect and performed marker
        assertNotNull(fnType.effects)
        assertEquals(setOf("FileSystem"), fnType.effects!!.effects)
        assertTrue(fnType.effects!!.performed)
    }

    @Test
    fun `perform with all effects handled returns function with performed marker`() {
        val source = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
                info = {msg} -> {}
            }

            let demo = {} -> do
                Log.info {msg = "test"}
                42

            # Handle all effects - wrapped in function to avoid top-level <*> restriction
            let getResult = {} -> perform Console in demo {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val getResultType = result.getVariableType("getResult")

        // Should be a FunctionType with performed marker (<*>)
        // Note: Lambda hoists the effect onto the function arrow
        assertTrue(getResultType is MezzeType.FunctionType, "Expected FunctionType but got ${getResultType!!::class.simpleName}")
        val fnType = getResultType as MezzeType.FunctionType

        // The function should have performed=true in its effects
        assertNotNull(fnType.effects)
        assertTrue(fnType.effects!!.performed, "Should have performed marker")
        assertTrue(fnType.effects!!.effects.isEmpty(), "Should have no concrete effects")

        // The return type should be Int (bare; the effect rode the arrow)
        assertTrue(fnType.returnType is MezzeType.IntType, "Expected IntType return but got ${fnType.returnType::class.simpleName}")
    }

    @Test
    fun `perform on function handles effects sequentially`() {
        val source = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            effect FileSystem where {
                write: {path: Str, content: Str} -> {} <FileSystem>
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

            # Handle effects on the FUNCTION (not calling it)
            let step1 = perform Console in demo
            let step2 = perform LocalDisk in step1
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // step1 should be a function with FileSystem remaining + <*>
        val step1Type = result.getVariableType("step1")
        assertTrue(step1Type is MezzeType.FunctionType)
        val step1FnType = step1Type as MezzeType.FunctionType
        assertEquals(setOf("FileSystem"), step1FnType.effects!!.effects)
        assertTrue(step1FnType.effects!!.performed)

        // step2 should be a function with all effects handled + <*>
        val step2Type = result.getVariableType("step2")
        assertTrue(step2Type is MezzeType.FunctionType)
        val step2FnType = step2Type as MezzeType.FunctionType
        assertTrue(step2FnType.effects!!.effects.isEmpty())
        assertTrue(step2FnType.effects!!.performed)
    }

    @Test
    fun `perform on function reference removes effects from function type`() {
        val source = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
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

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val resultType = result.getVariableType("result")

        // Should be FunctionType with no effects
        assertTrue(resultType is MezzeType.FunctionType)
        val functionType = resultType as MezzeType.FunctionType
        assertTrue(functionType.effects == null || functionType.effects!!.effects.isEmpty())
    }

    @Test
    fun `FunctionType with partial effect handling`() {
        val source = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            effect FileSystem where {
                write: {path: Str, content: Str} -> {} <FileSystem>
            }

            impl Log for Console where {
                info = {msg} -> {}
            }

            let myFunction = {} -> do
                Log.info {msg = "test"}
                FileSystem.write {path = "/tmp/test.txt", content = "hello"}

            # Function reference with effects - FunctionType (no <*> at top level)
            let funcRef = perform Console in myFunction
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // funcRef should be FunctionType with FileSystem effect and performed marker
        val funcRefType = result.getVariableType("funcRef")
        assertTrue(funcRefType is MezzeType.FunctionType)
        assertNotNull((funcRefType as MezzeType.FunctionType).effects)
        assertEquals(setOf("FileSystem"), funcRefType.effects!!.effects)
        assertTrue(funcRefType.effects!!.performed)  // <*> marker
    }

    @Test
    fun `multiple handlers can be applied sequentially to function`() {
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

            # Handle effects on the FUNCTION (not calling it)
            let step1 = perform HandlerA in demo
            let step2 = perform HandlerB in step1
            let step3 = perform HandlerC in step2
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // step1: B, C remain
        val step1Type = result.getVariableType("step1")
        assertTrue(step1Type is MezzeType.FunctionType)
        assertEquals(setOf("B", "C"), (step1Type as MezzeType.FunctionType).effects!!.effects)
        assertTrue(step1Type.effects!!.performed)

        // step2: C remains
        val step2Type = result.getVariableType("step2")
        assertTrue(step2Type is MezzeType.FunctionType)
        assertEquals(setOf("C"), (step2Type as MezzeType.FunctionType).effects!!.effects)
        assertTrue(step2Type.effects!!.performed)

        // step3: all handled
        val step3Type = result.getVariableType("step3")
        assertTrue(step3Type is MezzeType.FunctionType)
        assertTrue((step3Type as MezzeType.FunctionType).effects!!.effects.isEmpty())
        assertTrue(step3Type.effects!!.performed)
    }
}
