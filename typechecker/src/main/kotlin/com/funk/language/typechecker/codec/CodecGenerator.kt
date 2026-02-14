package com.funk.language.typechecker.codec

import com.funk.language.ast.*

/**
 * CodecGenerator - Generates Encode/Decode ability implementations automatically.
 *
 * This generator is invoked by the TypeChecker when it encounters an Encode/Decode
 * constraint that doesn't have an explicit implementation. It generates the
 * implementation AST based on the type structure.
 *
 * Example: For `json::encode {value = user}` where user: {name: String, age: Int},
 * this generates:
 *
 * ```
 * impl Encode Json {name: String, age: Int} where {
 *     encode = {value, path} ->
 *         let name_encoded = EncodeFormat.encode_string {value = value.name, path = append_path {path, "name"}}
 *         let age_encoded = EncodeFormat.encode_int {value = value.age, path = append_path {path, "age"}}
 *         EncodeFormat.encode_record {fields = [{key = "name", value = name_encoded}, ...], path = path}
 * }
 * ```
 */
class CodecGenerator {

    // Cache of generated implementations to avoid regeneration
    private val encodeCache = mutableMapOf<String, ImplAbilityDeclaration>()
    private val decodeCache = mutableMapOf<String, ImplAbilityDeclaration>()
    private val streamEncodeCache = mutableMapOf<String, ImplAbilityDeclaration>()
    private val streamDecodeCache = mutableMapOf<String, ImplAbilityDeclaration>()

    /**
     * Check if a type can be automatically serialized.
     *
     * Returns true for:
     * - Primitives (Int, Float, String, Bool, Nat, Frac, Char)
     * - Records with all serializable fields
     * - Variants with all serializable payloads
     * - Collections (List, Array, Set, Map) with serializable elements
     * - Option with serializable inner type
     *
     * Returns false for:
     * - FunctionType (can't serialize functions)
     * - SuspendedType (has unhandled effects)
     * - BytesType (would be circular - Bytes is the OUTPUT)
     * - ByteBufferType (mutable infrastructure)
     */
    fun isSerializable(type: FunkType): Boolean {
        return when (type) {
            // Primitives - always serializable
            is FunkType.IntType -> true
            is FunkType.FloatType -> true
            is FunkType.StringType -> true
            is FunkType.BooleanType -> true
            is FunkType.NatType -> true
            is FunkType.FracType -> true
            is FunkType.CharType -> true
            is FunkType.OrderingType -> true  // It's a variant, handled specially

            // Binary types - NOT serializable (would be circular or infrastructure)
            is FunkType.BytesType -> false
            is FunkType.ByteBufferType -> false

            // Functions - NOT serializable
            is FunkType.FunctionType -> false

            // Suspended computations - NOT serializable (have unhandled effects)
            is FunkType.SuspendedType -> false

            // Effect rows - NOT serializable
            is FunkType.EffectRow -> false

            // Records - check all fields
            is FunkType.RecordType -> type.fields.values.all { isSerializable(it) }

            // Variants - check all payloads
            is FunkType.VariantType -> type.constructors.values.all { payload ->
                payload == null || isSerializable(payload)
            }

            // Collections - check element types
            is FunkType.ListType -> isSerializable(type.elementType)
            is FunkType.ArrayType -> isSerializable(type.elementType)
            is FunkType.SetType -> isSerializable(type.elementType)
            is FunkType.SortedSetType -> isSerializable(type.elementType)
            is FunkType.MapType -> isSerializable(type.keyType) && isSerializable(type.valueType)
            is FunkType.SortedMapType -> isSerializable(type.keyType) && isSerializable(type.valueType)

            // Type applications (Option, Result, etc.) - check all args
            is FunkType.TypeApplication -> {
                // For Option and Result, check the type arguments
                type.args.all { isSerializable(it) }
            }

            // Type variables - assume serializable (constraint will be added during resolution)
            is FunkType.VarType -> true

            // Unknown types - not serializable by default
            else -> false
        }
    }

    /**
     * Generate an Encode implementation for the given format and data type.
     *
     * @param formatType The format type (e.g., Json, Csv)
     * @param dataType The data type to encode
     * @return The generated ImplAbilityDeclaration
     */
    fun generateEncodeImpl(formatType: FunkType, dataType: FunkType): ImplAbilityDeclaration {
        val cacheKey = "${formatType}_${dataType}"
        encodeCache[cacheKey]?.let { return it }

        val impl = when (dataType) {
            is FunkType.IntType -> generatePrimitiveEncode(formatType, dataType, "encode_int", "value")
            is FunkType.FloatType -> generatePrimitiveEncode(formatType, dataType, "encode_float", "value")
            is FunkType.StringType -> generatePrimitiveEncode(formatType, dataType, "encode_string", "value")
            is FunkType.BooleanType -> generatePrimitiveEncode(formatType, dataType, "encode_bool", "value")
            is FunkType.NatType -> generateNatEncode(formatType)
            is FunkType.FracType -> generatePrimitiveEncode(formatType, dataType, "encode_frac", "value")
            is FunkType.CharType -> generateCharEncode(formatType)
            is FunkType.RecordType -> generateRecordEncode(formatType, dataType)
            is FunkType.VariantType -> generateVariantEncode(formatType, dataType)
            is FunkType.ListType -> generateListEncode(formatType, dataType)
            is FunkType.ArrayType -> generateArrayEncode(formatType, dataType)
            is FunkType.SetType -> generateSetEncode(formatType, dataType)
            is FunkType.SortedSetType -> generateSortedSetEncode(formatType, dataType)
            is FunkType.MapType -> generateMapEncode(formatType, dataType)
            is FunkType.SortedMapType -> generateSortedMapEncode(formatType, dataType)
            is FunkType.TypeApplication -> generateTypeApplicationEncode(formatType, dataType)
            else -> throw IllegalArgumentException("Cannot generate Encode for type: $dataType")
        }

        encodeCache[cacheKey] = impl
        return impl
    }

    /**
     * Generate a Decode implementation for the given format and data type.
     */
    fun generateDecodeImpl(formatType: FunkType, dataType: FunkType): ImplAbilityDeclaration {
        val cacheKey = "${formatType}_${dataType}"
        decodeCache[cacheKey]?.let { return it }

        val impl = when (dataType) {
            is FunkType.IntType -> generatePrimitiveDecode(formatType, dataType, "decode_int")
            is FunkType.FloatType -> generatePrimitiveDecode(formatType, dataType, "decode_float")
            is FunkType.StringType -> generatePrimitiveDecode(formatType, dataType, "decode_string")
            is FunkType.BooleanType -> generatePrimitiveDecode(formatType, dataType, "decode_bool")
            is FunkType.NatType -> generateNatDecode(formatType)
            is FunkType.FracType -> generatePrimitiveDecode(formatType, dataType, "decode_frac")
            is FunkType.CharType -> generateCharDecode(formatType)
            is FunkType.RecordType -> generateRecordDecode(formatType, dataType)
            is FunkType.VariantType -> generateVariantDecode(formatType, dataType)
            is FunkType.ListType -> generateListDecode(formatType, dataType)
            is FunkType.ArrayType -> generateArrayDecode(formatType, dataType)
            is FunkType.SetType -> generateSetDecode(formatType, dataType)
            is FunkType.SortedSetType -> generateSortedSetDecode(formatType, dataType)
            is FunkType.MapType -> generateMapDecode(formatType, dataType)
            is FunkType.SortedMapType -> generateSortedMapDecode(formatType, dataType)
            is FunkType.TypeApplication -> generateTypeApplicationDecode(formatType, dataType)
            else -> throw IllegalArgumentException("Cannot generate Decode for type: $dataType")
        }

        decodeCache[cacheKey] = impl
        return impl
    }

