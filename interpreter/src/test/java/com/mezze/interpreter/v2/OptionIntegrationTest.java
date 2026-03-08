package com.mezze.interpreter.v2;

import com.mezze.language.typechecker.ModuleResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the std::option module functions.
 * Uses V2 unboxed values directly.
 */
class OptionIntegrationTest {

    @BeforeEach
    void setUp() {
        ModuleResolver.clearCache();
    }

    // ========================================
    // Basic Predicates
    // ========================================

    @Test
    void isSomeReturnsTrueForSome() {
        Object result = MezzeTestHelper.run("""
            use std::option
            let opt: (Option Int) = Some { val = 42 }
            option::is_some { opt = opt }
            """);
        assertEquals(true, result);
    }

    @Test
    void isSomeReturnsFalseForNone() {
        Object result = MezzeTestHelper.run("""
            use std::option
            let opt: (Option Int) = None
            option::is_some { opt = opt }
            """);
        assertEquals(false, result);
    }

    @Test
    void isNoneReturnsTrueForNone() {
        Object result = MezzeTestHelper.run("""
            use std::option
            let opt: (Option Int) = None
            option::is_none { opt = opt }
            """);
        assertEquals(true, result);
    }

    @Test
    void isNoneReturnsFalseForSome() {
        Object result = MezzeTestHelper.run("""
            use std::option
            let opt: (Option Int) = Some { val = 42 }
            option::is_none { opt = opt }
            """);
        assertEquals(false, result);
    }

    // ========================================
    // Extractors
    // ========================================

    @Test
    void getOrElseReturnsValueForSome() {
        Object result = MezzeTestHelper.run("""
            use std::option
            let opt: (Option Int) = Some { val = 42 }
            option::get_or_else { opt = opt, def = 0 }
            """);
        assertEquals(42L, result);
    }

    @Test
    void getOrElseReturnsDefaultForNone() {
        Object result = MezzeTestHelper.run("""
            use std::option
            let opt: (Option Int) = None
            option::get_or_else { opt = opt, def = 99 }
            """);
        assertEquals(99L, result);
    }

    // ========================================
    // Transformation Functions
    // ========================================

    @Test
    void mapTransformsSomeValue() {
        Object result = MezzeTestHelper.run("""
            use std::option
            let opt: (Option Int) = Some { val = 21 }
            let doubled = option::map { opt = opt, f = { val } -> val * 2 }
            option::get_or_else { opt = doubled, def = 0 }
            """);
        assertEquals(42L, result);
    }

    @Test
    void mapReturnsNoneForNone() {
        Object result = MezzeTestHelper.run("""
            use std::option
            let opt: (Option Int) = None
            let doubled = option::map { opt = opt, f = { val } -> val * 2 }
            option::is_none { opt = doubled }
            """);
        assertEquals(true, result);
    }

    @Test
    void flatMapChainsOptions() {
        Object result = MezzeTestHelper.run("""
            use std::option
            let opt: (Option Int) = Some { val = 5 }
            let result = option::flat_map { opt = opt, f = { val } ->
                if val > 0 then Some { val = val * 2 } else None
            }
            option::get_or_else { opt = result, def = 0 }
            """);
        assertEquals(10L, result);
    }

    @Test
    void filterKeepsMatchingSome() {
        Object result = MezzeTestHelper.run("""
            use std::option
            let opt: (Option Int) = Some { val = 10 }
            let filtered = option::filter { opt = opt, p = { val } -> val > 5 }
            option::is_some { opt = filtered }
            """);
        assertEquals(true, result);
    }

