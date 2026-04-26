package com.mezze.interpreter;

import com.mezze.interpreter.values.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for various effects in V2 interpreter.
 *
 * Tests continuation-based effects (Exception) as well as
 * scoped effects (SeededRandom, Log, Time).
 *
 * All handlers use scoped continuations internally - no need to pass
 * continuations as parameters.
 *
 * Run with: ./gradlew :interpreter:test --tests "*EffectsIntegrationTest*"
 */
public class EffectsIntegrationTest {

    // ========================
    // Exception Effect Tests
    // ========================

    @Test
    void testExceptionThrowAndCatch() {
        Object result = MezzeTestHelper.run("""
            use std::effects::exception::{Exception, ExceptionToResult, throw}

            let computation = {} -> do
                let x = 10
                match x > 5 is
                    True -> Exception.throw {error = "too big"}
                    False -> Ok {val = x}

            perform ExceptionToResult in
                computation {}
            """);

        // Should be Err variant
        assertTrue(result instanceof MezzeVariant);
        MezzeVariant variant = (MezzeVariant) result;
        assertEquals("Err", variant.getConstructorName());
    }

    @Test
    void testExceptionToResultSuccess() {
        Object result = MezzeTestHelper.run("""
            use std::effects::exception::{Exception, ExceptionToResult}

            let computation = {} -> do
                let x = 3
                match x > 5 is
                    True -> Exception.throw {error = "too big"}
                    False -> Ok {val = x}

            perform ExceptionToResult in
                computation {}
            """);

        // Should be Ok variant with value 3
        assertTrue(result instanceof MezzeVariant);
        MezzeVariant variant = (MezzeVariant) result;
        assertEquals("Ok", variant.getConstructorName());
    }

    @Test
    void testExceptionUnwrapSuccess() {
        Object result = MezzeTestHelper.run("""
            use std::effects::exception::{Exception, ExceptionToResult, unwrap}

            let good_result = Ok {val = 42}

            perform ExceptionToResult in
                Ok {val = unwrap {result = good_result}}
            """);

        assertTrue(result instanceof MezzeVariant);
        MezzeVariant variant = (MezzeVariant) result;
        assertEquals("Ok", variant.getConstructorName());
    }

    @Test
    void testExceptionUnwrapFailure() {
        Object result = MezzeTestHelper.run("""
            use std::effects::exception::{Exception, ExceptionToResult, unwrap}

            let bad_result = Err {error = "something went wrong"}

            perform ExceptionToResult in do
                let val = unwrap {result = bad_result}
                Ok {val = val}
            """);

        assertTrue(result instanceof MezzeVariant);
        MezzeVariant variant = (MezzeVariant) result;
        assertEquals("Err", variant.getConstructorName());
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
