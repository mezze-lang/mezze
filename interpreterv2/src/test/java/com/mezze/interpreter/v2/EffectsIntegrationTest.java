package com.mezze.interpreter.v2;

import com.mezze.interpreter.v2.values.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for various effects in V2 interpreter.
 *
 * Note: Continuation-based effects (Exception, Choice, Generator) have known issues
 * with the V2 continuation primitives. These tests are disabled pending investigation.
 *
 * Run with: ./gradlew :interpreterv2:test --tests "*EffectsIntegrationTest*"
 */
public class EffectsIntegrationTest {

    // ========================
    // Exception Effect Tests (Continuation-based - disabled)
    // ========================

    @Disabled("Continuation-based effects need investigation in V2")
    @Test
    void testExceptionThrowAndCatch() {
        Object result = MezzeTestHelper.run("""
            use std::effects::exception::{Exception, ToResult, throw}
            use std::control::continuation

            let computation = {} -> do
                let x = 10
                match x > 5 is
                    True -> Exception.throw {error = "too big"}
                    False -> x

            perform ToResult {cont = continuation::new {}} in
                computation {}
            """);

        // Should be Err variant
        assertTrue(result instanceof MezzeVariant);
        MezzeVariant variant = (MezzeVariant) result;
        assertEquals("Err", variant.getConstructorName());
    }

    @Disabled("Continuation-based effects need investigation in V2")
    @Test
    void testExceptionToResultSuccess() {
        Object result = MezzeTestHelper.run("""
            use std::effects::exception::{Exception, ToResult}
            use std::control::continuation

            let computation = {} -> do
                let x = 3
                match x > 5 is
                    True -> Exception.throw {error = "too big"}
                    False -> x

            perform ToResult {cont = continuation::new {}} in
                computation {}
            """);

        // Should be Ok variant with value 3
        assertTrue(result instanceof MezzeVariant);
        MezzeVariant variant = (MezzeVariant) result;
        assertEquals("Ok", variant.getConstructorName());
    }

    @Disabled("Continuation-based effects need investigation in V2")
    @Test
    void testExceptionUnwrapSuccess() {
        Object result = MezzeTestHelper.run("""
            use std::effects::exception::{Exception, ToResult, unwrap}
            use std::control::continuation

            let good_result = Ok {val = 42}

            perform ToResult {cont = continuation::new {}} in
                unwrap {result = good_result}
            """);

        assertTrue(result instanceof MezzeVariant);
        MezzeVariant variant = (MezzeVariant) result;
        assertEquals("Ok", variant.getConstructorName());
    }

    @Disabled("Continuation-based effects need investigation in V2")
    @Test
    void testExceptionUnwrapFailure() {
        Object result = MezzeTestHelper.run("""
            use std::effects::exception::{Exception, ToResult, unwrap}
            use std::control::continuation

            let bad_result = Err {error = "something went wrong"}

            perform ToResult {cont = continuation::new {}} in
                unwrap {result = bad_result}
            """);

        assertTrue(result instanceof MezzeVariant);
        MezzeVariant variant = (MezzeVariant) result;
        assertEquals("Err", variant.getConstructorName());
    }

    // ========================
    // Choice Effect Tests (Continuation-based - disabled)
    // ========================

    @Disabled("Continuation-based effects need investigation in V2")
    @Test
    void testChoiceSimple() {
        Object result = MezzeTestHelper.run("""
            use std::effects::choice::{Choice, AllPaths}
            use std::control::continuation

            perform AllPaths {cont = continuation::new {}} in do
                let x = Choice.choose {options = [1, 2, 3]}
                x * 2
            """);

        // Should return array [2, 4, 6]
        assertTrue(result instanceof MezzeList);
        MezzeList list = (MezzeList) result;
        assertEquals(3, list.size());
    }

    @Disabled("Continuation-based effects need investigation in V2")
    @Test
    void testChoiceWithFail() {
        Object result = MezzeTestHelper.run("""
            use std::effects::choice::{Choice, AllPaths}
            use std::control::continuation

            perform AllPaths {cont = continuation::new {}} in do
                let x = Choice.choose {options = [1, 2, 3, 4, 5]}
                match x % 2 == 0 is
                    True -> x
                    False -> Choice.fail {}
            """);

        // Should return only even numbers [2, 4]
        assertTrue(result instanceof MezzeList);
        MezzeList list = (MezzeList) result;
        assertEquals(2, list.size());
    }

