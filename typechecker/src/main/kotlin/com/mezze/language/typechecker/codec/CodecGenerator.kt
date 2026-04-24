package com.mezze.language.typechecker.codec

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.PrimitiveTypes
import com.mezze.language.ast.TypeVar
import com.mezze.language.parsedast.*

/**
 * CodecGenerator - Generates Encode/Decode ability implementations automatically.
 *
 * This generator is invoked by the TypeChecker when it encounters an Encode/Decode
 * constraint that doesn't have an explicit implementation. It generates the
 * implementation AST based on the type structure.
 *
 * Example: For `user.encode { fmt = json::utf8, config = default_config }` where
 * user: {name: Str, age: Int}, this generates an impl with `it` as the receiver:
 *
 * ```
 * impl EncodeRepr for {name: Str, age: Int} where {
 *     encode_repr = {fmt, config} ->
 *         let name_encoded = Format.encode_string {val = it.name, path = ...}
 *         let age_encoded = Format.encode_int {val = it.age, path = ...}
 *         Format.encode_record {fields = [{key = "name", value = name_encoded}, ...], path = ...}
 * }
 * ```
 */
class CodecGenerator {

    private val GEN_LOC = Loc("<generated>", 0, 0, 0, 0)

    // Cache of generated implementations to avoid regeneration
    private val encodeCache = mutableMapOf<String, ParsedImplAbilityDecl>()
    private val decodeCache = mutableMapOf<String, ParsedImplAbilityDecl>()
    private val streamEncodeCache = mutableMapOf<String, ParsedImplAbilityDecl>()
    private val streamDecodeCache = mutableMapOf<String, ParsedImplAbilityDecl>()

    /**
     * Check if a type can be automatically serialized.
     */
    fun isSerializable(type: MezzeType): Boolean {
        return when (type) {
            is MezzeType.IntType -> true
            is MezzeType.FloatType -> true
            is MezzeType.StringType -> true
            is MezzeType.BoolType -> true
            is MezzeType.NatType -> true
            is MezzeType.FracType -> true
            is MezzeType.CharType -> true
            is MezzeType.OrderingType -> true
            is MezzeType.BytesType -> false
            is MezzeType.ByteBufferType -> false
            is MezzeType.FunctionType -> false
            is MezzeType.SuspendedType -> false
            is MezzeType.EffectRow -> false
            is MezzeType.RecordType -> type.fields.values.all { isSerializable(it) }
            is MezzeType.VariantType -> type.constructors.values.all { payload ->
                payload == null || isSerializable(payload)
            }
            is MezzeType.ListType -> isSerializable(type.elementType)
            is MezzeType.SeqType -> isSerializable(type.elementType)
            is MezzeType.SetType -> isSerializable(type.elementType)
            is MezzeType.SortedSetType -> isSerializable(type.elementType)
            is MezzeType.MapType -> isSerializable(type.keyType) && isSerializable(type.valueType)
            is MezzeType.SortedMapType -> isSerializable(type.keyType) && isSerializable(type.valueType)
            is MezzeType.TypeApplication -> {
                type.args.all { isSerializable(it) }
            }
            is MezzeType.NewtypeType -> false
            is MezzeType.VarType -> true
        }
    }

    /**
     * Generate a normalized cache key for a type.
     */
    private fun normalizedCacheKey(formatType: MezzeType, dataType: MezzeType): String {
        val normalizedData = normalizeType(dataType)
        return "${normalizeType(formatType)}_$normalizedData"
    }

    /**
     * Normalize a type for consistent cache key generation.
     */
    private fun normalizeType(type: MezzeType): String {
        return when (type) {
            is MezzeType.RecordType -> {
                val sortedFields = type.fields.entries.sortedBy { it.key }
                    .joinToString(",") { "${it.key}:${normalizeType(it.value)}" }
                "{$sortedFields}"
            }
            is MezzeType.VariantType -> {
                val sortedCtors = type.constructors.entries.sortedBy { it.key }
                    .joinToString(",") { "${it.key}:${it.value?.let { v -> normalizeType(v) } ?: "()"}" }
                "|$sortedCtors|"
            }
            is MezzeType.ListType -> "List[${normalizeType(type.elementType)}]"
            is MezzeType.SeqType -> "Seq[${normalizeType(type.elementType)}]"
            is MezzeType.SetType -> "Set[${normalizeType(type.elementType)}]"
            is MezzeType.SortedSetType -> "SortedSet[${normalizeType(type.elementType)}]"
            is MezzeType.MapType -> "Map[${normalizeType(type.keyType)},${normalizeType(type.valueType)}]"
            is MezzeType.SortedMapType -> "SortedMap[${normalizeType(type.keyType)},${normalizeType(type.valueType)}]"
            is MezzeType.TypeApplication -> "${type.constructor}[${type.args.joinToString(",") { normalizeType(it) }}]"
            else -> type.toString()
        }
    }

    /**
     * Generate an Encode implementation for the given format and data type.
     */
    fun generateEncodeImpl(formatType: MezzeType, dataType: MezzeType): ParsedImplAbilityDecl {
        val cacheKey = normalizedCacheKey(formatType, dataType)
        encodeCache[cacheKey]?.let { return it }

        val impl = when {
            dataType is MezzeType.IntType -> generatePrimitiveEncode(formatType, dataType, "encode_int", "val")
            dataType is MezzeType.FloatType -> generatePrimitiveEncode(formatType, dataType, "encode_float", "val")
            dataType is MezzeType.StringType -> generatePrimitiveEncode(formatType, dataType, "encode_string", "val")
            dataType is MezzeType.BoolType -> generatePrimitiveEncode(formatType, dataType, "encode_bool", "val")
            dataType is MezzeType.NatType -> generateNatEncode(formatType)
            dataType is MezzeType.FracType -> generatePrimitiveEncode(formatType, dataType, "encode_frac", "val")
            dataType is MezzeType.CharType -> generateCharEncode(formatType)
            isBoolVariant(dataType) -> generateBoolEncode(formatType)
            dataType is MezzeType.RecordType -> generateRecordEncode(formatType, dataType)
            dataType is MezzeType.VariantType -> generateVariantEncode(formatType, dataType)
            dataType is MezzeType.ListType -> generateListEncode(formatType, dataType)
            dataType is MezzeType.SeqType -> generateArrayEncode(formatType, dataType)
            dataType is MezzeType.SetType -> generateSetEncode(formatType, dataType)
            dataType is MezzeType.SortedSetType -> generateSortedSetEncode(formatType, dataType)
            dataType is MezzeType.MapType -> generateMapEncode(formatType, dataType)
            dataType is MezzeType.SortedMapType -> generateSortedMapEncode(formatType, dataType)
            dataType is MezzeType.TypeApplication -> generateTypeApplicationEncode(formatType, dataType)
            else -> throw IllegalArgumentException("Cannot generate Encode for type: $dataType")
        }

        encodeCache[cacheKey] = impl
        return impl
    }

    private fun isBoolVariant(type: MezzeType): Boolean = PrimitiveTypes.isBoolVariant(type)

    /**
     * Generate encode for Bool (which is internally a variant | True, False |).
     */
    private fun generateBoolEncode(formatType: MezzeType): ParsedImplAbilityDecl {
        val body = call(
            member(encodeFormatImpl(formatType), "encode_bool"),
            record("val" to id("it"), "path" to id("path"))
        )
        return createEncodeImpl(formatType, MezzeType.VariantType(
            constructors = mapOf("True" to null, "False" to null),
            rowVar = null
        ), body)
    }

    /**
     * Generate a Decode implementation for the given format and data type.
     */
    fun generateDecodeImpl(formatType: MezzeType, dataType: MezzeType): ParsedImplAbilityDecl {
        val cacheKey = normalizedCacheKey(formatType, dataType)
        decodeCache[cacheKey]?.let { return it }

        val impl = when {
            dataType is MezzeType.IntType -> generatePrimitiveDecode(formatType, dataType, "decode_int")
            dataType is MezzeType.FloatType -> generatePrimitiveDecode(formatType, dataType, "decode_float")
            dataType is MezzeType.StringType -> generatePrimitiveDecode(formatType, dataType, "decode_string")
            dataType is MezzeType.BoolType -> generatePrimitiveDecode(formatType, dataType, "decode_bool")
            dataType is MezzeType.NatType -> generateNatDecode(formatType)
            dataType is MezzeType.FracType -> generatePrimitiveDecode(formatType, dataType, "decode_frac")
            dataType is MezzeType.CharType -> generateCharDecode(formatType)
            isBoolVariant(dataType) -> generateBoolDecode(formatType)
            dataType is MezzeType.RecordType -> generateRecordDecode(formatType, dataType)
            dataType is MezzeType.VariantType -> generateVariantDecode(formatType, dataType)
            dataType is MezzeType.ListType -> generateListDecode(formatType, dataType)
            dataType is MezzeType.SeqType -> generateArrayDecode(formatType, dataType)
            dataType is MezzeType.SetType -> generateSetDecode(formatType, dataType)
            dataType is MezzeType.SortedSetType -> generateSortedSetDecode(formatType, dataType)
            dataType is MezzeType.MapType -> generateMapDecode(formatType, dataType)
            dataType is MezzeType.SortedMapType -> generateSortedMapDecode(formatType, dataType)
            dataType is MezzeType.TypeApplication -> generateTypeApplicationDecode(formatType, dataType)
            else -> throw IllegalArgumentException("Cannot generate Decode for type: $dataType")
        }

        decodeCache[cacheKey] = impl
        return impl
    }

    /**
     * Generate decode for Bool (which is internally a variant | True, False |).
     */
    private fun generateBoolDecode(formatType: MezzeType): ParsedImplAbilityDecl {
        val body = call(
            member(decodeFormatImpl(formatType), "decode_bool"),
            record("val" to id("it"), "path" to id("path"))
        )
        return createDecodeImpl(formatType, MezzeType.VariantType(
            constructors = mapOf("True" to null, "False" to null),
            rowVar = null
        ), body)
    }

    /** Create a record literal with field entries */
    private fun record(vararg fields: Pair<String, ParsedExpr>): ParsedRecordLit {
        return ParsedRecordLit(
            entries = fields.map { (name, value) -> ParsedRecordEntry.Field(name, value, GEN_LOC) },
            loc = GEN_LOC
        )
    }

    /** Create a function call */
    private fun call(func: ParsedExpr, args: ParsedRecordLit): ParsedFunctionCall {
        return ParsedFunctionCall(function = func, arguments = args, loc = GEN_LOC)
    }

    /** Create a member access expression */
    private fun member(receiver: ParsedExpr, memberName: String): ParsedMemberAccess {
        return ParsedMemberAccess(receiver = receiver, member = memberName, loc = GEN_LOC)
    }

    /** Create an identifier expression */
    private fun id(name: String): ParsedIdentifier {
        return ParsedIdentifier(localName = name, qualifier = null, loc = GEN_LOC)
    }

    /** Create a string literal */
    private fun str(value: String): ParsedStringLit {
        return ParsedStringLit(parts = listOf(ParsedStringPart.Literal(value)), loc = GEN_LOC)
    }

    /** Create an int literal */
    private fun intLit(value: Long): ParsedIntLit {
        return ParsedIntLit(value = value, loc = GEN_LOC)
    }

