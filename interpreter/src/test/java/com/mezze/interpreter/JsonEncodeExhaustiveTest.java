package com.mezze.interpreter;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive JSON ENCODE coverage: `.to_json_str {}` (record -> JSON string) and
 * `.to_json_str_with { config }`, across every value shape the auto-derived
 * `Encode JsonC` walk handles. Object keys serialize in alphabetical order, so
 * assertions are written against that canonical form.
 *
 * The encoder is auto-derived from the record's structure — no `impl` and no
 * schema is written anywhere in these programs.
 */
@Execution(ExecutionMode.SAME_THREAD)
class JsonEncodeExhaustiveTest {

    @BeforeEach
    void setUp() {
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack();
    }

    private String enc(String recordExpr) {
        return MezzeTestHelper.run(
            "use std::codec::json::{ ToJson }\n" + recordExpr + ".to_json_str {}"
        ).toString();
    }

    @Test void intField()    { assertEquals("{\"n\":42}", enc("{ n = 42 }")); }
    @Test void strField()    { assertEquals("{\"s\":\"hi\"}", enc("{ s = \"hi\" }")); }
    @Test void boolTrue()    { assertEquals("{\"b\":true}", enc("{ b = True }")); }
    @Test void boolFalse()   { assertEquals("{\"b\":false}", enc("{ b = False }")); }
    @Test void floatField()  { assertEquals("{\"f\":1.5}", enc("{ f = 1.5 }")); }

    @Test void emptyRecord() { assertEquals("{}", enc("{ }")); }

    @Test void keysAreAlphabetical() {
        // written b, a, c -> serialized a, b, c
        assertEquals("{\"a\":1,\"b\":2,\"c\":3}", enc("{ b = 2, a = 1, c = 3 }"));
    }

    @Test void mixedScalars() {
        assertEquals("{\"b\":false,\"f\":1.5,\"i\":42,\"s\":\"hi\"}",
            enc("{ i = 42, s = \"hi\", b = False, f = 1.5 }"));
    }

    @Test void stringEscaping() {
        // quote escaped, newline escaped, forward slash left as-is
        assertEquals("{\"nl\":\"a\\nb\",\"q\":\"say \\\"hi\\\"\",\"slash\":\"a/b\"}",
            enc("{ q = \"say \\\"hi\\\"\", nl = \"a\\nb\", slash = \"a/b\" }"));
    }

    @Test void nestedRecord() {
        assertEquals("{\"outer\":{\"inner\":\"v\"}}", enc("{ outer = { inner = \"v\" } }"));
    }

    @Test void deeplyNested() {
        assertEquals("{\"a\":{\"b\":{\"c\":{\"d\":\"deep\"}}}}",
            enc("{ a = { b = { c = { d = \"deep\" } } } }"));
    }

    @Test void listOfScalars() {
        assertEquals("{\"xs\":[1,2,3]}", enc("{ xs = [1, 2, 3] }"));
    }

    @Test void emptyList() {
        assertEquals("{\"xs\":[]}",
            MezzeTestHelper.run(
                "use std::codec::json::{ ToJson }\n" +
                "let xs: [Int] = []\n" +
                "{ xs = xs }.to_json_str {}").toString());
    }

    @Test void listOfRecords() {
        assertEquals("{\"rs\":[{\"n\":\"a\"},{\"n\":\"b\"}]}",
            enc("{ rs = [{ n = \"a\" }, { n = \"b\" }] }"));
    }

    @Test void optionSome() {
        assertEquals("{\"m\":\"y\"}",
            MezzeTestHelper.run(
                "use std::codec::json::{ ToJson }\n" +
                "let o: Option Str = Some { val = \"y\" }\n" +
                "{ m = o }.to_json_str {}").toString());
    }

    @Test void optionNone() {
        assertEquals("{\"m\":null}",
            MezzeTestHelper.run(
                "use std::codec::json::{ ToJson }\n" +
                "let o: Option Str = None\n" +
                "{ m = o }.to_json_str {}").toString());
    }

    @Test void everythingAtOnce() {
        assertEquals(
            "{\"f\":3.14,\"items\":[{\"k\":\"a\"},{\"k\":\"b\"}],\"nested\":{\"deep\":\"v\"},\"nums\":[1,2,3]}",
            enc("{ f = 3.14, items = [{ k = \"a\" }, { k = \"b\" }], nested = { deep = \"v\" }, nums = [1, 2, 3] }"));
    }

    @Test void listOfRecordsEncodes() {
        assertEquals("{\"conds\":[{\"temp\":\"9\"},{\"temp\":\"3\"}]}",
            enc("{ conds = [{ temp = \"9\" }, { temp = \"3\" }] }"));
    }

    @Test void encodeThenDecodeRoundTrips() {
        // `to_json_str` output feeds straight back into `from_json` — the same
        // text on both sides, encode and decode both inferred, no schema.
        assertEquals("olives", MezzeTestHelper.run(
            "use std::codec::json::{ ToJson, FromJson }\n" +
            "let text = { product = { product_name = \"olives\" } }.to_json_str {}\n" +
            "text.from_json {}.map { f = { val } -> val.product.product_name }.get_or { def = \"?\" }").toString());
    }

    @Test void prettyPrints() {
        String r = MezzeTestHelper.run(
            "use std::codec::json::{ ToJson, json_pretty }\n" +
            "{ name = \"olives\", qty = 3 }.to_json_str_with { config = json_pretty }").toString();
        assertEquals("{\n  \"name\": \"olives\",\n  \"qty\": 3\n}", r);
    }
}
