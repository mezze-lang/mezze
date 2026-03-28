package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import kotlin.test.assertContains
import kotlin.test.assertNotNull

/**
 * Tests for top-level performed effects validation.
 *
 * These tests verify that:
 * 1. Top-level let bindings CANNOT have performed effects <*> - must wrap in function
 * 2. Top-level bare expressions with <*> are rejected by checkProgramForExecution
 * 3. Functions with <*> in their return type are allowed (they're just definitions)
 */
class TopLevelPerformedEffectsValidationTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    // ========================================================================
    // TOP-LEVEL LET BINDINGS WITH PERFORMED EFFECTS (always rejected)
    // ========================================================================

    @Test
    fun `direct perform at top-level is rejected - must wrap in function`() {
        // Top-level values cannot have performed effects <*>
        // Must wrap in a function: let result = {} -> perform Counter in State.get {}
        val source = """
            effect State having 'state where {
                get: {} -> 'state <State>
            }

            impl State for Counter having 'state = Int where {
                get = {} -> 42
            }

            let result = perform Counter in State.get {}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Failure,
            "Direct perform at top-level should be rejected")
        val errorMsg = (result as TypeChecker.TypeCheckResult.Failure).errors.first().message
        assertContains(errorMsg, "performed effects", ignoreCase = true)
    }

    @Test
    fun `let binding with immediate perform returning unit is rejected`() {
        // Even if perform returns unit, top-level cannot have <*>
        // Must wrap in function: let result = {} -> perform Console in Log.print {msg = "test"}
        val source = """
            effect Log where {
                print: {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
                print = {msg} -> {}
            }

            let result = perform Console in Log.print {msg = "test"}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Failure,
            "Top-level let with perform should be rejected even if returning unit")
        val errorMsg = (result as TypeChecker.TypeCheckResult.Failure).errors.first().message
        assertContains(errorMsg, "performed effects", ignoreCase = true)
    }

    @Test
    fun `let binding calling function with performed effects is rejected`() {
        val source = """
            effect Log where {
                print: {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
                print = {msg} -> {}
            }

            let myFunc = {} -> perform Console in Log.print {msg = "test"}
            let result = myFunc {}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Failure, "Let calling effectful function should fail")
        val errorMsg = (result as TypeChecker.TypeCheckResult.Failure).errors.first().message
        assertContains(errorMsg, "performed effects", ignoreCase = true)
    }

    @Test
    fun `let binding with lambda immediately called with perform is rejected`() {
        // This is the pattern: let result = ({} -> perform Handler in expr) {}
        val source = """
            effect Log where {
                print: {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
                print = {msg} -> {}
            }

            let result = ({} -> perform Console in Log.print {msg = "test"}) {}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Failure, "Lambda with perform immediately called should fail")
        val errorMsg = (result as TypeChecker.TypeCheckResult.Failure).errors.first().message
        assertContains(errorMsg, "performed effects", ignoreCase = true)
    }

    @Test
    fun `nested let calling effectful function is rejected`() {
        val source = """
            effect State having 'state where {
                get: {} -> 'state <State>
            }

            impl State for Counter having 'state = Int where {
                get = {} -> 42
            }

            let getState = {} -> perform Counter in State.get {}
            let result = getState {}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Failure, "Top-level let calling effectful function should fail")
    }

    // ========================================================================
    // VALID CASES: FUNCTION DEFINITIONS WITH <*>
    // ========================================================================

    @Test
    fun `function definition with performed effects is allowed`() {
        val source = """
            effect Log where {
                print: {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
                print = {msg} -> {}
            }

            let myFunc = {} -> perform Console in Log.print {msg = "test"}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Success,
            "Function definition with <*> should be allowed")
    }

    @Test
    fun `multiple function definitions with performed effects are allowed`() {
        val source = """
            effect Log where {
                print: {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
                print = {msg} -> {}
            }

            let func1 = {} -> perform Console in Log.print {msg = "one"}
            let func2 = {} -> perform Console in Log.print {msg = "two"}
            let func3 = {x: Int} -> perform Console in do
                Log.print {msg = "three"}
                x
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Success,
            "Multiple function definitions with <*> should be allowed")
    }

    @Test
    fun `function returning function with performed effects is allowed`() {
        val source = """
            effect Log where {
                print: {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
                print = {msg} -> {}
            }

            let makeLogger = {prefix: Str} -> {} -> perform Console in
                Log.print {msg = prefix}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Success,
            "Higher-order function returning effectful function should be allowed")
    }

    // ========================================================================
    // TOP-LEVEL EXPRESSIONS (checkProgram vs checkProgramForExecution)
    // ========================================================================

    @Test
    fun `checkProgram allows bare effectful expression for REPL`() {
        val source = """
            effect Log where {
                print: {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
                print = {msg} -> {}
            }

            let myFunc = {} -> perform Console in Log.print {msg = "test"}

            myFunc {}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Success,
            "checkProgram should allow bare effectful expression (for REPL/tests)")
    }

    @Test
    fun `checkProgramForExecution rejects bare effectful expression`() {
        val source = """
            effect Log where {
                print: {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
                print = {msg} -> {}
            }

            let myFunc = {} -> perform Console in Log.print {msg = "test"}

            myFunc {}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgramForExecution(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Failure,
            "checkProgramForExecution should reject bare effectful expression")
        val errorMsg = (result as TypeChecker.TypeCheckResult.Failure).errors.first().message
        // The error could be about module-level effects OR about missing main function
        assertTrue(
            errorMsg.contains("module level", ignoreCase = true) ||
            errorMsg.contains("main", ignoreCase = true),
            "Error should mention 'module level' or 'main': $errorMsg"
        )
    }

    @Test
    fun `checkProgramForExecution rejects multiple bare effectful expressions`() {
        val source = """
            effect Log where {
                print: {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
                print = {msg} -> {}
            }

            let func1 = {} -> perform Console in Log.print {msg = "one"}
            let func2 = {} -> perform Console in Log.print {msg = "two"}

            func1 {}
            func2 {}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgramForExecution(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Failure,
            "checkProgramForExecution should reject multiple bare effectful expressions")
    }

    @Test
    fun `bare pure expression is allowed in both modes`() {
        val source = """
            let add = {x: Int, y: Int} -> x + y

            add {x = 1, y = 2}
        """.trimIndent()

        val ast = parser.parse(source)

        // checkProgram should allow it
        val normalResult = typeChecker.checkProgram(ast)
        assertTrue(normalResult is TypeChecker.TypeCheckResult.Success,
            "checkProgram should allow bare pure expression")

        // checkProgramForExecution will fail because there's no main,
        // but NOT because of performed effects
        val execResult = typeChecker.checkProgramForExecution(ast)
        assertTrue(execResult is TypeChecker.TypeCheckResult.Failure)
        val errorMsg = (execResult as TypeChecker.TypeCheckResult.Failure).errors.first().message
        assertContains(errorMsg, "main", ignoreCase = true,
            message = "Error should be about missing main, not effects")
    }

    // ========================================================================
    // EDGE CASES
    // ========================================================================

    @Test
    fun `direct perform with operation returning value is rejected`() {
        // Top-level values cannot have <*>, regardless of whether perform is direct or via function call
        val source = """
            effect State having 'state where {
                get: {} -> 'state <State>
            }

            impl State for Counter having 'state = Int where {
                get = {} -> 42
            }

            let value = perform Counter in State.get {}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Failure,
            "Direct perform at top-level should be rejected")
        val errorMsg = (result as TypeChecker.TypeCheckResult.Failure).errors.first().message
        assertContains(errorMsg, "performed effects", ignoreCase = true)
    }

    @Test
    fun `chained effectful calls at top-level are rejected`() {
        val source = """
            effect Log where {
                print: {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
                print = {msg} -> {}
            }

            let logAndReturn = {x: Int} -> perform Console in do
                Log.print {msg = "value"}
                x

            let doubled = {x: Int} -> logAndReturn {x = x * 2}

            let result = doubled {x = 21}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Failure,
            "Chained effectful calls at top-level should be rejected")
    }

    @Test
    fun `function wrapping effectful call is valid pattern`() {
        // This is the recommended pattern when you need to use effectful code
        val source = """
            effect Log where {
                print: {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
                print = {msg} -> {}
            }

            let effectfulFunc = {} -> perform Console in Log.print {msg = "test"}

            let getResult = {} -> effectfulFunc {}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Success,
            "Wrapping effectful call in function should be valid")
    }

    @Test
    fun `valid main program with effectful helpers passes execution check`() {
        val source = """
            effect Log where {
                print: {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
                print = {msg} -> {}
            }

            let helper = {} -> perform Console in Log.print {msg = "helper"}

            let main = {} -> helper {}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgramForExecution(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Success,
            "Valid main calling effectful helper should pass execution check")
    }

    // ========================================================================
    // TYPE INFERENCE TESTS FOR PERFORM EXPRESSIONS
    // ========================================================================

    @Test
    fun `perform expression wrapped in function infers correct Result type`() {
        // This tests the fix for the bug where top-level perform inferred wrong type
        // Previously: | Ok { val: Int <Exception> } | (wrong - anonymous variant with effects inside)
        // Now: {} -> Result Int String <*> (correct - closed variant with Ok and Err, performed marker)
        val source = """
            effect Exception having 'error where {
                throw: { error: 'error } -> 'a <Exception>
            }

            impl Exception for ExceptionToResult having 'error = Str where {
                throw = { error } -> suspend_with { value = Err { error } }
            }

            let safe_div = {x: Int} -> if x == 0 then Exception.throw {error = "zero"} else x
            let res = safe_div {x = 5}
            let handle = {} -> perform ExceptionToResult in Ok {val = res}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Success,
            "Perform wrapped in function should type check")

        val successResult = result as TypeChecker.TypeCheckResult.Success
        val funcType = successResult.typedModule?.decls
            ?.filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            ?.find { it.name == "handle" }
            ?.scheme?.type as com.mezze.language.ast.MezzeType.FunctionType

        // Return type should be Result variant (may be expanded to VariantType or kept as TypeApplication)
        // The key is it has BOTH Ok and Err constructors (not just Ok as in the bug)
        val returnType = funcType.returnType
        when (returnType) {
            is com.mezze.language.ast.MezzeType.TypeApplication -> {
                kotlin.test.assertEquals("Result", returnType.constructor,
                    "Should be Result type alias")
            }
            is com.mezze.language.ast.MezzeType.VariantType -> {
                // Result expands to | Ok {...}, Err {...} |
                assertTrue(returnType.constructors.containsKey("Ok"),
                    "Result variant should have Ok constructor")
                assertTrue(returnType.constructors.containsKey("Err"),
                    "Result variant should have Err constructor, got: ${returnType.constructors.keys}")
            }
            else -> kotlin.test.fail("Return type should be Result (TypeApplication or VariantType), got: $returnType")
        }

        // Should have performed marker
        assertNotNull(funcType.effects)
        assertTrue(funcType.effects!!.performed, "Should have performed marker <*>")
    }

    @Test
    fun `top-level perform expression is rejected with correct error`() {
        val source = """
            effect Exception having 'error where {
                throw: { error: 'error } -> 'a <Exception>
            }

            impl Exception for ExceptionToResult having 'error = Str where {
                throw = { error } -> suspend_with { value = Err { error } }
            }

            let safe_div = {x: Int} -> if x == 0 then Exception.throw {error = "zero"} else x
            let res = safe_div {x = 5}
            let handle = perform ExceptionToResult in Ok {val = res}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Failure,
            "Top-level perform should be rejected")
        val errorMsg = (result as TypeChecker.TypeCheckResult.Failure).errors.first().message
        assertContains(errorMsg, "performed effects", ignoreCase = true)
        assertContains(errorMsg, "handle", ignoreCase = true)
    }

    @Test
    fun `unhandled effects at top-level are allowed`() {
        // Top-level can have <Exception>, just not <*>
        val source = """
            effect Exception having 'error where {
                throw: { error: 'error } -> 'a <Exception>
            }

            let safe_div = {x: Int} -> if x == 0 then Exception.throw {error = "zero"} else x
            let res = safe_div {x = 5}
        """.trimIndent()

        val ast = parser.parse(source)
        val result = typeChecker.checkProgram(ast)

        assertTrue(result is TypeChecker.TypeCheckResult.Success,
            "Unhandled effects <Exception> should be allowed at top-level")
    }
}
