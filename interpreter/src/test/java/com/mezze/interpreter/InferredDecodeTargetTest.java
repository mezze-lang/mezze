package com.mezze.interpreter;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `from_json` with the target type inferred purely from usage — no type
 * alias, no annotated binding, no match. `val.code` downstream is what pins
 * the decode target to `{ code: Str, ... }`.
 *
 * The load-bearing piece is record-row substitution. Growing an inferred open
 * record used to be expressed by rebinding whichever type variable's stored
 * binding compared equal to the record — and when several variables chained to
 * one record, or it was reached through a constraint instead of a binding, the
 * extension was silently dropped (`Success(Substitution.empty)`). The FIRST
 * field accessed made it into the type; every later one typechecked and then
 * vanished, so the derived decoder carried one field and the program died at
 * runtime with `Field not found: <second field>`.
 *
 * Row extensions now live in Substitution.recordRowMappings and are spliced
 * by apply() wherever the row appears, so every access contributes.
 */
@Execution(ExecutionMode.SAME_THREAD)
class InferredDecodeTargetTest {

    @BeforeEach
    void setUp() {
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack();
    }

    private static final String CHAIN = """
        use std::codec::json::{ parse_str, FromJson }

        let describe = { body } -> parse_str { s = body }
          .to_option {}
          .flat_map { f = { val } -> val.from_json {}.to_option {} }
          %s
          .get_or { def = "?" }

        describe { body = %s }
        """;

    private Object run(String mapLine, String body) {
        return MezzeTestHelper.run(String.format(CHAIN, mapLine, body));
    }

    @Test
    void oneInferredField() {
        Object r = run(
            ".map { f = { val } -> val.code }",
            "\"{\\\"code\\\":\\\"abc\\\"}\"");
        assertEquals("abc", r.toString());
    }

    @Test
    void twoInferredFields() {
        // The regression case: the second field used to vanish from the
        // decode target, and this died with `Field not found: status_verbose`.
        Object r = run(
            ".map { f = { val } -> \"$(val.code)/$(val.status_verbose)\" }",
            "\"{\\\"code\\\":\\\"1\\\",\\\"status_verbose\\\":\\\"ok\\\"}\"");
        assertEquals("1/ok", r.toString());
    }

    @Test
    void threeInferredFields() {
        Object r = run(
            ".map { f = { val } -> \"$(val.a)$(val.b)$(val.c)\" }",
            "\"{\\\"a\\\":\\\"x\\\",\\\"b\\\":\\\"y\\\",\\\"c\\\":\\\"z\\\"}\"");
        assertEquals("xyz", r.toString());
    }

    @Test
    void nestedInferredField() {
        // A nested access infers a nested decode target — the shape a real
        // Open Food Facts reply has.
        Object r = run(
            ".map { f = { val } -> val.product.product_name }",
            "\"{\\\"product\\\":{\\\"product_name\\\":\\\"tahini\\\"}}\"");
        assertEquals("tahini", r.toString());
    }

    @Test
    void arrayOfRecordsInferredFromUsage() {
        // The full shape — nested record, array of records, Str/Int/Float/Bool
        // fields — inferred from usage alone. sum/sum_float/interpolation/if
        // pin the scalar types; [].concat names the container for `items`.
        String json = "{\\\"customer\\\":{\\\"name\\\":\\\"Ada\\\"},\\\"paid\\\":true,\\\"items\\\":[{\\\"qty\\\":2,\\\"price\\\":4.5},{\\\"qty\\\":1,\\\"price\\\":8.99}]}";
        Object r = MezzeTestHelper.run("""
            use std::codec::json::{ parse_str, FromJson }

            let summarize = { body } -> parse_str { s = body }
              .to_option {}
              .flat_map { f = { val } -> val.from_json {}.to_option {} }
              .map { f = { val } -> do
                let items = [].concat { ys = val.items }
                let count = items.map { f = { x } -> x.qty }.sum {}
                let total = items.map { f = { x } -> x.price }.sum_float {}
                let status = if val.paid then "paid" else "UNPAID"
                "$(val.customer.name) ($(status)): $(count.to_str {}) items, $(total.to_str {})"
              }
              .get_or { def = "?" }

            summarize { body = "%BODY%" }
            """.replace("%BODY%", json));
        assertEquals("Ada (paid): 3 items, 13.49", r.toString());
    }

    @Test
    void malformedInputTakesTheDefault() {
        Object r = run(
            ".map { f = { val } -> val.code }",
            "\"not json at all\"");
        assertEquals("?", r.toString());
    }

    @Test
    void aReplyMissingAnInferredFieldTakesTheDefault() {
        // The decoder demands every inferred field: a reply without one is an
        // Err, which funnels into get_or rather than producing a half-record.
        Object r = run(
            ".map { f = { val } -> \"$(val.code)/$(val.status_verbose)\" }",
            "\"{\\\"code\\\":\\\"1\\\"}\"");
        assertEquals("?", r.toString());
    }
}