    @Test
    void filterRemovesNonMatchingSome() {
        Object result = MezzeTestHelper.run("""
            use std::option
            let opt: (Option Int) = Some { val = 3 }
            let filtered = option::filter { opt = opt, p = { val } -> val > 5 }
            option::is_none { opt = filtered }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // or_else
    // ========================================

    @Test
    void orElseReturnsFirstSome() {
        Object result = MezzeTestHelper.run("""
            use std::option
            let opt1: (Option Int) = Some { val = 1 }
            let opt2: (Option Int) = Some { val = 2 }
            let result = option::or_else { opt1 = opt1, opt2 = opt2 }
            option::get_or_else { opt = result, def = 0 }
            """);
        assertEquals(1L, result);
    }

    @Test
    void orElseReturnsSecondWhenFirstIsNone() {
        Object result = MezzeTestHelper.run("""
            use std::option
            let opt1: (Option Int) = None
            let opt2: (Option Int) = Some { val = 2 }
            let result = option::or_else { opt1 = opt1, opt2 = opt2 }
            option::get_or_else { opt = result, def = 0 }
            """);
        assertEquals(2L, result);
    }

    // ========================================
    // Flatten
    // ========================================

    @Test
    void flattenUnwrapsNestedSome() {
        Object result = MezzeTestHelper.run("""
            use std::option
            let inner: (Option Int) = Some { val = 42 }
            let outer: (Option (Option Int)) = Some { val = inner }
            let result = option::flatten { opt = outer }
            option::get_or_else { opt = result, def = 0 }
            """);
        assertEquals(42L, result);
    }

    @Test
    void flattenReturnsNoneForNestedNone() {
        Object result = MezzeTestHelper.run("""
            use std::option
            let inner: (Option Int) = None
            let outer: (Option (Option Int)) = Some { val = inner }
            let result = option::flatten { opt = outer }
            option::is_none { opt = result }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Conversion Functions
    // ========================================

    @Test
    void toResultConvertsSomeToOk() {
        Object result = MezzeTestHelper.run("""
            use std::option
            use std::result
            let opt: (Option Int) = Some { val = 42 }
            let res = option::to_result { opt = opt, error = "missing" }
            result::is_ok { result = res }
            """);
        assertEquals(true, result);
    }

    @Test
    void toResultConvertsNoneToErr() {
        Object result = MezzeTestHelper.run("""
            use std::option
            use std::result
            let opt: (Option Int) = None
            let res = option::to_result { opt = opt, error = "missing" }
            result::is_err { result = res }
            """);
        assertEquals(true, result);
    }

    @Test
    void toListConvertsSomeToSingleton() {
        Object result = MezzeTestHelper.run("""
            use std::option
            use std::collections::array
            let opt: (Option Int) = Some { val = 42 }
            let arr = option::to_list { opt = opt }
            array::length { xs = arr }
            """);
        assertEquals(1L, result);
    }

    @Test
    void toListConvertsNoneToEmpty() {
        Object result = MezzeTestHelper.run("""
            use std::option
            use std::collections::array
            let opt: (Option Int) = None
            let arr = option::to_list { opt = opt }
            array::length { xs = arr }
            """);
        assertEquals(0L, result);
    }

    // ========================================
    // Zip Operations
    // ========================================

    @Test
    void zipCombinesTwoSomes() {
        Object result = MezzeTestHelper.run("""
            use std::option
            let a: (Option Int) = Some { val = 1 }
            let b: (Option String) = Some { val = "x" }
            let result = option::zip { a = a, b = b }
            option::is_some { opt = result }
            """);
        assertEquals(true, result);
    }

    @Test
    void zipReturnsNoneWhenFirstIsNone() {
        Object result = MezzeTestHelper.run("""
            use std::option
            let a: (Option Int) = None
            let b: (Option String) = Some { val = "x" }
            let result = option::zip { a = a, b = b }
            option::is_none { opt = result }
            """);
        assertEquals(true, result);
    }

    @Test
    void zipWithCombinesValuesWithFunction() {
        Object result = MezzeTestHelper.run("""
            use std::option
            let a: (Option Int) = Some { val = 3 }
            let b: (Option Int) = Some { val = 4 }
            let result = option::zip_with { a = a, b = b, f = { x, y } -> x + y }
            option::get_or_else { opt = result, def = 0 }
            """);
        assertEquals(7L, result);
    }

    // ========================================
    // Fold
    // ========================================

    @Test
    void foldExecutesOnSomeForSome() {
        Object result = MezzeTestHelper.run("""
            use std::option
            let opt: (Option Int) = Some { val = 42 }
            option::fold {
                opt = opt,
                on_none = {} -> 0,
                on_some = { val } -> val * 2
            }
            """);
        assertEquals(84L, result);
    }

    @Test
    void foldExecutesOnNoneForNone() {
        Object result = MezzeTestHelper.run("""
            use std::option
            let opt: (Option Int) = None
            option::fold {
                opt = opt,
                on_none = {} -> 99,
                on_some = { val } -> val * 2
            }
            """);
        assertEquals(99L, result);
    }

    // ========================================
    // map2
    // ========================================

    @Test
    void map2CombinesTwoOptionsWithFunction() {
        Object result = MezzeTestHelper.run("""
            use std::option
            let a: (Option Int) = Some { val = 10 }
            let b: (Option Int) = Some { val = 5 }
            let result = option::map2 { opt_a = a, opt_b = b, f = { a, b } -> a + b }
            option::get_or_else { opt = result, def = 0 }
            """);
        assertEquals(15L, result);
    }

    @Test
    void map2ReturnsNoneIfEitherIsNone() {
        Object result = MezzeTestHelper.run("""
            use std::option
            let a: (Option Int) = Some { val = 10 }
            let b: (Option Int) = None
            let result = option::map2 { opt_a = a, opt_b = b, f = { a, b } -> a + b }
            option::is_none { opt = result }
            """);
        assertEquals(true, result);
    }
}
