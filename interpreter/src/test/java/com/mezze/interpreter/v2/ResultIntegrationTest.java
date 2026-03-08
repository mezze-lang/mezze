package com.mezze.interpreter.v2;

import com.mezze.language.typechecker.ModuleResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the std::result module functions.
 * Uses V2 unboxed values directly.
 */
class ResultIntegrationTest {

    @BeforeEach
    void setUp() {
        ModuleResolver.clearCache();
    }

    // ========================================
    // Basic Predicates
    // ========================================

    @Test
    void isOkReturnsTrueForOk() {
        Object result = MezzeTestHelper.run("""
            use std::result
            let res: (Result Int String) = Ok { val = 42 }
            result::is_ok { result = res }
            """);
        assertEquals(true, result);
    }

    @Test
    void isOkReturnsFalseForErr() {
        Object result = MezzeTestHelper.run("""
            use std::result
            let res: (Result Int String) = Err { error = "failed" }
            result::is_ok { result = res }
            """);
        assertEquals(false, result);
    }

    @Test
    void isErrReturnsTrueForErr() {
        Object result = MezzeTestHelper.run("""
            use std::result
            let res: (Result Int String) = Err { error = "failed" }
            result::is_err { result = res }
            """);
        assertEquals(true, result);
    }

    @Test
    void isErrReturnsFalseForOk() {
        Object result = MezzeTestHelper.run("""
            use std::result
            let res: (Result Int String) = Ok { val = 42 }
            result::is_err { result = res }
            """);
        assertEquals(false, result);
    }

    // ========================================
    // Extractors
    // ========================================

    @Test
    void getOrElseReturnsValueForOk() {
        Object result = MezzeTestHelper.run("""
            use std::result
            let res: (Result Int String) = Ok { val = 42 }
            result::get_or_else { result = res, def = 0 }
            """);
        assertEquals(42L, result);
    }

    @Test
    void getOrElseReturnsDefaultForErr() {
        Object result = MezzeTestHelper.run("""
            use std::result
            let res: (Result Int String) = Err { error = "failed" }
            result::get_or_else { result = res, def = 99 }
            """);
        assertEquals(99L, result);
    }

    // ========================================
    // Transformation Functions
    // ========================================

    @Test
    void mapTransformsOkValue() {
        Object result = MezzeTestHelper.run("""
            use std::result
            let res: (Result Int String) = Ok { val = 21 }
            let doubled = result::map { result = res, f = { val } -> val * 2 }
            result::get_or_else { result = doubled, def = 0 }
            """);
        assertEquals(42L, result);
    }

    @Test
    void mapPreservesErr() {
        Object result = MezzeTestHelper.run("""
            use std::result
            let res: (Result Int String) = Err { error = "failed" }
            let doubled = result::map { result = res, f = { val } -> val * 2 }
            result::is_err { result = doubled }
            """);
        assertEquals(true, result);
    }

    @Test
    void mapErrTransformsError() {
        Object result = MezzeTestHelper.run("""
            use std::result
            use std::string
            let res: (Result Int String) = Err { error = "error" }
            let mapped = result::map_err { result = res, f = { error } -> string::to_upper { s = error } }
            match mapped is
                Ok { val } -> "ok"
                Err { error } -> error
            """);
        assertEquals("ERROR", result);
    }

    @Test
    void flatMapChainsResults() {
        Object result = MezzeTestHelper.run("""
            use std::result
            let res: (Result Int String) = Ok { val = 5 }
            let chained = result::flat_map { result = res, f = { val } ->
                if val > 0 then Ok { val = val * 2 } else Err { error = "negative" }
            }
            result::get_or_else { result = chained, def = 0 }
            """);
        assertEquals(10L, result);
    }

    @Test
    void orElseRecoversFromError() {
        Object result = MezzeTestHelper.run("""
            use std::result
            let res: (Result Int String) = Err { error = "first error" }
            let recovered = result::or_else { result = res, f = { error } -> Ok { val = 99 } }
            result::get_or_else { result = recovered, def = 0 }
            """);
        assertEquals(99L, result);
    }

    // ========================================
    // Flatten
    // ========================================

    @Test
    void flattenUnwrapsNestedOk() {
        Object result = MezzeTestHelper.run("""
            use std::result
            let inner: (Result Int String) = Ok { val = 42 }
            let outer: (Result (Result Int String) String) = Ok { val = inner }
            let flat = result::flatten { result = outer }
            result::get_or_else { result = flat, def = 0 }
            """);
        assertEquals(42L, result);
    }

    @Test
    void flattenPropagatesInnerErr() {
        Object result = MezzeTestHelper.run("""
            use std::result
            let inner: (Result Int String) = Err { error = "inner" }
            let outer: (Result (Result Int String) String) = Ok { val = inner }
            let flat = result::flatten { result = outer }
            result::is_err { result = flat }
            """);
        assertEquals(true, result);
    }

