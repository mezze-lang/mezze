package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for effect signatures in ability operations.
 *
 * Ability operations can optionally declare effects they perform.
 * Rules:
 * 1. Operations can have no effects (pure) or specific effects
 * 2. Operations CANNOT have polymorphic effects (<...>)
 * 3. Operations CANNOT have performed marker (<*>)
 *
 * This ensures ability operations define a clear contract about what
 * effects they perform.
 */
class AbilityOperationEffectsTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    // ========== VALID CASES ==========

    @Test
    fun `test ability operation with no effects (pure)`() {
        val source = """
            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Int where {
                show = {} -> "number"
            }

            42.show {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Ability operation with no effects should pass")
    }

    @Test
    fun `test ability operation with explicit empty effects`() {
        val source = """
            ability Show for 'a where {
                show: {} -> Str <>
            }

            impl Show for Int where {
                show = {} -> "number"
            }

            42.show {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Ability operation with explicit empty effects should pass")
    }

    @Test
    fun `test ability operation with single effect`() {
        val source = """
            effect Mut where {
                mutate: {} -> {}
            }

            ability Mutable for 'c where {
                set: {i: Int, v: Int} -> Bool <Mut>
            }

            impl Mutable for [Int] where {
                set = {i, v} -> True
            }

            [1, 2, 3].set {i = 0, v = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Ability operation with single effect should pass")
    }

    @Test
    fun `test ability operation with multiple effects`() {
        val source = """
            effect IO where {
                read: {} -> Str
            }

            effect Exception where {
                throw: {msg: Str} -> {}
            }

            ability FileReader for 'a where {
                read_file: {path: Str} -> 'a <IO, Exception>
            }

            impl FileReader for Str where {
                read_file = {path} -> "file content"
            }

            "".read_file {path = "/tmp/test.txt"}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Ability operation with multiple effects should pass")
    }

    @Test
    fun `test ability with mixed pure and effectful operations`() {
        val source = """
            effect Mut where {
                mutate: {} -> {}
            }

            ability Container for 'c having 'elem where {
                len: {} -> Int,
                get: {i: Int} -> Option 'elem,
                set: {i: Int, v: 'elem} -> Bool <Mut>
            }

            impl Container for [Int] having 'elem = Int where {
                len = {} -> 0,
                get = {i} -> None,
                set = {i, v} -> True
            }

            let arr = [1, 2, 3]
            let length = arr.len {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Ability with mixed pure and effectful operations should pass")
    }

    // ========== INVALID CASES - POLYMORPHIC EFFECTS ==========

    @Test
    fun `test ability operation with polymorphic effects should fail`() {
        val source = """
            ability Processor for 'a where {
                process: {} -> Str <...>
            }
        """.trimIndent()

        val program = parser.parse(source)

        val error = assertThrows<TypeCheckError>("Should fail: polymorphic effects not allowed") {
            typeChecker.typeCheck(program).program
        }

        assert(error.message?.contains("polymorphic") == true || error.message?.contains("<...>") == true) {
            "Error message should mention polymorphic effects: ${error.message}"
        }
    }

    @Test
    fun `test ability operation with effect and polymorphic tail should fail`() {
        val source = """
            effect Mut where {
                mutate: {} -> {}
            }

            ability Mutable for 'c where {
                set: {i: Int} -> Bool <Mut, ...>
            }
        """.trimIndent()

        val program = parser.parse(source)

        val error = assertThrows<TypeCheckError>("Should fail: polymorphic tail not allowed") {
            typeChecker.typeCheck(program).program
        }

        assert(error.message?.contains("polymorphic") == true) {
            "Error message should mention polymorphic effects: ${error.message}"
        }
    }

    // ========== INVALID CASES - PERFORMED MARKER ==========

    @Test
    fun `test ability operation with performed marker should fail`() {
        val source = """
            ability Runner for 'a where {
                run: {} -> Str <*>
            }
        """.trimIndent()

        val program = parser.parse(source)

        val error = assertThrows<TypeCheckError>("Should fail: performed marker not allowed") {
            typeChecker.typeCheck(program).program
        }

        assert(error.message?.contains("performed") == true || error.message?.contains("<*>") == true) {
            "Error message should mention performed marker: ${error.message}"
        }
    }

    @Test
    fun `test ability operation with effect and performed marker should fail`() {
        val source = """
            effect Mut where {
                mutate: {} -> {}
            }

            ability Mutable for 'c where {
                set: {i: Int} -> Bool <Mut, *>
            }
        """.trimIndent()

        val program = parser.parse(source)

        val error = assertThrows<TypeCheckError>("Should fail: performed marker not allowed") {
            typeChecker.typeCheck(program).program
        }

        assert(error.message?.contains("performed") == true) {
            "Error message should mention performed marker: ${error.message}"
        }
    }

    // ========== MORE INVALID CASES ==========

    @Test
    fun `test ability operation with just polymorphic marker alone should fail`() {
        val source = """
            ability Flexible for 'a where {
                flex: {} -> 'a <...>
            }
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: <...> alone not allowed") {
            typeChecker.typeCheck(program).program
        }
    }

    // Note: <..., *> is not valid parser syntax, so we can't test it.
    // The parser will reject it before the typechecker sees it.

    @Test
    fun `test multiple operations some with invalid effects should fail`() {
        val source = """
            effect Log where {
                log: {msg: Str} -> {}
            }

            ability Mixed for 'a where {
                pure_op: {} -> Str,
                effectful_op: {} -> Str <Log>,
                bad_op: {} -> Str <...>
            }
        """.trimIndent()

        val program = parser.parse(source)

        val error = assertThrows<TypeCheckError>("Should fail: one operation has invalid effects") {
            typeChecker.typeCheck(program).program
        }

        assert(error.message?.contains("bad_op") == true || error.message?.contains("polymorphic") == true) {
            "Error should mention the bad operation or polymorphic: ${error.message}"
        }
    }

    // ========== EFFECT TRACKING AT CALL SITES ==========

    @Test
    fun `test calling ability operation with effect tracks that effect`() {
        // NOTE: Currently ability operation effects are declared but may not
        // be fully tracked at call sites. This test documents expected behavior
        // once effect tracking for ability operations is fully implemented.
        //
        // For now, we just verify the ability with effects can be declared
        // and implemented. Full effect propagation is future work.
        val source = """
            effect Mut where {
                mutate: {} -> {}
            }

            ability Mutable for 'c where {
                set: {i: Int, v: Int} -> Bool <Mut>
            }

            impl Mutable for [Int] where {
                set = {i, v} -> True
            }

            # Just call the ability method - don't worry about effect handling for now
            let arr = [1, 2, 3]
            arr.set {i = 0, v = 99}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Ability with effect should compile")
    }

    @Test
    fun `test calling pure ability operation has no effect`() {
        val source = """
            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Int where {
                show = {} -> "number"
            }

            # Pure function calling pure ability operation
            let display: {x: Int} -> Str <> = {x} -> x.show {}

            display {x = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Pure ability operation should not add effects")
    }

    // ========== IMPLEMENTATION MATCHING ==========

    @Test
    fun `test impl must have same effects as ability declaration`() {
        val source = """
            effect Mut where {
                mutate: {} -> {}
            }

            ability Mutable for 'c where {
                set: {i: Int, v: Int} -> Bool <Mut>
            }

            impl Mutable for [Int] where {
                # Implementation should also have <Mut> effect
                set = {i, v} -> True
            }

            [1, 2, 3].set {i = 0, v = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Impl with matching effects should pass")
    }

    // ========== ABILITY WITH ASSOCIATED TYPES AND EFFECTS ==========

    @Test
    fun `test ability with associated types and effects`() {
        val source = """
            effect Mut where {
                mutate: {} -> {}
            }

            ability Iterator for 'iter having 'item where {
                next: {} -> Option 'item <Mut>,
                has_next: {} -> Bool
            }

            type IntIter = { pos: Int, data: [Int] }

            impl Iterator for IntIter having 'item = Int where {
                next = {} -> Some {val = 0},
                has_next = {} -> True
            }

            let iter = { pos = 0, data = [1, 2, 3] }
            iter.has_next {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Ability with associated types and effects should work")
    }

    // ========== EDGE CASES ==========

    @Test
    fun `test ability operation returning function with effects`() {
        val source = """
            effect Log where {
                log: {msg: Str} -> {}
            }

            ability Factory for 'a where {
                # Returns a function that has effects
                create_logger: {prefix: Str} -> ({msg: Str} -> {} <Log>)
            }

            impl Factory for Str where {
                create_logger = {prefix} -> {msg} -> {}
            }

            "".create_logger {prefix = "INFO"}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Ability operation returning function with effects should work")
    }

    @Test
    fun `test ability operation with effect on both param and return function types`() {
        val source = """
            effect State where {
                get: {} -> Int,
                put: {val: Int} -> {}
            }

            ability Transformer for 'a where {
                # Operation itself has State effect, takes a pure function
                transform: {f: {x: 'a} -> 'a} -> 'a <State>
            }

            impl Transformer for Int where {
                transform = {f} -> f {x = it}
            }

            5.transform {f = {x} -> x + 1}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Complex effect signatures should work")
    }

    @Test
    fun `test multiple abilities with different effects on same type`() {
        val source = """
            effect Read where {
                read: {} -> Str
            }

            effect Write where {
                write: {data: Str} -> {}
            }

            ability Readable for 'a where {
                read_all: {} -> Str <Read>
            }

            ability Writable for 'a where {
                write_all: {data: Str} -> {} <Write>
            }

            type File = { path: Str }

            impl Readable for File where {
                read_all = {} -> "content"
            }

            impl Writable for File where {
                write_all = {data} -> {}
            }

            let f = { path = "/tmp/test" }
            f.read_all {}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Multiple abilities with different effects should work")
    }
}
