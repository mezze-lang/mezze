package com.mezze.interpreter;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A codec obligation ({@code .to_json {}} / {@code .encode {}}) that appears
 * inside a {@code perform Performer { … } in} handler-config record, where the
 * performer is imported but the EFFECT it implements is NOT imported by name.
 *
 * The performer's {@code PerformerTypeInfo} is what lets its handler-config
 * record be type-checked, and building it needs the effect decl in scope. When
 * the user imports only the performer (`use …::{ MockHttpClient }`) and not its
 * effect (`Http`), the effect decl used to be absent, so `addEffectPerformer`
 * bailed and the whole config subtree went unchecked — including any codec
 * obligation inside it. It therefore type-checked clean, but the auto-derived
 * codec was never registered, and the program died at runtime with
 * `Field not found: encode`.
 *
 * Fix: an imported performer now drags its effect decl into the import context
 * by hash (see ImportResolver + TypeChecker), so the config is checked and the
 * codec is derived — without making the effect referenceable by name.
 */
@Execution(ExecutionMode.SAME_THREAD)
class CodecInPerformHandlerConfigTest {

    @BeforeEach
    void setUp() {
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack();
    }

    /**
     * The regression case: `.to_json {}` inside the mock body, `Http` NOT
     * imported. Before the fix this threw `Field not found: encode`.
     */
    @Test
    void toJsonInMockBodyWithoutEffectImport() {
        Object r = MezzeTestHelper.run("""
            use std::effects::http::{ MockHttpClient, mock_get, request, body_str }
            use std::codec::json::{ ToJson, to_str }

            let fetch = {} ->
              perform MockHttpClient { rules = [
                mock_get { url = "*", body = to_str { json = { code = "abc", name = "widget" }.to_json {} } }
              ] } in do
                let resp = request { url = "http://x/y" }.send {}
                match resp is
                  Ok { val } -> match body_str { resp = val } is
                    Ok { val = s } -> s
                    Err { error = _ } -> "read-fail"
                  Err { error = _ } -> "req-fail"

            fetch {}
            """);
        assertEquals("{\"code\":\"abc\",\"name\":\"widget\"}", r.toString());
    }

    /**
     * The same via the lower-level `.encode { config = … }` — the form the
     * bisection first reproduced with.
     */
    @Test
    void encodeInMockBodyWithoutEffectImport() {
        Object r = MezzeTestHelper.run("""
            use std::effects::http::{ MockHttpClient, mock_get, request, body_str }
            use std::codec::json::{ json_default, to_str }
            use std::codec::core::{ Encode }

            let fetch = {} ->
              perform MockHttpClient { rules = [
                mock_get { url = "*", body = to_str { json = { code = "abc" }.encode { config = json_default } } }
              ] } in do
                let resp = request { url = "http://x/y" }.send {}
                match resp is
                  Ok { val } -> match body_str { resp = val } is
                    Ok { val = s } -> s
                    Err { error = _ } -> "read-fail"
                  Err { error = _ } -> "req-fail"

            fetch {}
            """);
        assertEquals("{\"code\":\"abc\"}", r.toString());
    }

    /**
     * Importing the effect explicitly must still work (no regression on the
     * common path).
     */
    @Test
    void stillWorksWhenEffectIsImported() {
        Object r = MezzeTestHelper.run("""
            use std::effects::http::{ Http, MockHttpClient, mock_get, request, body_str }
            use std::codec::json::{ ToJson, to_str }

            let fetch = {} ->
              perform MockHttpClient { rules = [
                mock_get { url = "*", body = to_str { json = { code = "z" }.to_json {} } }
              ] } in do
                let resp = request { url = "http://x/y" }.send {}
                match resp is
                  Ok { val } -> match body_str { resp = val } is
                    Ok { val = s } -> s
                    Err { error = _ } -> "read-fail"
                  Err { error = _ } -> "req-fail"

            fetch {}
            """);
        assertEquals("{\"code\":\"z\"}", r.toString());
    }
}
