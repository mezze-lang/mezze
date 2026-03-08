package com.mezze.interpreter.v2;

import com.mezze.language.typechecker.ModuleResolver;
import com.mezze.language.typechecker.TypeChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for if-let guards in match expressions.
 * Uses V2 unboxed values directly.
 */
class IfLetGuardIntegrationTest {

    @BeforeEach
    void setUp() {
        ModuleResolver.clearCache();
        TypeChecker.clearCache();
    }

    @Test
    void ifLetExtractsValueFromSome() {
        Object result = MezzeTestHelper.run("""
            let lookup: {key: Int} -> | Some {val: Int}, None | = {key} ->
                match key is
                    1 -> Some {val = 100}
                    _ -> None

            let test = {key: Int} ->
                match key is
                    k if let Some {val = v} = lookup {key = k} -> v
                    _ -> 0

            test {key = 1}
            """);
        assertEquals(100L, result);
    }

    @Test
    void ifLetFallsThroughToNextCaseOnNone() {
        Object result = MezzeTestHelper.run("""
            let lookup: {key: Int} -> | Some {val: Int}, None | = {key} ->
                match key is
                    1 -> Some {val = 100}
                    _ -> None

            let test = {key: Int} ->
                match key is
                    k if let Some {val = v} = lookup {key = k} -> v
                    _ -> -1

            test {key = 2}
            """);
        assertEquals(-1L, result);
    }

    @Test
    void ifLetWithNestedRecordPattern() {
        Object result = MezzeTestHelper.run("""
            let getPerson: {id: Int} -> | Some {val: {name: String, age: Int}}, None | = {id} ->
                match id is
                    1 -> Some {val = {name = "Alice", age = 30}}
                    _ -> None

            let test = {id: Int} ->
                match id is
                    i if let Some {val = {name, age}} = getPerson {id = i} -> age
                    _ -> 0

            test {id = 1}
            """);
        assertEquals(30L, result);
    }

    @Test
    void ifLetWithMainPatternBinding() {
        Object result = MezzeTestHelper.run("""
            let lookup: {key: Int} -> | Some {val: Int}, None | = {key} ->
                match key is
                    1 -> Some {val = 100}
                    _ -> None

            let test = {x: Int} ->
                match x is
                    k if let Some {val = v} = lookup {key = k} -> k + v
                    _ -> 0

            test {x = 1}
            """);
        // Both k (from main pattern) and v (from if-let) should be accessible
        assertEquals(101L, result);  // 1 + 100
    }

    @Test
    void ifLetWithGlobalOptionVariable() {
        Object result = MezzeTestHelper.run("""
            let globalOption: | Some {val: Int}, None | = Some {val = 42}

            let test = {x: Int} ->
                match x is
                    n if let Some {val = v} = globalOption -> n + v
                    _ -> 0

            test {x = 10}
            """);
        assertEquals(52L, result);  // 10 + 42
    }

    @Test
    void ifLetWithFieldAccess() {
        Object result = MezzeTestHelper.run("""
            let container: {value: | Some {val: Int}, None |} = {
                value = Some {val = 42}
            }

            let test = {x: Int} ->
                match x is
                    n if let Some {val = v} = container.value -> n + v
                    _ -> 0

            test {x = 8}
            """);
        assertEquals(50L, result);  // 8 + 42
    }

    @Test
    void multipleMatchCasesWithIfLet() {
        Object result = MezzeTestHelper.run("""
            let lookup: {key: Int} -> | Some {val: Int}, None | = {key} ->
                match key is
                    1 -> Some {val = 100}
                    2 -> Some {val = 200}
                    _ -> None

            let test = {x: Int} ->
                match x is
                    0 -> -1
                    k if let Some {val = v} = lookup {key = k} -> v
                    _ -> 0

            let r1 = test {x = 0}
            let r2 = test {x = 1}
            let r3 = test {x = 2}
            let r4 = test {x = 3}

            r1 + r2 + r3 + r4
            """);
        // r1 = -1, r2 = 100, r3 = 200, r4 = 0
        assertEquals(299L, result);
    }
}