    // ========================================================================
    // HELPER FUNCTIONS FOR AST CONSTRUCTION
    // ========================================================================

    /** Create a record literal with field entries */
    private fun record(vararg fields: Pair<String, Expression>): RecordLiteral {
        return RecordLiteral(
            entries = fields.map { (name, value) -> RecordFieldEntry(name, value) }
        )
    }

    /** Create a function call */
    private fun call(func: Expression, args: RecordLiteral): FunctionCall {
        return FunctionCall(function = func, arguments = args)
    }

    /** Create a member access expression */
    private fun member(receiver: Expression, memberName: String): MemberAccess {
        return MemberAccess(receiver = receiver, member = memberName)
    }

    /** Create an identifier expression */
    private fun id(name: String): Identifier {
        return Identifier(name = name)
    }

    /** Create a string literal */
    private fun str(value: String): StringLiteral {
        return StringLiteral(value = value)
    }

    /** Create a variant construction */
    private fun variant(constructor: String, payload: Expression? = null): VariantConstruction {
        return VariantConstruction(constructor = constructor, value = payload)
    }

    /** Create a constructor pattern for match */
    private fun constructorPat(name: String, recordPat: RecordPattern? = null): ConstructorPattern {
        return ConstructorPattern(constructor = name, recordPattern = recordPat)
    }

    /** Create a variable pattern */
    private fun varPat(name: String): VariablePattern {
        return VariablePattern(name = name)
    }

    /** Create a record pattern */
    private fun recordPat(vararg entries: Pair<String, Pattern?>): RecordPattern {
        return RecordPattern(entries = entries.map { (name, pat) -> RecordPatternEntry(name, pat) })
    }

    /** Create a match case with single pattern */
    private fun matchCase(pattern: Pattern, body: Expression, guard: Expression? = null): MatchCase {
        return MatchCase(patterns = listOf(pattern), guard = guard, expression = body)
    }

    /** Create a match expression */
    private fun match(expr: Expression, vararg cases: MatchCase): MatchExpression {
        return MatchExpression(expression = expr, cases = cases.toList())
    }

    /** Create a lambda expression with untyped parameters */
    private fun lambda(paramNames: List<String>, body: Expression): LambdaExpression {
        return LambdaExpression(
            parameters = UntypedParameters(names = paramNames),
            body = body
        )
    }

    /** Create an array literal from expressions */
    private fun array(elements: List<Expression>): ArrayLiteral {
        return ArrayLiteral(elements = elements.map { ArrayRegularElement(it) })
    }

    /** Create Ok variant */
    private fun ok(value: Expression): VariantConstruction {
        return variant("Ok", record("val" to value))
    }

    /** Create Err variant */
    private fun err(errorExpr: Expression): VariantConstruction {
        return variant("Err", record("error" to errorExpr))
    }

    /** Create Some variant */
    private fun some(value: Expression): VariantConstruction {
        return variant("Some", record("val" to value))
    }

    /** Create None variant */
    private fun none(): VariantConstruction {
        return variant("None", null)
    }

    /** Pattern for Ok {val = x} */
    private fun okPat(varName: String): ConstructorPattern {
        return constructorPat("Ok", recordPat("val" to varPat(varName)))
    }

    /** Pattern for Err {error = e} */
    private fun errPat(varName: String = "e"): ConstructorPattern {
        return constructorPat("Err", recordPat("error" to varPat(varName)))
    }

    /** Pattern for Some {val = x} */
    private fun somePat(varName: String): ConstructorPattern {
        return constructorPat("Some", recordPat("val" to varPat(varName)))
    }

    /** Pattern for None */
    private fun nonePat(): ConstructorPattern {
        return constructorPat("None", null)
    }

    /** Call codec::append_path */
    private fun appendPath(pathExpr: Expression, fieldName: String): Expression {
        return call(
            member(id("codec"), "append_path"),
            record("path" to pathExpr, "field" to str(fieldName))
        )
    }

    /** Call codec::append_index */
    private fun appendIndex(pathExpr: Expression, indexExpr: Expression): Expression {
        return call(
            member(id("codec"), "append_index"),
            record("path" to pathExpr, "index" to indexExpr)
        )
    }

    // ========================================================================
    // ENCODE GENERATORS
    // ========================================================================

    /**
     * Generate encode for primitive types (Int, Float, String, Bool, Frac).
     */
    private fun generatePrimitiveEncode(
        formatType: FunkType,
        dataType: FunkType,
        hookName: String,
        paramName: String
    ): ImplAbilityDeclaration {
        val body = call(
            member(id("EncodeFormat"), hookName),
            record(paramName to id("value"), "path" to id("path"))
        )
        return createEncodeImpl(formatType, dataType, body)
    }

    /**
     * Generate encode for Nat - converts to Int first.
     */
    private fun generateNatEncode(formatType: FunkType): ImplAbilityDeclaration {
        val toIntCall = call(
            member(id("nat"), "to_int"),
            record("n" to id("value"))
        )
        val body = call(
            member(id("EncodeFormat"), "encode_int"),
            record("value" to toIntCall, "path" to id("path"))
        )
        return createEncodeImpl(formatType, FunkType.NatType, body)
    }

    /**
     * Generate encode for Char - converts to String first.
     */
    private fun generateCharEncode(formatType: FunkType): ImplAbilityDeclaration {
        val toStringCall = call(
            member(id("char"), "to_string"),
            record("c" to id("value"))
        )
        val body = call(
            member(id("EncodeFormat"), "encode_string"),
            record("value" to toStringCall, "path" to id("path"))
        )
        return createEncodeImpl(formatType, FunkType.CharType, body)
    }

    /**
     * Generate encode for record types.
     */
    private fun generateRecordEncode(formatType: FunkType, recordType: FunkType.RecordType): ImplAbilityDeclaration {
        val fields = recordType.fields.entries.sortedBy { it.key }

        if (fields.isEmpty()) {
            val body = call(
                member(id("EncodeFormat"), "encode_record"),
                record("fields" to array(emptyList()), "path" to id("path"))
            )
            return createEncodeImpl(formatType, recordType, body)
        }

        val body = buildRecordEncodeBody(fields)
        return createEncodeImpl(formatType, recordType, body)
    }

    /**
     * Build the body for record encoding with Result handling.
     */
    private fun buildRecordEncodeBody(fields: List<Map.Entry<String, FunkType>>): Expression {
        // Final expression: encode_record call
        var resultExpr: Expression = call(
            member(id("EncodeFormat"), "encode_record"),
            record(
                "fields" to array(fields.map { (fieldName, _) ->
                    record(
                        "key" to str(fieldName),
                        "value" to id("${fieldName}_encoded")
                    )
                }),
                "path" to id("path")
            )
        )

        // Wrap with match expressions for error handling (in reverse order)
        for ((fieldName, _) in fields.reversed()) {
            val encodeCall = call(
                member(id("Encode"), "encode"),
                record(
                    "value" to member(id("value"), fieldName),
                    "path" to appendPath(id("path"), fieldName)
                )
            )

            resultExpr = match(
                encodeCall,
                matchCase(okPat("${fieldName}_encoded"), resultExpr),
                matchCase(errPat("e"), err(id("e")))
            )
        }

        return resultExpr
    }

