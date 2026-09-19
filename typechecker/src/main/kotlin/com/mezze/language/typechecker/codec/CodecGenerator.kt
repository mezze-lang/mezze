package com.mezze.language.typechecker.codec

import com.mezze.language.ast.BuiltinTypes
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.PrimitiveTypes
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.solving.expandTypeApplicationToRecord
import com.mezze.language.typechecker.solving.expandTypeApplicationToVariant

/**
 * Auto-derivation of the Tier-3 codec abilities (`Encode` and `Decode`).
 *
 * When the constraint solver sees an `Encode 'config 'a` or `Decode 'config 'a 'in`
 * obligation with no matching user impl, this generator synthesizes one by
 * walking the value-type shape and dispatching to the format's Tier-2 per-
 * shape ability methods (`config.encode_int`, `config.encode_record`,
 * `config.decode_variant`, …). Missing Tier-2 capability (e.g. CSV lacking
 * `EncodeVariant`) surfaces naturally as a typecheck error at the field's
 * method call site — the enclosing solver adds path context.
 *
 * User-written impls always take precedence over auto-derivation.
 *
 * See `docs/codec/redesign-2026-09.md` §"Tier 3 — Encode / Decode primitive".
 */
class CodecGenerator(
    // Used to expand `Declared` TypeApplication to its underlying RecordType /
    // VariantType so the emitter walks the shape here instead of recursing back
    // into itself through Tier-3 (`it.encode { config = config }`). Callers that
    // only need `isSerializable` can leave the default — expansion is only
    // consulted when a user-declared TypeApplication reaches the encode/decode
    // walk.
    private val registry: DeclarationRegistry = DeclarationRegistry.EMPTY,
) {

    private val encodeCache = mutableMapOf<String, ParsedImplAbilityDecl>()
    private val decodeCache = mutableMapOf<String, ParsedImplAbilityDecl>()

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /**
     * Return true iff a codec impl can be auto-derived for [type]. Function
     * / effect-row types are unsupported; everything else with derivable
     * children is.
     */
    fun isSerializable(type: MezzeType): Boolean = when (type) {
        is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
        is MezzeType.BoolType, is MezzeType.FracType, is MezzeType.CharType,
        is MezzeType.BytesType -> true
        is MezzeType.FunctionType -> false
        is MezzeType.EffectRow -> false
        is MezzeType.RecordType -> type.fields.values.all { isSerializable(it) }
        is MezzeType.VariantType -> type.constructors.values.all { payload ->
            payload == null || isSerializable(payload)
        }
        is MezzeType.ListType -> isSerializable(type.elementType)
        is MezzeType.SeqType -> isSerializable(type.elementType)
        is MezzeType.TypeApplication -> type.args.all { isSerializable(it) }
        is MezzeType.NewtypeType -> false
        is MezzeType.VarType -> true
    }

    /**
     * Synthesize `impl Encode <formatType> for <dataType>` whose body walks
     * [dataType] and dispatches per-field / per-element / per-constructor to
     * the format's Tier-2 encoder methods (via `config.encode_<shape>`) and
     * recurses into compound children through Tier 3 (`x.encode { config = config }`).
     *
     * The impl header omits `having 'out = …` — the Format ability's associated
     * type resolves that at constraint-solve time.
     */
    fun generateEncodeImpl(formatType: MezzeType, dataType: MezzeType): ParsedImplAbilityDecl {
        val key = "$formatType|$dataType"
        encodeCache[key]?.let { return it }

        val encoded = buildEncodeExpr(dataType, id("it"))
        val body = doWithLet("ctx", initialCtx(), encoded)
        val impl = createEncodeImpl(formatType, dataType, body)
        encodeCache[key] = impl
        return impl
    }

    /**
     * Synthesize `impl Decode <formatType> <dataType> for <inType>` whose body
     * walks [dataType] and dispatches per-shape to `config.decode_*` (Tier 2),
     * recursing into compound children through Tier 3.
     *
     * [inType] is the format's input representation (`Json` for `JsonC`,
     * `Bytes` for `JsonUtf8C`, `Str` for `UrlC`) — the emitter reads it off
     * the 3-slot constraint that triggered this synthesis.
     */
    fun generateDecodeImpl(
        formatType: MezzeType,
        dataType: MezzeType,
        inType: MezzeType,
    ): ParsedImplAbilityDecl {
        val key = "$formatType|$dataType|$inType"
        decodeCache[key]?.let { return it }

        val decoded = buildDecodeExpr(dataType, id("it"))
        val body = doWithLet("ctx", initialCtx(), decoded)
        val impl = createDecodeImpl(formatType, dataType, inType, body)
        decodeCache[key] = impl
        return impl
    }

    // ---------------------------------------------------------------------
    // Encode body construction
    // ---------------------------------------------------------------------

    /** Encode a value of [type] bound to [valueExpr] into the format's output. */
    private fun buildEncodeExpr(type: MezzeType, valueExpr: ParsedExpr): ParsedExpr {
        if (PrimitiveTypes.isBoolVariant(type)) return scalarEncode("encode_bool", valueExpr)
        return when (type) {
            is MezzeType.IntType    -> scalarEncode("encode_int",   valueExpr)
            is MezzeType.FloatType  -> scalarEncode("encode_float", valueExpr)
            is MezzeType.StringType -> scalarEncode("encode_str",   valueExpr)
            is MezzeType.BoolType   -> scalarEncode("encode_bool",  valueExpr)
            is MezzeType.FracType   -> scalarEncode("encode_frac",  valueExpr)
            is MezzeType.CharType   -> scalarEncode("encode_char",  valueExpr)
            is MezzeType.BytesType  -> scalarEncode("encode_bytes", valueExpr)

            is MezzeType.RecordType -> buildEncodeRecord(type, valueExpr)
            is MezzeType.VariantType -> buildEncodeVariant(type, valueExpr)

            // All four are encoded as a JSON array, but only a Seq is already a
            // MezzeSeq — List/Set/SortedSet are distinct runtime containers, so
            // they must be converted to a seq before __native_seq_map (which
            // rejects a non-Seq with "Expected MezzeSeq, got MezzeLongCons/…").
            is MezzeType.SeqType     -> buildEncodeList(type.elementType, valueExpr, toSeq = null)
            is MezzeType.ListType    -> buildEncodeList(type.elementType, valueExpr, toSeq = "__native_list_to_seq")


            is MezzeType.TypeApplication -> when (type.constructor) {
                BuiltinTypes.OPTION ->
                    buildEncodeOption(type.args.firstOrNull(), valueExpr)
                // Builtin collections can reach the walk as a `TypeApplication`
                // (identity=Builtin) rather than the structural `MezzeType.ListType`
                // / `MapType` / … — e.g. a native fn's declared `Map 'k 'v` return
                // type surfaces this way. Route them to the same dedicated builders
                // so we DON'T fall through to `tierThreeEncode` and emit
                // `it.encode { config }` — a self-call into this very derived impl
                // that stack-overflows at runtime. Mirrors AbilityChecker's
                // walkTier2Capabilities, which already special-cases these.
                BuiltinTypes.SEQ ->
                    buildEncodeListApp(type.args, valueExpr, toSeq = null)
                BuiltinTypes.LIST ->
                    buildEncodeListApp(type.args, valueExpr, toSeq = "__native_list_to_seq")
                BuiltinTypes.SET ->
                    buildEncodeListApp(type.args, valueExpr, toSeq = "__native_set_to_seq")
                BuiltinTypes.SORTED_SET ->
                    buildEncodeListApp(type.args, valueExpr, toSeq = "__native_sorted_set_to_seq")
                BuiltinTypes.MAP ->
                    buildEncodeMapApp(type.args, valueExpr, toSeqNative = "__native_map_to_seq")
                BuiltinTypes.SORTED_MAP ->
                    buildEncodeMapApp(type.args, valueExpr, toSeqNative = "__native_sorted_map_to_seq")
                else -> when {
                    type.identity is TypeIdentity.Declared ->
                        buildEncodeDeclared(type, valueExpr)
                    else -> tierThreeEncode(valueExpr)
                }
            }

            // Newtypes / VarTypes / anything else: recurse through Tier 3.
            // Any user-written `impl Encode 'config for T` wins the dispatch;
            // otherwise the constraint solver drives its own auto-derivation.
            else -> tierThreeEncode(valueExpr)
        }
    }

    /**
     * Expand a user-declared `TypeApplication` (e.g. `Person`, `Colour`) to its
     * underlying record / variant shape and walk that shape here. Without this
     * step the emit would fall through to [tierThreeEncode] and lower to
     * `it.encode { config = config }` — a self-call inside the impl body we're
     * synthesizing, causing runtime infinite recursion.
     *
     * If expansion doesn't yield a record / variant (e.g. the decl is a
     * newtype, or the registry is empty at this call site), fall back to
     * Tier-3 so a user-written impl can still win the dispatch.
     */
    private fun buildEncodeDeclared(type: MezzeType.TypeApplication, valueExpr: ParsedExpr): ParsedExpr {
        expandTypeApplicationToRecord(type, registry)?.let {
            return buildEncodeRecord(it, valueExpr)
        }
        expandTypeApplicationToVariant(type, registry)?.let { variant ->
            // Only walk true variants here. Newtypes also come back through
            // this helper wrapped as a single-constructor variant — that
            // opacity case is intentionally left to Tier-3 (see the known-gap
            // note at the top of CodecRoundTripTest.kt).
            val decl = registry.lookupType(type.constructor,
                (type.identity as? TypeIdentity.Declared)?.hash)
            if (decl?.definition !is ParsedNewtypeDef) {
                return buildEncodeVariant(variant, valueExpr)
            }
        }
        return tierThreeEncode(valueExpr)
    }

    /** `config.encode_<hook> { val = <valueExpr>, ctx = ctx }` */
    private fun scalarEncode(hookName: String, valueExpr: ParsedExpr): ParsedExpr =
        call(
            member(id("config"), hookName),
            record("val" to valueExpr, "ctx" to id("ctx")),
        )

    /** Nested-value dispatch via Tier 3: `<valueExpr>.encode { config = config }`. */
    private fun tierThreeEncode(valueExpr: ParsedExpr): ParsedExpr =
        call(
            member(valueExpr, "encode"),
            record("config" to id("config")),
        )

    private fun buildEncodeRecord(type: MezzeType.RecordType, valueExpr: ParsedExpr): ParsedExpr {
        val fields = type.fields.entries.sortedBy { it.key }
        val encodedFieldsSeq = seqLit(
            fields.map { (fieldName, fieldType) ->
                record(
                    "key" to transformFieldName(fieldName),
                    "value" to buildEncodeExpr(fieldType, member(valueExpr, fieldName)),
                )
            }
        )
        return call(
            member(id("config"), "encode_record"),
            record("fields" to encodedFieldsSeq, "ctx" to id("ctx")),
        )
    }

    private fun buildEncodeVariant(type: MezzeType.VariantType, valueExpr: ParsedExpr): ParsedExpr {
        val cases = type.constructors.entries.sortedBy { it.key }.map { (ctorName, payloadType) ->
            if (payloadType == null) {
                matchCase(
                    constructorPat(ctorName, null),
                    call(
                        member(id("config"), "encode_variant"),
                        record(
                            "tag" to str(ctorName),
                            "payload" to none(),
                            "ctx" to id("ctx"),
                        ),
                    ),
                )
            } else {
                // Payload is (in Mezze) a record — the compiler's variant-pattern
                // lowering only handles record-shaped payloads (constructor pattern
                // with a bare variable pattern uses the variable NAME as a real
                // field key at runtime — see TypedASTCompiler.compilePattern's
                // TypedVarPattern branch). So we always destructure the payload
                // record's fields explicitly, bind each to a fresh local, and
                // rebuild the record literal before encoding it.
                val (pattern, rebuiltPayload) = destructureAndRebuildPayload(ctorName, payloadType)
                val encodedPayload = buildEncodeExpr(payloadType, rebuiltPayload)
                matchCase(
                    constructorPat(ctorName, pattern),
                    call(
                        member(id("config"), "encode_variant"),
                        record(
                            "tag" to str(ctorName),
                            "payload" to some(encodedPayload),
                            "ctx" to id("ctx"),
                        ),
                    ),
                )
            }
        }
        return match(valueExpr, *cases.toTypedArray())
    }

    /**
     * Build a record pattern that binds every field of a variant's payload record,
     * plus the expression that reconstructs the payload record from those bindings.
     * For a non-record payload (rare — Mezze variants normally carry record
     * payloads), fall back to a bare-variable pattern; the runtime handles that
     * path via the single-field variant lowering.
     */
    private fun destructureAndRebuildPayload(
        ctorName: String,
        payloadType: MezzeType,
    ): Pair<ParsedPattern, ParsedExpr> {
        if (payloadType is MezzeType.RecordType) {
            val bindingNames = payloadType.fields.keys.associateWith { fieldName ->
                "__${ctorName}_${fieldName}"
            }
            val patternEntries = payloadType.fields.entries.map { (fieldName, _) ->
                ParsedRecordPatternEntry(
                    name = fieldName,
                    pattern = varPat(bindingNames[fieldName]!!),
                    loc = GEN_LOC,
                )
            }
            val pattern = ParsedRecordPattern(entries = patternEntries, isOpen = false, loc = GEN_LOC)
            val rebuiltPayload = ParsedRecordLit(
                entries = payloadType.fields.entries.map { (fieldName, _) ->
                    ParsedRecordEntry.Field(fieldName, id(bindingNames[fieldName]!!), GEN_LOC)
                },
                loc = GEN_LOC,
            )
            return pattern to rebuiltPayload
        }
        // Non-record payload — fall back to a whole-payload variable binding.
        // The runtime's variant-pattern lowering stores this as a single-slot
        // "value" field lookup, matching the single-argument variant shape.
        val payloadVar = "__${ctorName}_payload"
        return varPat(payloadVar) to id(payloadVar)
    }

    private fun buildEncodeList(elemType: MezzeType, valueExpr: ParsedExpr, toSeq: String?): ParsedExpr {
        // __native_seq_map only accepts a MezzeSeq, so a non-Seq container is
        // first converted (List/Set/SortedSet). The converters take the value
        // under different field names: list -> `xs`, set/sorted-set -> `s`.
        val asSeq = when (toSeq) {
            null -> valueExpr
            "__native_list_to_seq" -> call(id(toSeq), record("xs" to valueExpr))
            else -> call(id(toSeq), record("s" to valueExpr))
        }
        // seq::map { xs = <asSeq>, f = { x } -> <encode x> } — then wrap.
        val mappedElems = call(
            id("__native_seq_map"),
            record(
                "xs" to asSeq,
                "f" to lambda(listOf("x"), buildEncodeExpr(elemType, id("x"))),
            ),
        )
        return call(
            member(id("config"), "encode_list"),
            record("elems" to mappedElems, "ctx" to id("ctx")),
        )
    }

    private fun buildEncodeMap(
        keyType: MezzeType,
        valType: MezzeType,
        valueExpr: ParsedExpr,
        // The to-seq native differs per map kind: a Map is a MezzeMap
        // (__native_map_to_seq), a SortedMap is a distinct MezzeSortedMap
        // (__native_sorted_map_to_seq) — passing a SortedMap to the Map native
        // throws "Expected MezzeMap for field 'm'". Both yield a MezzeSeq of
        // `{ k, v }` records.
        toSeqNative: String = "__native_map_to_seq",
    ): ParsedExpr {
        // Entries as a MezzeSEQ of { k, v } records. Must be *_to_seq, not
        // *_entries: the latter returns a MezzeList, which __native_seq_map
        // rejects (Expected MezzeSeq). Element fields are `k` and `v` (see
        // MapNatives / SortedMapNatives) — NOT `key`/`value`; reading the wrong
        // field names produced garbage (and a runaway encode).
        val entries = call(id(toSeqNative), record("m" to valueExpr))
        // __native_seq_map wraps each element as `{ x = <elem> }` (MezzeRecord.wrapX),
        // so the callback param MUST be named `x` (untyped lambda binds by field pun)
        // and the entry record is reached via `x.k` / `x.v` — the `{k,v}` entry lives
        // under `x`, NOT as a bare `entry` binding. (A param named `entry` finds no
        // `entry` field in `{ x = … }`, then `entry.k` → "Field not found: k".)
        val encodedEntries = call(
            id("__native_seq_map"),
            record(
                "xs" to entries,
                "f" to lambda(
                    listOf("x"),
                    record(
                        "key" to buildEncodeExpr(keyType, member(id("x"), "k")),
                        "value" to buildEncodeExpr(valType, member(id("x"), "v")),
                    ),
                ),
            ),
        )
        return call(
            member(id("config"), "encode_map"),
            record("entries" to encodedEntries, "ctx" to id("ctx")),
        )
    }

    /** [buildEncodeList] entry when the container arrived as a `TypeApplication`
     *  (its element type is [args]`[0]`). With no element arg (opaque container),
     *  fall back to Tier-3 so a user impl can win — never emit a bare self-call. */
    private fun buildEncodeListApp(args: List<MezzeType>, valueExpr: ParsedExpr, toSeq: String?): ParsedExpr {
        val elem = args.firstOrNull() ?: return tierThreeEncode(valueExpr)
        return buildEncodeList(elem, valueExpr, toSeq)
    }

    /** [buildEncodeMap] entry when the map arrived as a `TypeApplication`
     *  (key = [args]`[0]`, value = [args]`[1]`). Missing args ⇒ Tier-3 fallback.
     *  [toSeqNative] selects the per-kind entries native (Map vs SortedMap). */
    private fun buildEncodeMapApp(args: List<MezzeType>, valueExpr: ParsedExpr, toSeqNative: String): ParsedExpr {
        val k = args.getOrNull(0) ?: return tierThreeEncode(valueExpr)
        val v = args.getOrNull(1) ?: return tierThreeEncode(valueExpr)
        return buildEncodeMap(k, v, valueExpr, toSeqNative)
    }

    private fun buildEncodeOption(innerType: MezzeType?, valueExpr: ParsedExpr): ParsedExpr {
        val optionVal = if (innerType == null) {
            valueExpr // opaque `Option 'a` — hand it to the format as-is.
        } else {
            match(
                valueExpr,
                matchCase(nonePat(), none()),
                matchCase(
                    somePat("__opt"),
                    some(buildEncodeExpr(innerType, id("__opt"))),
                ),
            )
        }
        return call(
            member(id("config"), "encode_option"),
            record("val" to optionVal, "ctx" to id("ctx")),
        )
    }

    // ---------------------------------------------------------------------
    // Decode body construction
    // ---------------------------------------------------------------------

    /** Decode [type] out of [inputExpr]. Returns `Result <type> DecodeError`. */
    private fun buildDecodeExpr(type: MezzeType, inputExpr: ParsedExpr): ParsedExpr {
        if (PrimitiveTypes.isBoolVariant(type)) return scalarDecode("decode_bool", inputExpr)
        return when (type) {
            is MezzeType.IntType    -> scalarDecode("decode_int",   inputExpr)
            is MezzeType.FloatType  -> scalarDecode("decode_float", inputExpr)
            is MezzeType.StringType -> scalarDecode("decode_str",   inputExpr)
            is MezzeType.BoolType   -> scalarDecode("decode_bool",  inputExpr)
            is MezzeType.FracType   -> scalarDecode("decode_frac",  inputExpr)
            is MezzeType.CharType   -> scalarDecode("decode_char",  inputExpr)
            is MezzeType.BytesType  -> scalarDecode("decode_bytes", inputExpr)

            is MezzeType.RecordType -> buildDecodeRecord(type, inputExpr)
            is MezzeType.VariantType -> buildDecodeVariant(type, inputExpr)

            is MezzeType.ListType     -> buildDecodeList(type.elementType, inputExpr)
            is MezzeType.SeqType      -> buildDecodeList(type.elementType, inputExpr)


            is MezzeType.TypeApplication -> when (type.constructor) {
                BuiltinTypes.OPTION ->
                    buildDecodeOption(type.args.firstOrNull(), inputExpr)
                // Builtin collections surfaced as `TypeApplication` (identity=Builtin)
                // instead of the structural `MezzeType.ListType`/`MapType`/… — mirror
                // the encode side and route to the dedicated builders rather than
                // falling through to `tierThreeDecode` (a self-call into this derived
                // impl → runtime stack overflow). Matches AbilityChecker's
                // walkTier2Capabilities.
                BuiltinTypes.LIST, BuiltinTypes.SEQ, BuiltinTypes.SET, BuiltinTypes.SORTED_SET ->
                    buildDecodeListApp(type.args, inputExpr)
                BuiltinTypes.MAP, BuiltinTypes.SORTED_MAP ->
                    buildDecodeMapApp(type.args, inputExpr)
                else -> when {
                    type.identity is TypeIdentity.Declared ->
                        buildDecodeDeclared(type, inputExpr)
                    else -> tierThreeDecode(inputExpr)
                }
            }

            else -> tierThreeDecode(inputExpr)
        }
    }

    /** Decode-side mirror of [buildEncodeDeclared]. Expands `Declared`
     *  TypeApplications to their underlying record / variant shape and walks
     *  that shape here — the previous fall-through to [tierThreeDecode]
     *  synthesized `it.decode { config = config }` inside the very impl we're
     *  generating (self-recursion). Newtypes are still routed through Tier-3
     *  so a hand-written impl can pick them up.
     */
    private fun buildDecodeDeclared(type: MezzeType.TypeApplication, inputExpr: ParsedExpr): ParsedExpr {
        expandTypeApplicationToRecord(type, registry)?.let {
            return buildDecodeRecord(it, inputExpr)
        }
        expandTypeApplicationToVariant(type, registry)?.let { variant ->
            val decl = registry.lookupType(type.constructor,
                (type.identity as? TypeIdentity.Declared)?.hash)
            if (decl?.definition !is ParsedNewtypeDef) {
                return buildDecodeVariant(variant, inputExpr)
            }
        }
        return tierThreeDecode(inputExpr)
    }

    /** `config.decode_<hook> { val = <inputExpr>, ctx = ctx }`. Returns `Result T DecodeError`. */
    private fun scalarDecode(hookName: String, inputExpr: ParsedExpr): ParsedExpr =
        call(
            member(id("config"), hookName),
            record("val" to inputExpr, "ctx" to id("ctx")),
        )

    private fun tierThreeDecode(inputExpr: ParsedExpr): ParsedExpr =
        call(
            member(inputExpr, "decode"),
            record("config" to id("config")),
        )

    private fun buildDecodeRecord(type: MezzeType.RecordType, inputExpr: ParsedExpr): ParsedExpr {
        val fields = type.fields.entries.sortedBy { it.key }
        if (fields.isEmpty()) {
            return ok(ParsedRecordLit(entries = emptyList(), loc = GEN_LOC))
        }

        val recordCtor = ParsedRecordLit(
            entries = fields.map { (fieldName, _) ->
                ParsedRecordEntry.Field(fieldName, id("${fieldName}_val"), GEN_LOC)
            },
            loc = GEN_LOC,
        )
        var acc: ParsedExpr = ok(recordCtor)

        for ((fieldName, fieldType) in fields.reversed()) {
            val fetchRaw = call(
                member(id("config"), "decode_record_field"),
                record(
                    "val" to inputExpr,
                    "key" to transformFieldName(fieldName),
                    "ctx" to id("ctx"),
                ),
            )
            val decodeSub = buildDecodeExpr(fieldType, id("${fieldName}_raw"))
            acc = match(
                fetchRaw,
                matchCase(
                    okPat("${fieldName}_raw"),
                    match(
                        decodeSub,
                        matchCase(okPat("${fieldName}_val"), acc),
                        matchCase(errPat("e"), err(id("e"))),
                    ),
                ),
                matchCase(errPat("e"), err(id("e"))),
            )
        }
        return acc
    }

    private fun buildDecodeVariant(type: MezzeType.VariantType, inputExpr: ParsedExpr): ParsedExpr {
        val ctors = type.constructors.entries.sortedBy { it.key }
        // config.decode_variant { val = it, ctx = ctx } -> Ok { val = { tag, payload } }.
        val decodeCall = call(
            member(id("config"), "decode_variant"),
            record("val" to inputExpr, "ctx" to id("ctx")),
        )

        // Build a per-tag dispatch: match info.tag string, decode payload
        // through the constructor's payload type, and build the Ok result.
        val tagVar = "__variant"
        val defaultBranch: ParsedExpr = err(
            record(
                "path" to member(id("ctx"), "path"),
                "message" to str("Unknown variant tag"),
                "expected" to str(ctors.joinToString(" | ") { it.key }),
                "found" to some(member(id(tagVar), "tag")),
            )
        )

        var tagDispatch: ParsedExpr = defaultBranch
        for ((ctorName, payloadType) in ctors.reversed()) {
            tagDispatch = ifExpr(
                condition = call(
                    id("__native_string_eq"),
                    record("l" to member(id(tagVar), "tag"), "r" to str(ctorName)),
                ),
                thenBranch = buildDecodeVariantCase(ctorName, payloadType),
                elseBranch = tagDispatch,
            )
        }

        return match(
            decodeCall,
            matchCase(okPat(tagVar), tagDispatch),
            matchCase(errPat("e"), err(id("e"))),
        )
    }

    private fun buildDecodeVariantCase(ctorName: String, payloadType: MezzeType?): ParsedExpr {
        val payloadOpt = member(id("__variant"), "payload")
        if (payloadType == null) {
            // Payloadless constructor. Ignore any incidental payload.
            return ok(variant(ctorName, null))
        }
        // The decoded payload IS the constructor's payload record — pass it
        // through as the variant's payload directly, never wrap in a synthetic
        // `{ val = ... }` (which would break multi-field payloads and inject
        // an extra layer for single-field ones).
        return match(
            payloadOpt,
            matchCase(nonePat(), err(
                record(
                    "path" to member(id("ctx"), "path"),
                    "message" to str("Missing payload for $ctorName"),
                    "expected" to str(ctorName),
                    "found" to none(),
                )
            )),
            matchCase(
                somePat("__raw_payload"),
                match(
                    buildDecodeExpr(payloadType, id("__raw_payload")),
                    matchCase(
                        okPat("__decoded_payload"),
                        ok(variant(ctorName, id("__decoded_payload"))),
                    ),
                    matchCase(errPat("e"), err(id("e"))),
                ),
            ),
        )
    }

    private fun buildDecodeList(elemType: MezzeType, inputExpr: ParsedExpr): ParsedExpr {
        // config.decode_list { val = it, ctx = ctx } -> Ok { val = ['in] }.
        // Then map each element through decodeExpr, gathering into Ok/Err.
        val listCall = call(
            member(id("config"), "decode_list"),
            record("val" to inputExpr, "ctx" to id("ctx")),
        )
        // Auto-derived list decode currently threads through a helper native
        // that iterates and decodes element-wise via `elem.decode { config }`.
        // We compose the element callback and let the runtime dispatch handle
        // it — a full try-fold in Mezze would work too but is a lot noisier.
        val mapped = call(
            id("__native_seq_try_map"),
            record(
                "xs" to id("__list_raw"),
                "f" to lambda(listOf("x"), buildDecodeExpr(elemType, id("x"))),
            ),
        )
        return match(
            listCall,
            matchCase(okPat("__list_raw"), mapped),
            matchCase(errPat("e"), err(id("e"))),
        )
    }

    private fun buildDecodeMap(keyType: MezzeType, valType: MezzeType, inputExpr: ParsedExpr): ParsedExpr {
        val mapCall = call(
            member(id("config"), "decode_map"),
            record("val" to inputExpr, "ctx" to id("ctx")),
        )
        // __native_seq_try_map wraps each element as `{ x = <elem> }` (wrapX), so the
        // callback param is `x` and the `{key,value}` entry is reached via `x.key` /
        // `x.value` — same convention as the encode side (see buildEncodeMap).
        val mapped = call(
            id("__native_seq_try_map"),
            record(
                "xs" to id("__map_raw"),
                "f" to lambda(
                    listOf("x"),
                    match(
                        buildDecodeExpr(keyType, member(id("x"), "key")),
                        matchCase(
                            okPat("__k"),
                            match(
                                buildDecodeExpr(valType, member(id("x"), "value")),
                                matchCase(
                                    okPat("__v"),
                                    ok(record("key" to id("__k"), "value" to id("__v"))),
                                ),
                                matchCase(errPat("e"), err(id("e"))),
                            ),
                        ),
                        matchCase(errPat("e"), err(id("e"))),
                    ),
                ),
            ),
        )
        return match(
            mapCall,
            matchCase(okPat("__map_raw"), mapped),
            matchCase(errPat("e"), err(id("e"))),
        )
    }

    /** [buildDecodeList] entry for a container that arrived as a `TypeApplication`
     *  (element = [args]`[0]`). Missing element arg ⇒ Tier-3 fallback. */
    private fun buildDecodeListApp(args: List<MezzeType>, inputExpr: ParsedExpr): ParsedExpr {
        val elem = args.firstOrNull() ?: return tierThreeDecode(inputExpr)
        return buildDecodeList(elem, inputExpr)
    }

    /** [buildDecodeMap] entry for a map that arrived as a `TypeApplication`
     *  (key = [args]`[0]`, value = [args]`[1]`). Missing args ⇒ Tier-3 fallback. */
    private fun buildDecodeMapApp(args: List<MezzeType>, inputExpr: ParsedExpr): ParsedExpr {
        val k = args.getOrNull(0) ?: return tierThreeDecode(inputExpr)
        val v = args.getOrNull(1) ?: return tierThreeDecode(inputExpr)
        return buildDecodeMap(k, v, inputExpr)
    }

    private fun buildDecodeOption(innerType: MezzeType?, inputExpr: ParsedExpr): ParsedExpr {
        val optCall = call(
            member(id("config"), "decode_option"),
            record("val" to inputExpr, "ctx" to id("ctx")),
        )
        if (innerType == null) {
            return optCall
        }
        return match(
            optCall,
            matchCase(
                okPat("__opt_raw"),
                match(
                    id("__opt_raw"),
                    matchCase(nonePat(), ok(none())),
                    matchCase(
                        somePat("__raw"),
                        match(
                            buildDecodeExpr(innerType, id("__raw")),
                            matchCase(okPat("__decoded"), ok(some(id("__decoded")))),
                            matchCase(errPat("e"), err(id("e"))),
                        ),
                    ),
                ),
            ),
            matchCase(errPat("e"), err(id("e"))),
        )
    }

    // ---------------------------------------------------------------------
    // Impl construction
    // ---------------------------------------------------------------------

    private fun createEncodeImpl(
        formatType: MezzeType,
        dataType: MezzeType,
        body: ParsedExpr,
    ): ParsedImplAbilityDecl = ParsedImplAbilityDecl(
        target = ParsedImplTarget.Named(ParsedQualifiedRef("Encode", null, GEN_LOC)),
        abilityTypeArgs = listOf(mezzeTypeToAnnotation(formatType)),
        targetTypes = listOf(mezzeTypeToAnnotation(dataType)),
        operations = listOf(
            ParsedImplOp(
                name = "encode",
                paramType = null,
                returnType = null,
                implementation = lambda(listOf("it", "config"), body),
                loc = GEN_LOC,
            )
        ),
        loc = GEN_LOC,
    )

    private fun createDecodeImpl(
        formatType: MezzeType,
        dataType: MezzeType,
        inType: MezzeType,
        body: ParsedExpr,
    ): ParsedImplAbilityDecl = ParsedImplAbilityDecl(
        target = ParsedImplTarget.Named(ParsedQualifiedRef("Decode", null, GEN_LOC)),
        abilityTypeArgs = listOf(
            mezzeTypeToAnnotation(formatType),
            mezzeTypeToAnnotation(dataType),
        ),
        targetTypes = listOf(mezzeTypeToAnnotation(inType)),
        operations = listOf(
            ParsedImplOp(
                name = "decode",
                paramType = null,
                returnType = null,
                implementation = lambda(listOf("it", "config"), body),
                loc = GEN_LOC,
            )
        ),
        loc = GEN_LOC,
    )

    // ---------------------------------------------------------------------
    // AST helpers
    // ---------------------------------------------------------------------

    /** `{ path = [], shared = config.shared }` — initial encode/decode ctx. */
    private fun initialCtx(): ParsedExpr = record(
        "path" to seqLit(emptyList()),
        "shared" to member(id("config"), "shared"),
    )

    /**
     * Apply the runtime field-name transform to a static field name at emit time.
     *
     * Semantically equivalent to `std::codec::core::apply_field_transform
     * { name = "<field>", shared = config.shared }`, but INLINED so the
     * generated impl body doesn't reference stdlib helpers by fully-qualified
     * path. buildExpr's `resolveBinding` for a qualified identifier only checks
     * the user module's hash table and imports — auto-derived impls land in an
     * arbitrary user module that doesn't import `std::codec::core`, so a naked
     * fully-qualified call slips through to a `BindingRef.Local(0,0)` fallback
     * and crashes at runtime.
     *
     * We inline as a match on `config.shared.field_transform`, dispatching each
     * arm to the corresponding `__native_string_to_*` intrinsic (native
     * symbols always resolve via `MagicParams.isIntrinsicSymbol`). Identity
     * returns the literal name.
     */
    private fun transformFieldName(fieldName: String): ParsedExpr {
        val nameLit = str(fieldName)
        fun nativeCase(nativeName: String): ParsedExpr =
            call(id(nativeName), record("s" to nameLit))
        return match(
            member(member(id("config"), "shared"), "field_transform"),
            matchCase(constructorPat("Identity", null), nameLit),
            matchCase(constructorPat("SnakeCase", null), nativeCase("__native_string_to_snake_case")),
            matchCase(constructorPat("CamelCase", null), nativeCase("__native_string_to_camel_case")),
            matchCase(constructorPat("KebabCase", null), nativeCase("__native_string_to_kebab_case")),
            matchCase(constructorPat("ScreamingSnakeCase", null),
                      nativeCase("__native_string_to_screaming_snake_case")),
        )
    }

    private fun record(vararg fields: Pair<String, ParsedExpr>): ParsedRecordLit =
        ParsedRecordLit(
            entries = fields.map { (n, v) -> ParsedRecordEntry.Field(n, v, GEN_LOC) },
            loc = GEN_LOC,
        )

    private fun call(func: ParsedExpr, args: ParsedRecordLit): ParsedFunctionCall =
        ParsedFunctionCall(function = func, arguments = args, loc = GEN_LOC)

    private fun member(receiver: ParsedExpr, memberName: String): ParsedMemberAccess =
        ParsedMemberAccess(receiver = receiver, member = memberName, loc = GEN_LOC)

    private fun id(name: String): ParsedIdentifier =
        ParsedIdentifier(localName = name, qualifier = null, loc = GEN_LOC)

    private fun str(value: String): ParsedStringLit =
        ParsedStringLit(parts = listOf(ParsedStringPart.Literal(value)), loc = GEN_LOC)

    private fun seqLit(elements: List<ParsedExpr>): ParsedSeqLit =
        ParsedSeqLit(
            elements = elements.map { ParsedSeqElement.Regular(it) },
            loc = GEN_LOC,
        )

    private fun variant(constructor: String, payload: ParsedExpr? = null): ParsedVariantConstruct =
        ParsedVariantConstruct(constructor = constructor, value = payload, loc = GEN_LOC)

    private fun constructorPat(name: String, payload: ParsedPattern?): ParsedConstructorPattern =
        ParsedConstructorPattern(constructor = name, payload = payload, loc = GEN_LOC)

    private fun varPat(name: String): ParsedVariablePattern =
        ParsedVariablePattern(name = name, loc = GEN_LOC)

    private fun matchCase(pattern: ParsedPattern, body: ParsedExpr): ParsedMatchCase =
        ParsedMatchCase(patterns = listOf(pattern), guard = null, body = body, loc = GEN_LOC)

    private fun match(scrutinee: ParsedExpr, vararg cases: ParsedMatchCase): ParsedMatch =
        ParsedMatch(scrutinee = scrutinee, cases = cases.toList(), loc = GEN_LOC)

    private fun lambda(paramNames: List<String>, body: ParsedExpr): ParsedLambda =
        ParsedLambda(
            params = ParsedLambdaParams.Untyped(names = paramNames),
            body = body,
            loc = GEN_LOC,
        )

    private fun doWithLet(varName: String, varValue: ParsedExpr, result: ParsedExpr): ParsedDo =
        ParsedDo(
            statements = listOf(
                ParsedStatement.Let(
                    pattern = ParsedVariablePattern(name = varName, loc = GEN_LOC),
                    typeAnnotation = null,
                    value = varValue,
                    loc = GEN_LOC,
                )
            ),
            result = result,
            loc = GEN_LOC,
        )

    private fun ifExpr(condition: ParsedExpr, thenBranch: ParsedExpr, elseBranch: ParsedExpr): ParsedIf =
        ParsedIf(condition = condition, thenBranch = thenBranch, elseBranch = elseBranch, loc = GEN_LOC)

    private fun ok(value: ParsedExpr): ParsedVariantConstruct =
        variant("Ok", record("val" to value))

    private fun err(errorExpr: ParsedExpr): ParsedVariantConstruct =
        variant("Err", record("error" to errorExpr))

    private fun some(value: ParsedExpr): ParsedVariantConstruct =
        variant("Some", record("val" to value))

    private fun none(): ParsedVariantConstruct = variant("None", null)

    private fun okPat(varName: String): ParsedConstructorPattern =
        constructorPat("Ok", recordPat("val" to varPat(varName)))

    private fun errPat(varName: String = "e"): ParsedConstructorPattern =
        constructorPat("Err", recordPat("error" to varPat(varName)))

    private fun somePat(varName: String): ParsedConstructorPattern =
        constructorPat("Some", recordPat("val" to varPat(varName)))

    private fun nonePat(): ParsedConstructorPattern = constructorPat("None", null)

    private fun recordPat(vararg entries: Pair<String, ParsedPattern?>): ParsedRecordPattern =
        ParsedRecordPattern(
            entries = entries.map { (n, p) -> ParsedRecordPatternEntry(n, p, GEN_LOC) },
            loc = GEN_LOC,
        )

    // ---------------------------------------------------------------------
    // MezzeType → ParsedTypeAnnotation
    // ---------------------------------------------------------------------

    private fun mezzeTypeToAnnotation(type: MezzeType): ParsedTypeAnnotation = mezzeTypeToParsedAnnotation(type, GEN_LOC)

    companion object {
        private val GEN_LOC = Loc("<generated>", 0, 0, 0, 0)
    }
}

