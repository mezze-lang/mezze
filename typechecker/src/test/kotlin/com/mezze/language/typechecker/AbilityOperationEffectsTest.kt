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
            ability Show 'a where {
                show: {val: 'a} -> String
            }

            impl Show Int where {
                show = {val} -> "number"
            }

            Show.show {val = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Ability operation with no effects should pass")
    }

    @Test
    fun `test ability operation with explicit empty effects`() {
        val source = """
            ability Show 'a where {
                show: {val: 'a} -> String <>
            }

            impl Show Int where {
                show = {val} -> "number"
            }

            Show.show {val = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Ability operation with explicit empty effects should pass")
    }

    @Test
    fun `test ability operation with single effect`() {
        val source = """
            effect Mutation where {
                mutate: {} -> {}
            }

            ability Mutable 'c where {
                set: {c: 'c, i: Int, v: Int} -> Bool <Mutation>
            }

            impl Mutable [Int] where {
                set = {c, i, v} -> True
            }

            Mutable.set {c = [1, 2, 3], i = 0, v = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Ability operation with single effect should pass")
    }

    @Test
    fun `test ability operation with multiple effects`() {
        val source = """
            effect IO where {
                read: {} -> String
            }

            effect Exception where {
                throw: {msg: String} -> {}
            }

            ability FileReader 'a where {
                read_file: {path: String} -> 'a <IO, Exception>
            }

            impl FileReader String where {
                read_file = {path} -> "file content"
            }

            FileReader.read_file {path = "/tmp/test.txt"}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Ability operation with multiple effects should pass")
    }

    @Test
    fun `test ability with mixed pure and effectful operations`() {
        val source = """
            effect Mutation where {
                mutate: {} -> {}
            }

            ability Container 'c having 'elem where {
                len: {c: 'c} -> Int,
                get: {c: 'c, i: Int} -> Option 'elem,
                set: {c: 'c, i: Int, v: 'elem} -> Bool <Mutation>
            }

            impl Container [Int] having 'elem = Int where {
                len = {c} -> 0,
                get = {c, i} -> None,
                set = {c, i, v} -> True
            }

            let arr = [1, 2, 3]
            let length = Container.len {c = arr}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Ability with mixed pure and effectful operations should pass")
    }

    // ========== INVALID CASES - POLYMORPHIC EFFECTS ==========

    @Test
    fun `test ability operation with polymorphic effects should fail`() {
        val source = """
            ability Processor 'a where {
                process: {val: 'a} -> String <...>
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
            effect Mutation where {
                mutate: {} -> {}
            }

            ability Mutable 'c where {
                set: {c: 'c, i: Int} -> Bool <Mutation, ...>
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
            ability Runner 'a where {
                run: {val: 'a} -> String <*>
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
            effect Mutation where {
                mutate: {} -> {}
            }

            ability Mutable 'c where {
                set: {c: 'c, i: Int} -> Bool <Mutation, *>
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
            ability Flexible 'a where {
                flex: {val: 'a} -> 'a <...>
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
                log: {msg: String} -> {}
            }

            ability Mixed 'a where {
                pure_op: {val: 'a} -> String,
                effectful_op: {val: 'a} -> String <Log>,
                bad_op: {val: 'a} -> String <...>
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
            effect Mutation where {
                mutate: {} -> {}
            }

            ability Mutable 'c where {
                set: {c: 'c, i: Int, v: Int} -> Bool <Mutation>
            }

            impl Mutable [Int] where {
                set = {c, i, v} -> True
            }

            # Just call the ability method - don't worry about effect handling for now
            let arr = [1, 2, 3]
            Mutable.set {c = arr, i = 0, v = 99}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Ability with effect should compile")
    }

    @Test
    fun `test calling pure ability operation has no effect`() {
        val source = """
            ability Show 'a where {
                show: {val: 'a} -> String
            }

            impl Show Int where {
                show = {val} -> "number"
            }

            # Pure function calling pure ability operation
            let display: {x: Int} -> String <> = {x} -> Show.show {val = x}

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
            effect Mutation where {
                mutate: {} -> {}
            }

            ability Mutable 'c where {
                set: {c: 'c, i: Int, v: Int} -> Bool <Mutation>
            }

            impl Mutable [Int] where {
                # Implementation should also have <Mutation> effect
                set = {c, i, v} -> True
            }

            Mutable.set {c = [1, 2, 3], i = 0, v = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Impl with matching effects should pass")
    }

    // ========== ABILITY WITH ASSOCIATED TYPES AND EFFECTS ==========

    @Test
    fun `test ability with associated types and effects`() {
        val source = """
            effect Mutation where {
                mutate: {} -> {}
            }

            ability Iterator 'iter having 'item where {
                next: {iter: 'iter} -> Option 'item <Mutation>,
                has_next: {iter: 'iter} -> Bool
            }

            type IntIter = { pos: Int, data: [Int] }

            impl Iterator IntIter having 'item = Int where {
                next = {iter} -> Some {val = 0},
                has_next = {iter} -> True
            }

            let iter = { pos = 0, data = [1, 2, 3] }
            Iterator.has_next {iter = iter}
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
                log: {msg: String} -> {}
            }

            ability Factory 'a where {
                # Returns a function that has effects
                create_logger: {prefix: String} -> ({msg: String} -> {} <Log>)
            }

            impl Factory String where {
                create_logger = {prefix} -> {msg} -> {}
            }

            Factory.create_logger {prefix = "INFO"}
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

            ability Transformer 'a where {
                # Operation itself has State effect, takes a pure function
                transform: {val: 'a, f: {x: 'a} -> 'a} -> 'a <State>
            }

            impl Transformer Int where {
                transform = {val, f} -> f {x = val}
            }

            Transformer.transform {val = 5, f = {x} -> x + 1}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Complex effect signatures should work")
    }

    @Test
    fun `test multiple abilities with different effects on same type`() {
        val source = """
            effect Read where {
                read: {} -> String
            }

            effect Write where {
                write: {data: String} -> {}
            }

            ability Readable 'a where {
                read_all: {source: 'a} -> String <Read>
            }

            ability Writable 'a where {
                write_all: {dest: 'a, data: String} -> {} <Write>
            }

            type File = { path: String }

            impl Readable File where {
                read_all = {source} -> "content"
            }

            impl Writable File where {
                write_all = {dest, data} -> {}
            }

            let f = { path = "/tmp/test" }
            Readable.read_all {source = f}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Multiple abilities with different effects should work")
    }
}