    /**
     * Generate encode for variant types.
     */
    private fun generateVariantEncode(formatType: FunkType, variantType: FunkType.VariantType): ImplAbilityDeclaration {
        val constructors = variantType.constructors.entries.sortedBy { it.key }

        val cases = constructors.map { (constructorName, payloadType) ->
            if (payloadType == null) {
                // No payload
                matchCase(
                    constructorPat(constructorName, null),
                    call(
                        member(id("EncodeFormat"), "encode_variant"),
                        record(
                            "tag" to str(constructorName),
                            "payload" to none(),
                            "path" to id("path")
                        )
                    )
                )
            } else {
                // Has payload
                matchCase(
                    constructorPat(constructorName, recordPat("val" to varPat("payload_val"))),
                    match(
                        call(
                            member(id("Encode"), "encode"),
                            record("value" to id("payload_val"), "path" to id("path"))
                        ),
                        matchCase(
                            okPat("encoded_payload"),
                            call(
                                member(id("EncodeFormat"), "encode_variant"),
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

        val body = match(id("value"), *cases.toTypedArray())
        return createEncodeImpl(formatType, variantType, body)
    }

    /**
     * Generate encode for List types.
     */
    private fun generateListEncode(formatType: FunkType, listType: FunkType.ListType): ImplAbilityDeclaration {
        val body = match(
            call(
                member(id("list"), "try_map_indexed"),
                record(
                    "list" to id("value"),
                    "f" to lambda(listOf("elem", "idx"),
                        call(
                            member(id("Encode"), "encode"),
                            record(
                                "value" to id("elem"),
                                "path" to appendIndex(id("path"), id("idx"))
                            )
                        )
                    )
                )
            ),
            matchCase(
                okPat("encoded_elements"),
                call(
                    member(id("EncodeFormat"), "encode_list"),
                    record("elements" to id("encoded_elements"), "path" to id("path"))
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )
        return createEncodeImpl(formatType, listType, body)
    }

    /**
     * Generate encode for Array - convert to List first.
     */
    private fun generateArrayEncode(formatType: FunkType, arrayType: FunkType.ArrayType): ImplAbilityDeclaration {
        val toListCall = call(member(id("array"), "to_list"), record("arr" to id("value")))
        val body = call(
            member(id("Encode"), "encode"),
            record("value" to toListCall, "path" to id("path"))
        )
        return createEncodeImpl(formatType, arrayType, body)
    }

    /**
     * Generate encode for Set types.
     */
    private fun generateSetEncode(formatType: FunkType, setType: FunkType.SetType): ImplAbilityDeclaration {
        val toListCall = call(member(id("set"), "to_list"), record("s" to id("value")))

        val body = match(
            call(
                member(id("list"), "try_map_indexed"),
                record(
                    "list" to toListCall,
                    "f" to lambda(listOf("elem", "idx"),
                        call(
                            member(id("Encode"), "encode"),
                            record(
                                "value" to id("elem"),
                                "path" to appendIndex(id("path"), id("idx"))
                            )
                        )
                    )
                )
            ),
            matchCase(
                okPat("encoded_elements"),
                call(
                    member(id("EncodeFormat"), "encode_set"),
                    record(
                        "elements" to call(
                            member(id("set"), "from_list"),
                            record("list" to id("encoded_elements"))
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
     * Generate encode for SortedSet - same as Set (already sorted).
     */
    private fun generateSortedSetEncode(formatType: FunkType, sortedSetType: FunkType.SortedSetType): ImplAbilityDeclaration {
        val toListCall = call(member(id("sortedset"), "to_list"), record("s" to id("value")))
        val body = call(
            member(id("Encode"), "encode"),
            record(
                "value" to call(member(id("set"), "from_list"), record("list" to toListCall)),
                "path" to id("path")
            )
        )
        return createEncodeImpl(formatType, sortedSetType, body)
    }

    /**
     * Generate encode for Map types.
     */
    private fun generateMapEncode(formatType: FunkType, mapType: FunkType.MapType): ImplAbilityDeclaration {
        val toListCall = call(member(id("map"), "to_list"), record("m" to id("value")))

        val body = match(
            call(
                member(id("list"), "try_map"),
                record(
                    "list" to toListCall,
                    "f" to lambda(listOf("entry"),
                        match(
                            call(
                                member(id("Encode"), "encode"),
                                record("value" to member(id("entry"), "key"), "path" to id("path"))
                            ),
                            matchCase(
                                okPat("encoded_key"),
                                match(
                                    call(
                                        member(id("Encode"), "encode"),
                                        record("value" to member(id("entry"), "value"), "path" to id("path"))
                                    ),
                                    matchCase(
                                        okPat("encoded_value"),
                                        ok(record("key" to id("encoded_key"), "value" to id("encoded_value")))
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
                okPat("encoded_entries"),
                call(
                    member(id("EncodeFormat"), "encode_map"),
                    record("entries" to id("encoded_entries"), "path" to id("path"))
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )
        return createEncodeImpl(formatType, mapType, body)
    }

    /**
     * Generate encode for SortedMap - same as Map (already sorted).
     */
    private fun generateSortedMapEncode(formatType: FunkType, sortedMapType: FunkType.SortedMapType): ImplAbilityDeclaration {
        val toListCall = call(member(id("sortedmap"), "to_list"), record("m" to id("value")))
        val body = call(
            member(id("Encode"), "encode"),
            record(
                "value" to call(member(id("map"), "from_list"), record("list" to toListCall)),
                "path" to id("path")
            )
        )
        return createEncodeImpl(formatType, sortedMapType, body)
    }

    /**
     * Generate encode for TypeApplication (Option, Result, etc.).
     */
    private fun generateTypeApplicationEncode(formatType: FunkType, typeApp: FunkType.TypeApplication): ImplAbilityDeclaration {
        return when (typeApp.constructor) {
            "Option" -> generateOptionEncode(formatType, typeApp)
            "Result" -> generateResultEncode(formatType, typeApp)
            else -> throw IllegalArgumentException("Unknown type application: ${typeApp.constructor}")
        }
    }

    /**
     * Generate encode for Option type.
     */
    private fun generateOptionEncode(formatType: FunkType, typeApp: FunkType.TypeApplication): ImplAbilityDeclaration {
        val body = match(
            id("value"),
            matchCase(
                nonePat(),
                call(
                    member(id("EncodeFormat"), "encode_option"),
                    record("value" to none(), "path" to id("path"))
                )
            ),
            matchCase(
                somePat("inner"),
                match(
                    call(
                        member(id("Encode"), "encode"),
                        record("value" to id("inner"), "path" to id("path"))
                    ),
                    matchCase(
                        okPat("encoded_inner"),
                        call(
                            member(id("EncodeFormat"), "encode_option"),
                            record("value" to some(id("encoded_inner")), "path" to id("path"))
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
    private fun generateResultEncode(formatType: FunkType, typeApp: FunkType.TypeApplication): ImplAbilityDeclaration {
        val body = match(
            id("value"),
            matchCase(
                okPat("ok_val"),
                match(
                    call(
                        member(id("Encode"), "encode"),
                        record("value" to id("ok_val"), "path" to id("path"))
                    ),
                    matchCase(
                        okPat("encoded_ok"),
                        call(
                            member(id("EncodeFormat"), "encode_variant"),
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
                    call(
                        member(id("Encode"), "encode"),
                        record("value" to id("err_val"), "path" to id("path"))
                    ),
                    matchCase(
                        okPat("encoded_err"),
                        call(
                            member(id("EncodeFormat"), "encode_variant"),
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

    // ========================================================================
    // DECODE GENERATORS
    // ========================================================================

    /**
     * Generate decode for primitive types.
     */
    private fun generatePrimitiveDecode(
        formatType: FunkType,
        dataType: FunkType,
        hookName: String
    ): ImplAbilityDeclaration {
        val body = call(
            member(id("DecodeFormat"), hookName),
            record("repr" to id("repr"), "path" to id("path"))
        )
        return createDecodeImpl(formatType, dataType, body)
    }

    /**
     * Generate decode for Nat - decode Int and validate.
     */
    private fun generateNatDecode(formatType: FunkType): ImplAbilityDeclaration {
        val body = match(
            call(
                member(id("DecodeFormat"), "decode_int"),
                record("repr" to id("repr"), "path" to id("path"))
            ),
            matchCase(
                okPat("int_val"),
                IfExpression(
                    condition = call(
                        member(id("int"), "gte"),
                        record("a" to id("int_val"), "b" to IntLiteral(0))
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
        return createDecodeImpl(formatType, FunkType.NatType, body)
    }

    /**
     * Generate decode for Char - decode String and validate single character.
     */
    private fun generateCharDecode(formatType: FunkType): ImplAbilityDeclaration {
        val body = match(
            call(
                member(id("DecodeFormat"), "decode_string"),
                record("repr" to id("repr"), "path" to id("path"))
            ),
            matchCase(
                okPat("str_val"),
                IfExpression(
                    condition = call(
                        member(id("int"), "eq"),
                        record(
                            "a" to call(member(id("string"), "length"), record("s" to id("str_val"))),
                            "b" to IntLiteral(1)
                        )
                    ),
                    thenBranch = ok(call(member(id("string"), "char_at"), record("s" to id("str_val"), "index" to IntLiteral(0)))),
                    elseBranch = call(
                        member(id("codec"), "decode_error"),
                        record(
                            "path" to id("path"),
                            "message" to str("Expected single character string for Char"),
                            "expected" to str("Char"),
                            "found" to some(call(
                                member(id("string"), "concat"),
                                record("a" to str("String of length "), "b" to call(
                                    member(id("int"), "to_string"),
                                    record("n" to call(member(id("string"), "length"), record("s" to id("str_val"))))
                                ))
                            ))
                        )
                    )
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )
        return createDecodeImpl(formatType, FunkType.CharType, body)
    }

    /**
     * Generate decode for record types.
     */
    private fun generateRecordDecode(formatType: FunkType, recordType: FunkType.RecordType): ImplAbilityDeclaration {
        val fields = recordType.fields.entries.sortedBy { it.key }

        if (fields.isEmpty()) {
            val body = ok(RecordLiteral(entries = emptyList()))
            return createDecodeImpl(formatType, recordType, body)
        }

        val body = buildRecordDecodeBody(fields)
        return createDecodeImpl(formatType, recordType, body)
    }

    /**
     * Build the body for record decoding with Result handling.
     */
    private fun buildRecordDecodeBody(fields: List<Map.Entry<String, FunkType>>): Expression {
        // Final expression: construct the record
        var resultExpr: Expression = ok(
            RecordLiteral(
                entries = fields.map { (fieldName, _) ->
                    RecordFieldEntry(fieldName, id("${fieldName}_val"))
                }
            )
        )

        // Wrap with match expressions for each field (in reverse order)
        for ((fieldName, _) in fields.reversed()) {
            // First get the field repr, then decode it
            val getFieldCall = call(
                member(id("DecodeFormat"), "decode_record_field"),
                record(
                    "repr" to id("repr"),
                    "key" to str(fieldName),
                    "path" to id("path")
                )
            )

            resultExpr = match(
                getFieldCall,
                matchCase(
                    okPat("${fieldName}_repr"),
                    match(
                        call(
                            member(id("Decode"), "decode"),
                            record(
                                "repr" to id("${fieldName}_repr"),
                                "path" to appendPath(id("path"), fieldName)
                            )
                        ),
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
     * Generate decode for variant types.
     */
    private fun generateVariantDecode(formatType: FunkType, variantType: FunkType.VariantType): ImplAbilityDeclaration {
        val constructors = variantType.constructors.entries.sortedBy { it.key }

        // Build match cases for each constructor tag
        val tagCases = constructors.map { (constructorName, payloadType) ->
            if (payloadType == null) {
                // No payload variant
                matchCase(
                    constructorPat("Ok", recordPat(
                        "val" to varPat("tag_info")
                    )),
                    IfExpression(
                        condition = call(
                            member(id("string"), "eq"),
                            record("a" to member(id("tag_info"), "tag"), "b" to str(constructorName))
                        ),
                        thenBranch = ok(variant(constructorName, null)),
                        elseBranch = id("continue_matching") // placeholder - handled by chaining
                    )
                )
            } else {
                // Has payload
                matchCase(
                    constructorPat("Ok", recordPat(
                        "val" to varPat("tag_info")
                    )),
                    IfExpression(
                        condition = call(
                            member(id("string"), "eq"),
                            record("a" to member(id("tag_info"), "tag"), "b" to str(constructorName))
                        ),
                        thenBranch = match(
                            member(id("tag_info"), "payload"),
                            matchCase(
                                somePat("payload_repr"),
                                match(
                                    call(
                                        member(id("Decode"), "decode"),
                                        record("repr" to id("payload_repr"), "path" to id("path"))
                                    ),
                                    matchCase(
                                        okPat("decoded_payload"),
                                        ok(variant(constructorName, record("val" to id("decoded_payload"))))
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
                        ),
                        elseBranch = id("continue_matching")
                    )
                )
            }
        }

        // Simplified: use decode_variant and match on tag
        val body = match(
            call(
                member(id("DecodeFormat"), "decode_variant"),
                record("repr" to id("repr"), "path" to id("path"))
            ),
            matchCase(
                okPat("variant_info"),
                buildVariantTagMatcher(constructors)
            ),
            matchCase(errPat("e"), err(id("e")))
        )

        return createDecodeImpl(formatType, variantType, body)
    }

    /**
     * Build nested if-else for variant tag matching.
     */
    private fun buildVariantTagMatcher(constructors: List<Map.Entry<String, FunkType?>>): Expression {
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
            match(
                member(id("variant_info"), "payload"),
                matchCase(
                    somePat("payload_repr"),
                    match(
                        call(
                            member(id("Decode"), "decode"),
                            record("repr" to id("payload_repr"), "path" to id("path"))
                        ),
                        matchCase(
                            okPat("decoded_payload"),
                            ok(variant(constructorName, record("val" to id("decoded_payload"))))
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

        return IfExpression(
            condition = call(
                member(id("string"), "eq"),
                record("a" to member(id("variant_info"), "tag"), "b" to str(constructorName))
            ),
            thenBranch = thenBranch,
            elseBranch = buildVariantTagMatcher(remaining)
        )
    }

    /**
     * Generate decode for List types.
     */
    private fun generateListDecode(formatType: FunkType, listType: FunkType.ListType): ImplAbilityDeclaration {
        val body = match(
            call(
                member(id("DecodeFormat"), "decode_list"),
                record("repr" to id("repr"), "path" to id("path"))
            ),
            matchCase(
                okPat("repr_list"),
                match(
                    call(
                        member(id("list"), "try_map_indexed"),
                        record(
                            "list" to id("repr_list"),
                            "f" to lambda(listOf("elem_repr", "idx"),
                                call(
                                    member(id("Decode"), "decode"),
                                    record(
                                        "repr" to id("elem_repr"),
                                        "path" to appendIndex(id("path"), id("idx"))
                                    )
                                )
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
     * Generate decode for Array - decode as List then convert.
     */
    private fun generateArrayDecode(formatType: FunkType, arrayType: FunkType.ArrayType): ImplAbilityDeclaration {
        val body = match(
            call(
                member(id("Decode"), "decode"),
                record("repr" to id("repr"), "path" to id("path"))
            ),
            matchCase(
                okPat("decoded_list"),
                ok(call(member(id("array"), "from_list"), record("list" to id("decoded_list"))))
            ),
            matchCase(errPat("e"), err(id("e")))
        )
        return createDecodeImpl(formatType, arrayType, body)
    }

    /**
     * Generate decode for Set types.
     */
    private fun generateSetDecode(formatType: FunkType, setType: FunkType.SetType): ImplAbilityDeclaration {
        val body = match(
            call(
                member(id("DecodeFormat"), "decode_set"),
                record("repr" to id("repr"), "path" to id("path"))
            ),
            matchCase(
                okPat("repr_set"),
                match(
                    call(
                        member(id("list"), "try_map_indexed"),
                        record(
                            "list" to call(member(id("set"), "to_list"), record("s" to id("repr_set"))),
                            "f" to lambda(listOf("elem_repr", "idx"),
                                call(
                                    member(id("Decode"), "decode"),
                                    record(
                                        "repr" to id("elem_repr"),
                                        "path" to appendIndex(id("path"), id("idx"))
                                    )
                                )
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
    private fun generateSortedSetDecode(formatType: FunkType, sortedSetType: FunkType.SortedSetType): ImplAbilityDeclaration {
        val body = match(
            call(
                member(id("Decode"), "decode"),
                record("repr" to id("repr"), "path" to id("path"))
            ),
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
    private fun generateMapDecode(formatType: FunkType, mapType: FunkType.MapType): ImplAbilityDeclaration {
        val body = match(
            call(
                member(id("DecodeFormat"), "decode_map"),
                record("repr" to id("repr"), "path" to id("path"))
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
                                    call(
                                        member(id("Decode"), "decode"),
                                        record("repr" to member(id("entry"), "key"), "path" to id("path"))
                                    ),
                                    matchCase(
                                        okPat("decoded_key"),
                                        match(
                                            call(
                                                member(id("Decode"), "decode"),
                                                record("repr" to member(id("entry"), "value"), "path" to id("path"))
                                            ),
                                            matchCase(
                                                okPat("decoded_value"),
                                                ok(record("key" to id("decoded_key"), "value" to id("decoded_value")))
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
    private fun generateSortedMapDecode(formatType: FunkType, sortedMapType: FunkType.SortedMapType): ImplAbilityDeclaration {
        val body = match(
            call(
                member(id("Decode"), "decode"),
                record("repr" to id("repr"), "path" to id("path"))
            ),
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
    private fun generateTypeApplicationDecode(formatType: FunkType, typeApp: FunkType.TypeApplication): ImplAbilityDeclaration {
        return when (typeApp.constructor) {
            "Option" -> generateOptionDecode(formatType, typeApp)
            "Result" -> generateResultDecode(formatType, typeApp)
            else -> throw IllegalArgumentException("Unknown type application: ${typeApp.constructor}")
        }
    }

    /**
     * Generate decode for Option type.
     */
    private fun generateOptionDecode(formatType: FunkType, typeApp: FunkType.TypeApplication): ImplAbilityDeclaration {
        val body = match(
            call(
                member(id("DecodeFormat"), "decode_option"),
                record("repr" to id("repr"), "path" to id("path"))
            ),
            matchCase(
                okPat("opt_repr"),
                match(
                    id("opt_repr"),
                    matchCase(nonePat(), ok(none())),
                    matchCase(
                        somePat("inner_repr"),
                        match(
                            call(
                                member(id("Decode"), "decode"),
                                record("repr" to id("inner_repr"), "path" to id("path"))
                            ),
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
    private fun generateResultDecode(formatType: FunkType, typeApp: FunkType.TypeApplication): ImplAbilityDeclaration {
        val body = match(
            call(
                member(id("DecodeFormat"), "decode_variant"),
                record("repr" to id("repr"), "path" to id("path"))
            ),
            matchCase(
                okPat("variant_info"),
                IfExpression(
                    condition = call(
                        member(id("string"), "eq"),
                        record("a" to member(id("variant_info"), "tag"), "b" to str("Ok"))
                    ),
                    thenBranch = match(
                        member(id("variant_info"), "payload"),
                        matchCase(
                            somePat("payload_repr"),
                            match(
                                call(
                                    member(id("Decode"), "decode"),
                                    record("repr" to id("payload_repr"), "path" to id("path"))
                                ),
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
                    elseBranch = IfExpression(
                        condition = call(
                            member(id("string"), "eq"),
                            record("a" to member(id("variant_info"), "tag"), "b" to str("Err"))
                        ),
                        thenBranch = match(
                            member(id("variant_info"), "payload"),
                            matchCase(
                                somePat("payload_repr"),
                                match(
                                    call(
                                        member(id("Decode"), "decode"),
                                        record("repr" to id("payload_repr"), "path" to id("path"))
                                    ),
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

    // ========================================================================
    // IMPL CREATION HELPERS
    // ========================================================================

    /**
     * Convert FunkType to TypeAnnotation for use in ImplAbilityDeclaration.
     */
    private fun funkTypeToAnnotation(type: FunkType): TypeAnnotation {
        return when (type) {
            is FunkType.IntType -> IdentifierTypeAnnotation("Int")
            is FunkType.FloatType -> IdentifierTypeAnnotation("Float")
            is FunkType.StringType -> IdentifierTypeAnnotation("String")
            is FunkType.BooleanType -> IdentifierTypeAnnotation("Bool")
            is FunkType.NatType -> IdentifierTypeAnnotation("Nat")
            is FunkType.FracType -> IdentifierTypeAnnotation("Frac")
            is FunkType.CharType -> IdentifierTypeAnnotation("Char")
            is FunkType.OrderingType -> IdentifierTypeAnnotation("Ordering")
            is FunkType.BytesType -> IdentifierTypeAnnotation("Bytes")
            is FunkType.ByteBufferType -> IdentifierTypeAnnotation("ByteBuffer")
            is FunkType.VarType -> IdentifierTypeAnnotation(type.variable.name)
            is FunkType.RecordType -> RecordTypeAnnotation(
                entries = type.fields.map { (name, fieldType) ->
                    RecordTypeEntry(name, funkTypeToAnnotation(fieldType))
                }
            )
            is FunkType.VariantType -> VariantTypeAnnotation(
                constructors = type.constructors.map { (name, payloadType) ->
                    VariantTypeConstructor(name, payloadType?.let { funkTypeToAnnotation(it) })
                }
            )
            is FunkType.ListType -> TypeApplicationAnnotation("List", listOf(funkTypeToAnnotation(type.elementType)))
            is FunkType.ArrayType -> ArrayTypeAnnotation(funkTypeToAnnotation(type.elementType))
            is FunkType.SetType -> TypeApplicationAnnotation("Set", listOf(funkTypeToAnnotation(type.elementType)))
            is FunkType.SortedSetType -> TypeApplicationAnnotation("SortedSet", listOf(funkTypeToAnnotation(type.elementType)))
            is FunkType.MapType -> TypeApplicationAnnotation("Map", listOf(
                funkTypeToAnnotation(type.keyType),
                funkTypeToAnnotation(type.valueType)
            ))
            is FunkType.SortedMapType -> TypeApplicationAnnotation("SortedMap", listOf(
                funkTypeToAnnotation(type.keyType),
                funkTypeToAnnotation(type.valueType)
            ))
            is FunkType.TypeApplication -> TypeApplicationAnnotation(
                type.constructor,
                type.args.map { funkTypeToAnnotation(it) }
            )
            is FunkType.FunctionType -> FunctionTypeAnnotation(
                paramType = funkTypeToAnnotation(type.paramType),
                returnType = funkTypeToAnnotation(type.returnType)
            )
            else -> throw IllegalArgumentException("Cannot convert FunkType to TypeAnnotation: $type")
        }
    }

    /**
     * Create an Encode impl declaration.
     */
    private fun createEncodeImpl(formatType: FunkType, dataType: FunkType, body: Expression): ImplAbilityDeclaration {
        return ImplAbilityDeclaration(
            abilityName = "Encode",
            targetTypes = listOf(funkTypeToAnnotation(formatType), funkTypeToAnnotation(dataType)),
            operations = listOf(
                ImplAbilityOperation(
                    name = "encode",
                    paramType = null,  // Inferred from ability declaration
                    returnType = null, // Inferred from ability declaration
                    implementation = lambda(listOf("value", "path"), body)
                )
            )
        )
    }

    /**
     * Create a Decode impl declaration.
     */
    private fun createDecodeImpl(formatType: FunkType, dataType: FunkType, body: Expression): ImplAbilityDeclaration {
        return ImplAbilityDeclaration(
            abilityName = "Decode",
            targetTypes = listOf(funkTypeToAnnotation(formatType), funkTypeToAnnotation(dataType)),
            operations = listOf(
                ImplAbilityOperation(
                    name = "decode",
                    paramType = null,
                    returnType = null,
                    implementation = lambda(listOf("repr", "path"), body)
                )
            )
        )
    }

    // ========================================================================
    // STREAMING ENCODE/DECODE GENERATORS (Tier 2)
    // ========================================================================

    /**
     * Generate a StreamEncode implementation for the given format and data type.
     */
    fun generateStreamEncodeImpl(formatType: FunkType, dataType: FunkType): ImplAbilityDeclaration {
        val cacheKey = "${formatType}_${dataType}"
        streamEncodeCache[cacheKey]?.let { return it }

        val impl = when (dataType) {
            is FunkType.IntType -> generatePrimitiveStreamEncode(formatType, dataType, "stream_int")
            is FunkType.FloatType -> generatePrimitiveStreamEncode(formatType, dataType, "stream_float")
            is FunkType.StringType -> generatePrimitiveStreamEncode(formatType, dataType, "stream_string")
            is FunkType.BooleanType -> generatePrimitiveStreamEncode(formatType, dataType, "stream_bool")
            is FunkType.FracType -> generatePrimitiveStreamEncode(formatType, dataType, "stream_frac")
            is FunkType.BytesType -> generatePrimitiveStreamEncode(formatType, dataType, "stream_bytes")
            is FunkType.NatType -> generateNatStreamEncode(formatType)
            is FunkType.CharType -> generateCharStreamEncode(formatType)
            is FunkType.RecordType -> generateRecordStreamEncode(formatType, dataType)
            is FunkType.VariantType -> generateVariantStreamEncode(formatType, dataType)
            is FunkType.ListType -> generateListStreamEncode(formatType, dataType)
            is FunkType.TypeApplication -> generateTypeApplicationStreamEncode(formatType, dataType)
            else -> throw IllegalArgumentException("Cannot generate StreamEncode for type: $dataType")
        }

        streamEncodeCache[cacheKey] = impl
        return impl
    }

    /**
     * Generate a StreamDecode implementation for the given format and data type.
     */
    fun generateStreamDecodeImpl(formatType: FunkType, dataType: FunkType): ImplAbilityDeclaration {
        val cacheKey = "${formatType}_${dataType}"
        streamDecodeCache[cacheKey]?.let { return it }

        val impl = when (dataType) {
            is FunkType.IntType -> generatePrimitiveStreamDecode(formatType, dataType, "stream_decode_int")
            is FunkType.FloatType -> generatePrimitiveStreamDecode(formatType, dataType, "stream_decode_float")
            is FunkType.StringType -> generatePrimitiveStreamDecode(formatType, dataType, "stream_decode_string")
            is FunkType.BooleanType -> generatePrimitiveStreamDecode(formatType, dataType, "stream_decode_bool")
            is FunkType.FracType -> generatePrimitiveStreamDecode(formatType, dataType, "stream_decode_frac")
            is FunkType.BytesType -> generatePrimitiveStreamDecode(formatType, dataType, "stream_decode_bytes")
            is FunkType.NatType -> generateNatStreamDecode(formatType)
            is FunkType.CharType -> generateCharStreamDecode(formatType)
            is FunkType.RecordType -> generateRecordStreamDecode(formatType, dataType)
            is FunkType.VariantType -> generateVariantStreamDecode(formatType, dataType)
            is FunkType.ListType -> generateListStreamDecode(formatType, dataType)
            is FunkType.TypeApplication -> generateTypeApplicationStreamDecode(formatType, dataType)
            else -> throw IllegalArgumentException("Cannot generate StreamDecode for type: $dataType")
        }

        streamDecodeCache[cacheKey] = impl
        return impl
    }

    // ========================================================================
    // STREAMING ENCODE GENERATORS
    // ========================================================================

    private fun generatePrimitiveStreamEncode(
        formatType: FunkType,
        dataType: FunkType,
        hookName: String
    ): ImplAbilityDeclaration {
        val body = call(
            member(id("StreamEncodeFormat"), hookName),
            record("value" to id("value"), "buffer" to id("buffer"), "path" to id("path"))
        )
        return createStreamEncodeImpl(formatType, dataType, body)
    }

    private fun generateNatStreamEncode(formatType: FunkType): ImplAbilityDeclaration {
        val toIntCall = call(member(id("nat"), "to_int"), record("n" to id("value")))
        val body = call(
            member(id("StreamEncodeFormat"), "stream_int"),
            record("value" to toIntCall, "buffer" to id("buffer"), "path" to id("path"))
        )
        return createStreamEncodeImpl(formatType, FunkType.NatType, body)
    }

    private fun generateCharStreamEncode(formatType: FunkType): ImplAbilityDeclaration {
        val toStringCall = call(member(id("char"), "to_string"), record("c" to id("value")))
        val body = call(
            member(id("StreamEncodeFormat"), "stream_string"),
            record("value" to toStringCall, "buffer" to id("buffer"), "path" to id("path"))
        )
        return createStreamEncodeImpl(formatType, FunkType.CharType, body)
    }

    private fun generateRecordStreamEncode(formatType: FunkType, recordType: FunkType.RecordType): ImplAbilityDeclaration {
        val fields = recordType.fields.entries.sortedBy { it.key }

        if (fields.isEmpty()) {
            // Empty record
            val body = match(
                call(
                    member(id("StreamEncodeFormat"), "stream_record_start"),
                    record("buffer" to id("buffer"), "path" to id("path"))
                ),
                matchCase(
                    okPat("buf1"),
                    call(
                        member(id("StreamEncodeFormat"), "stream_record_end"),
                        record("buffer" to id("buf1"), "path" to id("path"))
                    )
                ),
                matchCase(errPat("e"), err(id("e")))
            )
            return createStreamEncodeImpl(formatType, recordType, body)
        }

        // Build nested streaming calls for each field
        val body = buildRecordStreamEncodeBody(fields)
        return createStreamEncodeImpl(formatType, recordType, body)
    }

    private fun buildRecordStreamEncodeBody(fields: List<Map.Entry<String, FunkType>>): Expression {
        // Start with stream_record_start, then stream each field, then stream_record_end
        var currentBuffer = "buffer"
        var resultExpr: Expression = call(
            member(id("StreamEncodeFormat"), "stream_record_end"),
            record("buffer" to id("buf_final"), "path" to id("path"))
        )

        // Build from innermost to outermost
        for ((index, entry) in fields.reversed().withIndex()) {
            val (fieldName, _) = entry
            val isFirst = index == fields.size - 1  // Reversed, so last in loop is first field
            val bufVarIn = if (index == fields.size - 1) "buf_start" else "buf_${fields.size - 1 - index}"
            val bufVarOut = if (index == 0) "buf_final" else "buf_${fields.size - index}"

            val fieldStreamExpr = match(
                call(
                    member(id("StreamEncode"), "stream_encode"),
                    record(
                        "value" to member(id("value"), fieldName),
                        "buffer" to call(member(id("bytebuffer"), "new"), record()),
                        "path" to appendPath(id("path"), fieldName)
                    )
                ),
                matchCase(
                    okPat("field_buf"),
                    match(
                        call(
                            member(id("StreamEncodeFormat"), "stream_record_field"),
                            record(
                                "key" to str(fieldName),
                                "value" to id("field_buf"),
                                "is_first" to BooleanLiteral(isFirst),
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

        // Wrap with stream_record_start
        return match(
            call(
                member(id("StreamEncodeFormat"), "stream_record_start"),
                record("buffer" to id("buffer"), "path" to id("path"))
            ),
            matchCase(okPat("buf_start"), resultExpr),
            matchCase(errPat("e"), err(id("e")))
        )
    }

    private fun generateVariantStreamEncode(formatType: FunkType, variantType: FunkType.VariantType): ImplAbilityDeclaration {
        val constructors = variantType.constructors.entries.sortedBy { it.key }

        val cases = constructors.map { (constructorName, payloadType) ->
            if (payloadType == null) {
                matchCase(
                    constructorPat(constructorName, null),
                    call(
                        member(id("StreamEncodeFormat"), "stream_variant"),
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
                                "value" to id("payload_val"),
                                "buffer" to call(member(id("bytebuffer"), "new"), record()),
                                "path" to id("path")
                            )
                        ),
                        matchCase(
                            okPat("payload_buf"),
                            call(
                                member(id("StreamEncodeFormat"), "stream_variant"),
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

        val body = match(id("value"), *cases.toTypedArray())
        return createStreamEncodeImpl(formatType, variantType, body)
    }

    private fun generateListStreamEncode(formatType: FunkType, listType: FunkType.ListType): ImplAbilityDeclaration {
        // Use recursive approach with fold
        val body = match(
            call(
                member(id("StreamEncodeFormat"), "stream_list_start"),
                record("buffer" to id("buffer"), "path" to id("path"))
            ),
            matchCase(
                okPat("buf_start"),
                match(
                    call(
                        member(id("list"), "fold_indexed"),
                        record(
                            "list" to id("value"),
                            "init" to ok(record("buffer" to id("buf_start"), "is_first" to BooleanLiteral(true))),
                            "f" to lambda(listOf("acc", "elem", "idx"),
                                match(
                                    id("acc"),
                                    matchCase(
                                        okPat("state"),
                                        match(
                                            call(
                                                member(id("StreamEncode"), "stream_encode"),
                                                record(
                                                    "value" to id("elem"),
                                                    "buffer" to call(member(id("bytebuffer"), "new"), record()),
                                                    "path" to appendIndex(id("path"), id("idx"))
                                                )
                                            ),
                                            matchCase(
                                                okPat("elem_buf"),
                                                match(
                                                    call(
                                                        member(id("StreamEncodeFormat"), "stream_list_element"),
                                                        record(
                                                            "value" to id("elem_buf"),
                                                            "is_first" to member(id("state"), "is_first"),
                                                            "buffer" to member(id("state"), "buffer"),
                                                            "path" to id("path")
                                                        )
                                                    ),
                                                    matchCase(
                                                        okPat("new_buf"),
                                                        ok(record("buffer" to id("new_buf"), "is_first" to BooleanLiteral(false)))
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
                            member(id("StreamEncodeFormat"), "stream_list_end"),
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

    private fun generateTypeApplicationStreamEncode(formatType: FunkType, typeApp: FunkType.TypeApplication): ImplAbilityDeclaration {
        return when (typeApp.constructor) {
            "Option" -> generateOptionStreamEncode(formatType, typeApp)
            else -> throw IllegalArgumentException("Cannot generate StreamEncode for type application: ${typeApp.constructor}")
        }
    }

    private fun generateOptionStreamEncode(formatType: FunkType, typeApp: FunkType.TypeApplication): ImplAbilityDeclaration {
        val body = match(
            id("value"),
            matchCase(
                nonePat(),
                call(
                    member(id("StreamEncodeFormat"), "stream_none"),
                    record("buffer" to id("buffer"), "path" to id("path"))
                )
            ),
            matchCase(
                somePat("inner"),
                match(
                    call(
                        member(id("StreamEncode"), "stream_encode"),
                        record(
                            "value" to id("inner"),
                            "buffer" to call(member(id("bytebuffer"), "new"), record()),
                            "path" to id("path")
                        )
                    ),
                    matchCase(
                        okPat("inner_buf"),
                        call(
                            member(id("StreamEncodeFormat"), "stream_some"),
                            record("value" to id("inner_buf"), "buffer" to id("buffer"), "path" to id("path"))
                        )
                    ),
                    matchCase(errPat("e"), err(id("e")))
                )
            )
        )
        return createStreamEncodeImpl(formatType, typeApp, body)
    }

    // ========================================================================
    // STREAMING DECODE GENERATORS
    // ========================================================================

    private fun generatePrimitiveStreamDecode(
        formatType: FunkType,
        dataType: FunkType,
        hookName: String
    ): ImplAbilityDeclaration {
        val body = call(
            member(id("StreamDecodeFormat"), hookName),
            record("bytes" to id("bytes"), "offset" to id("offset"), "path" to id("path"))
        )
        return createStreamDecodeImpl(formatType, dataType, body)
    }

    private fun generateNatStreamDecode(formatType: FunkType): ImplAbilityDeclaration {
        val body = match(
            call(
                member(id("StreamDecodeFormat"), "stream_decode_int"),
                record("bytes" to id("bytes"), "offset" to id("offset"), "path" to id("path"))
            ),
            matchCase(
                okPat("result"),
                IfExpression(
                    condition = call(
                        member(id("int"), "gte"),
                        record("a" to member(id("result"), "value"), "b" to IntLiteral(0))
                    ),
                    thenBranch = ok(record(
                        "value" to call(member(id("nat"), "from_int"), record("n" to member(id("result"), "value"))),
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
        return createStreamDecodeImpl(formatType, FunkType.NatType, body)
    }

    private fun generateCharStreamDecode(formatType: FunkType): ImplAbilityDeclaration {
        val body = match(
            call(
                member(id("StreamDecodeFormat"), "stream_decode_string"),
                record("bytes" to id("bytes"), "offset" to id("offset"), "path" to id("path"))
            ),
            matchCase(
                okPat("result"),
                IfExpression(
                    condition = call(
                        member(id("int"), "eq"),
                        record(
                            "a" to call(member(id("string"), "length"), record("s" to member(id("result"), "value"))),
                            "b" to IntLiteral(1)
                        )
                    ),
                    thenBranch = ok(record(
                        "value" to call(
                            member(id("string"), "char_at"),
                            record("s" to member(id("result"), "value"), "index" to IntLiteral(0))
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
        return createStreamDecodeImpl(formatType, FunkType.CharType, body)
    }

    private fun generateRecordStreamDecode(formatType: FunkType, recordType: FunkType.RecordType): ImplAbilityDeclaration {
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
                        "value" to RecordLiteral(entries = emptyList()),
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

    private fun buildRecordStreamDecodeBody(fields: List<Map.Entry<String, FunkType>>): Expression {
        // Final expression: construct the record
        var resultExpr: Expression = ok(record(
            "value" to RecordLiteral(
                entries = fields.map { (fieldName, _) ->
                    RecordFieldEntry(fieldName, id("${fieldName}_val"))
                }
            ),
            "offset" to id("offset_final")
        ))

        // Build from innermost to outermost
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
                                "offset" to IntLiteral(0),
                                "path" to appendPath(id("path"), fieldName)
                            )
                        ),
                        matchCase(
                            okPat("decoded_field"),
                            DoExpression(
                                statements = listOf(
                                    LetStatement.simple("${fieldName}_val", member(id("decoded_field"), "value")),
                                    LetStatement.simple(offsetOut, member(id("field_result"), "offset"))
                                ),
                                result = resultExpr
                            )
                        ),
                        matchCase(errPat("e"), err(id("e")))
                    )
                ),
                matchCase(errPat("e"), err(id("e")))
            )
        }

        // Wrap with stream_decode_record_start
        return match(
            call(
                member(id("StreamDecodeFormat"), "stream_decode_record_start"),
                record("bytes" to id("bytes"), "offset" to id("offset"), "path" to id("path"))
            ),
            matchCase(
                okPat("start_result"),
                DoExpression(
                    statements = listOf(LetStatement.simple("offset_start", member(id("start_result"), "offset"))),
                    result = resultExpr
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )
    }

    private fun generateVariantStreamDecode(formatType: FunkType, variantType: FunkType.VariantType): ImplAbilityDeclaration {
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

    private fun buildVariantStreamTagMatcher(constructors: List<Map.Entry<String, FunkType?>>, resultVar: String): Expression {
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
                "value" to variant(constructorName, null),
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
                                "offset" to IntLiteral(0),
                                "path" to id("path")
                            )
                        ),
                        matchCase(
                            okPat("decoded_payload"),
                            ok(record(
                                "value" to variant(constructorName, record("val" to member(id("decoded_payload"), "value"))),
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

        return IfExpression(
            condition = call(
                member(id("string"), "eq"),
                record("a" to member(id(resultVar), "tag"), "b" to str(constructorName))
            ),
            thenBranch = thenBranch,
            elseBranch = buildVariantStreamTagMatcher(remaining, resultVar)
        )
    }

    private fun generateListStreamDecode(formatType: FunkType, listType: FunkType.ListType): ImplAbilityDeclaration {
        // Recursive decoding with stream_decode_list_next
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
                        "idx" to IntLiteral(0),
                        "path" to id("path")
                    )
                )
            ),
            matchCase(errPat("e"), err(id("e")))
        )

        // Note: This assumes a helper function decode_list_elements exists
        // In practice, this would need to be generated or use a different approach
        return createStreamDecodeImpl(formatType, listType, body)
    }

    private fun generateTypeApplicationStreamDecode(formatType: FunkType, typeApp: FunkType.TypeApplication): ImplAbilityDeclaration {
        return when (typeApp.constructor) {
            "Option" -> generateOptionStreamDecode(formatType, typeApp)
            else -> throw IllegalArgumentException("Cannot generate StreamDecode for type application: ${typeApp.constructor}")
        }
    }

    private fun generateOptionStreamDecode(formatType: FunkType, typeApp: FunkType.TypeApplication): ImplAbilityDeclaration {
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
                        ok(record("value" to none(), "offset" to member(id("opt_result"), "offset")))
                    ),
                    matchCase(
                        somePat("inner_bytes"),
                        match(
                            call(
                                member(id("StreamDecode"), "stream_decode"),
                                record(
                                    "bytes" to id("inner_bytes"),
                                    "offset" to IntLiteral(0),
                                    "path" to id("path")
                                )
                            ),
                            matchCase(
                                okPat("decoded_inner"),
                                ok(record(
                                    "value" to some(member(id("decoded_inner"), "value")),
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

    // ========================================================================
    // STREAMING IMPL CREATION HELPERS
    // ========================================================================

    private fun createStreamEncodeImpl(formatType: FunkType, dataType: FunkType, body: Expression): ImplAbilityDeclaration {
        return ImplAbilityDeclaration(
            abilityName = "StreamEncode",
            targetTypes = listOf(funkTypeToAnnotation(formatType), funkTypeToAnnotation(dataType)),
            operations = listOf(
                ImplAbilityOperation(
                    name = "stream_encode",
                    paramType = null,
                    returnType = null,
                    implementation = lambda(listOf("value", "buffer", "path"), body)
                )
            )
        )
    }

    private fun createStreamDecodeImpl(formatType: FunkType, dataType: FunkType, body: Expression): ImplAbilityDeclaration {
        return ImplAbilityDeclaration(
            abilityName = "StreamDecode",
            targetTypes = listOf(funkTypeToAnnotation(formatType), funkTypeToAnnotation(dataType)),
            operations = listOf(
                ImplAbilityOperation(
                    name = "stream_decode",
                    paramType = null,
                    returnType = null,
                    implementation = lambda(listOf("bytes", "offset", "path"), body)
                )
            )
        )
    }

    // ========================================================================
    // CACHE MANAGEMENT
    // ========================================================================

    /**
     * Clear all caches.
     */
    fun clearCaches() {
        encodeCache.clear()
        decodeCache.clear()
        streamEncodeCache.clear()
        streamDecodeCache.clear()
    }

    /**
     * Get statistics about cached implementations.
     */
    fun getCacheStats(): Map<String, Int> {
        return mapOf(
            "encode" to encodeCache.size,
            "decode" to decodeCache.size,
            "streamEncode" to streamEncodeCache.size,
            "streamDecode" to streamDecodeCache.size
        )
    }
}