/** [MezzeType] → the parsed annotation naming it, for compiler-synthesized decls
 *  (codec and derived impls). Declared shapes go by NAME; anonymous ones structurally. */
internal fun mezzeTypeToParsedAnnotation(type: MezzeType, loc: Loc): ParsedTypeAnnotation {
    PrimitiveTypes.toName(type)?.let { return ParsedNamedType(it, loc) }
    return when (type) {
        is MezzeType.VarType -> ParsedNamedType(type.variable.name, loc)
        is MezzeType.RecordType -> {
            // A nominal record (e.g. `type Point = { x: Int, y: Int }`) has a
            // `Declared` identity. The runtime ability dispatcher matches a
            // record receiver against a `TypeApplication` target by nominal
            // name (TypedASTCompiler.typeMatchesTarget), so the impl target
            // must be `Point`, not the anonymous structural `{x,y}` shape —
            // otherwise `p.encode {…}` falls back to a bytecode field read.
            val named = type.identity.displayNameOrNull()
            if (named != null) ParsedNamedType(named, loc)
            else ParsedRecordType(
                entries = type.fields.map { (name, fieldType) ->
                    ParsedRecordTypeEntry(name, mezzeTypeToParsedAnnotation(fieldType, loc), loc)
                },
                loc = loc,
            )
        }
        is MezzeType.VariantType -> {
            val named = type.identity.displayNameOrNull()
            if (named != null) ParsedNamedType(named, loc)
            else ParsedVariantType(
                constructors = type.constructors.map { (n, payload) ->
                    ParsedVariantTypeCtor(n, payload?.let { mezzeTypeToParsedAnnotation(it, loc) }, loc)
                },
                loc = loc,
            )
        }
        is MezzeType.ListType    -> ParsedTypeApp("List", listOf(mezzeTypeToParsedAnnotation(type.elementType, loc)), loc)
        is MezzeType.SeqType     -> ParsedSeqType(mezzeTypeToParsedAnnotation(type.elementType, loc), loc)
        is MezzeType.TypeApplication -> ParsedTypeApp(
            type.constructor,
            type.args.map { mezzeTypeToParsedAnnotation(it, loc) },
            loc,
        )
        is MezzeType.FunctionType -> ParsedFunctionType(
            paramType = mezzeTypeToParsedAnnotation(type.paramType, loc),
            returnType = mezzeTypeToParsedAnnotation(type.returnType, loc),
            loc = loc,
        )
        else -> throw IllegalArgumentException("Cannot convert MezzeType to ParsedTypeAnnotation: $type")
    }
}
