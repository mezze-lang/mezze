package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `JsonUtf8C` writes straight to UTF-8 bytes, skipping the `Json` tree.
 *
 * Its encoded form is a WRITER — `{ buf: ByteBuffer } -> ByteBuffer`, i.e.
 * "append yourself to this buffer and hand it back" — so a whole encode runs
 * against ONE buffer: no tree, and no per-node byte array either. (The obvious
 * `'out = Bytes` shape, where each node concatenates its children, measured
 * SLOWER than the tree because it materialises an array at every level.)
 *
 * Before this, only `EncodeScalar` existed for `JsonUtf8C`, so
 * `record.to_json_utf8 {}` did not compile — "format lacks EncodeRecord" — and
 * there was no way to encode anything but a scalar to UTF-8.
 *
 * Every case below asserts the two paths agree EXACTLY: the bytes from the
 * streaming writer against the text from the `Json` tree. Two encoders that
 * disagree on a float's rendering or a key's escaping would be worse than one.
 */
class JsonUtf8WriterTest {

    private val head = """
        use std::codec::json::{ ToJson, ToJsonUtf8 }
        use std::collections::bytes
        use std::collections::map
        use std::collections::seq
    """.trimIndent()

    /** Encode `expr` BOTH ways and return "writer|tree" so a mismatch is visible. */
    private fun bothWays(expr: String): String = MezzeTestHelper.run(
        """
        $head
        let go: {} -> Str = {} -> do
          let streamed = match bytes::to_str { b = ($expr).to_json_utf8 {} } is
            Ok { val } -> val
            Err { error = _ } -> "<undecodable>"
          let tree = ($expr).to_json_str {}
          "$(streamed)|$(tree)"
        go {}
        """.trimIndent(),
    ) as String

    /** Same check, for a value that needs a type annotation to be written at all. */
    private fun assertAgreeTyped(binding: String, name: String, expected: String) {
        val r = MezzeTestHelper.run(
            """
            $head
            let $binding
            let go: {} -> Str = {} -> do
              let streamed = match bytes::to_str { b = $name.to_json_utf8 {} } is
                Ok { val } -> val
                Err { error = _ } -> "<undecodable>"
              let tree = $name.to_json_str {}
              "$(streamed)|$(tree)"
            go {}
            """.trimIndent(),
        ) as String
        assertEquals("$expected|$expected", r, "the two encoders must agree for $name")
    }

    private fun assertAgree(expr: String, expected: String) {
        val r = bothWays(expr)
        assertEquals("$expected|$expected", r, "the two encoders must agree for $expr")
    }

    @Test
    fun `an int`() = assertAgree("42", "42")

    @Test
    fun `a negative int`() = assertAgree("-7", "-7")

    @Test
    fun `a bool`() = assertAgree("True", "true")

    @Test
    fun `a string`() = assertAgree("\"hi\"", "\"hi\"")

    @Test
    fun `a string needing escapes`() =
        assertAgree("\"a\\\"b\\\\c\\nd\\te\"", "\"a\\\"b\\\\c\\nd\\te\"")

    @Test
    fun `a record`() = assertAgree("""{ a = 1, b = "x" }""", """{"a":1,"b":"x"}""")

    @Test
    fun `a nested record`() =
        assertAgree("""{ outer = { inner = 2 }, n = 3 }""", """{"n":3,"outer":{"inner":2}}""")

    @Test
    fun `a seq of ints`() = assertAgree("[1, 2, 3]", "[1,2,3]")

    @Test
    fun `a seq of records`() =
        assertAgree("""[{ id = 1 }, { id = 2 }]""", """[{"id":1},{"id":2}]""")

    @Test
    fun `an empty seq`() = assertAgreeTyped("xs: [Int] = []", "xs", "[]")

    @Test
    fun `a record with a key needing escapes`() =
        assertAgree("""{ a = "say \"hi\"" }""", """{"a":"say \"hi\""}""")

    @Test
    fun `a float`() = assertAgree("1.5", "1.5")

    @Test
    fun `an option Some`() = assertAgree("""{ v = Some { val = 4 } }""", """{"v":4}""")

    @Test
    fun `an option None`() =
        assertAgreeTyped("r: { v: Option Int } = { v = None }", "r", """{"v":null}""")

    @Test
    fun `a deeply nested mix`() = assertAgree(
        """{ rows = [{ k = "a", vs = [1, 2] }, { k = "b", vs = [] }] }""",
        """{"rows":[{"k":"a","vs":[1,2]},{"k":"b","vs":[]}]}""",
    )
}
