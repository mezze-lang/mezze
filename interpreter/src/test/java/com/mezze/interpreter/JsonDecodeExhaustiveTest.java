package com.mezze.interpreter;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive JSON DECODE coverage: `.from_json {}` with the target shape
 * inferred purely from usage (the fields the chain reads), no schema written.
 *
 * The load-bearing cases are MULTIPLE decodes of DIFFERENT shapes in one scope.
 * A `from_json` result is `Result 'a DecodeError` and `'a` (the decode target)
 * stays a bare var everywhere the compiler consumes the type â so two calls of
 * different shape are indistinguishable to codegen unless their decoder is wired
 * per call site from the usage-inferred target (DecodeTargetResolver), and the
 * two structurally-identical decode callbacks are kept from being deduped onto
 * one decoder (the call's decoder ref participates in its hash). Before that fix
 * the SECOND decode crashed `Field not found: decode` or silently took the wrong
 * decoder.
 */
@Execution(ExecutionMode.SAME_THREAD)
class JsonDecodeExhaustiveTest {

    @BeforeEach
    void setUp() {
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack();
    }

    private Object run(String body) {
        return MezzeTestHelper.run(
            "use std::effects::log::{ Console, println }\n" +
            "use std::codec::json::{ FromJson, parse_str }\n" + body);
    }

    // ââ single decode, various shapes ââââââââââââââââââââââââââââââââââââââ

    @Test void oneScalarField() {
        assertEquals("5", run(
            "\"{\\\"n\\\":5}\".from_json {}.map { f = { val } -> val.n }.get_or { def = -1 }.to_str {}"
        ).toString());
    }

    @Test void oneStringField() {
        assertEquals("olives", run(
            "\"{\\\"name\\\":\\\"olives\\\"}\".from_json {}.map { f = { val } -> val.name }.get_or { def = \"?\" }"
        ).toString());
    }

    @Test void nestedField() {
        assertEquals("tahini", run(
            "\"{\\\"product\\\":{\\\"product_name\\\":\\\"tahini\\\"}}\".from_json {}" +
            ".map { f = { val } -> val.product.product_name }.get_or { def = \"?\" }"
        ).toString());
    }

    @Test void malformedTakesDefault() {
        assertEquals("?", run(
            "\"not json\".from_json {}.map { f = { val } -> val.name }.get_or { def = \"?\" }"
        ).toString());
    }

    @Test void missingFieldTakesDefault() {
        assertEquals("?", run(
            "\"{\\\"other\\\":1}\".from_json {}.map { f = { val } -> val.name }.get_or { def = \"?\" }"
        ).toString());
    }

    // ââ the regression: MULTIPLE different-shape decodes in one scope âââââââ

    @Test void twoDifferentShapes_directStr() {
        assertEquals("5/olives", run(
            "let main = {} -> do\n" +
            "  let a = \"{\\\"n\\\":5}\".from_json {}.map { f = { val } -> val.n }.get_or { def = -1 }\n" +
            "  let b = \"{\\\"name\\\":\\\"olives\\\"}\".from_json {}.map { f = { val } -> val.name }.get_or { def = \"?\" }\n" +
            "  \"$(a.to_str {})/$(b)\"\n" +
            "main {}"
        ).toString());
    }

    @Test void twoDifferentShapes_reverseOrder() {
        assertEquals("olives/5", run(
            "let main = {} -> do\n" +
            "  let b = \"{\\\"name\\\":\\\"olives\\\"}\".from_json {}.map { f = { val } -> val.name }.get_or { def = \"?\" }\n" +
            "  let a = \"{\\\"n\\\":5}\".from_json {}.map { f = { val } -> val.n }.get_or { def = -1 }\n" +
            "  \"$(b)/$(a.to_str {})\"\n" +
            "main {}"
        ).toString());
    }

    @Test void threeDifferentShapes() {
        assertEquals("1/olives/True", run(
            "let main = {} -> do\n" +
            "  let a = \"{\\\"n\\\":1}\".from_json {}.map { f = { val } -> val.n }.get_or { def = -1 }\n" +
            "  let b = \"{\\\"name\\\":\\\"olives\\\"}\".from_json {}.map { f = { val } -> val.name }.get_or { def = \"?\" }\n" +
            "  let c = \"{\\\"flag\\\":true}\".from_json {}.map { f = { val } -> val.flag }.get_or { def = False }\n" +
            "  \"$(a.to_str {})/$(b)/$(c.to_str {})\"\n" +
            "main {}"
        ).toString());
    }

    @Test void sameShapeTwice_stillWorks() {
        assertEquals("5/7", run(
            "let main = {} -> do\n" +
            "  let a = \"{\\\"n\\\":5}\".from_json {}.map { f = { val } -> val.n }.get_or { def = -1 }\n" +
            "  let b = \"{\\\"n\\\":7}\".from_json {}.map { f = { val } -> val.n }.get_or { def = -1 }\n" +
            "  \"$(a.to_str {})/$(b.to_str {})\"\n" +
            "main {}"
        ).toString());
    }

    // ââ list fields inferred from usage âââââââââââââââââââââââââââââââââââââ

    @Test void listOfScalars() {
        // element type pinned by `.sum {}`
        assertEquals("6", run(
            "use std::collections::list\n" +
            "let g = { body } -> body.from_json {}" +
            ".map { f = { val } -> [].concat { ys = val.nums }.sum {} }" +
            ".get_or { def = -1 }\n" +
            "g { body = \"{\\\"nums\\\":[1,2,3]}\" }.to_str {}"));
    }

    @Test void listOfRecords_readElementFieldViaMap() {
        // The element RECORD shape is inferred from `x.temp` read inside
        // `[].concat { â¦ }.map { â¦ }` — a list of records, no schema.
        assertEquals("14", run(
            "use std::collections::list\n" +
            "let g = { body } -> body.from_json {}" +
            ".map { f = { val } -> [].concat { ys = val.conds }.map { f = { x } -> x.temp }.first {}.get_or { def = \"none\" } }" +
            ".get_or { def = \"?\" }\n" +
            "g { body = \"{\\\"conds\\\":[{\\\"temp\\\":\\\"14\\\"}]}\" }"));
    }

    @Test void twoDifferentShapes_nestedInFlatMapChain() {
        // The hardest: each decode nested in a `.to_option {}.flat_map {}` chain,
        // with STRUCTURALLY IDENTICAL callbacks â they must not dedupe onto one.
        assertEquals("5/olives", run(
            "let main = {} -> do\n" +
            "  let a = parse_str { s = \"{\\\"n\\\":5}\" }.to_option {}" +
            ".flat_map { f = { val } -> val.from_json {}.to_option {} }" +
            ".map { f = { val } -> val.n }.get_or { def = -1 }\n" +
            "  let b = parse_str { s = \"{\\\"name\\\":\\\"olives\\\"}\" }.to_option {}" +
            ".flat_map { f = { val } -> val.from_json {}.to_option {} }" +
            ".map { f = { val } -> val.name }.get_or { def = \"?\" }\n" +
            "  \"$(a.to_str {})/$(b)\"\n" +
            "main {}"
        ).toString());
    }
}
