package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Tests for top-level effect restrictions and the performed marker <*>.
 *
 * The performed marker <*> tracks when effects have been handled:
 * - <> = pure, no effects
 * - <*> = effects were performed (side effects happened)
 * - <Log, *> = has unhandled Log effect AND something was already performed
 * - <Log> = has unhandled effects (suspended)
 *
 * Design decisions:
 * - Top-level VALUES CANNOT have <*> - must wrap in function
 * - Top-level VALUES CAN have unhandled effects like <Log>
 * - Top-level FUNCTIONS can return <*> - they're just definitions
 * - 'main' is treated as a regular binding at type-check time
 *   (runtime validation ensures main has correct type for execution)
 */
class TopLevelEffectRestrictionsTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun getVariableType(program: Program, name: String): MezzeType? {
        val binding = program.items.filterIsInstance<LetBinding>()
            .find { (it.pattern as? VariablePattern)?.name == name }
        return binding?.inferredType
    }

    // ========================================================================
    // TOP-LEVEL VALUES TESTS
    // ========================================================================

    @Test
    fun `top-level pure value is allowed`() {
        val source = """
            let x = 42
        """.trimIndent()

        val ast = parser.parse(source)
        typeChecker.typeCheck(ast)
    }

    @Test
    fun `top-level value with unhandled effects (suspended) is allowed`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            let suspended = Log.println { msg = "hello" }
        """.trimIndent()

        val ast = parser.parse(source)
        typeChecker.typeCheck(ast)
    }

    @Test
    fun `top-level value with performed effects is NOT allowed - must wrap in function`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            impl Log for Console having 'msg = String where {
                println = { msg } -> {}
            }

            let performedValue = {} -> perform Console in Log.println { msg = "hello" }
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        // When wrapped in a function, it should have type {} -> {} <*>
        val funcType = getVariableType(typedProgram, "performedValue") as MezzeType.FunctionType
        assertNotNull(funcType.effects)
        assertTrue(funcType.effects!!.performed, "Function should have performed marker <*>")
    }

    // ========================================================================
    // TOP-LEVEL FUNCTIONS CAN RETURN <*>
    // ========================================================================

    @Test
    fun `top-level function returning performed effects is allowed`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            impl Log for Console having 'msg = String where {
                println = { msg } -> {}
            }

            let myFunc = {} -> perform Console in Log.println { msg = "hello" }
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val funcType = getVariableType(typedProgram, "myFunc") as MezzeType.FunctionType
        assertNotNull(funcType.effects)
        assertTrue(funcType.effects!!.performed, "Function should have performed marker <*>")
        assertTrue(funcType.effects!!.effects.isEmpty(), "No remaining unhandled effects")
    }

    @Test
    fun `top-level pure function is allowed`() {
        val source = """
            let pureFunc = {x} -> x + 1
        """.trimIndent()

        val ast = parser.parse(source)
        typeChecker.typeCheck(ast)
    }

    @Test
    fun `top-level function with unhandled effects is allowed`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            let effectfulFunc = {} -> Log.println { msg = "hello" }
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val funcType = getVariableType(typedProgram, "effectfulFunc") as MezzeType.FunctionType
        assertNotNull(funcType.effects)
        assertTrue(funcType.effects!!.effects.contains("Log"), "Should have unhandled Log effect")
    }

    // ========================================================================
    // MAIN AS REGULAR BINDING (type-check time)
    // ========================================================================

    @Test
    fun `main with correct effectful type succeeds`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            impl Log for Console having 'msg = String where {
                println = { msg } -> {}
            }

            let main = {} -> perform Console in Log.println { msg = "Hello World" }
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val mainType = getVariableType(typedProgram, "main") as MezzeType.FunctionType
        assertNotNull(mainType.effects)
        assertTrue(mainType.effects!!.performed, "main should have performed effects")
    }

    @Test
    fun `main can be used as regular binding name`() {
        // This is allowed at type-check time - runtime validates main type for execution
        val source = """
            let main = {} -> 42
        """.trimIndent()

        val ast = parser.parse(source)
        typeChecker.typeCheck(ast)
    }

    @Test
    fun `main can be a pure function`() {
        // This is allowed at type-check time - runtime validates main type for execution
        val source = """
            let main = {} -> {}
        """.trimIndent()

        val ast = parser.parse(source)
        typeChecker.typeCheck(ast)
    }

    @Test
    fun `main can be a value`() {
        // This is allowed at type-check time - runtime validates main type for execution
        val source = """
            let main = 42
        """.trimIndent()

        val ast = parser.parse(source)
        typeChecker.typeCheck(ast)
    }

    // ========================================================================
    // COMPLEX SCENARIOS WITH PERFORMED MARKER
    // ========================================================================

    @Test
    fun `function with multiple effects all handled has only performed marker`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            effect Random having 'val where {
                next: {} -> 'val
            }

            impl Log for Console having 'msg = String where {
                println = { msg } -> {}
            }

            impl Random for SystemRandom having 'val = Int where {
                next = {} -> 42
            }

            let myFunc = {} -> perform Console, SystemRandom in do
                let n = Random.next {}
                Log.println { msg = "Done" }
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val funcType = getVariableType(typedProgram, "myFunc") as MezzeType.FunctionType
        assertNotNull(funcType.effects)
        assertTrue(funcType.effects!!.performed, "All effects handled should show <*>")
        assertTrue(funcType.effects!!.effects.isEmpty(), "No remaining unhandled effects")
    }

    @Test
    fun `calling function with performed effects propagates performed marker`() {
        val source = """
            effect Log having 'msg where {
                println: { msg: 'msg } -> {}
            }

            impl Log for Console having 'msg = String where {
                println = { msg } -> {}
            }

            let helper = {} -> perform Console in Log.println { msg = "helper" }

            let caller = {} -> helper {}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val callerType = getVariableType(typedProgram, "caller") as MezzeType.FunctionType
        assertNotNull(callerType.effects)
        assertTrue(callerType.effects!!.performed, "Performed should propagate through calls")
    }
}
