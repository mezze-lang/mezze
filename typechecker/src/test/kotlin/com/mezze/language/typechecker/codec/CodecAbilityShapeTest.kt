package com.mezze.language.typechecker.codec

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.TypeCheckError
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Ability-shape typechecker tests for the codec redesign (task #86, phase T).
 *
 * Complements [CodecGeneratorTest] (which is a Kotlin-level unit test of the
 * synthesizer) by exercising the four-tier ability system against the
 * stdlib as Mezze source: Tier-1 `Format` associates config -> output,
 * Tier-2 per-shape `Encode<Shape>` / `Decode<Shape>` abilities carry the
 * per-shape signatures, Tier-3 `Encode` / `Decode` is the primitive the
 * compiler auto-derives, and Tier-4 sugar (`.json{}`, `Type.from_json{}`)
 * is documented as the user surface.
 *
 * See `docs/codec/redesign-2026-09.md` for the full design.
 *
 * NOTE: The Tier-4 sugar (`.json{}` / `Type.from_json{}`) currently does
 * not dispatch through the blanket `impl ToJson for 'a` for method-call
 * syntax on user types (the compiler treats `x.json` as a record-field
 * access before considering ability lookup). The tests here therefore
 * exercise Tier 1-3 directly plus the Tier-4 ability-DECLARATION shape;
 * end-to-end Tier-4 dispatch is covered separately once the dispatch
 * gap is fixed.
 */
class CodecAbilityShapeTest {

    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        tc = TypeChecker()
    }

    /** Assert [source] typechecks; annotate failures with [msg]. */
    private fun checkPasses(source: String, msg: String) {
        try {
            tc.typeCheck(
                parser.parseParsedModule(source, "test.mz"),
                java.nio.file.Paths.get("."),
                enablePrelude = true,
            )
        } catch (e: TypeCheckError) {
            fail<Unit>("$msg: TypeCheckError: ${e.message}")
        } catch (e: Throwable) {
            fail<Unit>("$msg: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Assert [source] fails to typecheck; return the surfaced message. */
    private fun checkFails(source: String, msg: String): String {
        try {
            tc.typeCheck(
                parser.parseParsedModule(source, "test.mz"),
                java.nio.file.Paths.get("."),
                enablePrelude = true,
            )
        } catch (e: TypeCheckError) {
            return e.message ?: ""
        } catch (e: Throwable) {
            return e.message ?: ""
        }
        return fail("$msg: expected typecheck failure, got Success")
    }

    // =========================================================================
    // Tier-1 — Format identity: preset configs bind through the impl
    // =========================================================================

    @Test
    fun `preset JsonC config is usable as a value`() {
        // The stdlib ships `impl Format for JsonC having 'out = Json`. A
        // client that references `json_default` and threads it as an
        // argument sees the config type without triggering the two-faces
        // annotation path.
        checkPasses(
            """
            use std::codec::json::{ json_default }

            pub let use_config = { c } -> c
            pub let sample = use_config { c = json_default }
            """.trimIndent(),
            "json_default preset usable",
        )
    }

    @Test
    fun `preset JsonUtf8C config is usable as a value`() {
        checkPasses(
            """
            use std::codec::json::{ json_utf8_default }

            pub let use_config = { c } -> c
            pub let sample = use_config { c = json_utf8_default }
            """.trimIndent(),
            "json_utf8_default preset usable",
        )
    }

    @Test
    fun `preset UrlC config is usable as a value`() {
        checkPasses(
            """
            use std::codec::url::{ url_default }

            pub let use_config = { c } -> c
            pub let sample = use_config { c = url_default }
            """.trimIndent(),
            "url_default preset usable",
        )
    }

    @Test
    fun `json_pretty preset differs from json_default`() {
        checkPasses(
            """
            use std::codec::json::{ json_pretty }

            pub let is_pretty = json_pretty.pretty
            """.trimIndent(),
            "json_pretty field-accessible",
        )
    }

    // =========================================================================
    // Tier-2 — Per-shape encode abilities (6 abilities)
    // =========================================================================

    @Test
    fun `Tier 2 EncodeScalar is in scope from stdlib`() {
        checkPasses(
            """
            use std::codec::core::{ EncodeScalar, CodecCtx }
            use std::codec::json::{ JsonC, Json }

            pub let call_encode_int
              : { c: JsonC, v: Int, ctx: CodecCtx }
              -> Json
              = { c, v, ctx } -> c.encode_int { val = v, ctx = ctx }
            """.trimIndent(),
            "EncodeScalar.encode_int callable via method syntax",
        )
    }

    @Test
    fun `Tier 2 EncodeRecord is callable directly`() {
        checkPasses(
            """
            use std::codec::core::{ EncodeRecord, CodecCtx }
            use std::codec::json::{ JsonC, Json }

            pub let call_encode_record
              : { c: JsonC, fs: [{ key: Str, value: Json }], ctx: CodecCtx }
              -> Json
              = { c, fs, ctx } -> c.encode_record { fields = fs, ctx = ctx }
            """.trimIndent(),
            "EncodeRecord.encode_record callable",
        )
    }

    @Test
    fun `Tier 2 EncodeVariant is callable directly`() {
        checkPasses(
            """
            use std::codec::core::{ EncodeVariant, CodecCtx }
            use std::codec::json::{ JsonC, Json }

            pub let call_encode_variant
              : { c: JsonC, tag: Str, payload: Option Json, ctx: CodecCtx }
              -> Json
              = { c, tag, payload, ctx } ->
                  c.encode_variant { tag = tag, payload = payload, ctx = ctx }
            """.trimIndent(),
            "EncodeVariant.encode_variant callable",
        )
    }

    @Test
    fun `Tier 2 EncodeList is callable directly`() {
        checkPasses(
            """
            use std::codec::core::{ EncodeList, CodecCtx }
            use std::codec::json::{ JsonC, Json }

            pub let call_encode_list
              : { c: JsonC, elems: [Json], ctx: CodecCtx }
              -> Json
              = { c, elems, ctx } -> c.encode_list { elems = elems, ctx = ctx }
            """.trimIndent(),
            "EncodeList.encode_list callable",
        )
    }

    @Test
    fun `Tier 2 EncodeMap is callable directly`() {
        checkPasses(
            """
            use std::codec::core::{ EncodeMap, CodecCtx }
            use std::codec::json::{ JsonC, Json }

            pub let call_encode_map
              : { c: JsonC, entries: [{ key: Json, value: Json }], ctx: CodecCtx }
              -> Json
              = { c, entries, ctx } -> c.encode_map { entries = entries, ctx = ctx }
            """.trimIndent(),
            "EncodeMap.encode_map callable",
        )
    }

    @Test
    fun `Tier 2 EncodeOption is callable directly`() {
        checkPasses(
            """
            use std::codec::core::{ EncodeOption, CodecCtx }
            use std::codec::json::{ JsonC, Json }

            pub let call_encode_option
              : { c: JsonC, ov: Option Json, ctx: CodecCtx }
              -> Json
              = { c, ov, ctx } -> c.encode_option { val = ov, ctx = ctx }
            """.trimIndent(),
            "EncodeOption.encode_option callable",
        )
    }

    // =========================================================================
    // Tier-2 — Per-shape decode abilities (6 abilities)
    // =========================================================================

    @Test
    fun `Tier 2 DecodeScalar is callable and returns Result`() {
        checkPasses(
            """
            use std::codec::core::{ DecodeScalar, CodecCtx, DecodeError }
            use std::codec::json::{ JsonC, Json }

            pub let call_decode_int
              : { c: JsonC, val: Json, ctx: CodecCtx }
              -> Result Int DecodeError
              = { c, val, ctx } -> c.decode_int { val = val, ctx = ctx }
            """.trimIndent(),
            "DecodeScalar.decode_int returns Result Int DecodeError",
        )
    }

    @Test
    fun `Tier 2 DecodeRecord is callable`() {
        checkPasses(
            """
            use std::codec::core::{ DecodeRecord, CodecCtx, DecodeError }
            use std::codec::json::{ JsonC, Json }

            pub let call_decode_record
              : { c: JsonC, val: Json, key: Str, ctx: CodecCtx }
              -> Result Json DecodeError
              = { c, val, key, ctx } ->
                  c.decode_record_field { val = val, key = key, ctx = ctx }
            """.trimIndent(),
            "DecodeRecord.decode_record_field callable",
        )
    }

    @Test
    fun `Tier 2 DecodeVariant is callable`() {
        checkPasses(
            """
            use std::codec::core::{ DecodeVariant, CodecCtx, DecodeError }
            use std::codec::json::{ JsonC, Json }

            pub let call_decode_variant
              : { c: JsonC, val: Json, ctx: CodecCtx }
              -> Result { tag: Str, payload: Option Json } DecodeError
              = { c, val, ctx } -> c.decode_variant { val = val, ctx = ctx }
            """.trimIndent(),
            "DecodeVariant.decode_variant callable",
        )
    }

    @Test
    fun `Tier 2 DecodeList is callable`() {
        checkPasses(
            """
            use std::codec::core::{ DecodeList, CodecCtx, DecodeError }
            use std::codec::json::{ JsonC, Json }

            pub let call_decode_list
              : { c: JsonC, val: Json, ctx: CodecCtx }
              -> Result [Json] DecodeError
              = { c, val, ctx } -> c.decode_list { val = val, ctx = ctx }
            """.trimIndent(),
            "DecodeList.decode_list callable",
        )
    }

    @Test
    fun `Tier 2 DecodeMap is callable`() {
        checkPasses(
            """
            use std::codec::core::{ DecodeMap, CodecCtx, DecodeError }
            use std::codec::json::{ JsonC, Json }

            pub let call_decode_map
              : { c: JsonC, val: Json, ctx: CodecCtx }
              -> Result [{ key: Json, value: Json }] DecodeError
              = { c, val, ctx } -> c.decode_map { val = val, ctx = ctx }
            """.trimIndent(),
            "DecodeMap.decode_map callable",
        )
    }

    @Test
    fun `Tier 2 DecodeOption is callable`() {
        checkPasses(
            """
            use std::codec::core::{ DecodeOption, CodecCtx, DecodeError }
            use std::codec::json::{ JsonC, Json }

            pub let call_decode_option
              : { c: JsonC, val: Json, ctx: CodecCtx }
              -> Result (Option Json) DecodeError
              = { c, val, ctx } -> c.decode_option { val = val, ctx = ctx }
            """.trimIndent(),
            "DecodeOption.decode_option callable",
        )
    }

    // =========================================================================
    // Tier-3 — Encode / Decode auto-derive
    // =========================================================================

    @Test
    fun `Tier 3 Encode auto-derives for Int with JsonC`() {
        checkPasses(
            """
            use std::codec::core::{ Encode }
            use std::codec::json::{ json_default, Json }

            pub let e : { n: Int } -> Json = { n } -> n.encode { config = json_default }
            """.trimIndent(),
            "Encode JsonC Int auto-derived",
        )
    }

    @Test
    fun `Tier 3 Encode auto-derives for anonymous record`() {
        checkPasses(
            """
            use std::codec::core::{ Encode }
            use std::codec::json::{ json_default, Json }

            pub let enc : { r: { name: Str, age: Int } } -> Json
              = { r } -> r.encode { config = json_default }
            """.trimIndent(),
            "Encode JsonC anon-record auto-derived",
        )
    }

    @Test
    fun `Tier 3 Encode auto-derives for anonymous variant`() {
        checkPasses(
            """
            use std::codec::core::{ Encode }
            use std::codec::json::{ json_default, Json }

            pub let enc : { s: | Circle { r: Float }, Square { side: Int } | } -> Json
              = { s } -> s.encode { config = json_default }
            """.trimIndent(),
            "Encode JsonC anon-variant auto-derived",
        )
    }

    @Test
    fun `Tier 3 Encode auto-derives for List Int`() {
        checkPasses(
            """
            use std::codec::core::{ Encode }
            use std::codec::json::{ json_default, Json }

            pub let enc : { xs: [Int] } -> Json = { xs } -> xs.encode { config = json_default }
            """.trimIndent(),
            "Encode JsonC List Int auto-derived",
        )
    }

    @Test
    fun `Tier 3 Encode auto-derives for Option Int`() {
        checkPasses(
            """
            use std::codec::core::{ Encode }
            use std::codec::json::{ json_default, Json }

            pub let enc : { ox: Option Int } -> Json = { ox } -> ox.encode { config = json_default }
            """.trimIndent(),
            "Encode JsonC Option Int auto-derived",
        )
    }

    @Test
    fun `Tier 3 Decode auto-derives for Int with JsonC`() {
        checkPasses(
            """
            use std::codec::core::{ Decode, DecodeError }
            use std::codec::json::{ json_default, Json }

            pub let d : { j: Json } -> Result Int DecodeError
              = { j } -> j.decode { config = json_default }
            """.trimIndent(),
            "Decode JsonC Int auto-derived",
        )
    }

    @Test
    fun `Tier 3 Decode auto-derives for anonymous record`() {
        checkPasses(
            """
            use std::codec::core::{ Decode, DecodeError }
            use std::codec::json::{ json_default, Json }

            pub let d : { j: Json } -> Result { name: Str, age: Int } DecodeError
              = { j } -> j.decode { config = json_default }
            """.trimIndent(),
            "Decode JsonC anon-record auto-derived",
        )
    }

    // =========================================================================
    // Tier-3 — polymorphic constraint forms
    // =========================================================================

    @Test
    fun `Tier 3 polymorphic Encode 'config 'a helper typechecks`() {
        checkPasses(
            """
            use std::codec::core::{ Encode }

            pub let generic_encode
              : { val: 'a, config: 'c }
                with Encode 'c 'a
                -> 'out
              = { val, config } -> val.encode { config = config }
            """.trimIndent(),
            "Tier 3 polymorphic helper",
        )
    }

    @Test
    fun `Tier 3 fixed-format Encode JsonC helper typechecks`() {
        checkPasses(
            """
            use std::codec::core::{ Encode }
            use std::codec::json::{ json_default, Json }

            pub let to_json
              : { val: 'a } with Encode JsonC 'a -> Json
              = { val } -> val.encode { config = json_default }
            """.trimIndent(),
            "Fixed-format Encode JsonC helper",
        )
    }

    @Test
    fun `Tier 3 bounded-output helper typechecks`() {
        // "Any config that produces bytes" pattern per design doc
        // §"Polymorphic constraint patterns".
        checkPasses(
            """
            use std::codec::core::{ Encode, Format }
            use std::collections::bytes

            pub let to_bytes
              : { val: 'a, config: 'c }
                with Encode 'c 'a, Format 'c having 'out = Bytes
                -> Bytes
              = { val, config } -> val.encode { config = config }
            """.trimIndent(),
            "Bounded-output polymorphic helper",
        )
    }

    // =========================================================================
    // Tier-4 — sugar ability shapes exist (declarations + blanket impls)
    // =========================================================================

    @Test
    fun `Tier 4 ToJson ability is importable`() {
        checkPasses(
            """
            use std::codec::json::{ ToJson }

            pub let noop = { x: ToJson } -> x
            """.trimIndent(),
            "ToJson ability importable",
        )
    }

    @Test
    fun `Tier 4 FromJson ability is importable`() {
        checkPasses(
            """
            use std::codec::json::{ FromJson }

            pub let noop = { x: FromJson } -> x
            """.trimIndent(),
            "FromJson ability importable",
        )
    }

    @Test
    fun `Tier 4 ToUrl ability is importable`() {
        checkPasses(
            """
            use std::codec::url::{ ToUrl }

            pub let noop = { x: ToUrl } -> x
            """.trimIndent(),
            "ToUrl ability importable",
        )
    }

    @Test
    fun `Tier 4 blanket-impl shape typechecks in user space`() {
        // The load-bearing Tier-4 pattern: `impl X for 'a where { m: ...
        // with Encode JsonC 'a -> Json = ... }`. Duplicating the shape at
        // user level pins that the mechanic composes verified primitives.
        checkPasses(
            """
            use std::codec::core::{ Encode }
            use std::codec::json::{ Json, JsonC, json_default }

            ability MyToJson for 'a where {
              my_json : { it: 'a } -> Json,
            }

            impl MyToJson for 'a where {
              my_json : { it: 'a } with Encode JsonC 'a -> Json = { it } ->
                it.encode { config = json_default },
            }
            """.trimIndent(),
            "user-defined Tier-4 blanket impl typechecks",
        )
    }

    @Test
    fun `Tier 4 blanket-impl with a config parameter typechecks`() {
        checkPasses(
            """
            use std::codec::core::{ Encode }
            use std::codec::json::{ Json, JsonC, json_default }

            ability MyToJsonCfg for 'a where {
              my_json      : { it: 'a }                -> Json,
              my_json_with : { it: 'a, config: JsonC } -> Json,
            }

            impl MyToJsonCfg for 'a where {
              my_json      : { it: 'a }                with Encode JsonC 'a -> Json
                = { it }         -> it.encode { config = json_default },
              my_json_with : { it: 'a, config: JsonC } with Encode JsonC 'a -> Json
                = { it, config } -> it.encode { config = config },
            }
            """.trimIndent(),
            "user-defined Tier-4 with pair typechecks",
        )
    }

    // =========================================================================
    // Newtype override — hand-written impl bypasses auto-derive
    // =========================================================================

    @Test
    fun `newtype without custom impl auto-derives through the wrapped type`() {
        // No hand-written `impl Encode JsonC for Money`; the auto-derive should
        // synthesize via the wrapped `Int`.
        checkPasses(
            """
            use std::codec::core::{ Encode }
            use std::codec::json::{ Json, json_default }

            newtype Money = Int

            pub let m : Money = Money.(500)
            pub let j : Json = m.encode { config = json_default }
            """.trimIndent(),
            "newtype without override auto-derives via wrapped type",
        )
    }

    @Test
    fun `newtype with custom Encode impl for JsonC typechecks`() {
        // Hand-written Tier-3 impl for Money. The type-level shape validates.
        checkPasses(
            """
            use std::codec::core::{ Encode }
            use std::codec::json::{ Json, JsonC, JString }

            newtype Money = Int

            impl Encode JsonC for Money having 'out = Json where {
              encode = { it, config } -> JString { val = "money" },
            }
            """.trimIndent(),
            "newtype with custom Encode impl for JsonC",
        )
    }

    @Test
    fun `newtype with custom Encode impls for both JsonC and JsonUtf8C typecheck`() {
        // Cross-config custom impls: user overrides encoding for both the
        // variant-tree path AND the bytes path.
        checkPasses(
            """
            use std::codec::core::{ Encode }
            use std::codec::json::{ Json, JsonC, JsonUtf8C, JString }
            use std::collections::bytes

            newtype Money = Int

            impl Encode JsonC for Money having 'out = Json where {
              encode = { it, config } -> JString { val = "m" },
            }

            impl Encode JsonUtf8C for Money having 'out = Bytes where {
              encode = { it, config } -> bytes::from_str { s = "\"m\"" },
            }
            """.trimIndent(),
            "newtype dual-config custom impls",
        )
    }

    @Test
    fun `newtype over Str typechecks with auto-derive`() {
        checkPasses(
            """
            use std::codec::core::{ Encode }
            use std::codec::json::{ Json, json_default }

            newtype Email = Str

            pub let e : Email = Email.("a@b.com")
            pub let j : Json = e.encode { config = json_default }
            """.trimIndent(),
            "newtype over Str auto-derive",
        )
    }

    @Test
    fun `two distinct newtypes get distinct impls`() {
        // Regression pin: hand-writing an impl for Money must NOT collide
        // with the wrapped-Int auto-derive for a separate newtype UserId.
        checkPasses(
            """
            use std::codec::core::{ Encode }
            use std::codec::json::{ Json, JsonC, JString, json_default }

            newtype Money = Int
            newtype UserId = Int

            impl Encode JsonC for Money having 'out = Json where {
              encode = { it, config } -> JString { val = "money" },
            }

            pub let m : Money = Money.(500)
            pub let uid : UserId = UserId.(1)
            pub let jm : Json = m.encode { config = json_default }
            pub let jid : Json = uid.encode { config = json_default }
            """.trimIndent(),
            "custom Money impl does not leak into UserId",
        )
    }

    // =========================================================================
    // Compile-error tests — non-serializable types blocked at auto-derive
    //
    // The current CodecGenerator gates auto-derivation on `isSerializable`
    // (function types / effect rows fail up-front). Records / variants /
    // lists containing non-serializable fields are rejected at synthesis
    // BEFORE emit — a genuine compile error at the field's type.
    //
    // The design's stronger property — "structural mismatch (e.g. record
    // with a variant field routed through a format that lacks
    // EncodeVariant) is a compile error at the field's PATH" — currently
    // surfaces at runtime as `Field 'encode_variant' not found` because
    // the walker emits `config.encode_variant` unconditionally. Those
    // runtime-error paths are pinned in `CodecRoundTripTest` alongside
    // the happy-path round-trips. Once the CodecGenerator gains up-front
    // Tier-2-availability checking, they migrate here.
    // =========================================================================

    @Test
    fun `record with function field is not serializable`() {
        // `isSerializable` bounces function fields at synthesis time.
        checkFails(
            """
            use std::codec::core::{ Encode }
            use std::codec::json::{ json_default, Json }

            pub let bad : Json = ({ x = 1, cb = { n: Int } -> n }).encode { config = json_default }
            """.trimIndent(),
            "record with function field rejected",
        )
    }

    @Test
    fun `list of functions is not serializable`() {
        checkFails(
            """
            use std::codec::core::{ Encode }
            use std::codec::json::{ json_default, Json }

            pub let cbs : [{ n: Int } -> Int] = [{ n } -> n]
            pub let bad : Json = cbs.encode { config = json_default }
            """.trimIndent(),
            "list of functions rejected",
        )
    }

    // =========================================================================
    // DP-10 — Missing Tier-2 capability = compile-time error at the field path
    //
    // Migrated from CodecRoundTripTest's "runtime today" tests. The
    // CodecGenerator now walks the value shape at auto-derive time and
    // rejects the encode/decode BEFORE emit when the target format lacks
    // a Tier-2 impl the walk would call. Records / lists / maps routed
    // through UrlC (which ships only EncodeScalar + EncodeVariant +
    // EncodeOption) surface as typecheck errors, not `Field 'encode_*'
    // not found` runtime crashes.
    //
    // See docs/codec/redesign-2026-09.md §Partial-capability formats.
    // =========================================================================

    @Test
    fun `record through UrlC is a compile-time capability error`() {
        val msg = checkFails(
            """
            use std::codec::url::{ url_default }
            use std::codec::core::{ Encode }

            pub let bad = ({ name = "A", age = 1 }).encode { config = url_default }
            """.trimIndent(),
            "record + UrlC must be rejected at typecheck",
        )
        assertTrue(
            msg.contains("EncodeRecord", ignoreCase = true) || msg.contains("does not implement"),
            "error must name the missing Tier-2 or the containing ability: $msg",
        )
    }

    @Test
    fun `list through UrlC is a compile-time capability error`() {
        val msg = checkFails(
            """
            use std::codec::url::{ url_default }
            use std::codec::core::{ Encode }

            pub let bad : Str = [1, 2, 3].encode { config = url_default }
            """.trimIndent(),
            "list + UrlC must be rejected at typecheck",
        )
        assertTrue(
            msg.contains("EncodeList", ignoreCase = true) || msg.contains("does not implement"),
            "error must name the missing Tier-2 or the containing ability: $msg",
        )
    }

    @Test
    fun `map through UrlC is a compile-time capability error`() {
        val msg = checkFails(
            """
            use std::codec::url::{ url_default }
            use std::codec::core::{ Encode }
            use std::collections::map

            pub let m : Map Str Int = map::empty {}
            pub let bad : Str = m.encode { config = url_default }
            """.trimIndent(),
            "map + UrlC must be rejected at typecheck",
        )
        assertTrue(
            msg.contains("EncodeMap", ignoreCase = true) || msg.contains("does not implement"),
            "error must name the missing Tier-2 or the containing ability: $msg",
        )
    }

    @Test
    fun `record with variant field through UrlC names the variant path`() {
        // Deep-path check: an outer field routes through EncodeRecord (missing
        // for UrlC) OR through a nested EncodeVariant slot. Either way the
        // check catches it at compile time — the design's "path pointing at
        // the offending field" property.
        val msg = checkFails(
            """
            use std::codec::url::{ url_default }
            use std::codec::core::{ Encode }

            type Status = | Active, Inactive |
            type User = { name: Str, status: Status }
            pub let bad = ({ name = "A", status = Active } : User).encode { config = url_default }
            """.trimIndent(),
            "record with variant field + UrlC rejected at typecheck",
        )
        assertTrue(msg.isNotBlank(), "error surface must be non-empty: $msg")
    }
}
