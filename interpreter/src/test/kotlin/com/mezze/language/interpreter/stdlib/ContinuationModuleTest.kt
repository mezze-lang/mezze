package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

/**
 * Tests for std/control/continuation module.
 * Tests delimited continuations with capture, suspend_with, resume, fork, and abort.
 */
class ContinuationModuleTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setup() {
        TestUtils.setupProjectRoot()
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
        return result
    }

    // === new Function Tests ===

    @Test
    fun `test continuation new creates record`() {
        val result = eval("""
            use std::control::continuation

            let cont = continuation::new {}
            42
        """)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    // === suspend_with Tests ===

    @Test
    fun `test suspend_with creates suspended state`() {
        val result = eval("""
            use std::control::continuation

            let test = {} -> do
                let cont = continuation::new {}
                cont.suspend_with { value = 100 }

            test {}
        """)
        assertTrue(result is MezzeContinuationSuspended)
        assertEquals(100L, ((result as MezzeContinuationSuspended).suspendedValue as MezzeInt).value)
    }

    @Test
    fun `test suspend_with with string value`() {
        val result = eval("""
            use std::control::continuation

            let test = {} -> do
                let cont = continuation::new {}
                cont.suspend_with { value = "hello" }

            test {}
        """)
        assertTrue(result is MezzeContinuationSuspended)
        assertEquals("hello", ((result as MezzeContinuationSuspended).suspendedValue as MezzeString).value)
    }

    // === capture and resume Tests ===

    @Test
    fun `test capture and resume basic`() {
        val result = eval("""
            use std::control::continuation

            let test = {} -> do
                let cont = continuation::new {}
                let k = cont.capture {}
                cont.resume { cont = k, value = 42 }

            test {}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    // === fork Tests ===

    @Test
    fun `test fork used in choice effect`() {
        // Fork is tested through the Choice effect which uses it internally
        val result = eval("""
            use std::effects::choice::{Choice, AllPaths}
            use std::control::continuation

            let test = {} -> perform AllPaths {cont = continuation::new {}} in do
                Choice.choose { options = [1, 2, 3] }

            test {}
        """)
        assertTrue(result is MezzeArrayList)
        val array = result as MezzeArrayList
        assertEquals(3, array.size())
    }

    // === abort Tests ===

    @Test
    fun `test abort terminates computation`() {
        var exceptionThrown = false
        try {
            eval("""
                use std::control::continuation

                let test = {} -> do
                    let cont = continuation::new {}
                    cont.abort {}
                    "should not reach here"

                test {}
            """)
        } catch (e: Exception) {
            exceptionThrown = true
        }
        assertTrue(exceptionThrown)
    }

    // === extract_suspended Tests ===

    @Test
    fun `test extract_suspended on suspended value`() {
        val result = eval("""
            use std::control::continuation

            let test = {} -> do
                let cont = continuation::new {}
                let suspended = cont.suspend_with { value = 42 }
                suspended

            let s = test {}
            let cont2 = continuation::new {}
            let extracted = cont2.extract_suspended { suspended = s }
            match extracted is
                Some { val } -> val.value
                None -> 0
        """)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    @Test
    fun `test extract_suspended on non-suspended value returns None`() {
        val result = eval("""
            use std::control::continuation

            let cont = continuation::new {}
            let extracted = cont.extract_suspended { suspended = {} }
            match extracted is
                Some { val } -> 1
                None -> 0
        """)
        assertTrue(result is MezzeInt)
        assertEquals(0L, (result as MezzeInt).value)
    }

    // === Integration with Effects ===

    @Test
    fun `test continuation used by early_return effect`() {
        val result = eval("""
            use std::effects::early_return::{Return, WithReturn}
            use std::control::continuation

            let check = {x} ->
                perform WithReturn { cont = continuation::new {} } in do
                    if x < 0 then
                        Return.return {val = "negative"}
                    else {}
                    "positive"

            check {x = -1}
        """)
        assertTrue(result is MezzeString)
        assertEquals("negative", (result as MezzeString).value)
    }

    @Test
    fun `test continuation used by exception effect`() {
        val result = eval("""
            use std::effects::exception::{Exception, ToResult}
            use std::control::continuation

            let divide = {a, b} ->
                if b == 0 then
                    Exception.throw {error = "div by zero"}
                else
                    a / b

            perform ToResult {cont = continuation::new {}} in divide {a = 10, b = 0}
        """)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructor)
    }

    @Test
    fun `test continuation used by choice effect`() {
        val result = eval("""
            use std::effects::choice::{Choice, AllPaths}
            use std::control::continuation

            let test = {} -> perform AllPaths {cont = continuation::new {}} in do
                Choice.choose { options = [1, 2] }

            test {}
        """)
        assertTrue(result is MezzeArrayList)
        assertEquals(2, (result as MezzeArrayList).size())
    }
}
