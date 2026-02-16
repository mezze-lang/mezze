package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Tests for the <*> performed marker in the effect system.
 *
 * The performed marker tracks when effects have been handled via 'perform':
 * - <> = truly pure, no effects ever
 * - <*> = effects were performed and handled
 * - <Log, *> = has unhandled Log AND something was already performed
 * - <Log> = has unhandled effects (suspended)
 */
class PerformedMarkerTest {
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    private fun getVariableType(program: Program, name: String): FunkType? {
        val binding = program.items.filterIsInstance<LetBinding>()
            .find { (it.pattern as? VariablePattern)?.name == name }
        return binding?.inferredType
    }

    // ========================================================================
    // BASIC PERFORM MARKER TESTS
    // ========================================================================

    @Test
    fun `perform expression marks result as performed`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            impl Log for Console having 'msg = String where {
                println = { msg } -> __native_print { s = msg }
            }

            let result = {} -> perform Console in Log.println { msg = "hello" }
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val resultType = getVariableType(typedProgram, "result") as FunkType.FunctionType
        assertNotNull(resultType.effects)
        assertTrue(resultType.effects!!.performed, "Effect should be marked as performed after perform")
        assertTrue(resultType.effects!!.effects.isEmpty(), "No remaining effects after perform")
    }

    @Test
    fun `pure function has no effects and not performed`() {
        val source = """
            let pureFunc = {} -> 42
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val funcType = getVariableType(typedProgram, "pureFunc") as FunkType.FunctionType
        assertNull(funcType.effects, "Pure function should have null effects")
    }

    @Test
    fun `effectful function without perform has unhandled effects`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            let effectfulFunc = {} -> Log.println { msg = "hello" }
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val funcType = getVariableType(typedProgram, "effectfulFunc") as FunkType.FunctionType
        assertNotNull(funcType.effects)
        assertFalse(funcType.effects!!.performed, "Unhandled effect should not be marked as performed")
        assertTrue(funcType.effects!!.effects.contains("Log"), "Should have Log effect")
    }

    @Test
    fun `partial perform leaves remaining effects with performed marker`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            effect State having 'state where {
                get: {} -> 'state
            }

            impl Log for Console having 'msg = String where {
                println = { msg } -> __native_print { s = msg }
            }

            let partialHandle = {} -> perform Console in do
                Log.println { msg = "hello" }
                State.get {}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val funcType = getVariableType(typedProgram, "partialHandle") as FunkType.FunctionType
        assertNotNull(funcType.effects)
        assertTrue(funcType.effects!!.performed, "Should be marked as performed")
        assertTrue(funcType.effects!!.effects.contains("State"), "Should have remaining State effect")
        assertFalse(funcType.effects!!.effects.contains("Log"), "Log should be handled")
    }

    // ========================================================================
    // PERFORMED MARKER PROPAGATION TESTS
    // ========================================================================

    @Test
    fun `performed marker propagates through function calls`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            impl Log for Console having 'msg = String where {
                println = { msg } -> __native_print { s = msg }
            }

            let inner = {} -> perform Console in Log.println { msg = "inner" }
            let outer = {} -> inner {}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val outerType = getVariableType(typedProgram, "outer") as FunkType.FunctionType
        assertNotNull(outerType.effects)
        assertTrue(outerType.effects!!.performed, "Performed should propagate through function calls")
    }

    @Test
    fun `performed marker propagates through if branches`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            impl Log for Console having 'msg = String where {
                println = { msg } -> __native_print { s = msg }
            }

            let conditional = {cond} -> if cond then perform Console in Log.println { msg = "yes" } else {}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val funcType = getVariableType(typedProgram, "conditional") as FunkType.FunctionType
        assertNotNull(funcType.effects)
        assertTrue(funcType.effects!!.performed, "Performed should propagate through if branches")
    }

    @Test
    fun `performed marker propagates through match expressions`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            impl Log for Console having 'msg = String where {
                println = { msg } -> __native_print { s = msg }
            }

            let matchExpr = {x} -> match x is
                Some {val} -> perform Console in Log.println { msg = "found" }
                None -> {}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val funcType = getVariableType(typedProgram, "matchExpr") as FunkType.FunctionType
        assertNotNull(funcType.effects)
        assertTrue(funcType.effects!!.performed, "Performed should propagate through match branches")
    }

    @Test
    fun `performed marker propagates through do blocks`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            impl Log for Console having 'msg = String where {
                println = { msg } -> __native_print { s = msg }
            }

            let doBlock = {} -> do
                let x = perform Console in Log.println { msg = "first" }
                42
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val funcType = getVariableType(typedProgram, "doBlock") as FunkType.FunctionType
        assertNotNull(funcType.effects)
        assertTrue(funcType.effects!!.performed, "Performed should propagate through do blocks")
    }

    // ========================================================================
    // EFFECT ROW STRING REPRESENTATION TESTS
    // ========================================================================

    @Test
    fun `EffectRow toString shows performed marker correctly`() {
        // <*> - performed only
        val performedOnly = FunkType.EffectRow(emptySet(), null, true)
        assertTrue(performedOnly.toString().contains("*"), "Should show * for performed")

        // <Log, *> - effects plus performed
        val effectsAndPerformed = FunkType.EffectRow(setOf("Log"), null, true)
        assertTrue(effectsAndPerformed.toString().contains("Log"), "Should show Log")
        assertTrue(effectsAndPerformed.toString().contains("*"), "Should show *")

        // <Log> - effects without performed
        val effectsOnly = FunkType.EffectRow(setOf("Log"), null, false)
        assertTrue(effectsOnly.toString().contains("Log"), "Should show Log")
        assertFalse(effectsOnly.toString().contains("*"), "Should not show * when not performed")

        // <> - empty
        val empty = FunkType.EffectRow(emptySet(), null, false)
        assertFalse(empty.toString().contains("*"), "Empty should not show *")
    }

    @Test
    fun `EffectRow isPure correctly identifies pure effects`() {
        // Pure: no effects, no row var, not performed
        val pure = FunkType.EffectRow(emptySet(), null, false)
        assertTrue(pure.isPure, "Empty non-performed effect row should be pure")

        // Not pure: performed
        val performed = FunkType.EffectRow(emptySet(), null, true)
        assertFalse(performed.isPure, "Performed effect row should not be pure")

        // Not pure: has effects
        val hasEffects = FunkType.EffectRow(setOf("Log"), null, false)
        assertFalse(hasEffects.isPure, "Effect row with effects should not be pure")

        // Not pure: has row var (open)
        val hasRowVar = FunkType.EffectRow(emptySet(), RowVar("e"), false)
        assertFalse(hasRowVar.isPure, "Open effect row should not be pure")
    }

    // ========================================================================
    // MULTIPLE PERFORM TESTS - SINGLE * MARKER
    // ========================================================================

    @Test
    fun `multiple performs result in single performed marker`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            effect State having 'state where {
                get: {} -> 'state,
                put: { val: 'state } -> {}
            }

            impl Log for Console having 'msg = String where {
                println = { msg } -> __native_print { s = msg }
            }

            impl State for IntState having 'state = Int where {
                get = {} -> 0,
                put = { val } -> {}
            }

            let multiPerform = {} -> perform Console in perform IntState in do
                Log.println { msg = "hello" }
                let x = State.get {}
                State.put { val = x }
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val funcType = getVariableType(typedProgram, "multiPerform") as FunkType.FunctionType
        assertNotNull(funcType.effects)
        assertTrue(funcType.effects!!.performed, "Should be marked as performed")
        assertTrue(funcType.effects!!.effects.isEmpty(), "All effects should be handled")

        // Check toString only has one * marker
        val effectStr = funcType.effects!!.toString()
        val starCount = effectStr.count { it == '*' }
        assertEquals(1, starCount, "Effect row should have exactly one * marker, got: $effectStr")
    }

    @Test
    fun `sequential performs in do block result in single performed marker`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            effect State having 'state where {
                get: {} -> 'state
            }

            impl Log for Console having 'msg = String where {
                println = { msg } -> __native_print { s = msg }
            }

            impl State for IntState having 'state = Int where {
                get = {} -> 42
            }

            let seqPerform = {} -> do
                let _ = perform Console in Log.println { msg = "first" }
                perform IntState in State.get {}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val funcType = getVariableType(typedProgram, "seqPerform") as FunkType.FunctionType
        assertNotNull(funcType.effects)
        assertTrue(funcType.effects!!.performed, "Should be marked as performed")

        // Verify single * marker
        val effectStr = funcType.effects!!.toString()
        val starCount = effectStr.count { it == '*' }
        assertEquals(1, starCount, "Effect row should have exactly one * marker, got: $effectStr")
    }

    @Test
    fun `calling multiple performed functions results in single performed marker`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            effect State having 'state where {
                get: {} -> 'state
            }

            impl Log for Console having 'msg = String where {
                println = { msg } -> __native_print { s = msg }
            }

            impl State for IntState having 'state = Int where {
                get = {} -> 42
            }

            let helper1 = {} -> perform Console in Log.println { msg = "h1" }
            let helper2 = {} -> perform IntState in State.get {}
            let combined = {} -> do
                helper1 {}
                helper2 {}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val funcType = getVariableType(typedProgram, "combined") as FunkType.FunctionType
        assertNotNull(funcType.effects)
        assertTrue(funcType.effects!!.performed, "Should be marked as performed")

        // Verify single * marker
        val effectStr = funcType.effects!!.toString()
        val starCount = effectStr.count { it == '*' }
        assertEquals(1, starCount, "Effect row should have exactly one * marker, got: $effectStr")
    }

    // ========================================================================
    // * MARKER POSITION TESTS - ALWAYS AT END
    // ========================================================================

    @Test
    fun `performed marker appears at end of effect row string`() {
        // <Log, *> - effects plus performed, * should be at the end
        val effectsAndPerformed = FunkType.EffectRow(setOf("Log"), null, true)
        val str = effectsAndPerformed.toString()
        assertTrue(str.contains("Log"), "Should contain Log")
        assertTrue(str.contains("*"), "Should contain *")

        // * should appear after Log
        val logIndex = str.indexOf("Log")
        val starIndex = str.indexOf("*")
        assertTrue(starIndex > logIndex, "* should appear after effect names in: $str")
    }

    @Test
    fun `performed marker at end with multiple effects`() {
        // <Log, State, *> - multiple effects plus performed
        val multiEffects = FunkType.EffectRow(setOf("Log", "State"), null, true)
        val str = multiEffects.toString()

        assertTrue(str.contains("Log"), "Should contain Log")
        assertTrue(str.contains("State"), "Should contain State")
        assertTrue(str.contains("*"), "Should contain *")

        // * should appear after all effect names
        val starIndex = str.indexOf("*")
        val logIndex = str.indexOf("Log")
        val stateIndex = str.indexOf("State")
        assertTrue(starIndex > logIndex, "* should appear after Log in: $str")
        assertTrue(starIndex > stateIndex, "* should appear after State in: $str")
    }

    @Test
    fun `performed marker at end with row variable`() {
        // <Log, 'e, *> - effect, row var, and performed
        val withRowVar = FunkType.EffectRow(setOf("Log"), RowVar("e"), true)
        val str = withRowVar.toString()

        assertTrue(str.contains("Log"), "Should contain Log")
        assertTrue(str.contains("*"), "Should contain *")

        // * should be at the end (after Log and row var)
        val starIndex = str.indexOf("*")
        val logIndex = str.indexOf("Log")
        assertTrue(starIndex > logIndex, "* should appear after Log in: $str")
    }

    @Test
    fun `perform one by one in separate let bindings`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            effect State having 'state where {
                get: {} -> 'state
            }

            impl Log for Console having 'msg = String where {
                println = { msg } -> __native_print { s = msg }
            }

            impl State for IntState having 'state = Int where {
                get = {} -> 42
            }

            let stepByStep = {} -> do
                let a = perform Console in Log.println { msg = "step1" }
                let b = perform IntState in State.get {}
                let c = perform Console in Log.println { msg = "step2" }
                {}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val funcType = getVariableType(typedProgram, "stepByStep") as FunkType.FunctionType
        assertNotNull(funcType.effects)
        assertTrue(funcType.effects!!.performed, "Should be marked as performed")
        assertTrue(funcType.effects!!.effects.isEmpty(), "All effects should be handled")

        // Verify single * marker despite multiple separate performs
        val effectStr = funcType.effects!!.toString()
        val starCount = effectStr.count { it == '*' }
        assertEquals(1, starCount, "Should have exactly one * marker after multiple separate performs, got: $effectStr")
    }

    @Test
    fun `perform in different if branches results in single marker`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            effect State having 'state where {
                get: {} -> 'state
            }

            impl Log for Console having 'msg = String where {
                println = { msg } -> __native_print { s = msg }
            }

            impl State for IntState having 'state = Int where {
                get = {} -> 0
            }

            let branchPerform = {cond} ->
                if cond
                then perform Console in Log.println { msg = "true branch" }
                else perform IntState in do
                    let _ = State.get {}
                    {}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val funcType = getVariableType(typedProgram, "branchPerform") as FunkType.FunctionType
        assertNotNull(funcType.effects)
        assertTrue(funcType.effects!!.performed, "Should be marked as performed from both branches")

        // Verify single * marker
        val effectStr = funcType.effects!!.toString()
        val starCount = effectStr.count { it == '*' }
        assertEquals(1, starCount, "Should have exactly one * marker from branched performs, got: $effectStr")
    }

    @Test
    fun `perform in different match branches results in single marker`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            effect State having 'state where {
                get: {} -> 'state
            }

            impl Log for Console having 'msg = String where {
                println = { msg } -> __native_print { s = msg }
            }

            impl State for IntState having 'state = Int where {
                get = {} -> 0
            }

            let matchPerform = {opt} -> match opt is
                Some {val} -> perform Console in Log.println { msg = "some" }
                None -> perform IntState in do
                    let _ = State.get {}
                    {}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val funcType = getVariableType(typedProgram, "matchPerform") as FunkType.FunctionType
        assertNotNull(funcType.effects)
        assertTrue(funcType.effects!!.performed, "Should be marked as performed from match branches")

        // Verify single * marker
        val effectStr = funcType.effects!!.toString()
        val starCount = effectStr.count { it == '*' }
        assertEquals(1, starCount, "Should have exactly one * marker from match performs, got: $effectStr")
    }

    @Test
    fun `partial perform with remaining effects has star at end`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            effect State having 'state where {
                get: {} -> 'state
            }

            impl Log for Console having 'msg = String where {
                println = { msg } -> __native_print { s = msg }
            }

            let partial = {} -> perform Console in do
                Log.println { msg = "hello" }
                State.get {}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val funcType = getVariableType(typedProgram, "partial") as FunkType.FunctionType
        assertNotNull(funcType.effects)
        assertTrue(funcType.effects!!.performed, "Should be performed")
        assertTrue(funcType.effects!!.effects.contains("State"), "Should have State")

        val effectStr = funcType.effects!!.toString()
        val starIndex = effectStr.indexOf("*")
        val stateIndex = effectStr.indexOf("State")
        assertTrue(starIndex > stateIndex, "* should appear after State in: $effectStr")
    }
}
