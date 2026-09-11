package com.mezze.interpreter.polyglot

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.values.MezzeVariant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Integration tests for std::polyglot::wasm.
 *
 * Every test performs the `GraalWasm` handler for the `Wasm` effect,
 * compiles a small hand-crafted wasm module (add + mul + sub +
 * memory), and drives it through the `impl WasmForeign` inherent
 * surface (get_export / call / call_export / memory_read /
 * memory_write / memory_size).
 *
 * The wasm binary is embedded as a hex string so the test is a
 * single-file drop — no `.wasm` resource file, no wat2wasm build
 * step. The module hex was computed by hand from the WebAssembly
 * binary specification (magic + type + function + memory + export +
 * code sections); its shape is documented in [canonicalHex] below.
 */
class WasmInteropTest {

    /**
     * Minimal WebAssembly module. Exports:
     *   - `add : (i32, i32) -> i32`   — local.get 0, local.get 1, i32.add
     *   - `mul : (i32, i32) -> i32`   — local.get 0, local.get 1, i32.mul
     *   - `sub : (i32, i32) -> i32`   — local.get 0, local.get 1, i32.sub
     *   - `memory` — 1 page (64 KiB) linear memory
     *
     * Layout (85 bytes):
     *   00 61 73 6D 01 00 00 00                   — magic + version 1
     *   01 07 01 60 02 7F 7F 01 7F                — type: (i32 i32) -> i32
     *   03 04 03 00 00 00                          — funcs: 3 x type 0
     *   05 03 01 00 01                             — memory: min = 1 page
     *   07 1C 04 03 61 64 64 00 00                — export "add"    func 0
     *          03 6D 75 6C 00 01                  — export "mul"    func 1
     *          03 73 75 62 00 02                  — export "sub"    func 2
     *          06 6D 65 6D 6F 72 79 02 00         — export "memory" mem  0
     *   0A 19 03 07 00 20 00 20 01 6A 0B          — body: add
     *          07 00 20 00 20 01 6C 0B            — body: mul
     *          07 00 20 00 20 01 6B 0B            — body: sub
     */
    /** Canonical 85-byte module hex, formatted so each byte is a two-char
     *  slice sitting under the comment that describes it. */
    private val canonicalHex =
        // Header
        "0061736D01000000" +
        // Type section: 1 type = (i32,i32)->i32
        "0107" + "01" + "60" + "02" + "7F7F" + "01" + "7F" +
        // Function section: 3 functions of type 0
        "0304" + "03" + "000000" +
        // Memory section: 1 memory, min=1 page
        "0503" + "01" + "00" + "01" +
        // Export section: 4 exports
        "071C" + "04" +
        "03" + "616464" + "00" + "00" +   // "add"    -> func 0
        "03" + "6D756C" + "00" + "01" +   // "mul"    -> func 1
        "03" + "737562" + "00" + "02" +   // "sub"    -> func 2
        "06" + "6D656D6F7279" + "02" + "00" + // "memory" -> mem 0
        // Code section: 3 function bodies
        "0A19" + "03" +
        "07" + "00" + "2000" + "2001" + "6A" + "0B" +  // add: local.get 0/1, i32.add, end
        "07" + "00" + "2000" + "2001" + "6C" + "0B" +  // mul: local.get 0/1, i32.mul, end
        "07" + "00" + "2000" + "2001" + "6B" + "0B"    // sub: local.get 0/1, i32.sub, end

    /**
     * Produce a Mezze `Bytes` literal by inlining the module hex via
     * `bytes::from_hex`. Avoids the alternative of piping the byte
     * array through the test host machinery.
     */
    private fun moduleBytesMezzeLet(name: String = "wasm_bytes"): String =
        "let $name: Bytes = match bytes::from_hex { s = \"$canonicalHex\" } is\n" +
        "  Some { val = b } -> b\n" +
        "  None -> bytes::empty{}\n"

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private fun assertOk(result: Any?): MezzeVariant {
        assertTrue(result is MezzeVariant, "Expected MezzeVariant, got $result (${result?.javaClass})")
        val v = result as MezzeVariant
        assertTrue(v.isOk, "Expected Ok variant, got tag=${v.tag}, payload=${v.payload}")
        return v
    }

    private fun assertErr(result: Any?): MezzeVariant {
        assertTrue(result is MezzeVariant, "Expected MezzeVariant, got $result (${result?.javaClass})")
        val v = result as MezzeVariant
        assertTrue(v.isErr, "Expected Err variant, got tag=${v.tag}, payload=${v.payload}")
        return v
    }

    // ==================== Loading ====================

