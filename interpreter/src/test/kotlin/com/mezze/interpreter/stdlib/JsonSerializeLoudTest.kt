package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.nodes.builtin.io.IONatives
import com.mezze.interpreter.values.MezzeRecord
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Serializing a `Json` tree cannot fail, and the signatures now say so.
 *
 * `__native_json_serialize` used to return `Result` and catch EVERYTHING —
 * including the NativeTypeMismatchException it threw itself for a non-Json
 * input. The stdlib had nowhere to put that error, because `to_str` returns
 * `Str`, so it answered `""`; `to_bytes` answered empty bytes. A handler
 * encoding a bad value therefore replied 200 with a ZERO-BYTE body and said
 * nothing.
 *
 * A well-formed tree is always serializable — the writer is an in-memory buffer
 * and the only throw is the codec's `default` arm, reached only by a value that
 * is not a `Json` variant at all. So the error channel is gone and a contract
 * violation is reported the way every other native reports one.
 */
class JsonSerializeLoudTest {

    private val head = """
        use std::codec::json::{ to_str, Json, JInt, JObject, JString }
        use std::collections::seq
    """.trimIndent()

    @Test
    fun `a lambda stored in a record field returns its own result`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let enc = { int = { v: Int } -> JInt { val = v } }
            let go: {} -> Str = {} -> to_str { json = enc.int { v = 7 } }
            go {}
            """.trimIndent(),
        )
        assertEquals("7", r)
    }

    @Test
    fun `a record built through a record-held lambda serializes`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let enc = {
              int = { v: Int } -> JInt { val = v },
              rec = { fields: [{ key: Str, value: Json }] } -> JObject { fields = fields },
            }
            let go: {} -> Str = {} -> to_str { json = enc.rec { fields = [
              { key = "a", value = enc.int { v = 1 } },
            ] } }
            go {}
            """.trimIndent(),
        )
        assertEquals("""{"a":1}""", r)
    }

    @Test
    fun `a non-Json value is refused, not answered with an empty string`() {
        // The one case the old Result arm could see. It is unreachable from
        // well-typed Mezze — which is the point: the signature can say so.
        val args = MezzeRecord.fromFields(
            arrayOf("json", "pretty"),
            arrayOf<Any>("not a Json variant", false),
        )
        val thrown = runCatching { IONatives.jsonSerialize(args) }.exceptionOrNull()
        assertTrue(
            thrown != null,
            "a non-Json input must be refused; it used to come back as Err and then as an empty string",
        )
        assertTrue(
            thrown!!.message!!.contains("Json variant"),
            "the refusal should name what it wanted, got: ${thrown.message}",
        )
    }

    @Test
    fun `a non-Json value is refused by the bytes serializer too`() {
        val args = MezzeRecord.fromFields(
            arrayOf("json", "pretty"),
            arrayOf<Any>(42L, false),
        )
        val thrown = runCatching { IONatives.jsonSerializeBytes(args) }.exceptionOrNull()
        assertTrue(thrown != null, "a non-Json input must be refused, not answered with empty bytes")
    }
}
