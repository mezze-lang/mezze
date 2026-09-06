package com.mezze.interpreter;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ability dispatch on a variant whose nominal identity was stripped.
 *
 * AliasStripper sets `Anonymous` on a variant whose declaring name is not in
 * the caller's scope — that is what lets two same-shaped variants from
 * different modules unify structurally (#148 rule 6), and it must keep doing
 * so. But dispatch was matching the receiver against an impl target by NAME,
 * so a stripped receiver had nothing to match:
 *
 *     j.from_json {}   →   RuntimeException: Field not found: from_json
 *
 * `Json` from std::codec::json arrives as a bare seven-constructor variant
 * while `impl FromJson 'a for Json` targets `TypeApplication(Json)`. The same
 * call through an annotated binding worked, which is what made it look like an
 * inference problem rather than a dispatch one.
 *
 * typeMatchesTarget now falls back to comparing the target declaration's own
 * constructor set against the receiver's — the thing that survives stripping.
 */
@Execution(ExecutionMode.SAME_THREAD)
class StrippedVariantDispatchTest {

    @BeforeEach
    void setUp() {
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack();
    }

    @Test
    void decodeChainResolvesWithoutAnyAnnotation() {
        // No `type` alias, no annotated decode binding, no match — the target
        // shape is pinned only by `val.code` further down the chain.
        Object r = MezzeTestHelper.run("""
            use std::codec::json::{ parse_str, FromJson }

            let describe = { body } -> parse_str { s = body }
              .to_option {}
              .flat_map { f = { val } -> val.from_json {}.to_option {} }
              .map { f = { val } -> val.code }
              .get_or { def = "?" }

            describe { body = "{\\"code\\":\\"abc\\"}" }
            """);
        assertEquals("abc", r.toString());
    }

    @Test
    void aMalformedReplyTakesTheDefault() {
        // The same chain must still fail softly rather than throw: input that
        // does not parse funnels into get_or.
        Object r = MezzeTestHelper.run("""
            use std::codec::json::{ parse_str, FromJson }

            let describe = { body } -> parse_str { s = body }
              .to_option {}
              .flat_map { f = { val } -> val.from_json {}.to_option {} }
              .map { f = { val } -> val.code }
              .get_or { def = "?" }

            describe { body = "not json at all" }
            """);
        assertEquals("?", r.toString());
    }
}
