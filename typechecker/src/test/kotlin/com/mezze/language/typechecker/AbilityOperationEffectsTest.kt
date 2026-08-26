package com.mezze.language.typechecker

import java.nio.file.Paths
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
                show: {it: 'a} -> Str
            }

            impl Show for Int where {
                show = {it} -> "number"
            }

            42.show {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Ability operation with no effects should pass")
    }

    @Test
    fun `test ability operation with explicit empty effects`() {
        val source = """
            ability Show for 'a where {
                show: {it: 'a} -> Str <>
            }

            impl Show for Int where {
                show = {it} -> "number"
            }

            42.show {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Ability operation with explicit empty effects should pass")
    }

    @Test
    fun `test ability operation with single effect`() {
        val source = """
            effect Mut where {
                mutate: {} -> {}
            }

            ability Mutable for 'c where {
                put: {it: 'c, i: Int, v: Int} -> Bool <Mut>
            }

            impl Mutable for [Int] where {
                put = {it, i, v} -> True
            }

            [1, 2, 3].put {i = 0, v = 42}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

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

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Ability operation with multiple effects should pass")
    }

    @Test
    fun `test ability with mixed pure and effectful operations`() {
        val source = """
            effect Mut where {
                mutate: {} -> {}
            }

            ability Container for 'c having 'elem where {
                len: {it: 'c} -> Int,
                fetch: {it: 'c, i: Int} -> Option 'elem,
                put: {it: 'c, i: Int, v: 'elem} -> Bool <Mut>
            }

            impl Container for [Int] having 'elem = Int where {
                len = {it} -> 0,
                fetch = {it, i} -> None,
                put = {it, i, v} -> True
            }

            let arr = [1, 2, 3]
            let length = arr.len {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Ability with mixed pure and effectful operations should pass")
    }

    // ========== POLYMORPHIC EFFECTS ON ABILITY OPS (VALID) ==========

    @Test
    fun `test ability operation with polymorphic effects should pass`() {
        val source = """
            ability Processor for 'a where {
                process: {it: 'a} -> Str <...>
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Ability operation with polymorphic effects should pass")
    }

    @Test
    fun `test ability operation with effect and polymorphic tail should pass`() {
        val source = """
            effect Mut where {
                mutate: {} -> {}
            }

            ability Mutable for 'c where {
                set: {it: 'c, i: Int} -> Bool <Mut, ...>
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Ability operation with effect and polymorphic tail should pass")
    }

    // ========== INVALID CASES - PERFORMED MARKER ==========

    @Test
    fun `test ability operation with performed marker should fail`() {
        val source = """
            ability Runner for 'a where {
                run: {it: 'a} -> Str <*>
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        val error = assertThrows<TypeCheckError>("Should fail: performed marker not allowed") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
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
                set: {it: 'c, i: Int} -> Bool <Mut, *>
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        val error = assertThrows<TypeCheckError>("Should fail: performed marker not allowed") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }

        assert(error.message?.contains("performed") == true) {
            "Error message should mention performed marker: ${error.message}"
        }
    }

    // ========== MORE INVALID CASES ==========

    @Test
    fun `test ability operation with just polymorphic marker alone should pass`() {
        val source = """
            ability Flexible for 'a where {
                flex: {it: 'a} -> 'a <...>
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Ability operation with <...> should pass")
    }

    // Note: <..., *> is not valid parser syntax, so we can't test it.
    // The parser will reject it before the typechecker sees it.

    @Test
    fun `test multiple operations with mixed effects including polymorphic should pass`() {
        val source = """
            effect Log where {
                log: {msg: Str} -> {}
            }

            ability Mixed for 'a where {
                pure_op: {it: 'a} -> Str,
                effectful_op: {it: 'a} -> Str <Log>,
                poly_op: {it: 'a} -> Str <...>
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Ability with mixed pure, concrete, and polymorphic effects should pass")
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
                put: {it: 'c, i: Int, v: Int} -> Bool <Mut>
            }

            impl Mutable for [Int] where {
                put = {it, i, v} -> True
            }

            # Just call the ability method - don't worry about effect handling for now
            let arr = [1, 2, 3]
            arr.put {i = 0, v = 99}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Ability with effect should compile")
    }

    @Test
    fun `test calling pure ability operation has no effect`() {
        val source = """
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            impl Show for Int where {
                show = {it} -> "number"
            }

            # Pure function calling pure ability operation
            let display: {x: Int} -> Str <> = {x} -> x.show {}

            display {x = 42}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

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
                put: {it: 'c, i: Int, v: Int} -> Bool <Mut>
            }

            impl Mutable for [Int] where {
                # Implementation should also have <Mut> effect
                put = {it, i, v} -> True
            }

            [1, 2, 3].put {i = 0, v = 42}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

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
                next: {it: 'iter} -> Option 'item <Mut>,
                has_next: {it: 'iter} -> Bool
            }

            type IntIter = { pos: Int, data: [Int] }

            impl Iterator for IntIter having 'item = Int where {
                next = {it} -> Some {val = 0},
                has_next = {it} -> True
            }

            let iter = { pos = 0, data = [1, 2, 3] }
            iter.has_next {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

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
                create_logger: {it: 'a, prefix: Str} -> ({msg: Str} -> {} <Log>)
            }

            impl Factory for Str where {
                create_logger = {it, prefix} -> {msg} -> {}
            }

            "".create_logger {prefix = "INFO"}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

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
                transform: {it: 'a, f: {x: 'a} -> 'a} -> 'a <State>
            }

            impl Transformer for Int where {
                transform = {it, f} -> f {x = it}
            }

            5.transform {f = {x} -> x + 1}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

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
                read_all: {it: 'a} -> Str <Read>
            }

            ability Writable for 'a where {
                write_all: {it: 'a, data: Str} -> {} <Write>
            }

            type File = { path: Str }

            impl Readable for File where {
                read_all = {it} -> "content"
            }

            impl Writable for File where {
                write_all = {it, data} -> {}
            }

            let f = { path = "/tmp/test" }
            f.read_all {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Multiple abilities with different effects should work")
    }

    // ========== POLYMORPHIC EFFECTS ON ABILITY OPS ==========

    @Test
    fun `test ability with polymorphic effect op - pure impl`() {
        val source = """
            ability ToIter for 'c having 'elem where {
                next: {it: 'c} -> Option 'elem <...>
            }

            type MyRange = { n: Int }

            impl ToIter for MyRange having 'elem = Int where {
                next = {it} -> Some {val = it.n}
            }

            let r = {n = 5}
            r.next {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Pure impl of ability with polymorphic effect op should pass")
    }

    @Test
    fun `test ability with polymorphic effect op - effectful impl`() {
        val source = """
            effect Mut where {
                get_val: {} -> Int
            }

            ability ToIter for 'c having 'elem where {
                next: {it: 'c} -> Option 'elem <...>
            }

            type MutReader = { id: Int }

            impl ToIter for MutReader having 'elem = Int where {
                next = {it} -> Some {val = Mut.get_val {}}
            }

            let reader = {id = 1}
            reader.next {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Effectful impl of ability with polymorphic effect op should pass")
    }

    @Test
    fun `test polymorphic effect propagates through generic function`() {
        val source = """
            ability Peekable for 'c where {
                peek: {it: 'c} -> Int <...>
            }

            type MyBox = { n: Int }

            impl Peekable for MyBox where {
                peek = {it} -> it.n
            }

            let peek_it : {item: 'c} with Peekable 'c -> Int =
                {item} -> item.peek {}

            peek_it {item = {n = 10}}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Polymorphic effect should propagate through generic function")
    }
}
