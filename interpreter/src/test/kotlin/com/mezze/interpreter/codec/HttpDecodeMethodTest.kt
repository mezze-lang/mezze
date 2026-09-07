package com.mezze.interpreter.codec

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Chainable `.decode {}` (target-directed JSON decode) on the `Result Response
 * HttpError` an HTTP `.send {}` returns. The `.decode` INHERENT method (in
 * http.mz) forwards to the `decode_result` free fn (`with FromJson 'a Str`),
 * which forwards to `from_json`.
 *
 * Fixed 2026-09-07: `generateInherentMethodCall` now emits the inherent
 * method's OWN ability constraints, so the concrete decode target (pinned by
 * the result annotation / match usage) flows into the `FromJson`/`Decode`
 * obligation and the codec derivation fires; the imported-body `from_json`
 * (inside `decode_result`) is then wired by DecodeTargetResolver.
 * Previously the constraint was dropped → no derivation → runtime
 * `UnsupportedSpecialization`.
 */
class HttpDecodeMethodTest {
    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `decode method on http response yields typed record`() {
        val out = eval("""
            use std::effects::http::{ Http, request, mock_get, MockHttpClient, HttpError }
            perform MockHttpClient { rules = [ mock_get { url = "*", body = "{\"name\": \"Ada\"}" } ] } in do
              let r: Result { name: Str } HttpError = request { url = "u" }.send {}.decode {}
              match r is
                Ok { val = { name } } -> name
                Err { error = _ } -> "e"
        """)
        assertEquals("Ada", out)
    }
}
