package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Reported: `from_json` into a record checks clean and then fails at run time
 * with I0004 "Unexpected values provided for CallIndirect_Node.Uncached:
 * [Object[], Object[]]".
 */
class FromJsonRecordDecodeTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    @Test
    fun `from_json decodes a JSON object into a record`() {
        val code = """
            use std::codec::json::{ FromJson }

            let decode: {} -> Str = {} -> do
              let r: Result { q: Str, page: Int } DecodeError =
                "{ \"q\": \"mezze\", \"page\": 3 }".from_json {}
              match r is
                Ok { val } -> val.q
                Err { error = e } -> "err"

            decode {}
        """.trimIndent()
        assertEquals("mezze", MezzeTestHelper.run(code))
    }
}
