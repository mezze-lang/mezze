package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Tests for effect merging when unifying SuspendedType.
 *
 * When different branches of a match/if have different effects,
 * the overall type should include ALL effects from all branches.
 */
class SuspendedTypeEffectMergeTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `match with different effects in branches should have union of effects`() {
        // When one branch has Log effect and another has State effect,
        // the overall match should have both effects
        val source = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            effect State where {
                get: {} -> Int <State>
            }

            impl Log for ConsoleLogger where {
                info = {msg} -> {}
            }

            impl State for StateHandler where {
                get = {} -> 42
            }

            let test = {flag: Bool} -> do
                let result = if flag
                    then do
                        Log.info {msg = "logging"}
                        1
                    else do
                        State.get {}
                result
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        val binding = typedProgram.items.last() as com.mezze.language.ast.LetBinding
        val fnType = binding.inferredType as MezzeType.FunctionType

        // The return type may be SuspendedType wrapping Int if effects are propagated via SuspendedType
        // OR plain Int with effects tracked in the function's effect row
        val valueType = when (val rt = fnType.returnType) {
            is MezzeType.SuspendedType -> rt.valueType
            else -> rt
        }
        assertTrue(valueType is MezzeType.IntType, "Expected IntType value but got ${valueType::class.simpleName}")

        // Should have BOTH Log and State effects (union of branches)
        assertNotNull(fnType.effects, "Function should have effects")
        assertTrue(fnType.effects!!.effects.contains("Log"), "Expected Log effect but got ${fnType.effects!!.effects}")
        assertTrue(fnType.effects!!.effects.contains("State"), "Expected State effect but got ${fnType.effects!!.effects}")
    }

    @Test
    fun `if-then-else with effectful then and pure else should have effects from then`() {
        val source = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            impl Log for ConsoleLogger where {
                info = {msg} -> {}
            }

            let test = {flag: Bool} -> do
                let result = if flag
                    then do
                        Log.info {msg = "logging"}
                        42
                    else 99
                result
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        val binding = typedProgram.items.last() as com.mezze.language.ast.LetBinding
        val fnType = binding.inferredType as MezzeType.FunctionType

        // Should have Log effect from the then branch
        assertNotNull(fnType.effects, "Function should have effects")
        assertTrue(fnType.effects!!.effects.contains("Log"), "Expected Log effect but got ${fnType.effects!!.effects}")
    }

    @Test
    fun `if-then-else with pure then and effectful else should have effects from else`() {
        val source = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            impl Log for ConsoleLogger where {
                info = {msg} -> {}
            }

            let test = {flag: Bool} -> do
                let result = if flag
                    then 42
                    else do
                        Log.info {msg = "logging"}
                        99
                result
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        val binding = typedProgram.items.last() as com.mezze.language.ast.LetBinding
        val fnType = binding.inferredType as MezzeType.FunctionType

        // Should have Log effect from the else branch
        assertNotNull(fnType.effects, "Function should have effects")
        assertTrue(fnType.effects!!.effects.contains("Log"), "Expected Log effect but got ${fnType.effects!!.effects}")
    }

    @Test
    fun `match with three branches with different effects should have all effects`() {
        val source = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            effect State where {
                get: {} -> Int <State>
            }

            effect FileSystem where {
                read: {path: Str} -> Str <FileSystem>
            }

            impl Log for ConsoleLogger where {
                info = {msg} -> {}
            }

            impl State for StateHandler where {
                get = {} -> 42
            }

            impl FileSystem for LocalFS where {
                read = {path} -> "content"
            }

            type Choice = | A, B, C |

            let test = {choice: Choice} ->
                match choice is
                    A -> do
                        Log.info {msg = "A"}
                        1
                    B -> do
                        State.get {}
                    C -> do
                        let _ = FileSystem.read {path = "/tmp/test"}
                        3
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        val binding = typedProgram.items.last() as com.mezze.language.ast.LetBinding
        val fnType = binding.inferredType as MezzeType.FunctionType

        // Should have ALL effects: Log, State, FileSystem
        assertNotNull(fnType.effects, "Function should have effects")
        assertTrue(fnType.effects!!.effects.contains("Log"), "Expected Log effect but got ${fnType.effects!!.effects}")
        assertTrue(fnType.effects!!.effects.contains("State"), "Expected State effect but got ${fnType.effects!!.effects}")
        assertTrue(fnType.effects!!.effects.contains("FileSystem"), "Expected FileSystem effect but got ${fnType.effects!!.effects}")
    }

    @Test
    fun `nested conditionals should accumulate all effects`() {
        val source = """
            effect A where {
                doA: {} -> {} <A>
            }

            effect B where {
                doB: {} -> {} <B>
            }

            impl A for HandlerA where {
                doA = {} -> {}
            }

            impl B for HandlerB where {
                doB = {} -> {}
            }

            let test = {x: Bool, y: Bool} -> do
                let result = if x
                    then do
                        A.doA {}
                        if y
                            then do
                                B.doB {}
                                1
                            else 2
                    else 3
                result
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        val binding = typedProgram.items.last() as com.mezze.language.ast.LetBinding
        val fnType = binding.inferredType as MezzeType.FunctionType

        // Should have both A and B effects
        assertNotNull(fnType.effects, "Function should have effects")
        assertTrue(fnType.effects!!.effects.contains("A"), "Expected A effect but got ${fnType.effects!!.effects}")
        assertTrue(fnType.effects!!.effects.contains("B"), "Expected B effect but got ${fnType.effects!!.effects}")
    }
}