    @Test
    fun `wasm load_module returns module Foreign`() {
        val source = """
            use std::collections::bytes as bytes
            use std::polyglot::wasm::{ Wasm, GraalWasm }
            perform GraalWasm in do
              ${moduleBytesMezzeLet()}
              match Wasm.load_module { bytes = wasm_bytes, name = "arith" } is
                Ok { val = _ } -> Ok { val = 1 }
                Err { error } -> Err { error = error }
        """.trimIndent()
        val v = assertOk(eval(source))
        assertEquals(1L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `wasm load_module with invalid bytes errors`() {
        val source = """
            use std::collections::bytes as bytes
            use std::polyglot::wasm::{ Wasm, GraalWasm }
            perform GraalWasm in
              Wasm.load_module { bytes = bytes::from_str { s = "not a wasm binary" }, name = "bad" }
        """.trimIndent()
        assertErr(eval(source))
    }

    // ==================== Call exports ====================

    @Test
    fun `wasm call add of 3 and 4 returns 7`() {
        val source = """
            use std::collections::bytes as bytes
            use std::polyglot::wasm::{ Wasm, GraalWasm, from_int }
            perform GraalWasm in do
              ${moduleBytesMezzeLet()}
              match Wasm.load_module { bytes = wasm_bytes, name = "arith" } is
                Ok { val = mod } -> match mod.call_export {
                  name = "add",
                  args = [from_int { val = 3 }, from_int { val = 4 }],
                } is
                  Ok { val = r } -> r.as_int{}
                  Err { error } -> Err { error = error }
                Err { error } -> Err { error = error }
        """.trimIndent()
        val v = assertOk(eval(source))
        assertEquals(7L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `wasm call mul of 6 and 7 returns 42`() {
        val source = """
            use std::collections::bytes as bytes
            use std::polyglot::wasm::{ Wasm, GraalWasm, from_int }
            perform GraalWasm in do
              ${moduleBytesMezzeLet()}
              match Wasm.load_module { bytes = wasm_bytes, name = "arith" } is
                Ok { val = mod } -> match mod.call_export {
                  name = "mul",
                  args = [from_int { val = 6 }, from_int { val = 7 }],
                } is
                  Ok { val = r } -> r.as_int{}
                  Err { error } -> Err { error = error }
                Err { error } -> Err { error = error }
        """.trimIndent()
        val v = assertOk(eval(source))
        assertEquals(42L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `wasm call sub of 10 and 3 returns 7`() {
        val source = """
            use std::collections::bytes as bytes
            use std::polyglot::wasm::{ Wasm, GraalWasm, from_int }
            perform GraalWasm in do
              ${moduleBytesMezzeLet()}
              match Wasm.load_module { bytes = wasm_bytes, name = "arith" } is
                Ok { val = mod } -> match mod.call_export {
                  name = "sub",
                  args = [from_int { val = 10 }, from_int { val = 3 }],
                } is
                  Ok { val = r } -> r.as_int{}
                  Err { error } -> Err { error = error }
                Err { error } -> Err { error = error }
        """.trimIndent()
        val v = assertOk(eval(source))
        assertEquals(7L, v.getPayloadFieldByName("val"))
    }

    // ==================== get_export + call separately ====================

    @Test
    fun `wasm get_export then call composes`() {
        val source = """
            use std::collections::bytes as bytes
            use std::polyglot::wasm::{ Wasm, GraalWasm, from_int }
            perform GraalWasm in do
              ${moduleBytesMezzeLet()}
              match Wasm.load_module { bytes = wasm_bytes, name = "arith" } is
                Ok { val = mod } -> match mod.get_export { name = "add" } is
                  Ok { val = f } -> match f.call { args = [from_int { val = 1 }, from_int { val = 2 }] } is
                    Ok { val = r } -> r.as_int{}
                    Err { error } -> Err { error = error }
                  Err { error } -> Err { error = error }
                Err { error } -> Err { error = error }
        """.trimIndent()
        val v = assertOk(eval(source))
        assertEquals(3L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `wasm get_export of missing name errors`() {
        val source = """
            use std::collections::bytes as bytes
            use std::polyglot::wasm::{ Wasm, GraalWasm }
            perform GraalWasm in do
              ${moduleBytesMezzeLet()}
              match Wasm.load_module { bytes = wasm_bytes, name = "arith" } is
                Ok { val = mod } -> mod.get_export { name = "does_not_exist" }
                Err { error } -> Err { error = error }
        """.trimIndent()
        assertErr(eval(source))
    }

    // ==================== Memory ====================

    @Test
    fun `wasm memory_size returns one page`() {
        val source = """
            use std::collections::bytes as bytes
            use std::polyglot::wasm::{ Wasm, GraalWasm }
            perform GraalWasm in do
              ${moduleBytesMezzeLet()}
              match Wasm.load_module { bytes = wasm_bytes, name = "arith" } is
                Ok { val = mod } -> match mod.get_export { name = "memory" } is
                  Ok { val = mem } -> mem.memory_size{}
                  Err { error } -> Err { error = error }
                Err { error } -> Err { error = error }
        """.trimIndent()
        val v = assertOk(eval(source))
        // 1 page = 65536 bytes
        assertEquals(65536L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `wasm memory_write then memory_read round-trips`() {
        val source = """
            use std::collections::bytes as bytes
            use std::polyglot::wasm::{ Wasm, GraalWasm }
            perform GraalWasm in do
              ${moduleBytesMezzeLet()}
              match Wasm.load_module { bytes = wasm_bytes, name = "arith" } is
                Ok { val = mod } -> match mod.get_export { name = "memory" } is
                  Ok { val = mem } -> match mem.memory_write {
                    offset = 128,
                    bytes = bytes::from_seq { arr = [72, 105, 33, 0] },
                  } is
                    Ok { val = _ } -> mem.memory_read { offset = 128, length = 4 }
                    Err { error } -> Err { error = error }
                  Err { error } -> Err { error = error }
                Err { error } -> Err { error = error }
        """.trimIndent()
        val v = assertOk(eval(source))
        val readBack = v.getPayloadFieldByName("val")
        // Payload should be a MezzeBytes with the 4 written bytes.
        assertNotNull(readBack)
        // Inspect via reflection to keep the test decoupled from the
        // exact package location of MezzeBytes.
        val getData = readBack.javaClass.getMethod("getData")
        val data = getData.invoke(readBack) as ByteArray
        assertEquals(4, data.size)
        assertEquals(72, data[0].toInt() and 0xFF)  // 'H'
        assertEquals(105, data[1].toInt() and 0xFF) // 'i'
        assertEquals(33, data[2].toInt() and 0xFF)  // '!'
        assertEquals(0, data[3].toInt() and 0xFF)
    }

    @Test
    fun `wasm memory_read out of range errors`() {
        val source = """
            use std::collections::bytes as bytes
            use std::polyglot::wasm::{ Wasm, GraalWasm }
            perform GraalWasm in do
              ${moduleBytesMezzeLet()}
              match Wasm.load_module { bytes = wasm_bytes, name = "arith" } is
                Ok { val = mod } -> match mod.get_export { name = "memory" } is
                  Ok { val = mem } -> mem.memory_read { offset = 100000, length = 10 }
                  Err { error } -> Err { error = error }
                Err { error } -> Err { error = error }
        """.trimIndent()
        assertErr(eval(source))
    }

    // ==================== Arity ====================

    @Test
    fun `wasm call with wrong arity errors`() {
        val source = """
            use std::collections::bytes as bytes
            use std::polyglot::wasm::{ Wasm, GraalWasm, from_int }
            perform GraalWasm in do
              ${moduleBytesMezzeLet()}
              match Wasm.load_module { bytes = wasm_bytes, name = "arith" } is
                Ok { val = mod } -> mod.call_export {
                  name = "add",
                  args = [from_int { val = 1 }],
                }
                Err { error } -> Err { error = error }
        """.trimIndent()
        assertErr(eval(source))
    }

    // ==================== Codec round-trip ====================

    @Test
    fun `wasm to_wasm int round trip via from_wasm`() {
        val result = eval(
            """
            use std::codec::wasm::{ ToWasm, FromWasm }
            use std::result
            let wf = (42).to_wasm{}
            wf.from_wasm{}.get_or { def = -1 }
            """.trimIndent()
        )
        assertEquals(42L, result)
    }

    @Test
    fun `wasm to_wasm float round trip via from_wasm`() {
        val result = eval(
            """
            use std::codec::wasm::{ ToWasm, FromWasm }
            use std::result
            let wf = (3.14).to_wasm{}
            wf.from_wasm{}.get_or { def = 0.0 }
            """.trimIndent()
        )
        assertEquals(3.14, result)
    }

    @Test
    fun `wasm from_int arg passed to add via wasm-idiomatic surface`() {
        // Exercises the wasm-tagged from_int constructor (__native_wasm_from_int)
        // vs the generic __native_polyglot_from_int — proves the language
        // tag routing works end-to-end.
        val source = """
            use std::collections::bytes as bytes
            use std::polyglot::wasm::{ Wasm, GraalWasm, from_int }
            perform GraalWasm in do
              ${moduleBytesMezzeLet()}
              match Wasm.load_module { bytes = wasm_bytes, name = "arith" } is
                Ok { val = mod } -> match mod.call_export {
                  name = "add",
                  args = [from_int { val = 100 }, from_int { val = 23 }],
                } is
                  Ok { val = r } -> r.as_int{}
                  Err { error } -> Err { error = error }
                Err { error } -> Err { error = error }
        """.trimIndent()
        val v = assertOk(eval(source))
        assertEquals(123L, v.getPayloadFieldByName("val"))
    }
}
