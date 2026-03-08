package com.mezze.interpreter.v2;

import com.mezze.interpreter.v2.values.MezzeRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SeededRandom effect determinism.
 *
 * Verifies that:
 * 1. Same seed produces same sequence
 * 2. Different seeds produce different sequences
 * 3. Multiple perform blocks with same seed produce identical sequences
 */
public class SeededRandomTest {

    @Test
    void sameSeedProducesSameSequence() {
        String code = """
            use std::effects::random::{Random, SeededRandom, random_int}

            let get_two_randoms = {} ->
                perform SeededRandom {seed = 42} in do
                    let a = random_int {min = 1, max = 100}
                    let b = random_int {min = 1, max = 100}
                    {a = a, b = b}

            let test = {} -> do
                let first = get_two_randoms {}
                let second = get_two_randoms {}
                { first_a = first.a, first_b = first.b, second_a = second.a, second_b = second.b }

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        MezzeRecord record = MezzeTestHelper.asRecord(result);

        long firstA = MezzeTestHelper.asLong(record.getField("first_a"));
        long firstB = MezzeTestHelper.asLong(record.getField("first_b"));
        long secondA = MezzeTestHelper.asLong(record.getField("second_a"));
        long secondB = MezzeTestHelper.asLong(record.getField("second_b"));

        // Same seed should produce same sequence
        assertEquals(firstA, secondA, "First random value should be identical for same seed");
        assertEquals(firstB, secondB, "Second random value should be identical for same seed");
    }

    @Test
    void differentSeedsProduceDifferentSequences() {
        String code = """
            use std::effects::random::{Random, SeededRandom, random_int}

            let test = {} -> do
                let first = perform SeededRandom {seed = 42} in random_int {min = 1, max = 1000000}
                let second = perform SeededRandom {seed = 99} in random_int {min = 1, max = 1000000}
                { first = first, second = second }

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        MezzeRecord record = MezzeTestHelper.asRecord(result);

        long first = MezzeTestHelper.asLong(record.getField("first"));
        long second = MezzeTestHelper.asLong(record.getField("second"));

        // Different seeds should produce different values (with high probability)
        assertNotEquals(first, second, "Different seeds should produce different values");
    }

    @Test
    void sequentialPerformBlocksWithSameSeedAreIdentical() {
        String code = """
            use std::effects::random::{Random, SeededRandom, random_int}

            let run_with_seed = {seed} ->
                perform SeededRandom {seed = seed} in do
                    let a = random_int {min = 0, max = 999}
                    let b = random_int {min = 0, max = 999}
                    let c = random_int {min = 0, max = 999}
                    {a = a, b = b, c = c}

            let test = {} -> do
                let result1 = run_with_seed {seed = 123}
                let result2 = run_with_seed {seed = 123}
                {
                    r1_a = result1.a, r1_b = result1.b, r1_c = result1.c,
                    r2_a = result2.a, r2_b = result2.b, r2_c = result2.c
                }

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        MezzeRecord record = MezzeTestHelper.asRecord(result);

        // Extract all values
        long r1a = MezzeTestHelper.asLong(record.getField("r1_a"));
        long r1b = MezzeTestHelper.asLong(record.getField("r1_b"));
        long r1c = MezzeTestHelper.asLong(record.getField("r1_c"));
        long r2a = MezzeTestHelper.asLong(record.getField("r2_a"));
        long r2b = MezzeTestHelper.asLong(record.getField("r2_b"));
        long r2c = MezzeTestHelper.asLong(record.getField("r2_c"));

        // All corresponding values should be equal
        assertEquals(r1a, r2a, "First value in sequence should match");
        assertEquals(r1b, r2b, "Second value in sequence should match");
        assertEquals(r1c, r2c, "Third value in sequence should match");
    }

    @Test
    void nestedPerformBlocksWithDifferentSeeds() {
        String code = """
            use std::effects::random::{Random, SeededRandom, random_int}

            let run_nested = {} ->
                perform SeededRandom {seed = 1} in do
                    let inner = perform SeededRandom {seed = 2} in random_int {min = 1, max = 100}
                    let outer_val = random_int {min = 1, max = 100}
                    {inner = inner, outer = outer_val}

            let test = {} -> do
                let outer_a = run_nested {}
                let outer_b = run_nested {}
                {
                    a_inner = outer_a.inner, a_outer = outer_a.outer,
                    b_inner = outer_b.inner, b_outer = outer_b.outer
                }

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        MezzeRecord record = MezzeTestHelper.asRecord(result);

        long aInner = MezzeTestHelper.asLong(record.getField("a_inner"));
        long aOuter = MezzeTestHelper.asLong(record.getField("a_outer"));
        long bInner = MezzeTestHelper.asLong(record.getField("b_inner"));
        long bOuter = MezzeTestHelper.asLong(record.getField("b_outer"));

        // Both runs should produce identical results
        assertEquals(aInner, bInner, "Inner values should match across runs");
        assertEquals(aOuter, bOuter, "Outer values should match across runs");
    }

    @Test
    void randomFloatIsDeterministic() {
        String code = """
            use std::effects::random::{Random, SeededRandom, random_float}

            let test = {} -> do
                let f1 = perform SeededRandom {seed = 777} in random_float {}
                let f2 = perform SeededRandom {seed = 777} in random_float {}
                {f1 = f1, f2 = f2, equal = f1 == f2}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        MezzeRecord record = MezzeTestHelper.asRecord(result);

        double f1 = MezzeTestHelper.asDouble(record.getField("f1"));
        double f2 = MezzeTestHelper.asDouble(record.getField("f2"));
        boolean equal = MezzeTestHelper.asBoolean(record.getField("equal"));

        assertEquals(f1, f2, 0.0001, "Same seed should produce same float");
        assertTrue(equal, "Equality check should be true");
    }
}