    @Disabled("Continuation-based effects need investigation in V2")
    @Test
    void testChoiceMultiple() {
        Object result = MezzeTestHelper.run("""
            use std::effects::choice::{Choice, AllPaths}
            use std::control::continuation

            perform AllPaths {cont = continuation::new {}} in do
                let a = Choice.choose {options = [1, 2]}
                let b = Choice.choose {options = [10, 20]}
                a + b
            """);

        // Should return [11, 21, 12, 22] - all combinations
        assertTrue(result instanceof MezzeList);
        MezzeList list = (MezzeList) result;
        assertEquals(4, list.size());
    }

    @Disabled("Continuation-based effects need investigation in V2")
    @Test
    void testRunAllPaths() {
        Object result = MezzeTestHelper.run("""
            use std::effects::choice::{Choice, run_all_paths}

            run_all_paths {
                computation = {} -> do
                    let x = Choice.choose {options = [1, 2, 3]}
                    x * x
            }
            """);

        // Should return [1, 4, 9]
        assertTrue(result instanceof MezzeList);
        MezzeList list = (MezzeList) result;
        assertEquals(3, list.size());
    }

    // ========================
    // SeededRandom Effect Tests (Scoped Effect)
    // ========================

    @Test
    void testSeededRandomDeterministic() {
        // Run twice with same seed, should get same results
        Object result1 = MezzeTestHelper.run("""
            use std::effects::random::{Random, SeededRandom}

            perform SeededRandom {seed = 12345} in do
                let a = Random.random_int {min = 0, max = 100}
                let b = Random.random_int {min = 0, max = 100}
                {a = a, b = b}
            """);

        Object result2 = MezzeTestHelper.run("""
            use std::effects::random::{Random, SeededRandom}

            perform SeededRandom {seed = 12345} in do
                let a = Random.random_int {min = 0, max = 100}
                let b = Random.random_int {min = 0, max = 100}
                {a = a, b = b}
            """);

        assertTrue(result1 instanceof MezzeRecord);
        assertTrue(result2 instanceof MezzeRecord);
        MezzeRecord r1 = (MezzeRecord) result1;
        MezzeRecord r2 = (MezzeRecord) result2;
        assertEquals(r1.getField("a"), r2.getField("a"));
        assertEquals(r1.getField("b"), r2.getField("b"));
    }

    @Test
    void testSeededRandomDifferentSeeds() {
        // Different seeds should give different results (with high probability)
        Object result1 = MezzeTestHelper.run("""
            use std::effects::random::{Random, SeededRandom}

            perform SeededRandom {seed = 111} in
                Random.random_int {min = 0, max = 1000000}
            """);

        Object result2 = MezzeTestHelper.run("""
            use std::effects::random::{Random, SeededRandom}

            perform SeededRandom {seed = 222} in
                Random.random_int {min = 0, max = 1000000}
            """);

        // Very unlikely to be equal with different seeds and large range
        assertNotEquals(result1, result2);
    }

    // ========================
    // Log Effect Tests
    // ========================

    @Test
    void testLogEffect() {
        // Just verify it doesn't crash - log output goes to console
        Object result = MezzeTestHelper.run("""
            use std::effects::log::{Log, Console}

            perform Console in do
                Log.log {level = Info, msg = "Test message"}
                42
            """);

        assertEquals(42L, result);
    }

    // ========================
    // Time Effect Tests
    // ========================

    @Test
    void testTimeNow() {
        Object result = MezzeTestHelper.run("""
            use std::effects::time::{Time, SystemTime}

            perform SystemTime in do
                let now = Time.now {}
                now > 0
            """);

        assertEquals(true, result);
    }

    @Test
    void testMockTime() {
        Object result = MezzeTestHelper.run("""
            use std::effects::time::{Time, MockTime}

            perform MockTime {fixed_time = 1234567890000} in do
                Time.now {}
            """);

        assertEquals(1234567890000L, result);
    }
}