    @Test
    void flattenPropagatesOuterErr() {
        Object result = MezzeTestHelper.run("""
            use std::result
            let outer: (Result (Result Int String) String) = Err { error = "outer" }
            let flat = result::flatten { result = outer }
            result::is_err { result = flat }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Conversion Functions
    // ========================================

    @Test
    void toOptionConvertsOkToSome() {
        Object result = MezzeTestHelper.run("""
            use std::result
            use std::option
            let res: (Result Int String) = Ok { val = 42 }
            let opt = result::to_option { result = res }
            option::is_some { opt = opt }
            """);
        assertEquals(true, result);
    }

    @Test
    void toOptionConvertsErrToNone() {
        Object result = MezzeTestHelper.run("""
            use std::result
            use std::option
            let res: (Result Int String) = Err { error = "failed" }
            let opt = result::to_option { result = res }
            option::is_none { opt = opt }
            """);
        assertEquals(true, result);
    }

    @Test
    void fromOptionConvertsSomeToOk() {
        Object result = MezzeTestHelper.run("""
            use std::result
            let opt: (Option Int) = Some { val = 42 }
            let res = result::from_option { opt = opt, error = "missing" }
            result::is_ok { result = res }
            """);
        assertEquals(true, result);
    }

    @Test
    void fromOptionConvertsNoneToErr() {
        Object result = MezzeTestHelper.run("""
            use std::result
            let opt: (Option Int) = None
            let res = result::from_option { opt = opt, error = "missing" }
            result::is_err { result = res }
            """);
        assertEquals(true, result);
    }

    @Test
    void toListConvertsOkToSingleton() {
        Object result = MezzeTestHelper.run("""
            use std::result
            use std::collections::array
            let res: (Result Int String) = Ok { val = 42 }
            let arr = result::to_list { result = res }
            array::length { xs = arr }
            """);
        assertEquals(1L, result);
    }

    @Test
    void toListConvertsErrToEmpty() {
        Object result = MezzeTestHelper.run("""
            use std::result
            use std::collections::array
            let res: (Result Int String) = Err { error = "failed" }
            let arr = result::to_list { result = res }
            array::length { xs = arr }
            """);
        assertEquals(0L, result);
    }

    // ========================================
    // Zip
    // ========================================

    @Test
    void zipCombinesTwoOks() {
        Object result = MezzeTestHelper.run("""
            use std::result
            let a: (Result Int String) = Ok { val = 1 }
            let b: (Result Int String) = Ok { val = 2 }
            let zipped = result::zip { a = a, b = b }
            result::is_ok { result = zipped }
            """);
        assertEquals(true, result);
    }

    @Test
    void zipReturnsErrWhenFirstIsErr() {
        Object result = MezzeTestHelper.run("""
            use std::result
            let a: (Result Int String) = Err { error = "first" }
            let b: (Result Int String) = Ok { val = 2 }
            let zipped = result::zip { a = a, b = b }
            result::is_err { result = zipped }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Fold
    // ========================================

    @Test
    void foldExecutesOnOkForOk() {
        Object result = MezzeTestHelper.run("""
            use std::result
            let res: (Result Int String) = Ok { val = 42 }
            result::fold {
                result = res,
                on_err = { error } -> 0,
                on_ok = { val } -> val * 2
            }
            """);
        assertEquals(84L, result);
    }

    @Test
    void foldExecutesOnErrForErr() {
        Object result = MezzeTestHelper.run("""
            use std::result
            use std::string
            let res: (Result Int String) = Err { error = "failed" }
            result::fold {
                result = res,
                on_err = { error } -> string::length { s = error },
                on_ok = { val } -> val
            }
            """);
        assertEquals(6L, result);  // "failed".length = 6
    }

    // ========================================
    // map2
    // ========================================

    @Test
    void map2CombinesTwoResultsWithFunction() {
        Object result = MezzeTestHelper.run("""
            use std::result
            let a: (Result Int String) = Ok { val = 10 }
            let b: (Result Int String) = Ok { val = 5 }
            let combined = result::map2 { result_a = a, result_b = b, f = { a, b } -> a + b }
            result::get_or_else { result = combined, def = 0 }
            """);
        assertEquals(15L, result);
    }

    @Test
    void map2ReturnsErrIfEitherIsErr() {
        Object result = MezzeTestHelper.run("""
            use std::result
            let a: (Result Int String) = Ok { val = 10 }
            let b: (Result Int String) = Err { error = "failed" }
            let combined = result::map2 { result_a = a, result_b = b, f = { a, b } -> a + b }
            result::is_err { result = combined }
            """);
        assertEquals(true, result);
    }
}