    /** Create a variant construction */
    private fun variant(constructor: String, payload: ParsedExpr? = null): ParsedVariantConstruct {
        return ParsedVariantConstruct(constructor = constructor, value = payload, loc = GEN_LOC)
    }

    /** Create a constructor pattern for match */
    private fun constructorPat(name: String, payload: ParsedPattern? = null): ParsedConstructorPattern {
        return ParsedConstructorPattern(constructor = name, payload = payload, loc = GEN_LOC)
    }

    /** Create a variable pattern */
    private fun varPat(name: String): ParsedVariablePattern {
        return ParsedVariablePattern(name = name, loc = GEN_LOC)
    }

    /** Create a record pattern */
    private fun recordPat(vararg entries: Pair<String, ParsedPattern?>): ParsedRecordPattern {
        return ParsedRecordPattern(
            entries = entries.map { (name, pat) -> ParsedRecordPatternEntry(name, pat, GEN_LOC) },
            loc = GEN_LOC
        )
    }

    /** Create a match case with single pattern */
    private fun matchCase(pattern: ParsedPattern, body: ParsedExpr, guard: ParsedExpr? = null): ParsedMatchCase {
        return ParsedMatchCase(patterns = listOf(pattern), guard = guard, body = body, loc = GEN_LOC)
    }

    /** Create a match expression */
    private fun match(expr: ParsedExpr, vararg cases: ParsedMatchCase): ParsedMatch {
        return ParsedMatch(scrutinee = expr, cases = cases.toList(), loc = GEN_LOC)
    }

    /** Create a lambda expression with untyped parameters */
    private fun lambda(paramNames: List<String>, body: ParsedExpr): ParsedLambda {
        return ParsedLambda(
            params = ParsedLambdaParams.Untyped(names = paramNames),
            body = body,
            loc = GEN_LOC
        )
    }

    /** Create a do expression with a let statement followed by a result */
    private fun doWithLet(varName: String, varValue: ParsedExpr, result: ParsedExpr): ParsedDo {
        return ParsedDo(
            statements = listOf(
                ParsedStatement.Let(
                    pattern = ParsedVariablePattern(name = varName, loc = GEN_LOC),
                    typeAnnotation = null,
                    value = varValue,
                    loc = GEN_LOC
                )
            ),
            result = result,
            loc = GEN_LOC
        )
    }

    /** Create a simple let statement */
    private fun letStmt(varName: String, value: ParsedExpr): ParsedStatement.Let {
        return ParsedStatement.Let(
            pattern = ParsedVariablePattern(name = varName, loc = GEN_LOC),
            typeAnnotation = null,
            value = value,
            loc = GEN_LOC
        )
    }

    /** Create a seq literal from expressions */
    private fun array(elements: List<ParsedExpr>): ParsedSeqLit {
        return ParsedSeqLit(elements = elements.map { ParsedSeqElement.Regular(it) }, loc = GEN_LOC)
    }

    /** Create a list from a seq expression using __native_list_from_seq */
    private fun listFromArray(elements: List<ParsedExpr>): ParsedExpr {
        return call(
            id("__native_list_from_seq"),
            record("xs" to array(elements))
        )
    }

    /** Create an if expression */
    private fun ifExpr(condition: ParsedExpr, thenBranch: ParsedExpr, elseBranch: ParsedExpr): ParsedIf {
        return ParsedIf(condition = condition, thenBranch = thenBranch, elseBranch = elseBranch, loc = GEN_LOC)
    }

    /** Create Ok variant */
    private fun ok(value: ParsedExpr): ParsedVariantConstruct {
        return variant("Ok", record("val" to value))
    }

    /** Create Err variant */
    private fun err(errorExpr: ParsedExpr): ParsedVariantConstruct {
        return variant("Err", record("error" to errorExpr))
    }

    /** Create Some variant */
    private fun some(value: ParsedExpr): ParsedVariantConstruct {
        return variant("Some", record("val" to value))
    }

    /** Create None variant */
    private fun none(): ParsedVariantConstruct {
        return variant("None", null)
    }

    /** Pattern for Ok {val = x} */
    private fun okPat(varName: String): ParsedConstructorPattern {
        return constructorPat("Ok", recordPat("val" to varPat(varName)))
    }

    /** Pattern for Err {error = e} */
    private fun errPat(varName: String = "e"): ParsedConstructorPattern {
        return constructorPat("Err", recordPat("error" to varPat(varName)))
    }

    /** Pattern for Some {val = x} */
    private fun somePat(varName: String): ParsedConstructorPattern {
        return constructorPat("Some", recordPat("val" to varPat(varName)))
    }

    /** Pattern for None */
    private fun nonePat(): ParsedConstructorPattern {
        return constructorPat("None", null)
    }

    /**
     * Get the Format ability identifier.
     */
    private fun encodeFormatImpl(formatType: MezzeType): ParsedIdentifier {
        // Use the `fmt` parameter directly as the receiver for Format ability methods.
        // The `fmt` param holds the concrete format impl (e.g., json_fmt) at runtime.
        // Previously used id("Format") which is an ability name that can't be resolved
        // as a value in the generated impl's buildExpr pass.
        return id("fmt")
    }

    /**
     * Get the DecodeFormat ability identifier.
     */
    private fun decodeFormatImpl(formatType: MezzeType): ParsedIdentifier {
        // Use the `fmt` parameter directly — see encodeFormatImpl comment.
        return id("fmt")
    }

    /**
     * Get the StreamFormat ability identifier.
     */
    private fun streamEncodeFormatImpl(formatType: MezzeType): ParsedIdentifier {
        return id("StreamFormat")
    }

    /**
     * Get the StreamDecodeFormat ability identifier.
     */
    private fun streamDecodeFormatImpl(formatType: MezzeType): ParsedIdentifier {
        return id("StreamDecodeFormat")
    }

    /** Call append_path using native seq operations directly */
    private fun appendPath(pathExpr: ParsedExpr, fieldName: String): ParsedExpr {
        return call(
            id("__native_seq_add"),
            record(
                "xs" to pathExpr,
                "x" to str(fieldName)
            )
        )
    }

    /** Transform a field name using EncodeFormat.transform_field */
    private fun transformFieldName(formatType: MezzeType, fieldName: String, configExpr: ParsedExpr = id("config")): ParsedExpr {
        return call(
            member(encodeFormatImpl(formatType), "transform_field"),
            record("name" to str(fieldName), "config" to configExpr)
        )
    }

    /** Transform a field name for decoding using DecodeFormat.transform_field */
    private fun transformFieldNameForDecode(formatType: MezzeType, fieldName: String, configExpr: ParsedExpr = id("config")): ParsedExpr {
        return call(
            member(decodeFormatImpl(formatType), "transform_field"),
            record("name" to str(fieldName), "config" to configExpr)
        )
    }

    /** Call append_index using native seq operations directly */
    private fun appendIndex(pathExpr: ParsedExpr, indexExpr: ParsedExpr): ParsedExpr {
        return call(
            id("__native_seq_add"),
            record(
                "xs" to pathExpr,
                "x" to call(id("__native_int_to_string"), record("n" to indexExpr))
            )
        )
    }

    /**
     * Generate encode for primitive types (Int, Float, String, Bool, Frac).
     */
    private fun generatePrimitiveEncode(
        formatType: MezzeType,
        dataType: MezzeType,
        hookName: String,
        paramName: String
    ): ParsedImplAbilityDecl {
        val body = call(
            member(encodeFormatImpl(formatType), hookName),
            record(paramName to id("it"), "path" to id("path"))
        )
        return createEncodeImpl(formatType, dataType, body)
    }

    /**
     * Generate encode for Nat - converts to Int first.
     */
    private fun generateNatEncode(formatType: MezzeType): ParsedImplAbilityDecl {
        val toIntCall = call(
            id("__native_nat_to_int"),
            record("n" to id("it"))
        )
        val body = call(
            member(encodeFormatImpl(formatType), "encode_int"),
            record("val" to toIntCall, "path" to id("path"))
        )
        return createEncodeImpl(formatType, MezzeType.NatType, body)
    }

    /**
     * Generate encode for Char - converts to String first.
     */
    private fun generateCharEncode(formatType: MezzeType): ParsedImplAbilityDecl {
        val toStringCall = call(
            id("__native_char_to_string"),
            record("c" to id("it"))
        )
        val body = call(
            member(encodeFormatImpl(formatType), "encode_string"),
            record("val" to toStringCall, "path" to id("path"))
        )
        return createEncodeImpl(formatType, MezzeType.CharType, body)
    }

    /**
     * Generate encode for record types.
     */
    private fun generateRecordEncode(formatType: MezzeType, recordType: MezzeType.RecordType): ParsedImplAbilityDecl {
        val fields = recordType.fields.entries.sortedBy { it.key }

        if (fields.isEmpty()) {
            val body = call(
                member(encodeFormatImpl(formatType), "encode_record"),
                record("fields" to array(emptyList()), "path" to id("path"))
            )
            return createEncodeImpl(formatType, recordType, body)
        }

        val body = buildRecordEncodeBody(formatType, fields)
        return createEncodeImpl(formatType, recordType, body)
    }

    /**
     * Build the body for record encoding with Result handling.
     */
    private fun buildRecordEncodeBody(formatType: MezzeType, fields: List<Map.Entry<String, MezzeType>>): ParsedExpr {
        var resultExpr: ParsedExpr = call(
            member(encodeFormatImpl(formatType), "encode_record"),
            record(
                "fields" to array(fields.map { (fieldName, _) ->
                    record(
                        "key" to transformFieldName(formatType, fieldName),
                        "value" to id("${fieldName}_encoded")
                    )
                }),
                "path" to id("path")
            )
        )

        for ((fieldName, fieldType) in fields.reversed()) {
            val encodeCall = generateDirectEncodeCall(formatType, fieldType, member(id("it"), fieldName), fieldName)

            resultExpr = match(
                encodeCall,
                matchCase(okPat("${fieldName}_encoded"), resultExpr),
                matchCase(errPat("e"), err(id("e")))
            )
        }

        return resultExpr
    }

