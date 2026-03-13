package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Exhaustive tests for the Mutation effect system.
 *
 * All tests use the public Mezze stdlib API via imports from std::effects::mutation.
 * This ensures tests verify real user-facing behavior rather than internal implementations.
 *
 * Tests cover:
 * 1. Mutation effect declaration and operations
 * 2. LocalMut performer
 * 3. DebugMut performer
 * 4. Mutable newtypes (MutSeq, MutMap, MutSet, MutBytes, MutInt, MutFloat, MutBool)
 * 5. Conversion functions (to_mut, to_immut)
 * 6. MutSeq operations (sort, reverse, fill, etc.)
 * 7. MutInt operations (read, write, incr, decr)
 * 8. Looping constructs (for_range, while)
 * 9. Effect propagation and handling
 * 10. Hermetic rule enforcement
 */
class MutationEffectTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    // ========================================================================
    // MUTATION EFFECT DECLARATION
    // ========================================================================

    @Test
    fun `test Mutation effect can be declared with mutate operation`() {
        val source = """
            effect Mutation where {
                mutate: {} -> {}
            }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test Mutation effect operation can be called`() {
        val source = """
            effect Mutation where {
                mutate: {} -> {}
            }

            let doMutate: {} -> {} <Mutation> = {} -> Mutation.mutate {}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    // ========================================================================
    // LOCALMUT PERFORMER
    // ========================================================================

    @Test
    fun `test LocalMut performer can be declared`() {
        val source = """
            effect Mutation where {
                mutate: {} -> {}
            }

            impl Mutation for LocalMut where {
                mutate = {} -> {}
            }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test LocalMut can handle Mutation effect`() {
        val source = """
            effect Mutation where {
                mutate: {} -> {}
            }

            impl Mutation for LocalMut where {
                mutate = {} -> {}
            }

            let test = {} -> perform LocalMut in Mutation.mutate {}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test LocalMut handles multiple mutate calls`() {
        val source = """
            effect Mutation where {
                mutate: {} -> {}
            }

            impl Mutation for LocalMut where {
                mutate = {} -> {}
            }

            let test = {} -> perform LocalMut in do
                Mutation.mutate {}
                Mutation.mutate {}
                Mutation.mutate {}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    // ========================================================================
    // DEBUGMUT PERFORMER (uses stdlib)
    // ========================================================================

    @Test
    fun `test DebugMut can handle Mutation effect`() {
        val source = """
            use std::effects::mutation::{Mutation, DebugMut}

            let test = {} -> perform DebugMut in Mutation.mutate {}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    // ========================================================================
    // MUTABLE NEWTYPES (via stdlib)
    // ========================================================================

    @Test
    fun `test MutSeq can be created from seq`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, MutSeq, seq_to_mut}

            let test = {} -> perform LocalMut in do
                let arr: MutSeq Int = seq_to_mut {seq = [1, 2, 3]}
                {}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test MutMap can be created from map`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, MutMap, map_to_mut}
            use std::collections::map::{empty}

            let test = {} -> perform LocalMut in do
                let m: MutMap String Int = map_to_mut {map = empty {}}
                {}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test MutSet can be created from set`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, MutSet, set_to_mut}
            use std::collections::set::{empty}

            let test = {} -> perform LocalMut in do
                let s: MutSet Int = set_to_mut {set = empty {}}
                {}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test MutInt can be created from int`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, MutInt, int_to_mut}

            let test = {} -> perform LocalMut in do
                let n: MutInt = int_to_mut {n = 42}
                {}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    // ========================================================================
    // CONVERSION FUNCTIONS - SEQ
    // ========================================================================

    @Test
    fun `test seq_to_mut has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, MutSeq, seq_to_mut}

            let convert: {xs: ['a]} -> MutSeq 'a = {xs} -> perform LocalMut in
                seq_to_mut {seq = xs}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test seq_to_immut has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, seq_to_mut, seq_to_immut}

            let round_trip: {xs: ['a]} -> ['a] = {xs} -> perform LocalMut in do
                let arr = seq_to_mut {seq = xs}
                seq_to_immut {seq = arr}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    // ========================================================================
    // CONVERSION FUNCTIONS - MAP
    // ========================================================================

    @Test
    fun `test map_to_mut has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, MutMap, map_to_mut}

            let convert: {m: Map 'k 'v} -> MutMap 'k 'v = {m} -> perform LocalMut in
                map_to_mut {map = m}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test map_to_immut has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, map_to_mut, map_to_immut}

            let round_trip: {m: Map 'k 'v} -> Map 'k 'v = {m} -> perform LocalMut in do
                let mm = map_to_mut {map = m}
                map_to_immut {map = mm}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    // ========================================================================
    // CONVERSION FUNCTIONS - SET
    // ========================================================================

    @Test
    fun `test set_to_mut has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, MutSet, set_to_mut}

            let convert: {s: Set 'a} -> MutSet 'a = {s} -> perform LocalMut in
                set_to_mut {set = s}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test set_to_immut has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, set_to_mut, set_to_immut}

            let round_trip: {s: Set 'a} -> Set 'a = {s} -> perform LocalMut in do
                let ms = set_to_mut {set = s}
                set_to_immut {set = ms}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    // ========================================================================
    // CONVERSION FUNCTIONS - BYTES
    // ========================================================================

    @Test
    fun `test bytes_to_mut has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, MutBytes, bytes_to_mut}

            let convert: {b: Bytes} -> MutBytes = {b} -> perform LocalMut in
                bytes_to_mut {bytes = b}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test bytes_to_immut has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, bytes_to_mut, bytes_to_immut}

            let round_trip: {b: Bytes} -> Bytes = {b} -> perform LocalMut in do
                let mb = bytes_to_mut {bytes = b}
                bytes_to_immut {bytes = mb}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    // ========================================================================
    // CONVERSION FUNCTIONS - PRIMITIVES
    // ========================================================================

    @Test
    fun `test int_to_mut has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, MutInt, int_to_mut}

            let convert: {n: Int} -> MutInt = {n} -> perform LocalMut in
                int_to_mut {n = n}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test int_to_immut has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, int_to_mut, read_int}

            let round_trip: {n: Int} -> Int = {n} -> perform LocalMut in do
                let mn = int_to_mut {n = n}
                read_int {n = mn}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test float_to_mut has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, MutFloat, float_to_mut}

            let convert: {n: Float} -> MutFloat = {n} -> perform LocalMut in
                float_to_mut {n = n}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test float_to_immut has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, float_to_mut, float_to_immut}

            let round_trip: {n: Float} -> Float = {n} -> perform LocalMut in do
                let mn = float_to_mut {n = n}
                float_to_immut {n = mn}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test bool_to_mut has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, MutBool, bool_to_mut}

            let convert: {b: Bool} -> MutBool = {b} -> perform LocalMut in
                bool_to_mut {b = b}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test bool_to_immut has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, bool_to_mut, bool_to_immut}

            let round_trip: {b: Bool} -> Bool = {b} -> perform LocalMut in do
                let mb = bool_to_mut {b = b}
                bool_to_immut {b = mb}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    // ========================================================================
    // MUTSEQ OPERATIONS
    // ========================================================================

    @Test
    fun `test sort has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, seq_to_mut, seq_to_immut, sort}

            let sort_ints: {xs: [Int]} -> [Int] = {xs} -> perform LocalMut in do
                let arr = seq_to_mut {seq = xs}
                sort {seq = arr}
                seq_to_immut {seq = arr}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test sort_by has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, seq_to_mut, seq_to_immut, sort_by}

            let sort_descending: {xs: [Int]} -> [Int] = {xs} -> perform LocalMut in do
                let arr = seq_to_mut {seq = xs}
                sort_by {seq = arr, cmp = {a, b} -> b - a}
                seq_to_immut {seq = arr}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test reverse has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, seq_to_mut, seq_to_immut, reverse}

            let reverse_seq: {xs: ['a]} -> ['a] = {xs} -> perform LocalMut in do
                let arr = seq_to_mut {seq = xs}
                reverse {seq = arr}
                seq_to_immut {seq = arr}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test fill has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, seq_to_mut, seq_to_immut, fill}

            let fill_with: {xs: ['a], v: 'a} -> ['a] = {xs, v} -> perform LocalMut in do
                let arr = seq_to_mut {seq = xs}
                fill {seq = arr, v = v}
                seq_to_immut {seq = arr}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test fill_range has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, seq_to_mut, seq_to_immut, fill_range}

            let fill_partial: {xs: ['a], start: Int, end: Int, v: 'a} -> ['a] = {xs, start, end, v} -> perform LocalMut in do
                let arr = seq_to_mut {seq = xs}
                fill_range {seq = arr, start = start, end = end, v = v}
                seq_to_immut {seq = arr}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    // ========================================================================
    // MUTINT OPERATIONS
    // ========================================================================

    @Test
    fun `test read_int has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, int_to_mut, read_int}

            let get_value: {n: Int} -> Int = {n} -> perform LocalMut in do
                let mn = int_to_mut {n = n}
                read_int {n = mn}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test write_int has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, int_to_mut, write_int, read_int}

            let set_value: {initial: Int, new_val: Int} -> Int = {initial, new_val} -> perform LocalMut in do
                let mn = int_to_mut {n = initial}
                write_int {n = mn, v = new_val}
                read_int {n = mn}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test incr has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, int_to_mut, incr, read_int}

            let increment: {n: Int, delta: Int} -> Int = {n, delta} -> perform LocalMut in do
                let mn = int_to_mut {n = n}
                incr {n = mn, delta = delta}
                read_int {n = mn}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test decr has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, int_to_mut, decr, read_int}

            let decrement: {n: Int, delta: Int} -> Int = {n, delta} -> perform LocalMut in do
                let mn = int_to_mut {n = n}
                decr {n = mn, delta = delta}
                read_int {n = mn}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    // ========================================================================
    // LOOPING CONSTRUCTS
    // ========================================================================

    @Test
    fun `test for_range has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, for_range, int_to_mut, incr, read_int}

            let sum_range: {start: Int, end: Int} -> Int = {start, end} -> perform LocalMut in do
                let acc = int_to_mut {n = 0}
                for_range {
                    start = start,
                    end = end,
                    body = {i} -> incr {n = acc, delta = i}
                }
                read_int {n = acc}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test while has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, while, int_to_mut, incr, read_int}

            let count_to: {target: Int} -> Int = {target} -> perform LocalMut in do
                let counter = int_to_mut {n = 0}
                while {
                    cond = {} -> read_int {n = counter} < target,
                    body = {} -> incr {n = counter, delta = 1}
                }
                read_int {n = counter}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test for_range can be used with mutation body`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, for_range, int_to_mut, incr, read_int}

            let test = {} -> perform LocalMut in do
                let sum = int_to_mut {n = 0}
                for_range {
                    start = 0,
                    end = 10,
                    body = {i} -> incr {n = sum, delta = i}
                }
                read_int {n = sum}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    // ========================================================================
    // CAPACITY CONSTRUCTORS
    // ========================================================================

    @Test
    fun `test seq_with_capacity has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, MutSeq, seq_with_capacity}

            let create_seq: {cap: Int} -> MutSeq Int = {cap} -> perform LocalMut in
                seq_with_capacity {capacity = cap}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test map_with_capacity has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, MutMap, map_with_capacity}

            let create_map: {cap: Int} -> MutMap String Int = {cap} -> perform LocalMut in
                map_with_capacity {capacity = cap}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test set_with_capacity has correct type signature`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, MutSet, set_with_capacity}

            let create_set: {cap: Int} -> MutSet Int = {cap} -> perform LocalMut in
                set_with_capacity {capacity = cap}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    // ========================================================================
    // EFFECT PROPAGATION AND HANDLING
    // ========================================================================

    @Test
    fun `test Mutation effect propagates through function calls`() {
        val source = """
            effect Mutation where {
                mutate: {} -> {}
            }

            let inner: {} -> {} <Mutation> = {} -> Mutation.mutate {}
            let outer: {} -> {} <Mutation> = {} -> inner {}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test Mutation effect handled by perform removes effect`() {
        val source = """
            effect Mutation where {
                mutate: {} -> {}
            }

            impl Mutation for LocalMut where {
                mutate = {} -> {}
            }

            # This function has <Mutation> effect
            let mutating: {} -> {} <Mutation> = {} -> Mutation.mutate {}

            # After perform, the effect is handled - result is pure
            let pure_result: {} -> {} = {} -> perform LocalMut in mutating {}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test nested perform with LocalMut`() {
        val source = """
            effect Mutation where {
                mutate: {} -> {}
            }

            impl Mutation for LocalMut where {
                mutate = {} -> {}
            }

            let test = {} -> perform LocalMut in do
                Mutation.mutate {}
                perform LocalMut in do
                    Mutation.mutate {}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test complete mutation workflow - convert mutate convert back`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, seq_to_mut, seq_to_immut, reverse}

            let reverse_seq: {seq: ['a]} -> ['a] = {seq} -> perform LocalMut in do
                let mut_seq = seq_to_mut {seq = seq}
                reverse {seq = mut_seq}
                seq_to_immut {seq = mut_seq}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    // ========================================================================
    // ERROR CASES
    // ========================================================================

    @Test
    fun `test assigning effectful function to pure type causes error`() {
        val source = """
            effect Mutation where {
                mutate: {} -> {}
            }

            # Function has <Mutation> effect
            let effectful: {} -> {} <Mutation> = {} -> Mutation.mutate {}

            # Trying to assign effectful function to pure function type should fail
            let pure_fn: {} -> {} = effectful
        """.trimIndent()

        val program = parser.parse(source)
        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program)
        }
    }

    @Test
    fun `test wrong performer type causes error`() {
        val source = """
            effect Mutation where {
                mutate: {} -> {}
            }

            effect OtherEffect where {
                other: {} -> {}
            }

            impl OtherEffect for OtherPerformer where {
                other = {} -> {}
            }

            # Using wrong performer for Mutation should fail
            let test = {} -> perform OtherPerformer in Mutation.mutate {}
        """.trimIndent()

        val program = parser.parse(source)
        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program)
        }
    }

    @Test
    fun `test calling mutate without effect in signature causes error`() {
        val source = """
            effect Mutation where {
                mutate: {} -> {}
            }

            # Function claims to be pure but calls effectful operation
            let bad_fn: {} -> {} = {} -> Mutation.mutate {}
        """.trimIndent()

        val program = parser.parse(source)
        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program)
        }
    }

    // ========================================================================
    // HERMETIC RULE TESTS - Mutation cannot coexist with other effects
    // ========================================================================

    @Test
    fun `test validateHermeticEffects directly`() {
        // Direct test of the validation function
        val error = assertThrows<TypeCheckError> {
            com.mezze.language.typechecker.context.ContextBuilder.validateHermeticEffects(setOf("Mutation", "IO"))
        }
        assertTrue(error.message?.contains("hermetic") == true,
            "Expected hermetic error but got: ${error.message}")
    }

    @Test
    fun `test Mutation cannot coexist with IO effect`() {
        val source = """
            effect Mutation where {
                mutate: {} -> {}
            }

            effect IO where {
                read: {} -> String
            }

            # This should fail - Mutation is hermetic and cannot mix with IO
            let bad_fn: {} -> {} <Mutation, IO> = {} -> do
                Mutation.mutate {}
                IO.read {}
                {}
        """.trimIndent()

        val program = parser.parse(source)
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program)
        }
        assertTrue(error.message?.contains("hermetic") == true || error.message?.contains("Mutation") == true,
            "Expected hermetic error but got: ${error.message}")
    }

    @Test
    fun `test Mutation cannot coexist with State effect`() {
        val source = """
            effect Mutation where {
                mutate: {} -> {}
            }

            effect State where {
                get: {} -> Int,
                put: {v: Int} -> {}
            }

            # This should fail - Mutation cannot coexist with State
            let bad_fn: {} -> Int <Mutation, State> = {} -> do
                Mutation.mutate {}
                State.get {}
        """.trimIndent()

        val program = parser.parse(source)
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program)
        }
        assertTrue(error.message?.contains("hermetic") == true || error.message?.contains("Mutation") == true,
            "Expected hermetic error but got: ${error.message}")
    }

    @Test
    fun `test Mutation alone is valid`() {
        val source = """
            effect Mutation where {
                mutate: {} -> {}
            }

            impl Mutation for LocalMut where {
                mutate = {} -> {}
            }

            # This is fine - Mutation alone
            let good_fn: {} -> {} <Mutation> = {} -> Mutation.mutate {}

            # This is also fine - handled by performer
            let handled_fn: {} -> {} = {} -> perform LocalMut in Mutation.mutate {}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test nested perform handles Mutation hermetically`() {
        val source = """
            effect Mutation where {
                mutate: {} -> {}
            }

            effect IO where {
                print: {msg: String} -> {}
            }

            impl Mutation for LocalMut where {
                mutate = {} -> {}
            }

            impl IO for Console where {
                print = {msg} -> {}
            }

            # This should work: Mutation is fully handled before IO
            let sequential_fn: {} -> {} = {} -> do
                # First handle mutation completely
                perform LocalMut in Mutation.mutate {}
                # Then do IO separately
                perform Console in IO.print {msg = "done"}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    // ========================================================================
    // IMPORT AND USE FROM STDLIB
    // ========================================================================

    @Test
    fun `test importing mutation module`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, seq_to_mut, seq_to_immut}

            let test = {} -> perform LocalMut in do
                let arr = seq_to_mut {seq = [1, 2, 3]}
                seq_to_immut {seq = arr}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test using MutSeq operations from stdlib`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, seq_to_mut, seq_to_immut, reverse}

            let reverse_array: {xs: ['a]} -> ['a] = {xs} -> perform LocalMut in do
                let arr = seq_to_mut {seq = xs}
                reverse {seq = arr}
                seq_to_immut {seq = arr}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test using for_range from stdlib`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, for_range, int_to_mut, read_int, incr}

            let sum_to_n: {n: Int} -> Int = {n} -> perform LocalMut in do
                let acc = int_to_mut {n = 0}
                for_range {
                    start = 1,
                    end = n + 1,
                    body = {i} -> incr {n = acc, delta = i}
                }
                read_int {n = acc}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test using while from stdlib`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, while, int_to_mut, read_int, incr}

            let count_to: {target: Int} -> Int = {target} -> perform LocalMut in do
                let counter = int_to_mut {n = 0}
                while {
                    cond = {} -> read_int {n = counter} < target,
                    body = {} -> incr {n = counter, delta = 1}
                }
                read_int {n = counter}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test using sort from stdlib`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, seq_to_mut, seq_to_immut, sort}

            let sort_ints: {xs: [Int]} -> [Int] = {xs} -> perform LocalMut in do
                let arr = seq_to_mut {seq = xs}
                sort {seq = arr}
                seq_to_immut {seq = arr}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test using sort_by from stdlib`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, seq_to_mut, seq_to_immut, sort_by}

            let sort_descending: {xs: [Int]} -> [Int] = {xs} -> perform LocalMut in do
                let arr = seq_to_mut {seq = xs}
                sort_by {seq = arr, cmp = {a, b} -> b - a}
                seq_to_immut {seq = arr}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test using DebugMut from stdlib`() {
        val source = """
            use std::effects::mutation::{Mutation, DebugMut, seq_to_mut, seq_to_immut}

            let test = {} -> perform DebugMut in do
                let arr = seq_to_mut {seq = [1, 2, 3]}
                seq_to_immut {seq = arr}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test using fill from stdlib`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, seq_to_mut, seq_to_immut, fill}

            let fill_with_zeros: {xs: [Int]} -> [Int] = {xs} -> perform LocalMut in do
                let arr = seq_to_mut {seq = xs}
                fill {seq = arr, v = 0}
                seq_to_immut {seq = arr}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test using map conversions from stdlib`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, map_to_mut, map_to_immut}
            use std::collections::map

            let process_map: {m: Map String Int} -> Map String Int = {m} -> perform LocalMut in do
                let mut_m = map_to_mut {map = m}
                map_to_immut {map = mut_m}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test using set conversions from stdlib`() {
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, set_to_mut, set_to_immut}
            use std::collections::set

            let process_set: {s: Set Int} -> Set Int = {s} -> perform LocalMut in do
                let mut_s = set_to_mut {set = s}
                set_to_immut {set = mut_s}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    // ========================================================================
    // USER-DEFINED NEWTYPES WITH MUTATION
    // ========================================================================

    @Test
    fun `test user-defined newtype wrapping record with MutInt`() {
        // User defines a newtype that wraps a record containing MutInt
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, MutInt, int_to_mut, read_int, incr}

            # User-defined newtype wrapping a record with a mutable field
            newtype Counter = Counter { count: MutInt }

            # Construct using record field names directly
            let make_counter = {n} -> Counter {count = int_to_mut {n = n}}

            # Unwrap with match
            let get = {c: Counter} -> match c is
                Counter {count = m} -> read_int {n = m}

            let tick = {c: Counter} -> match c is
                Counter {count = m} -> incr {n = m, delta = 1}

            let test = {} -> perform LocalMut in do
                let c = make_counter {n = 0}
                tick {c = c}
                tick {c = c}
                get {c = c}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test user-defined newtype wrapping record with MutSeq`() {
        // User defines a newtype that wraps a record containing MutSeq
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, MutSeq, seq_to_mut, seq_to_immut, sort}

            # User-defined buffer newtype wrapping a record
            newtype Buffer 'a = Buffer { data: MutSeq 'a }

            let make_buffer = {xs} -> Buffer {data = seq_to_mut {seq = xs}}

            let freeze = {buf: Buffer Int} -> match buf is
                Buffer {data = s} -> seq_to_immut {seq = s}

            let sort_buffer = {buf: Buffer Int} -> match buf is
                Buffer {data = s} -> sort {seq = s}

            let test = {} -> perform LocalMut in do
                let buf = make_buffer {xs = [3, 1, 2]}
                sort_buffer {buf = buf}
                freeze {buf = buf}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test using stdlib MutInt newtype in function signatures`() {
        // User can use the stdlib-provided MutInt newtype in their own functions
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, MutInt, int_to_mut, read_int, incr}

            # User function that takes MutInt and modifies it
            let double_incr: {n: MutInt} -> {} <Mutation> = {n} -> do
                incr {n = n, delta = 1}
                incr {n = n, delta = 1}

            let test = {} -> perform LocalMut in do
                let counter = int_to_mut {n = 0}
                double_incr {n = counter}
                double_incr {n = counter}
                read_int {n = counter}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test using stdlib MutSeq newtype in function signatures`() {
        // User can use the stdlib-provided MutSeq newtype in their own functions
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, MutSeq, seq_to_mut, seq_to_immut, sort, reverse}

            # User function that operates on MutSeq
            let sort_and_reverse: {arr: MutSeq Int} -> {} <Mutation> = {arr} -> do
                sort {seq = arr}
                reverse {seq = arr}

            let test = {} -> perform LocalMut in do
                let arr = seq_to_mut {seq = [3, 1, 4, 1, 5]}
                sort_and_reverse {arr = arr}
                seq_to_immut {seq = arr}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test record containing mutable fields`() {
        // User can create records that contain mutable fields
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, MutInt, int_to_mut, read_int, incr}

            # A record with mutable coordinates
            let make_point: {x: Int, y: Int} -> {x: MutInt, y: MutInt} <Mutation> = {x, y} ->
                {x = int_to_mut {n = x}, y = int_to_mut {n = y}}

            let move_x: {p: {x: MutInt, y: MutInt}, delta: Int} -> {} <Mutation> = {p, delta} ->
                incr {n = p.x, delta = delta}

            let get_x: {p: {x: MutInt, y: MutInt}} -> Int <Mutation> = {p} ->
                read_int {n = p.x}

            let test = {} -> perform LocalMut in do
                let p = make_point {x = 10, y = 20}
                move_x {p = p, delta = 5}
                get_x {p = p}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    // ========================================================================
    // TYPE ALIASES WITH MUTATION
    // ========================================================================

    @Test
    fun `test type alias for record with mutable fields`() {
        // Type alias for a record containing mutable data
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, MutInt, int_to_mut, read_int, incr}

            # Type alias for a mutable counter record
            type MutCounterRec = {value: MutInt, name: String}

            let make_counter: {name: String} -> MutCounterRec <Mutation> = {name} ->
                {value = int_to_mut {n = 0}, name = name}

            let increment: {c: MutCounterRec} -> {} <Mutation> = {c} ->
                incr {n = c.value, delta = 1}

            let get_value: {c: MutCounterRec} -> Int <Mutation> = {c} ->
                read_int {n = c.value}

            let test = {} -> perform LocalMut in do
                let c = make_counter {name = "my counter"}
                increment {c = c}
                increment {c = c}
                get_value {c = c}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test type alias variant with mutable payload`() {
        // Type alias for a variant that can hold mutable data
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, MutInt, int_to_mut, read_int, incr}

            # Type alias for a variant - mutable or immutable counter
            type CounterState = | Frozen { value: Int }, Active { value: MutInt } |

            let freeze_counter: {state: CounterState} -> Int <Mutation> = {state} ->
                match state is
                    Frozen { value } -> value
                    Active { value } -> read_int {n = value}

            let test = {} -> perform LocalMut in do
                let active = Active {value = int_to_mut {n = 5}}
                let frozen = Frozen {value = 10}
                freeze_counter {state = active}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test using MutSeq directly without alias`() {
        // Using MutSeq directly in function signatures
        val source = """
            use std::effects::mutation::{Mutation, LocalMut, MutSeq, seq_to_mut, seq_to_immut, sort}

            let sort_array: {arr: MutSeq Int} -> {} <Mutation> = {arr} ->
                sort {seq = arr}

            let test = {} -> perform LocalMut in do
                let arr: MutSeq Int = seq_to_mut {seq = [3, 1, 2]}
                sort_array {arr = arr}
                seq_to_immut {seq = arr}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    // ========================================================================
    // CUSTOM PERFORMER FOR MUTATION
    // ========================================================================

    @Test
    fun `test user can define custom performer for Mutation`() {
        // User defines their own performer (like DebugMut or LocalMut)
        val source = """
            use std::effects::mutation::{Mutation, seq_to_mut, seq_to_immut}

            # User-defined custom performer
            impl Mutation for MyMut where {
                mutate = {} -> {}
            }

            let test = {} -> perform MyMut in do
                let arr = seq_to_mut {seq = [1, 2, 3]}
                seq_to_immut {seq = arr}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    @Test
    fun `test user performer with logging via nested effect`() {
        // User creates a performer that logs before mutating
        // Note: This works because the effects are handled separately
        val source = """
            effect Mutation where {
                mutate: {} -> {}
            }

            effect Log where {
                log: {msg: String} -> {}
            }

            impl Mutation for LocalMut where {
                mutate = {} -> {}
            }

            impl Log for Console where {
                log = {msg} -> {}
            }

            # A function that uses Mutation
            let do_mutation: {} -> {} <Mutation> = {} -> Mutation.mutate {}

            # Run with both effects handled separately (not mixed!)
            let test = {} -> do
                perform LocalMut in do_mutation {}
                perform Console in Log.log {msg = "done"}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }
}