    /**
     * Generate a direct EncodeFormat impl method call for a value of the given type.
     */
    private fun generateDirectEncodeCall(formatType: MezzeType, type: MezzeType, valueExpr: ParsedExpr, fieldName: String): ParsedExpr {
        val pathExpr = appendPath(id("path"), fieldName)
        val formatImpl = encodeFormatImpl(formatType)

        if (isBoolVariant(type)) {
            return call(
                member(formatImpl, "encode_bool"),
                record("val" to valueExpr, "path" to pathExpr)
            )
        }

        return when (type) {
            is MezzeType.IntType -> call(
                member(formatImpl, "encode_int"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is MezzeType.FloatType -> call(
                member(formatImpl, "encode_float"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is MezzeType.StringType -> call(
                member(formatImpl, "encode_string"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is MezzeType.BoolType -> call(
                member(formatImpl, "encode_bool"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is MezzeType.NatType -> {
                val toIntCall = call(id("__native_nat_to_int"), record("n" to valueExpr))
                call(member(formatImpl, "encode_int"), record("val" to toIntCall, "path" to pathExpr))
            }
            is MezzeType.FracType -> call(
                member(formatImpl, "encode_frac"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is MezzeType.CharType -> {
                val toStrCall = call(id("__native_char_to_string"), record("c" to valueExpr))
                call(member(formatImpl, "encode_string"), record("val" to toStrCall, "path" to pathExpr))
            }
            is MezzeType.RecordType, is MezzeType.ListType, is MezzeType.SeqType, is MezzeType.SetType,
            is MezzeType.MapType, is MezzeType.VariantType, is MezzeType.TypeApplication -> {
                typedEncodeCall(type, valueExpr)
            }
            else -> {
                typedEncodeCall(type, valueExpr)
            }
        }
    }

    /**
     * Create a typed encode call using method-call syntax: value.encode_repr { fmt, config }.
     */
    private fun typedEncodeCall(dataType: MezzeType, valueExpr: ParsedExpr): ParsedFunctionCall {
        val argsRecord = record(
            "fmt" to id("fmt"),
            "config" to id("config")
        )
        return ParsedFunctionCall(
            function = ParsedMemberAccess(
                receiver = valueExpr,
                member = "encode_repr",
                loc = GEN_LOC
            ),
            arguments = argsRecord,
            loc = GEN_LOC
        )
    }

    /**
     * Generate a direct DecodeFormat impl record lookup and method call for a value of the given type.
     */
    private fun generateDirectDecodeCall(formatType: MezzeType, type: MezzeType, valueExpr: ParsedExpr, fieldName: String): ParsedExpr {
        val pathExpr = appendPath(id("path"), fieldName)
        val formatImpl = decodeFormatImpl(formatType)

        if (isBoolVariant(type)) {
            return call(
                member(formatImpl, "decode_bool"),
                record("val" to valueExpr, "path" to pathExpr)
            )
        }

        return when (type) {
            is MezzeType.IntType -> call(
                member(formatImpl, "decode_int"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is MezzeType.FloatType -> call(
                member(formatImpl, "decode_float"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is MezzeType.StringType -> call(
                member(formatImpl, "decode_string"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is MezzeType.BoolType -> call(
                member(formatImpl, "decode_bool"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is MezzeType.NatType -> {
                val decodeIntCall = call(member(formatImpl, "decode_int"), record("val" to valueExpr, "path" to pathExpr))
                match(
                    decodeIntCall,
                    matchCase(okPat("int_val"), ok(call(id("__native_int_to_nat"), record("n" to id("int_val"))))),
                    matchCase(errPat("e"), err(id("e")))
                )
            }
            is MezzeType.FracType -> call(
                member(formatImpl, "decode_frac"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is MezzeType.CharType -> {
                val decodeStrCall = call(member(formatImpl, "decode_string"), record("val" to valueExpr, "path" to pathExpr))
                match(
                    decodeStrCall,
                    matchCase(okPat("str_val"), ok(call(id("__native_string_head"), record("s" to id("str_val"))))),
                    matchCase(errPat("e"), err(id("e")))
                )
            }
            is MezzeType.RecordType, is MezzeType.ListType, is MezzeType.SeqType, is MezzeType.SetType,
            is MezzeType.MapType, is MezzeType.VariantType, is MezzeType.TypeApplication -> {
                typedDecodeCall(type, valueExpr)
            }
            else -> {
                typedDecodeCall(type, valueExpr)
            }
        }
    }

    /**
     * Create a typed decode call using method-call syntax: value.decode_repr { fmt, config }.
     */
    private fun typedDecodeCall(targetType: MezzeType, valueExpr: ParsedExpr): ParsedFunctionCall {
        val argsRecord = record(
            "fmt" to id("fmt"),
            "config" to id("config")
        )
        return ParsedFunctionCall(
            function = ParsedMemberAccess(
                receiver = valueExpr,
                member = "decode_repr",
                loc = GEN_LOC
            ),
            arguments = argsRecord,
            loc = GEN_LOC
        )
    }

    /**
     * Generate encode for variant types.
     */
    private fun generateVariantEncode(formatType: MezzeType, variantType: MezzeType.VariantType): ParsedImplAbilityDecl {
        val constructors = variantType.constructors.entries.sortedBy { it.key }

        val cases = constructors.map { (constructorName, payloadType) ->
            if (payloadType == null) {
                matchCase(
                    constructorPat(constructorName, null),
                    call(
                        member(encodeFormatImpl(formatType), "encode_variant"),
                        record(
                            "tag" to str(constructorName),
                            "payload" to none(),
                            "path" to id("path")
                        )
                    )
                )
            } else {
                generateVariantCaseWithPayload(formatType, constructorName, payloadType)
            }
        }

        val body = match(id("it"), *cases.toTypedArray())
        return createEncodeImpl(formatType, variantType, body)
    }

    /**
     * Generate a match case for a variant constructor with a payload.
     */
    private fun generateVariantCaseWithPayload(formatType: MezzeType, constructorName: String, payloadType: MezzeType): ParsedMatchCase {
        return when (payloadType) {
            is MezzeType.RecordType -> {
                val fieldNames = payloadType.fields.keys.sorted()

                val patternEntries = fieldNames.map { fieldName ->
                    ParsedRecordPatternEntry(fieldName, ParsedVariablePattern("__payload_$fieldName", GEN_LOC), GEN_LOC)
                }
                val recordPattern = ParsedRecordPattern(entries = patternEntries, loc = GEN_LOC)

                val reconstructedRecord = record(
                    *fieldNames.map { fieldName -> fieldName to id("__payload_$fieldName") }.toTypedArray()
                )

                val encodeCall = generateDirectEncodeCall(
                    formatType, payloadType, reconstructedRecord, "__variant_payload"
                )

                matchCase(
                    constructorPat(constructorName, recordPattern),
                    match(
                        encodeCall,
                        matchCase(
                            okPat("encoded_payload"),
                            call(
                                member(encodeFormatImpl(formatType), "encode_variant"),
                                record(
                                    "tag" to str(constructorName),
                                    "payload" to some(id("encoded_payload")),
                                    "path" to id("path")
                                )
                            )
                        ),
                        matchCase(errPat("e"), err(id("e")))
                    )
                )
            }
            else -> {
                val encodeCall = generateDirectEncodeCall(
                    formatType, payloadType, id("payload_val"), "__variant_payload"
                )

                matchCase(
                    constructorPat(constructorName, recordPat("val" to varPat("payload_val"))),
                    match(
                        encodeCall,
                        matchCase(
                            okPat("encoded_payload"),
                            call(
                                member(encodeFormatImpl(formatType), "encode_variant"),
                                record(
                                    "tag" to str(constructorName),
                                    "payload" to some(id("encoded_payload")),
                                    "path" to id("path")
                                )
                            )
                        ),
                        matchCase(errPat("e"), err(id("e")))
                    )
                )
            }
        }
    }

    /**
     * Generate encode for List types.
     */
    private fun generateListEncode(formatType: MezzeType, listType: MezzeType.ListType): ParsedImplAbilityDecl {
        val elementType = listType.elementType
        val elementEncodeCall = generateDirectEncodeCall(formatType, elementType, id("elem"), "__list_elem")

        val foldBody = match(
            id("acc"),
            matchCase(
                okPat("encoded_list"),
                match(
                    elementEncodeCall,
                    matchCase(
                        okPat("encoded"),
                        ok(call(
                            id("__native_list_append"),
                            record(
                                "xs" to id("encoded_list"),
                                "ys" to call(
                                    id("__native_list_cons"),
                                    record(
                                        "head" to id("encoded"),
                                        "tail" to call(id("__native_list_empty"), record())
                                    )
                                )
                            )
                        ))
                    ),
                    matchCase(errPat("e"), err(id("e")))
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )

        val foldCall = call(
            id("__native_list_fold_left"),
            record(
                "xs" to id("it"),
                "init" to ok(call(id("__native_list_empty"), record())),
                "f" to lambda(listOf("acc", "elem"), foldBody)
            )
        )

        val body = match(
            foldCall,
            matchCase(
                okPat("final_list"),
                call(
                    member(encodeFormatImpl(formatType), "encode_list"),
                    record("elems" to id("final_list"), "path" to id("path"))
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )

        return createEncodeImpl(formatType, listType, body)
    }

    /**
     * Generate encode for Seq - convert to List first using native function.
     */
    private fun generateArrayEncode(formatType: MezzeType, seqType: MezzeType.SeqType): ParsedImplAbilityDecl {
        val toListCall = call(id("__native_seq_to_list"), record("xs" to id("it")))
        val listType = MezzeType.ListType(seqType.elementType)
        val body = generateDirectEncodeCall(formatType, listType, toListCall, "__seq_as_list")
        return createEncodeImpl(formatType, seqType, body)
    }

    /**
     * Generate encode for Set types.
     */
    private fun generateSetEncode(formatType: MezzeType, setType: MezzeType.SetType): ParsedImplAbilityDecl {
        val toListCall = call(id("__native_set_to_list"), record("s" to id("it")))
        val elementType = setType.elementType
        val elementEncodeCall = generateDirectEncodeCall(formatType, elementType, id("elem"), "__set_elem")

        val foldBody = match(
            id("acc"),
            matchCase(
                okPat("encoded_list"),
                match(
                    elementEncodeCall,
                    matchCase(
                        okPat("encoded"),
                        ok(call(
                            id("__native_list_append"),
                            record(
                                "xs" to id("encoded_list"),
                                "ys" to call(
                                    id("__native_list_cons"),
                                    record(
                                        "head" to id("encoded"),
                                        "tail" to call(id("__native_list_empty"), record())
                                    )
                                )
                            )
                        ))
                    ),
                    matchCase(errPat("e"), err(id("e")))
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )

        val foldCall = call(
            id("__native_list_fold_left"),
            record(
                "xs" to toListCall,
                "init" to ok(call(id("__native_list_empty"), record())),
                "f" to lambda(listOf("acc", "elem"), foldBody)
            )
        )

        val body = match(
            foldCall,
            matchCase(
                okPat("final_list"),
                call(
                    member(encodeFormatImpl(formatType), "encode_set"),
                    record(
                        "elems" to call(
                            id("__native_set_from_list"),
                            record("xs" to id("final_list"))
                        ),
                        "path" to id("path")
                    )
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )
        return createEncodeImpl(formatType, setType, body)
    }

    /**
     * Generate encode for SortedSet.
     */
    private fun generateSortedSetEncode(formatType: MezzeType, sortedSetType: MezzeType.SortedSetType): ParsedImplAbilityDecl {
        val toListCall = call(id("__native_sorted_set_to_list"), record("s" to id("it")))
        val elementType = sortedSetType.elementType
        val elementEncodeCall = generateDirectEncodeCall(formatType, elementType, id("elem"), "__sorted_set_elem")

        val foldBody = match(
            id("acc"),
            matchCase(
                okPat("encoded_list"),
                match(
                    elementEncodeCall,
                    matchCase(
                        okPat("encoded"),
                        ok(call(
                            id("__native_list_append"),
                            record(
                                "xs" to id("encoded_list"),
                                "ys" to call(
                                    id("__native_list_cons"),
                                    record(
                                        "head" to id("encoded"),
                                        "tail" to call(id("__native_list_empty"), record())
                                    )
                                )
                            )
                        ))
                    ),
                    matchCase(errPat("e"), err(id("e")))
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )

        val foldCall = call(
            id("__native_list_fold_left"),
            record(
                "xs" to toListCall,
                "init" to ok(call(id("__native_list_empty"), record())),
                "f" to lambda(listOf("acc", "elem"), foldBody)
            )
        )

        val body = match(
            foldCall,
            matchCase(
                okPat("final_list"),
                call(
                    member(encodeFormatImpl(formatType), "encode_set"),
                    record(
                        "elems" to call(
                            id("__native_set_from_list"),
                            record("xs" to id("final_list"))
                        ),
                        "path" to id("path")
                    )
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )
        return createEncodeImpl(formatType, sortedSetType, body)
    }

    /**
     * Generate encode for Map types.
     */
    private fun generateMapEncode(formatType: MezzeType, mapType: MezzeType.MapType): ParsedImplAbilityDecl {
        val entriesCall = call(id("__native_map_entries"), record("m" to id("it")))
        val keyType = mapType.keyType
        val valueType = mapType.valueType
        val keyEncodeCall = generateDirectEncodeCall(formatType, keyType, member(id("entry"), "key"), "__map_key")
        val valueEncodeCall = generateDirectEncodeCall(formatType, valueType, member(id("entry"), "value"), "__map_value")

        val foldBody = match(
            id("acc"),
            matchCase(
                okPat("encoded_list"),
                match(
                    keyEncodeCall,
                    matchCase(
                        okPat("encoded_key"),
                        match(
                            valueEncodeCall,
                            matchCase(
                                okPat("encoded_value"),
                                ok(call(
                                    id("__native_list_append"),
                                    record(
                                        "xs" to id("encoded_list"),
                                        "ys" to call(
                                            id("__native_list_cons"),
                                            record(
                                                "head" to record("key" to id("encoded_key"), "value" to id("encoded_value")),
                                                "tail" to call(id("__native_list_empty"), record())
                                            )
                                        )
                                    )
                                ))
                            ),
                            matchCase(errPat("e"), err(id("e")))
                        )
                    ),
                    matchCase(errPat("e"), err(id("e")))
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )

        val foldCall = call(
            id("__native_list_fold_left"),
            record(
                "xs" to entriesCall,
                "init" to ok(call(id("__native_list_empty"), record())),
                "f" to lambda(listOf("acc", "entry"), foldBody)
            )
        )

        val body = match(
            foldCall,
            matchCase(
                okPat("final_list"),
                call(
                    member(encodeFormatImpl(formatType), "encode_map"),
                    record("entries" to id("final_list"), "path" to id("path"))
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )
        return createEncodeImpl(formatType, mapType, body)
    }

    /**
     * Generate encode for SortedMap.
     */
    private fun generateSortedMapEncode(formatType: MezzeType, sortedMapType: MezzeType.SortedMapType): ParsedImplAbilityDecl {
        val entriesCall = call(id("__native_sorted_map_entries"), record("m" to id("it")))
        val keyType = sortedMapType.keyType
        val valueType = sortedMapType.valueType
        val keyEncodeCall = generateDirectEncodeCall(formatType, keyType, member(id("entry"), "key"), "__sorted_map_key")
        val valueEncodeCall = generateDirectEncodeCall(formatType, valueType, member(id("entry"), "value"), "__sorted_map_value")

        val foldBody = match(
            id("acc"),
            matchCase(
                okPat("encoded_list"),
                match(
                    keyEncodeCall,
                    matchCase(
                        okPat("encoded_key"),
                        match(
                            valueEncodeCall,
                            matchCase(
                                okPat("encoded_value"),
                                ok(call(
                                    id("__native_list_append"),
                                    record(
                                        "xs" to id("encoded_list"),
                                        "ys" to call(
                                            id("__native_list_cons"),
                                            record(
                                                "head" to record("key" to id("encoded_key"), "value" to id("encoded_value")),
                                                "tail" to call(id("__native_list_empty"), record())
                                            )
                                        )
                                    )
                                ))
                            ),
                            matchCase(errPat("e"), err(id("e")))
                        )
                    ),
                    matchCase(errPat("e"), err(id("e")))
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )

        val foldCall = call(
            id("__native_list_fold_left"),
            record(
                "xs" to entriesCall,
                "init" to ok(call(id("__native_list_empty"), record())),
                "f" to lambda(listOf("acc", "entry"), foldBody)
            )
        )

        val body = match(
            foldCall,
            matchCase(
                okPat("final_list"),
                call(
                    member(encodeFormatImpl(formatType), "encode_map"),
                    record("entries" to id("final_list"), "path" to id("path"))
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )
        return createEncodeImpl(formatType, sortedMapType, body)
    }

    /**
     * Generate encode for TypeApplication (Option, Result, etc.).
     */
    private fun generateTypeApplicationEncode(formatType: MezzeType, typeApp: MezzeType.TypeApplication): ParsedImplAbilityDecl {
        return when (typeApp.constructor) {
            "Option" -> generateOptionEncode(formatType, typeApp)
            "Result" -> generateResultEncode(formatType, typeApp)
            else -> throw IllegalArgumentException("Unknown type application: ${typeApp.constructor}")
        }
    }

    /**
     * Generate encode for Option type.
     */
    private fun generateOptionEncode(formatType: MezzeType, typeApp: MezzeType.TypeApplication): ParsedImplAbilityDecl {
        val innerType = typeApp.args.firstOrNull() ?: MezzeType.VarType(TypeVar("a", 0))
        val innerEncodeCall = generateDirectEncodeCall(formatType, innerType, id("inner"), "__option_inner")

        val body = match(
            id("it"),
            matchCase(
                nonePat(),
                call(
                    member(encodeFormatImpl(formatType), "encode_option"),
                    record("val" to none(), "path" to id("path"))
                )
            ),
            matchCase(
                somePat("inner"),
                match(
                    innerEncodeCall,
                    matchCase(
                        okPat("encoded_inner"),
                        call(
                            member(encodeFormatImpl(formatType), "encode_option"),
                            record("val" to some(id("encoded_inner")), "path" to id("path"))
                        )
                    ),
                    matchCase(errPat("e"), err(id("e")))
                )
            )
        )
        return createEncodeImpl(formatType, typeApp, body)
    }

    /**
     * Generate encode for Result type.
     */
    private fun generateResultEncode(formatType: MezzeType, typeApp: MezzeType.TypeApplication): ParsedImplAbilityDecl {
        val okType = typeApp.args.getOrNull(0) ?: MezzeType.VarType(TypeVar("ok", 0))
        val errType = typeApp.args.getOrNull(1) ?: MezzeType.VarType(TypeVar("err", 0))

        val okEncodeCall = generateDirectEncodeCall(formatType, okType, id("ok_val"), "__result_ok")
        val errEncodeCall = generateDirectEncodeCall(formatType, errType, id("err_val"), "__result_err")

        val body = match(
            id("it"),
            matchCase(
                okPat("ok_val"),
                match(
                    okEncodeCall,
                    matchCase(
                        okPat("encoded_ok"),
                        call(
                            member(encodeFormatImpl(formatType), "encode_variant"),
                            record(
                                "tag" to str("Ok"),
                                "payload" to some(id("encoded_ok")),
                                "path" to id("path")
                            )
                        )
                    ),
                    matchCase(errPat("e"), err(id("e")))
                )
            ),
            matchCase(
                errPat("err_val"),
                match(
                    errEncodeCall,
                    matchCase(
                        okPat("encoded_err"),
                        call(
                            member(encodeFormatImpl(formatType), "encode_variant"),
                            record(
                                "tag" to str("Err"),
                                "payload" to some(id("encoded_err")),
                                "path" to id("path")
                            )
                        )
                    ),
                    matchCase(errPat("e"), err(id("e")))
                )
            )
        )
        return createEncodeImpl(formatType, typeApp, body)
    }

    /**
     * Generate decode for primitive types.
     */
    private fun generatePrimitiveDecode(
        formatType: MezzeType,
        dataType: MezzeType,
        hookName: String
    ): ParsedImplAbilityDecl {
        val body = call(
            member(decodeFormatImpl(formatType), hookName),
            record("val" to id("it"), "path" to id("path"))
        )
        return createDecodeImpl(formatType, dataType, body)
    }

    /**
     * Generate decode for Nat - decode Int and validate.
     */
    private fun generateNatDecode(formatType: MezzeType): ParsedImplAbilityDecl {
        val body = match(
            call(
                member(decodeFormatImpl(formatType), "decode_int"),
                record("val" to id("it"), "path" to id("path"))
            ),
            matchCase(
                okPat("int_val"),
                ifExpr(
                    condition = call(
                        member(id("int"), "gte"),
                        record("a" to id("int_val"), "b" to intLit(0))
                    ),
                    thenBranch = ok(call(member(id("nat"), "from_int"), record("n" to id("int_val")))),
                    elseBranch = call(
                        member(id("codec"), "decode_error"),
                        record(
                            "path" to id("path"),
                            "message" to str("Expected non-negative integer for Nat"),
                            "expected" to str("Nat"),
                            "found" to some(str("negative Int"))
                        )
                    )
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )
        return createDecodeImpl(formatType, MezzeType.NatType, body)
    }

    /**
     * Generate decode for Char - decode String and validate single character.
     */
    private fun generateCharDecode(formatType: MezzeType): ParsedImplAbilityDecl {
        val body = match(
            call(
                member(decodeFormatImpl(formatType), "decode_string"),
                record("val" to id("it"), "path" to id("path"))
            ),
            matchCase(
                okPat("str_val"),
                ifExpr(
                    condition = call(
                        member(id("int"), "eq"),
                        record(
                            "a" to call(member(id("str"), "length"), record("s" to id("str_val"))),
                            "b" to intLit(1)
                        )
                    ),
                    thenBranch = ok(call(member(id("str"), "char_at"), record("s" to id("str_val"), "index" to intLit(0)))),
                    elseBranch = call(
                        member(id("codec"), "decode_error"),
                        record(
                            "path" to id("path"),
                            "message" to str("Expected single character string for Char"),
                            "expected" to str("Char"),
                            "found" to some(call(
                                member(id("str"), "concat"),
                                record("a" to str("String of length "), "b" to call(
                                    member(id("int"), "to_string"),
                                    record("n" to call(member(id("str"), "length"), record("s" to id("str_val"))))
                                ))
                            ))
                        )
                    )
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )
        return createDecodeImpl(formatType, MezzeType.CharType, body)
    }

    /**
     * Generate decode for record types.
     */
    private fun generateRecordDecode(formatType: MezzeType, recordType: MezzeType.RecordType): ParsedImplAbilityDecl {
        val fields = recordType.fields.entries.sortedBy { it.key }

        if (fields.isEmpty()) {
            val body = ok(ParsedRecordLit(entries = emptyList(), loc = GEN_LOC))
            return createDecodeImpl(formatType, recordType, body)
        }

        // Generate a native call body: __native_codec_decode_record { json = it, field_names = [...] }
        // The native function handles JSON field extraction and primitive decoding in Java,
        // avoiding the complex nested match AST that causes OOM during Truffle execution.
        val fieldNameArray = array(fields.map { (fieldName, _) -> str(fieldName) })
        val fieldTypeArray = array(fields.map { (_, fieldType) ->
            str(when (fieldType) {
                is MezzeType.IntType -> "Int"
                is MezzeType.FloatType -> "Float"
                is MezzeType.StringType -> "Str"
                is MezzeType.BoolType -> "Bool"
                else -> "Any"
            })
        })
        val body = call(
            id("__native_codec_decode_record"),
            record(
                "json" to id("it"),
                "field_names" to fieldNameArray,
                "field_types" to fieldTypeArray
            )
        )
        return createDecodeImpl(formatType, recordType, body)
    }

    /**
     * Build the body for record decoding with Result handling.
     */
    private fun buildRecordDecodeBody(formatType: MezzeType, fields: List<Map.Entry<String, MezzeType>>): ParsedExpr {
        var resultExpr: ParsedExpr = ok(
            ParsedRecordLit(
                entries = fields.map { (fieldName, _) ->
                    ParsedRecordEntry.Field(fieldName, id("${fieldName}_val"), GEN_LOC)
                },
                loc = GEN_LOC
            )
        )

        for ((fieldName, fieldType) in fields.reversed()) {
            val getFieldCall = call(
                member(decodeFormatImpl(formatType), "decode_record_field"),
                record(
                    "val" to id("it"),
                    "key" to transformFieldNameForDecode(formatType, fieldName),
                    "path" to id("path")
                )
            )

            val decodeCall = generateFieldDecodeCall(formatType, fieldType, id("${fieldName}_repr"), fieldName)

            resultExpr = match(
                getFieldCall,
                matchCase(
                    okPat("${fieldName}_repr"),
                    match(
                        decodeCall,
                        matchCase(okPat("${fieldName}_val"), resultExpr),
                        matchCase(errPat("e"), err(id("e")))
                    )
                ),
                matchCase(errPat("e"), err(id("e")))
            )
        }

        return resultExpr
    }

    /**
     * Generate decode call for a record field using DecodeFormat hooks directly.
     */
    private fun generateFieldDecodeCall(formatType: MezzeType, type: MezzeType, valueExpr: ParsedExpr, fieldName: String): ParsedExpr {
        val pathExpr = appendPath(id("path"), fieldName)
        val formatImpl = decodeFormatImpl(formatType)

        if (isBoolVariant(type)) {
            return call(
                member(formatImpl, "decode_bool"),
                record("val" to valueExpr, "path" to pathExpr)
            )
        }

        return when (type) {
            is MezzeType.IntType -> call(
                member(formatImpl, "decode_int"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is MezzeType.FloatType -> call(
                member(formatImpl, "decode_float"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is MezzeType.StringType -> call(
                member(formatImpl, "decode_string"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is MezzeType.BoolType -> call(
                member(formatImpl, "decode_bool"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is MezzeType.FracType -> call(
                member(formatImpl, "decode_frac"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is MezzeType.NatType -> {
                val decodeIntCall = call(member(formatImpl, "decode_int"), record("val" to valueExpr, "path" to pathExpr))
                match(
                    decodeIntCall,
                    matchCase(okPat("int_val"), ok(call(id("__native_int_to_nat"), record("n" to id("int_val"))))),
                    matchCase(errPat("e"), err(id("e")))
                )
            }
            is MezzeType.CharType -> {
                val decodeStrCall = call(member(formatImpl, "decode_string"), record("val" to valueExpr, "path" to pathExpr))
                match(
                    decodeStrCall,
                    matchCase(okPat("str_val"), ok(call(id("__native_string_head"), record("s" to id("str_val"))))),
                    matchCase(errPat("e"), err(id("e")))
                )
            }
            is MezzeType.RecordType, is MezzeType.ListType, is MezzeType.SeqType, is MezzeType.SetType,
            is MezzeType.MapType, is MezzeType.VariantType, is MezzeType.TypeApplication -> {
                typedDecodeCall(type, valueExpr)
            }
            else -> {
                typedDecodeCall(type, valueExpr)
            }
        }
    }

    /**
     * Generate decode for variant types.
     */
    private fun generateVariantDecode(formatType: MezzeType, variantType: MezzeType.VariantType): ParsedImplAbilityDecl {
        val constructors = variantType.constructors.entries.sortedBy { it.key }

        val body = match(
            call(
                member(decodeFormatImpl(formatType), "decode_variant"),
                record("val" to id("it"), "path" to id("path"))
            ),
            matchCase(
                okPat("variant_info"),
                buildVariantTagMatcher(formatType, constructors)
            ),
            matchCase(errPat("e"), err(id("e")))
        )

        return createDecodeImpl(formatType, variantType, body)
    }

    /**
     * Build nested if-else for variant tag matching.
     */
    private fun buildVariantTagMatcher(formatType: MezzeType, constructors: List<Map.Entry<String, MezzeType?>>): ParsedExpr {
        if (constructors.isEmpty()) {
            return call(
                member(id("codec"), "decode_error"),
                record(
                    "path" to id("path"),
                    "message" to str("Unknown variant tag"),
                    "expected" to str("valid tag"),
                    "found" to some(member(id("variant_info"), "tag"))
                )
            )
        }

        val (constructorName, payloadType) = constructors.first()
        val remaining = constructors.drop(1)

        val thenBranch = if (payloadType == null) {
            ok(variant(constructorName, null))
        } else {
            val variantConstruction = when (payloadType) {
                is MezzeType.RecordType -> {
                    variant(constructorName, id("decoded_payload"))
                }
                else -> {
                    variant(constructorName, record("val" to id("decoded_payload")))
                }
            }

            match(
                member(id("variant_info"), "payload"),
                matchCase(
                    somePat("payload_repr"),
                    match(
                        typedDecodeCall(payloadType, id("payload_repr")),
                        matchCase(
                            okPat("decoded_payload"),
                            ok(variantConstruction)
                        ),
                        matchCase(errPat("e"), err(id("e")))
                    )
                ),
                matchCase(
                    nonePat(),
                    call(
                        member(id("codec"), "decode_error"),
                        record(
                            "path" to id("path"),
                            "message" to str("Expected payload for variant $constructorName"),
                            "expected" to str("payload"),
                            "found" to none()
                        )
                    )
                )
            )
        }

        return ifExpr(
            condition = call(
                member(id("str"), "eq"),
                record("a" to member(id("variant_info"), "tag"), "b" to str(constructorName))
            ),
            thenBranch = thenBranch,
            elseBranch = buildVariantTagMatcher(formatType, remaining)
        )
    }

    /**
     * Generate decode for List types.
     */
    private fun generateListDecode(formatType: MezzeType, listType: MezzeType.ListType): ParsedImplAbilityDecl {
        val elementType = listType.elementType

        val body = match(
            call(
                member(decodeFormatImpl(formatType), "decode_list"),
                record("val" to id("it"), "path" to id("path"))
            ),
            matchCase(
                okPat("repr_list"),
                match(
                    call(
                        member(id("list"), "try_map_indexed"),
                        record(
                            "list" to id("repr_list"),
                            "f" to lambda(listOf("elem_repr", "idx"),
                                typedDecodeCall(elementType, id("elem_repr"))
                            )
                        )
                    ),
                    matchCase(okPat("decoded_list"), ok(id("decoded_list"))),
                    matchCase(errPat("e"), err(id("e")))
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )
        return createDecodeImpl(formatType, listType, body)
    }

    /**
     * Generate decode for Seq - decode as List then convert.
     */
    private fun generateArrayDecode(formatType: MezzeType, seqType: MezzeType.SeqType): ParsedImplAbilityDecl {
        val listType = MezzeType.ListType(seqType.elementType)

        val body = match(
            typedDecodeCall(listType, id("it")),
            matchCase(
                okPat("decoded_list"),
                ok(call(member(id("seq"), "from_list"), record("list" to id("decoded_list"))))
            ),
            matchCase(errPat("e"), err(id("e")))
        )
        return createDecodeImpl(formatType, seqType, body)
    }

    /**
     * Generate decode for Set types.
     */
    private fun generateSetDecode(formatType: MezzeType, setType: MezzeType.SetType): ParsedImplAbilityDecl {
        val elementType = setType.elementType

        val body = match(
            call(
                member(decodeFormatImpl(formatType), "decode_set"),
                record("val" to id("it"), "path" to id("path"))
            ),
            matchCase(
                okPat("repr_set"),
                match(
                    call(
                        member(id("list"), "try_map_indexed"),
                        record(
                            "list" to call(member(id("set"), "to_list"), record("s" to id("repr_set"))),
                            "f" to lambda(listOf("elem_repr", "idx"),
                                typedDecodeCall(elementType, id("elem_repr"))
                            )
                        )
                    ),
                    matchCase(
                        okPat("decoded_list"),
                        ok(call(member(id("set"), "from_list"), record("list" to id("decoded_list"))))
                    ),
                    matchCase(errPat("e"), err(id("e")))
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )
        return createDecodeImpl(formatType, setType, body)
    }

    /**
     * Generate decode for SortedSet.
     */
    private fun generateSortedSetDecode(formatType: MezzeType, sortedSetType: MezzeType.SortedSetType): ParsedImplAbilityDecl {
        val setType = MezzeType.SetType(sortedSetType.elementType)

        val body = match(
            typedDecodeCall(setType, id("it")),
            matchCase(
                okPat("decoded_set"),
                ok(call(member(id("sortedset"), "from_set"), record("s" to id("decoded_set"))))
            ),
            matchCase(errPat("e"), err(id("e")))
        )
        return createDecodeImpl(formatType, sortedSetType, body)
    }

    /**
     * Generate decode for Map types.
     */
    private fun generateMapDecode(formatType: MezzeType, mapType: MezzeType.MapType): ParsedImplAbilityDecl {
        val keyType = mapType.keyType
        val valueType = mapType.valueType

        val body = match(
            call(
                member(decodeFormatImpl(formatType), "decode_map"),
                record("val" to id("it"), "path" to id("path"))
            ),
            matchCase(
                okPat("repr_entries"),
                match(
                    call(
                        member(id("list"), "try_map"),
                        record(
                            "list" to id("repr_entries"),
                            "f" to lambda(listOf("entry"),
                                match(
                                    typedDecodeCall(keyType, member(id("entry"), "key")),
                                    matchCase(
                                        okPat("decoded_key"),
                                        match(
                                            typedDecodeCall(valueType, member(id("entry"), "value")),
                                            matchCase(
                                                okPat("decoded_value"),
                                                ok(record("key" to id("decoded_key"), "val" to id("decoded_value")))
                                            ),
                                            matchCase(errPat("e"), err(id("e")))
                                        )
                                    ),
                                    matchCase(errPat("e"), err(id("e")))
                                )
                            )
                        )
                    ),
                    matchCase(
                        okPat("decoded_entries"),
                        ok(call(member(id("map"), "from_list"), record("list" to id("decoded_entries"))))
                    ),
                    matchCase(errPat("e"), err(id("e")))
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )
        return createDecodeImpl(formatType, mapType, body)
    }

    /**
     * Generate decode for SortedMap.
     */
    private fun generateSortedMapDecode(formatType: MezzeType, sortedMapType: MezzeType.SortedMapType): ParsedImplAbilityDecl {
        val mapType = MezzeType.MapType(sortedMapType.keyType, sortedMapType.valueType)

        val body = match(
            typedDecodeCall(mapType, id("it")),
            matchCase(
                okPat("decoded_map"),
                ok(call(member(id("sortedmap"), "from_map"), record("m" to id("decoded_map"))))
            ),
            matchCase(errPat("e"), err(id("e")))
        )
        return createDecodeImpl(formatType, sortedMapType, body)
    }

    /**
     * Generate decode for TypeApplication (Option, Result, etc.).
     */
    private fun generateTypeApplicationDecode(formatType: MezzeType, typeApp: MezzeType.TypeApplication): ParsedImplAbilityDecl {
        return when (typeApp.constructor) {
            "Option" -> generateOptionDecode(formatType, typeApp)
            "Result" -> generateResultDecode(formatType, typeApp)
            else -> throw IllegalArgumentException("Unknown type application: ${typeApp.constructor}")
        }
    }

    /**
     * Generate decode for Option type.
     */
    private fun generateOptionDecode(formatType: MezzeType, typeApp: MezzeType.TypeApplication): ParsedImplAbilityDecl {
        val innerType = typeApp.args.firstOrNull() ?: throw IllegalArgumentException("Option requires type argument")

        val body = match(
            call(
                member(decodeFormatImpl(formatType), "decode_option"),
                record("val" to id("it"), "path" to id("path"))
            ),
            matchCase(
                okPat("opt_repr"),
                match(
                    id("opt_repr"),
                    matchCase(nonePat(), ok(none())),
                    matchCase(
                        somePat("inner_repr"),
                        match(
                            typedDecodeCall(innerType, id("inner_repr")),
                            matchCase(okPat("decoded_inner"), ok(some(id("decoded_inner")))),
                            matchCase(errPat("e"), err(id("e")))
                        )
                    )
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )
        return createDecodeImpl(formatType, typeApp, body)
    }

    /**
     * Generate decode for Result type.
     */
    private fun generateResultDecode(formatType: MezzeType, typeApp: MezzeType.TypeApplication): ParsedImplAbilityDecl {
        val okType = typeApp.args.getOrNull(0) ?: throw IllegalArgumentException("Result requires Ok type argument")
        val errType = typeApp.args.getOrNull(1) ?: throw IllegalArgumentException("Result requires Err type argument")

        val body = match(
            call(
                member(decodeFormatImpl(formatType), "decode_variant"),
                record("val" to id("it"), "path" to id("path"))
            ),
            matchCase(
                okPat("variant_info"),
                ifExpr(
                    condition = call(
                        member(id("str"), "eq"),
                        record("a" to member(id("variant_info"), "tag"), "b" to str("Ok"))
                    ),
                    thenBranch = match(
                        member(id("variant_info"), "payload"),
                        matchCase(
                            somePat("payload_repr"),
                            match(
                                typedDecodeCall(okType, id("payload_repr")),
                                matchCase(okPat("decoded_ok"), ok(ok(id("decoded_ok")))),
                                matchCase(errPat("e"), err(id("e")))
                            )
                        ),
                        matchCase(
                            nonePat(),
                            call(
                                member(id("codec"), "decode_error"),
                                record(
                                    "path" to id("path"),
                                    "message" to str("Expected payload for Ok variant"),
                                    "expected" to str("payload"),
                                    "found" to none()
                                )
                            )
                        )
                    ),
                    elseBranch = ifExpr(
                        condition = call(
                            member(id("str"), "eq"),
                            record("a" to member(id("variant_info"), "tag"), "b" to str("Err"))
                        ),
                        thenBranch = match(
                            member(id("variant_info"), "payload"),
                            matchCase(
                                somePat("payload_repr"),
                                match(
                                    typedDecodeCall(errType, id("payload_repr")),
                                    matchCase(okPat("decoded_err"), ok(err(id("decoded_err")))),
                                    matchCase(errPat("e"), err(id("e")))
                                )
                            ),
                            matchCase(
                                nonePat(),
                                call(
                                    member(id("codec"), "decode_error"),
                                    record(
                                        "path" to id("path"),
                                        "message" to str("Expected payload for Err variant"),
                                        "expected" to str("payload"),
                                        "found" to none()
                                    )
                                )
                            )
                        ),
                        elseBranch = call(
                            member(id("codec"), "decode_error"),
                            record(
                                "path" to id("path"),
                                "message" to str("Expected Ok or Err variant for Result"),
                                "expected" to str("Result"),
                                "found" to some(member(id("variant_info"), "tag"))
                            )
                        )
                    )
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )
        return createDecodeImpl(formatType, typeApp, body)
    }

    /**
     * Convert MezzeType to ParsedTypeAnnotation for use in ParsedImplAbilityDecl.
     */
    private fun mezzeTypeToAnnotation(type: MezzeType): ParsedTypeAnnotation {
        // Use PrimitiveTypes to get the name for primitive types
        PrimitiveTypes.toName(type)?.let { name ->
            return ParsedNamedType(name, GEN_LOC)
        }

        return when (type) {
            is MezzeType.VarType -> ParsedNamedType(type.variable.name, GEN_LOC)
            is MezzeType.RecordType -> ParsedRecordType(
                entries = type.fields.map { (name, fieldType) ->
                    ParsedRecordTypeEntry(name, mezzeTypeToAnnotation(fieldType), GEN_LOC)
                },
                loc = GEN_LOC
            )
            is MezzeType.VariantType -> ParsedVariantType(
                constructors = type.constructors.map { (name, payloadType) ->
                    ParsedVariantTypeCtor(name, payloadType?.let { mezzeTypeToAnnotation(it) }, GEN_LOC)
                },
                loc = GEN_LOC
            )
            is MezzeType.ListType -> ParsedTypeApp("List", listOf(mezzeTypeToAnnotation(type.elementType)), GEN_LOC)
            is MezzeType.SeqType -> ParsedSeqType(mezzeTypeToAnnotation(type.elementType), GEN_LOC)
            is MezzeType.SetType -> ParsedTypeApp("Set", listOf(mezzeTypeToAnnotation(type.elementType)), GEN_LOC)
            is MezzeType.SortedSetType -> ParsedTypeApp("SortedSet", listOf(mezzeTypeToAnnotation(type.elementType)), GEN_LOC)
            is MezzeType.MapType -> ParsedTypeApp("Map", listOf(
                mezzeTypeToAnnotation(type.keyType),
                mezzeTypeToAnnotation(type.valueType)
            ), GEN_LOC)
            is MezzeType.SortedMapType -> ParsedTypeApp("SortedMap", listOf(
                mezzeTypeToAnnotation(type.keyType),
                mezzeTypeToAnnotation(type.valueType)
            ), GEN_LOC)
            is MezzeType.TypeApplication -> ParsedTypeApp(
                type.constructor,
                type.args.map { mezzeTypeToAnnotation(it) },
                GEN_LOC
            )
            is MezzeType.FunctionType -> ParsedFunctionType(
                paramType = mezzeTypeToAnnotation(type.paramType),
                returnType = mezzeTypeToAnnotation(type.returnType),
                loc = GEN_LOC
            )
            else -> throw IllegalArgumentException("Cannot convert MezzeType to ParsedTypeAnnotation: $type")
        }
    }

    /**
     * Create an EncodeRepr impl declaration.
     */
    private fun createEncodeImpl(formatType: MezzeType, dataType: MezzeType, body: ParsedExpr): ParsedImplAbilityDecl {
        val encodeBody = doWithLet("path", array(emptyList()), body)

        return ParsedImplAbilityDecl(
            target = ParsedImplTarget.Named(ParsedQualifiedRef("EncodeRepr", null, GEN_LOC)),
            targetTypes = listOf(mezzeTypeToAnnotation(dataType)),
            operations = listOf(
                ParsedImplOp(
                    name = "encode_repr",
                    paramType = null,
                    returnType = null,
                    implementation = lambda(listOf("it", "fmt", "config"), encodeBody),
                    loc = GEN_LOC
                )
            ),
            loc = GEN_LOC
        )
    }

    /**
     * Create a DecodeRepr impl declaration.
     */
    private fun createDecodeImpl(formatType: MezzeType, dataType: MezzeType, body: ParsedExpr): ParsedImplAbilityDecl {
        val decodeBody = doWithLet("path", array(emptyList()), body)

        // The ability is `ability DecodeRepr 'a for 'repr`.
        // targetTypes is the types after `for` — the repr/format type.
        // The data type 'a is passed as an associated type parameter.
        // Use shallow type annotation to avoid infinite recursion for recursive types like Json
        // (JArray contains [Json], JObject contains [{key: Str, value: Json}]).
        val shallowFormatAnnotation = when {
            formatType is MezzeType.VariantType && formatType.typeName != null ->
                ParsedNamedType(formatType.typeName!!, GEN_LOC)
            formatType is MezzeType.VariantType ->
                mezzeTypeToAnnotation(formatType)  // fallback for unnamed variants
            else -> mezzeTypeToAnnotation(formatType)
        }
        return ParsedImplAbilityDecl(
            target = ParsedImplTarget.Named(ParsedQualifiedRef("DecodeRepr", null, GEN_LOC)),
            targetTypes = listOf(shallowFormatAnnotation),
            operations = listOf(
                ParsedImplOp(
                    name = "decode_repr",
                    paramType = null,
                    returnType = null,
                    implementation = lambda(listOf("it", "fmt", "config"), decodeBody),
                    loc = GEN_LOC
                )
            ),
            loc = GEN_LOC
        )
    }

    /**
     * Generate a StreamEncode implementation for the given format and data type.
     */
    fun generateStreamEncodeImpl(formatType: MezzeType, dataType: MezzeType): ParsedImplAbilityDecl {
        val cacheKey = "${formatType}_${dataType}"
        streamEncodeCache[cacheKey]?.let { return it }

        val impl = when (dataType) {
            is MezzeType.IntType -> generatePrimitiveStreamEncode(formatType, dataType, "stream_int")
            is MezzeType.FloatType -> generatePrimitiveStreamEncode(formatType, dataType, "stream_float")
            is MezzeType.StringType -> generatePrimitiveStreamEncode(formatType, dataType, "stream_string")
            is MezzeType.BoolType -> generatePrimitiveStreamEncode(formatType, dataType, "stream_bool")
            is MezzeType.FracType -> generatePrimitiveStreamEncode(formatType, dataType, "stream_frac")
            is MezzeType.BytesType -> generatePrimitiveStreamEncode(formatType, dataType, "stream_bytes")
            is MezzeType.NatType -> generateNatStreamEncode(formatType)
            is MezzeType.CharType -> generateCharStreamEncode(formatType)
            is MezzeType.RecordType -> generateRecordStreamEncode(formatType, dataType)
            is MezzeType.VariantType -> generateVariantStreamEncode(formatType, dataType)
            is MezzeType.ListType -> generateListStreamEncode(formatType, dataType)
            is MezzeType.TypeApplication -> generateTypeApplicationStreamEncode(formatType, dataType)
            else -> throw IllegalArgumentException("Cannot generate StreamEncode for type: $dataType")
        }

        streamEncodeCache[cacheKey] = impl
        return impl
    }

    /**
     * Generate a StreamDecode implementation for the given format and data type.
     */
    fun generateStreamDecodeImpl(formatType: MezzeType, dataType: MezzeType): ParsedImplAbilityDecl {
        val cacheKey = "${formatType}_${dataType}"
        streamDecodeCache[cacheKey]?.let { return it }

        val impl = when (dataType) {
            is MezzeType.IntType -> generatePrimitiveStreamDecode(formatType, dataType, "stream_decode_int")
            is MezzeType.FloatType -> generatePrimitiveStreamDecode(formatType, dataType, "stream_decode_float")
            is MezzeType.StringType -> generatePrimitiveStreamDecode(formatType, dataType, "stream_decode_string")
            is MezzeType.BoolType -> generatePrimitiveStreamDecode(formatType, dataType, "stream_decode_bool")
            is MezzeType.FracType -> generatePrimitiveStreamDecode(formatType, dataType, "stream_decode_frac")
            is MezzeType.BytesType -> generatePrimitiveStreamDecode(formatType, dataType, "stream_decode_bytes")
            is MezzeType.NatType -> generateNatStreamDecode(formatType)
            is MezzeType.CharType -> generateCharStreamDecode(formatType)
            is MezzeType.RecordType -> generateRecordStreamDecode(formatType, dataType)
            is MezzeType.VariantType -> generateVariantStreamDecode(formatType, dataType)
            is MezzeType.ListType -> generateListStreamDecode(formatType, dataType)
            is MezzeType.TypeApplication -> generateTypeApplicationStreamDecode(formatType, dataType)
            else -> throw IllegalArgumentException("Cannot generate StreamDecode for type: $dataType")
        }

        streamDecodeCache[cacheKey] = impl
        return impl
    }

    private fun generatePrimitiveStreamEncode(
        formatType: MezzeType,
        dataType: MezzeType,
        hookName: String
    ): ParsedImplAbilityDecl {
        val body = call(
            member(id("StreamFormat"), hookName),
            record("val" to id("it"), "buffer" to id("buffer"), "path" to id("path"))
        )
        return createStreamEncodeImpl(formatType, dataType, body)
    }

    private fun generateNatStreamEncode(formatType: MezzeType): ParsedImplAbilityDecl {
        val toIntCall = call(member(id("nat"), "to_int"), record("n" to id("it")))
        val body = call(
            member(id("StreamFormat"), "stream_int"),
            record("val" to toIntCall, "buffer" to id("buffer"), "path" to id("path"))
        )
        return createStreamEncodeImpl(formatType, MezzeType.NatType, body)
    }

    private fun generateCharStreamEncode(formatType: MezzeType): ParsedImplAbilityDecl {
        val toStringCall = call(member(id("char"), "to_string"), record("c" to id("it")))
        val body = call(
            member(id("StreamFormat"), "stream_string"),
            record("val" to toStringCall, "buffer" to id("buffer"), "path" to id("path"))
        )
        return createStreamEncodeImpl(formatType, MezzeType.CharType, body)
    }

    private fun generateRecordStreamEncode(formatType: MezzeType, recordType: MezzeType.RecordType): ParsedImplAbilityDecl {
        val fields = recordType.fields.entries.sortedBy { it.key }

        if (fields.isEmpty()) {
            val body = match(
                call(
                    member(id("StreamFormat"), "stream_record_start"),
                    record("buffer" to id("buffer"), "path" to id("path"))
                ),
                matchCase(
                    okPat("buf1"),
                    call(
                        member(id("StreamFormat"), "stream_record_end"),
                        record("buffer" to id("buf1"), "path" to id("path"))
                    )
                ),
                matchCase(errPat("e"), err(id("e")))
            )
            return createStreamEncodeImpl(formatType, recordType, body)
        }

        val body = buildRecordStreamEncodeBody(fields)
        return createStreamEncodeImpl(formatType, recordType, body)
    }

    private fun buildRecordStreamEncodeBody(fields: List<Map.Entry<String, MezzeType>>): ParsedExpr {
        var resultExpr: ParsedExpr = call(
            member(id("StreamFormat"), "stream_record_end"),
            record("buffer" to id("buf_final"), "path" to id("path"))
        )

        for ((index, entry) in fields.reversed().withIndex()) {
            val (fieldName, _) = entry
            val isFirst = index == fields.size - 1
            val bufVarIn = if (index == fields.size - 1) "buf_start" else "buf_${fields.size - 1 - index}"
            val bufVarOut = if (index == 0) "buf_final" else "buf_${fields.size - index}"

            val fieldStreamExpr = match(
                call(
                    member(id("StreamEncode"), "stream_encode"),
                    record(
                        "val" to member(id("it"), fieldName),
                        "buffer" to call(member(id("bytebuffer"), "new"), record()),
                        "path" to appendPath(id("path"), fieldName)
                    )
                ),
                matchCase(
                    okPat("field_buf"),
                    match(
                        call(
                            member(id("StreamFormat"), "stream_record_field"),
                            record(
                                "key" to str(fieldName),
                                "val" to id("field_buf"),
                                "is_first" to variant(if (isFirst) "True" else "False"),
                                "buffer" to id(bufVarIn),
                                "path" to id("path")
                            )
                        ),
                        matchCase(okPat(bufVarOut), resultExpr),
                        matchCase(errPat("e"), err(id("e")))
                    )
                ),
                matchCase(errPat("e"), err(id("e")))
            )

            resultExpr = fieldStreamExpr
        }

        return match(
            call(
                member(id("StreamFormat"), "stream_record_start"),
                record("buffer" to id("buffer"), "path" to id("path"))
            ),
            matchCase(okPat("buf_start"), resultExpr),
            matchCase(errPat("e"), err(id("e")))
        )
    }

    private fun generateVariantStreamEncode(formatType: MezzeType, variantType: MezzeType.VariantType): ParsedImplAbilityDecl {
        val constructors = variantType.constructors.entries.sortedBy { it.key }

        val cases = constructors.map { (constructorName, payloadType) ->
            if (payloadType == null) {
                matchCase(
                    constructorPat(constructorName, null),
                    call(
                        member(id("StreamFormat"), "stream_variant"),
                        record(
                            "tag" to str(constructorName),
                            "payload" to none(),
                            "buffer" to id("buffer"),
                            "path" to id("path")
                        )
                    )
                )
            } else {
                matchCase(
                    constructorPat(constructorName, recordPat("val" to varPat("payload_val"))),
                    match(
                        call(
                            member(id("StreamEncode"), "stream_encode"),
                            record(
                                "val" to id("payload_val"),
                                "buffer" to call(member(id("bytebuffer"), "new"), record()),
                                "path" to id("path")
                            )
                        ),
                        matchCase(
                            okPat("payload_buf"),
                            call(
                                member(id("StreamFormat"), "stream_variant"),
                                record(
                                    "tag" to str(constructorName),
                                    "payload" to some(id("payload_buf")),
                                    "buffer" to id("buffer"),
                                    "path" to id("path")
                                )
                            )
                        ),
                        matchCase(errPat("e"), err(id("e")))
                    )
                )
            }
        }

        val body = match(id("it"), *cases.toTypedArray())
        return createStreamEncodeImpl(formatType, variantType, body)
    }

    private fun generateListStreamEncode(formatType: MezzeType, listType: MezzeType.ListType): ParsedImplAbilityDecl {
        val body = match(
            call(
                member(id("StreamFormat"), "stream_list_start"),
                record("buffer" to id("buffer"), "path" to id("path"))
            ),
            matchCase(
                okPat("buf_start"),
                match(
                    call(
                        member(id("list"), "fold_indexed"),
                        record(
                            "list" to id("it"),
                            "init" to ok(record("buffer" to id("buf_start"), "is_first" to variant("True"))),
                            "f" to lambda(listOf("acc", "elem", "idx"),
                                match(
                                    id("acc"),
                                    matchCase(
                                        okPat("state"),
                                        match(
                                            call(
                                                member(id("StreamEncode"), "stream_encode"),
                                                record(
                                                    "val" to id("elem"),
                                                    "buffer" to call(member(id("bytebuffer"), "new"), record()),
                                                    "path" to appendIndex(id("path"), id("idx"))
                                                )
                                            ),
                                            matchCase(
                                                okPat("elem_buf"),
                                                match(
                                                    call(
                                                        member(id("StreamFormat"), "stream_list_element"),
                                                        record(
                                                            "val" to id("elem_buf"),
                                                            "is_first" to member(id("state"), "is_first"),
                                                            "buffer" to member(id("state"), "buffer"),
                                                            "path" to id("path")
                                                        )
                                                    ),
                                                    matchCase(
                                                        okPat("new_buf"),
                                                        ok(record("buffer" to id("new_buf"), "is_first" to variant("False")))
                                                    ),
                                                    matchCase(errPat("e"), err(id("e")))
                                                )
                                            ),
                                            matchCase(errPat("e"), err(id("e")))
                                        )
                                    ),
                                    matchCase(errPat("e"), err(id("e")))
                                )
                            )
                        )
                    ),
                    matchCase(
                        okPat("final_state"),
                        call(
                            member(id("StreamFormat"), "stream_list_end"),
                            record("buffer" to member(id("final_state"), "buffer"), "path" to id("path"))
                        )
                    ),
                    matchCase(errPat("e"), err(id("e")))
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )

        return createStreamEncodeImpl(formatType, listType, body)
    }

    private fun generateTypeApplicationStreamEncode(formatType: MezzeType, typeApp: MezzeType.TypeApplication): ParsedImplAbilityDecl {
        return when (typeApp.constructor) {
            "Option" -> generateOptionStreamEncode(formatType, typeApp)
            else -> throw IllegalArgumentException("Cannot generate StreamEncode for type application: ${typeApp.constructor}")
        }
    }

    private fun generateOptionStreamEncode(formatType: MezzeType, typeApp: MezzeType.TypeApplication): ParsedImplAbilityDecl {
        val body = match(
            id("it"),
            matchCase(
                nonePat(),
                call(
                    member(id("StreamFormat"), "stream_none"),
                    record("buffer" to id("buffer"), "path" to id("path"))
                )
            ),
            matchCase(
                somePat("inner"),
                match(
                    call(
                        member(id("StreamEncode"), "stream_encode"),
                        record(
                            "val" to id("inner"),
                            "buffer" to call(member(id("bytebuffer"), "new"), record()),
                            "path" to id("path")
                        )
                    ),
                    matchCase(
                        okPat("inner_buf"),
                        call(
                            member(id("StreamFormat"), "stream_some"),
                            record("val" to id("inner_buf"), "buffer" to id("buffer"), "path" to id("path"))
                        )
                    ),
                    matchCase(errPat("e"), err(id("e")))
                )
            )
        )
        return createStreamEncodeImpl(formatType, typeApp, body)
    }

    private fun generatePrimitiveStreamDecode(
        formatType: MezzeType,
        dataType: MezzeType,
        hookName: String
    ): ParsedImplAbilityDecl {
        val body = call(
            member(id("StreamDecodeFormat"), hookName),
            record("bytes" to id("bytes"), "offset" to id("offset"), "path" to id("path"))
        )
        return createStreamDecodeImpl(formatType, dataType, body)
    }

    private fun generateNatStreamDecode(formatType: MezzeType): ParsedImplAbilityDecl {
        val body = match(
            call(
                member(id("StreamDecodeFormat"), "stream_decode_int"),
                record("bytes" to id("bytes"), "offset" to id("offset"), "path" to id("path"))
            ),
            matchCase(
                okPat("result"),
                ifExpr(
                    condition = call(
                        member(id("int"), "gte"),
                        record("a" to member(id("result"), "value"), "b" to intLit(0))
                    ),
                    thenBranch = ok(record(
                        "val" to call(member(id("nat"), "from_int"), record("n" to member(id("result"), "value"))),
                        "offset" to member(id("result"), "offset")
                    )),
                    elseBranch = call(
                        member(id("codec"), "decode_error"),
                        record(
                            "path" to id("path"),
                            "message" to str("Expected non-negative integer for Nat"),
                            "expected" to str("Nat"),
                            "found" to some(str("negative Int"))
                        )
                    )
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )
        return createStreamDecodeImpl(formatType, MezzeType.NatType, body)
    }

    private fun generateCharStreamDecode(formatType: MezzeType): ParsedImplAbilityDecl {
        val body = match(
            call(
                member(id("StreamDecodeFormat"), "stream_decode_string"),
                record("bytes" to id("bytes"), "offset" to id("offset"), "path" to id("path"))
            ),
            matchCase(
                okPat("result"),
                ifExpr(
                    condition = call(
                        member(id("int"), "eq"),
                        record(
                            "a" to call(member(id("str"), "length"), record("s" to member(id("result"), "value"))),
                            "b" to intLit(1)
                        )
                    ),
                    thenBranch = ok(record(
                        "val" to call(
                            member(id("str"), "char_at"),
                            record("s" to member(id("result"), "value"), "index" to intLit(0))
                        ),
                        "offset" to member(id("result"), "offset")
                    )),
                    elseBranch = call(
                        member(id("codec"), "decode_error"),
                        record(
                            "path" to id("path"),
                            "message" to str("Expected single character string for Char"),
                            "expected" to str("Char"),
                            "found" to some(str("multi-char string"))
                        )
                    )
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )
        return createStreamDecodeImpl(formatType, MezzeType.CharType, body)
    }

    private fun generateRecordStreamDecode(formatType: MezzeType, recordType: MezzeType.RecordType): ParsedImplAbilityDecl {
        val fields = recordType.fields.entries.sortedBy { it.key }

        if (fields.isEmpty()) {
            val body = match(
                call(
                    member(id("StreamDecodeFormat"), "stream_decode_record_start"),
                    record("bytes" to id("bytes"), "offset" to id("offset"), "path" to id("path"))
                ),
                matchCase(
                    okPat("start_result"),
                    ok(record(
                        "val" to ParsedRecordLit(entries = emptyList(), loc = GEN_LOC),
                        "offset" to member(id("start_result"), "offset")
                    ))
                ),
                matchCase(errPat("e"), err(id("e")))
            )
            return createStreamDecodeImpl(formatType, recordType, body)
        }

        val body = buildRecordStreamDecodeBody(fields)
        return createStreamDecodeImpl(formatType, recordType, body)
    }

    private fun buildRecordStreamDecodeBody(fields: List<Map.Entry<String, MezzeType>>): ParsedExpr {
        var resultExpr: ParsedExpr = ok(record(
            "val" to ParsedRecordLit(
                entries = fields.map { (fieldName, _) ->
                    ParsedRecordEntry.Field(fieldName, id("${fieldName}_val"), GEN_LOC)
                },
                loc = GEN_LOC
            ),
            "offset" to id("offset_final")
        ))

        for ((index, entry) in fields.reversed().withIndex()) {
            val (fieldName, _) = entry
            val offsetIn = if (index == fields.size - 1) "offset_start" else "offset_${fields.size - 1 - index}"
            val offsetOut = if (index == 0) "offset_final" else "offset_${fields.size - index}"

            resultExpr = match(
                call(
                    member(id("StreamDecodeFormat"), "stream_decode_record_field"),
                    record(
                        "bytes" to id("bytes"),
                        "offset" to id(offsetIn),
                        "key" to str(fieldName),
                        "path" to id("path")
                    )
                ),
                matchCase(
                    okPat("field_result"),
                    match(
                        call(
                            member(id("StreamDecode"), "stream_decode"),
                            record(
                                "bytes" to member(id("field_result"), "value"),
                                "offset" to intLit(0),
                                "path" to appendPath(id("path"), fieldName)
                            )
                        ),
                        matchCase(
                            okPat("decoded_field"),
                            ParsedDo(
                                statements = listOf(
                                    letStmt("${fieldName}_val", member(id("decoded_field"), "value")),
                                    letStmt(offsetOut, member(id("field_result"), "offset"))
                                ),
                                result = resultExpr,
                                loc = GEN_LOC
                            )
                        ),
                        matchCase(errPat("e"), err(id("e")))
                    )
                ),
                matchCase(errPat("e"), err(id("e")))
            )
        }

        return match(
            call(
                member(id("StreamDecodeFormat"), "stream_decode_record_start"),
                record("bytes" to id("bytes"), "offset" to id("offset"), "path" to id("path"))
            ),
            matchCase(
                okPat("start_result"),
                ParsedDo(
                    statements = listOf(letStmt("offset_start", member(id("start_result"), "offset"))),
                    result = resultExpr,
                    loc = GEN_LOC
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )
    }

    private fun generateVariantStreamDecode(formatType: MezzeType, variantType: MezzeType.VariantType): ParsedImplAbilityDecl {
        val constructors = variantType.constructors.entries.sortedBy { it.key }

        val body = match(
            call(
                member(id("StreamDecodeFormat"), "stream_decode_variant"),
                record("bytes" to id("bytes"), "offset" to id("offset"), "path" to id("path"))
            ),
            matchCase(
                okPat("variant_result"),
                buildVariantStreamTagMatcher(constructors, "variant_result")
            ),
            matchCase(errPat("e"), err(id("e")))
        )

        return createStreamDecodeImpl(formatType, variantType, body)
    }

    private fun buildVariantStreamTagMatcher(constructors: List<Map.Entry<String, MezzeType?>>, resultVar: String): ParsedExpr {
        if (constructors.isEmpty()) {
            return call(
                member(id("codec"), "decode_error"),
                record(
                    "path" to id("path"),
                    "message" to str("Unknown variant tag"),
                    "expected" to str("valid tag"),
                    "found" to some(member(id(resultVar), "tag"))
                )
            )
        }

        val (constructorName, payloadType) = constructors.first()
        val remaining = constructors.drop(1)

        val thenBranch = if (payloadType == null) {
            ok(record(
                "val" to variant(constructorName, null),
                "offset" to member(id(resultVar), "offset")
            ))
        } else {
            match(
                member(id(resultVar), "payload"),
                matchCase(
                    somePat("payload_bytes"),
                    match(
                        call(
                            member(id("StreamDecode"), "stream_decode"),
                            record(
                                "bytes" to id("payload_bytes"),
                                "offset" to intLit(0),
                                "path" to id("path")
                            )
                        ),
                        matchCase(
                            okPat("decoded_payload"),
                            ok(record(
                                "val" to variant(constructorName, record("val" to member(id("decoded_payload"), "value"))),
                                "offset" to member(id(resultVar), "offset")
                            ))
                        ),
                        matchCase(errPat("e"), err(id("e")))
                    )
                ),
                matchCase(
                    nonePat(),
                    call(
                        member(id("codec"), "decode_error"),
                        record(
                            "path" to id("path"),
                            "message" to str("Expected payload for variant $constructorName"),
                            "expected" to str("payload"),
                            "found" to none()
                        )
                    )
                )
            )
        }

        return ifExpr(
            condition = call(
                member(id("str"), "eq"),
                record("a" to member(id(resultVar), "tag"), "b" to str(constructorName))
            ),
            thenBranch = thenBranch,
            elseBranch = buildVariantStreamTagMatcher(remaining, resultVar)
        )
    }

    private fun generateListStreamDecode(formatType: MezzeType, listType: MezzeType.ListType): ParsedImplAbilityDecl {
        val body = match(
            call(
                member(id("StreamDecodeFormat"), "stream_decode_list_start"),
                record("bytes" to id("bytes"), "offset" to id("offset"), "path" to id("path"))
            ),
            matchCase(
                okPat("start_result"),
                call(
                    id("decode_list_elements"),
                    record(
                        "bytes" to id("bytes"),
                        "offset" to member(id("start_result"), "offset"),
                        "acc" to array(emptyList()),
                        "idx" to intLit(0),
                        "path" to id("path")
                    )
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )

        return createStreamDecodeImpl(formatType, listType, body)
    }

    private fun generateTypeApplicationStreamDecode(formatType: MezzeType, typeApp: MezzeType.TypeApplication): ParsedImplAbilityDecl {
        return when (typeApp.constructor) {
            "Option" -> generateOptionStreamDecode(formatType, typeApp)
            else -> throw IllegalArgumentException("Cannot generate StreamDecode for type application: ${typeApp.constructor}")
        }
    }

    private fun generateOptionStreamDecode(formatType: MezzeType, typeApp: MezzeType.TypeApplication): ParsedImplAbilityDecl {
        val body = match(
            call(
                member(id("StreamDecodeFormat"), "stream_decode_option"),
                record("bytes" to id("bytes"), "offset" to id("offset"), "path" to id("path"))
            ),
            matchCase(
                okPat("opt_result"),
                match(
                    member(id("opt_result"), "value"),
                    matchCase(
                        nonePat(),
                        ok(record("val" to none(), "offset" to member(id("opt_result"), "offset")))
                    ),
                    matchCase(
                        somePat("inner_bytes"),
                        match(
                            call(
                                member(id("StreamDecode"), "stream_decode"),
                                record(
                                    "bytes" to id("inner_bytes"),
                                    "offset" to intLit(0),
                                    "path" to id("path")
                                )
                            ),
                            matchCase(
                                okPat("decoded_inner"),
                                ok(record(
                                    "val" to some(member(id("decoded_inner"), "value")),
                                    "offset" to member(id("opt_result"), "offset")
                                ))
                            ),
                            matchCase(errPat("e"), err(id("e")))
                        )
                    )
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )
        return createStreamDecodeImpl(formatType, typeApp, body)
    }

    /**
     * Create a StreamEncode impl declaration.
     */
    private fun createStreamEncodeImpl(formatType: MezzeType, dataType: MezzeType, body: ParsedExpr): ParsedImplAbilityDecl {
        return ParsedImplAbilityDecl(
            target = ParsedImplTarget.Named(ParsedQualifiedRef("StreamEncode", null, GEN_LOC)),
            targetTypes = listOf(mezzeTypeToAnnotation(dataType)),
            operations = listOf(
                ParsedImplOp(
                    name = "stream_encode",
                    paramType = null,
                    returnType = null,
                    implementation = lambda(listOf("buffer", "path"), body),
                    loc = GEN_LOC
                )
            ),
            loc = GEN_LOC
        )
    }

    /**
     * Create a StreamDecode impl declaration.
     */
    private fun createStreamDecodeImpl(formatType: MezzeType, dataType: MezzeType, body: ParsedExpr): ParsedImplAbilityDecl {
        return ParsedImplAbilityDecl(
            target = ParsedImplTarget.Named(ParsedQualifiedRef("StreamDecode", null, GEN_LOC)),
            targetTypes = listOf(mezzeTypeToAnnotation(dataType)),
            operations = listOf(
                ParsedImplOp(
                    name = "stream_decode",
                    paramType = null,
                    returnType = null,
                    implementation = lambda(listOf("offset", "path"), body),
                    loc = GEN_LOC
                )
            ),
            loc = GEN_LOC
        )
    }

}
