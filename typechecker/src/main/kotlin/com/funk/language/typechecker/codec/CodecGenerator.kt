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
     * Generate a normalized cache key for a type.
     * This ensures that record types with different field orders produce the same key.
     */
    private fun normalizedCacheKey(formatType: FunkType, dataType: FunkType): String {
        val normalizedData = normalizeType(dataType)
        return "${normalizeType(formatType)}_$normalizedData"
    }

    /**
     * Normalize a type for consistent cache key generation.
     * Record fields are sorted alphabetically.
     */
    private fun normalizeType(type: FunkType): String {
        return when (type) {
            is FunkType.RecordType -> {
                val sortedFields = type.fields.entries.sortedBy { it.key }
                    .joinToString(",") { "${it.key}:${normalizeType(it.value)}" }
                "{$sortedFields}"
            }
            is FunkType.VariantType -> {
                val sortedCtors = type.constructors.entries.sortedBy { it.key }
                    .joinToString(",") { "${it.key}:${it.value?.let { v -> normalizeType(v) } ?: "()"}" }
                "|$sortedCtors|"
            }
            is FunkType.ListType -> "List[${normalizeType(type.elementType)}]"
            is FunkType.ArrayType -> "Array[${normalizeType(type.elementType)}]"
            is FunkType.SetType -> "Set[${normalizeType(type.elementType)}]"
            is FunkType.SortedSetType -> "SortedSet[${normalizeType(type.elementType)}]"
            is FunkType.MapType -> "Map[${normalizeType(type.keyType)},${normalizeType(type.valueType)}]"
            is FunkType.SortedMapType -> "SortedMap[${normalizeType(type.keyType)},${normalizeType(type.valueType)}]"
            is FunkType.TypeApplication -> "${type.constructor}[${type.args.joinToString(",") { normalizeType(it) }}]"
            else -> type.toString()
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
        val cacheKey = normalizedCacheKey(formatType, dataType)
        encodeCache[cacheKey]?.let { return it }

        val impl = when {
            // Primitives
            dataType is FunkType.IntType -> generatePrimitiveEncode(formatType, dataType, "encode_int", "val")
            dataType is FunkType.FloatType -> generatePrimitiveEncode(formatType, dataType, "encode_float", "val")
            dataType is FunkType.StringType -> generatePrimitiveEncode(formatType, dataType, "encode_string", "val")
            dataType is FunkType.BooleanType -> generatePrimitiveEncode(formatType, dataType, "encode_bool", "val")
            dataType is FunkType.NatType -> generateNatEncode(formatType)
            dataType is FunkType.FracType -> generatePrimitiveEncode(formatType, dataType, "encode_frac", "val")
            dataType is FunkType.CharType -> generateCharEncode(formatType)

            // CRITICAL: Check for Bool variant pattern BEFORE generic variant handling
            // Bool is internally represented as VariantType({True -> null, False -> null})
            isBoolVariant(dataType) -> generateBoolEncode(formatType)

            // Records and variants
            dataType is FunkType.RecordType -> generateRecordEncode(formatType, dataType)
            dataType is FunkType.VariantType -> generateVariantEncode(formatType, dataType)

            // Collections
            dataType is FunkType.ListType -> generateListEncode(formatType, dataType)
            dataType is FunkType.ArrayType -> generateArrayEncode(formatType, dataType)
            dataType is FunkType.SetType -> generateSetEncode(formatType, dataType)
            dataType is FunkType.SortedSetType -> generateSortedSetEncode(formatType, dataType)
            dataType is FunkType.MapType -> generateMapEncode(formatType, dataType)
            dataType is FunkType.SortedMapType -> generateSortedMapEncode(formatType, dataType)

            // Type applications (Option, Result, etc.)
            dataType is FunkType.TypeApplication -> generateTypeApplicationEncode(formatType, dataType)

            else -> throw IllegalArgumentException("Cannot generate Encode for type: $dataType")
        }

        encodeCache[cacheKey] = impl
        return impl
    }

    /**
     * Check if a type is the Bool variant pattern: | True, False |
     */
    private fun isBoolVariant(type: FunkType): Boolean {
        return type is FunkType.VariantType &&
               type.constructors.keys == setOf("True", "False") &&
               type.constructors["True"] == null &&
               type.constructors["False"] == null
    }

    /**
     * Generate encode for Bool (which is internally a variant | True, False |).
     * Uses encode_bool hook which expects a Bool value.
     */
    private fun generateBoolEncode(formatType: FunkType): ImplAbilityDeclaration {
        // Convert the variant to a Bool value using pattern matching, then encode
        val body = call(
            member(encodeFormatImpl(formatType), "encode_bool"),
            record("val" to id("val"), "path" to id("path"))
        )
        // Use BooleanType for the impl to ensure proper dispatch
        return createEncodeImpl(formatType, FunkType.VariantType(
            constructors = mapOf("True" to null, "False" to null),
            rowVar = null
        ), body)
    }

    /**
     * Generate a Decode implementation for the given format and data type.
     */
    fun generateDecodeImpl(formatType: FunkType, dataType: FunkType): ImplAbilityDeclaration {
        val cacheKey = normalizedCacheKey(formatType, dataType)
        decodeCache[cacheKey]?.let { return it }

        val impl = when {
            // Primitives
            dataType is FunkType.IntType -> generatePrimitiveDecode(formatType, dataType, "decode_int")
            dataType is FunkType.FloatType -> generatePrimitiveDecode(formatType, dataType, "decode_float")
            dataType is FunkType.StringType -> generatePrimitiveDecode(formatType, dataType, "decode_string")
            dataType is FunkType.BooleanType -> generatePrimitiveDecode(formatType, dataType, "decode_bool")
            dataType is FunkType.NatType -> generateNatDecode(formatType)
            dataType is FunkType.FracType -> generatePrimitiveDecode(formatType, dataType, "decode_frac")
            dataType is FunkType.CharType -> generateCharDecode(formatType)

            // CRITICAL: Check for Bool variant pattern BEFORE generic variant handling
            isBoolVariant(dataType) -> generateBoolDecode(formatType)

            // Records and variants
            dataType is FunkType.RecordType -> generateRecordDecode(formatType, dataType)
            dataType is FunkType.VariantType -> generateVariantDecode(formatType, dataType)

            // Collections
            dataType is FunkType.ListType -> generateListDecode(formatType, dataType)
            dataType is FunkType.ArrayType -> generateArrayDecode(formatType, dataType)
            dataType is FunkType.SetType -> generateSetDecode(formatType, dataType)
            dataType is FunkType.SortedSetType -> generateSortedSetDecode(formatType, dataType)
            dataType is FunkType.MapType -> generateMapDecode(formatType, dataType)
            dataType is FunkType.SortedMapType -> generateSortedMapDecode(formatType, dataType)

            // Type applications (Option, Result, etc.)
            dataType is FunkType.TypeApplication -> generateTypeApplicationDecode(formatType, dataType)

            else -> throw IllegalArgumentException("Cannot generate Decode for type: $dataType")
        }

        decodeCache[cacheKey] = impl
        return impl
    }

    /**
     * Generate decode for Bool (which is internally a variant | True, False |).
     * Uses decode_bool hook which returns a Bool value.
     */
    private fun generateBoolDecode(formatType: FunkType): ImplAbilityDeclaration {
        val body = call(
            member(decodeFormatImpl(formatType), "decode_bool"),
            record("val" to id("val"), "path" to id("path"))
        )
        return createDecodeImpl(formatType, FunkType.VariantType(
            constructors = mapOf("True" to null, "False" to null),
            rowVar = null
        ), body)
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

    /** Create a do expression with a let statement followed by a result */
    private fun doWithLet(varName: String, varValue: Expression, result: Expression): DoExpression {
        return DoExpression(
            statements = listOf(
                LetStatement(
                    pattern = VariablePattern(name = varName),
                    typeAnnotation = null,
                    expression = varValue
                )
            ),
            result = result
        )
    }

    /** Create an array literal from expressions */
    private fun array(elements: List<Expression>): ArrayLiteral {
        return ArrayLiteral(elements = elements.map { ArrayRegularElement(it) })
    }

    /** Create a list from an array expression using __native_list_from_array */
    private fun listFromArray(elements: List<Expression>): Expression {
        return call(
            id("__native_list_from_array"),
            record("xs" to array(elements))
        )
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

    /** Get the format name from a format type (e.g., Json from TypeApplication("Json")) */
    private fun getFormatName(formatType: FunkType): String {
        return when (formatType) {
            is FunkType.TypeApplication -> formatType.constructor
            is FunkType.VarType -> formatType.variable.name
            else -> formatType.toString()
        }
    }

    /**
     * Get the EncodeFormat ability identifier.
     * This returns just "EncodeFormat" - the ability name itself.
     * When used in a member access like EncodeFormat.encode_int, the SubstitutionApplier
     * will resolve this to the correct implementation based on the format type in scope.
     */
    private fun encodeFormatImpl(formatType: FunkType): Identifier {
        // Return the ability name - SubstitutionApplier will resolve to the correct impl
        return id("EncodeFormat")
    }

    /**
     * Get the DecodeFormat ability identifier.
     * This returns just "DecodeFormat" - the ability name itself.
     */
    private fun decodeFormatImpl(formatType: FunkType): Identifier {
        return id("DecodeFormat")
    }

    /**
     * Get the StreamEncodeFormat ability identifier.
     * This returns just "StreamEncodeFormat" - the ability name itself.
     */
    private fun streamEncodeFormatImpl(formatType: FunkType): Identifier {
        return id("StreamEncodeFormat")
    }

    /**
     * Get the StreamDecodeFormat ability identifier.
     * This returns just "StreamDecodeFormat" - the ability name itself.
     */
    private fun streamDecodeFormatImpl(formatType: FunkType): Identifier {
        return id("StreamDecodeFormat")
    }

    /** Call append_path using native list operations directly */
    private fun appendPath(pathExpr: Expression, fieldName: String): Expression {
        // Use native functions since they're always available
        // Inline: __native_list_append {xs = path, ys = __native_list_cons {head = field, tail = __native_list_empty {}}}
        return call(
            id("__native_list_append"),
            record(
                "xs" to pathExpr,
                "ys" to call(
                    id("__native_list_cons"),
                    record("head" to str(fieldName), "tail" to call(id("__native_list_empty"), record()))
                )
            )
        )
    }

    /** Transform a field name using EncodeFormat.transform_field (e.g., camelCase -> snake_case) */
    private fun transformFieldName(formatType: FunkType, fieldName: String, configExpr: Expression = id("config")): Expression {
        return call(
            member(encodeFormatImpl(formatType), "transform_field"),
            record("name" to str(fieldName), "config" to configExpr)
        )
    }

    /** Transform a field name for decoding using DecodeFormat.transform_field (e.g., snake_case -> camelCase) */
    private fun transformFieldNameForDecode(formatType: FunkType, fieldName: String, configExpr: Expression = id("config")): Expression {
        return call(
            member(decodeFormatImpl(formatType), "transform_field"),
            record("name" to str(fieldName), "config" to configExpr)
        )
    }

    /** Call append_index using native list operations directly */
    private fun appendIndex(pathExpr: Expression, indexExpr: Expression): Expression {
        // Use native functions since they're always available
        return call(
            id("__native_list_append"),
            record(
                "xs" to pathExpr,
                "ys" to call(
                    id("__native_list_cons"),
                    record(
                        "head" to call(id("__native_int_to_string"), record("n" to indexExpr)),
                        "tail" to call(id("__native_list_empty"), record())
                    )
                )
            )
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
            member(encodeFormatImpl(formatType), hookName),
            record(paramName to id("val"), "path" to id("path"))
        )
        return createEncodeImpl(formatType, dataType, body)
    }

    /**
     * Generate encode for Nat - converts to Int first.
     */
    private fun generateNatEncode(formatType: FunkType): ImplAbilityDeclaration {
        val toIntCall = call(
            id("__native_nat_to_int"),
            record("n" to id("val"))
        )
        val body = call(
            member(encodeFormatImpl(formatType), "encode_int"),
            record("val" to toIntCall, "path" to id("path"))
        )
        return createEncodeImpl(formatType, FunkType.NatType, body)
    }

    /**
     * Generate encode for Char - converts to String first.
     */
    private fun generateCharEncode(formatType: FunkType): ImplAbilityDeclaration {
        val toStringCall = call(
            id("__native_char_to_string"),
            record("c" to id("val"))
        )
        val body = call(
            member(encodeFormatImpl(formatType), "encode_string"),
            record("val" to toStringCall, "path" to id("path"))
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
    private fun buildRecordEncodeBody(formatType: FunkType, fields: List<Map.Entry<String, FunkType>>): Expression {
        // Final expression: encode_record call
        // Field names are transformed via EncodeFormat.transform_field (e.g., camelCase -> snake_case)
        // NOTE: Entry type uses "key" and "value" fields (not "val")
        // NOTE: JObject.fields is now Array, so we use array literals directly
        var resultExpr: Expression = call(
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

        // Wrap with match expressions for error handling (in reverse order)
        for ((fieldName, fieldType) in fields.reversed()) {
            // Generate direct EncodeFormat.encode_* call based on field type
            val encodeCall = generateDirectEncodeCall(formatType, fieldType, member(id("val"), fieldName), fieldName)

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
     * Uses the EncodeFormat ability identifier which SubstitutionApplier resolves to the
     * correct implementation using content hash lookup.
     *
     * IMPORTANT: For complex types (records, variants, etc.), this generates Encode.encode calls
     * with inferredType set on the arguments so SubstitutionApplier can resolve the correct impl.
     */
    private fun generateDirectEncodeCall(formatType: FunkType, type: FunkType, valueExpr: Expression, fieldName: String): Expression {
        val pathExpr = appendPath(id("path"), fieldName)
        // Reference the EncodeFormat impl record directly
        val formatImpl = encodeFormatImpl(formatType)

        // CRITICAL: Check for Bool variant pattern first (before generic VariantType handling)
        if (isBoolVariant(type)) {
            return call(
                member(formatImpl, "encode_bool"),
                record("val" to valueExpr, "path" to pathExpr)
            )
        }

        return when (type) {
            is FunkType.IntType -> call(
                member(formatImpl, "encode_int"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is FunkType.FloatType -> call(
                member(formatImpl, "encode_float"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is FunkType.StringType -> call(
                member(formatImpl, "encode_string"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is FunkType.BooleanType -> call(
                member(formatImpl, "encode_bool"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is FunkType.NatType -> {
                // Convert Nat to Int first
                val toIntCall = call(id("__native_nat_to_int"), record("n" to valueExpr))
                call(member(formatImpl, "encode_int"), record("val" to toIntCall, "path" to pathExpr))
            }
            is FunkType.FracType -> call(
                member(formatImpl, "encode_frac"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is FunkType.CharType -> {
                // Convert Char to String first
                val toStrCall = call(id("__native_char_to_string"), record("c" to valueExpr))
                call(member(formatImpl, "encode_string"), record("val" to toStrCall, "path" to pathExpr))
            }
            // For complex types, generate a typed Encode.encode call
            // SubstitutionApplier will resolve this to the correct impl using content hash
            is FunkType.RecordType, is FunkType.ListType, is FunkType.ArrayType, is FunkType.SetType,
            is FunkType.MapType, is FunkType.VariantType, is FunkType.TypeApplication -> {
                // Use typed ability call - SubstitutionApplier resolves by type
                typedEncodeCall(type, valueExpr)
            }
            else -> {
                // For unknown types, also use typed ability call
                typedEncodeCall(type, valueExpr)
            }
        }
    }

    /**
     * Create a typed Encode.encode call with inferredType set on the arguments.
     * This allows SubstitutionApplier to resolve the correct implementation.
     */
    private fun typedEncodeCall(dataType: FunkType, valueExpr: Expression): FunctionCall {
        // Create the arguments record with type information
        val argsRecord = RecordLiteral(
            entries = listOf(
                RecordFieldEntry("val", valueExpr),
                RecordFieldEntry("config", id("config"))
            ),
            // Set the inferredType so SubstitutionApplier can find the val field's type
            inferredType = FunkType.RecordType(
                fields = mapOf(
                    "val" to dataType,
                    "config" to FunkType.VarType(TypeVar("config", 0))  // Config type is polymorphic
                )
            )
        )
        return FunctionCall(
            function = member(id("Encode"), "encode"),
            arguments = argsRecord
        )
    }

    /**
     * Generate a direct DecodeFormat impl record lookup and method call for a value of the given type.
     * Similar to generateDirectEncodeCall but for decoding.
     *
     * IMPORTANT: For complex types (records, variants, etc.), this generates Decode.decode calls
     * with inferredType set on the arguments so SubstitutionApplier can resolve the correct impl.
     */
    private fun generateDirectDecodeCall(formatType: FunkType, type: FunkType, valueExpr: Expression, fieldName: String): Expression {
        val pathExpr = appendPath(id("path"), fieldName)
        val formatImpl = decodeFormatImpl(formatType)

        // CRITICAL: Check for Bool variant pattern first
        if (isBoolVariant(type)) {
            return call(
                member(formatImpl, "decode_bool"),
                record("val" to valueExpr, "path" to pathExpr)
            )
        }

        return when (type) {
            is FunkType.IntType -> call(
                member(formatImpl, "decode_int"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is FunkType.FloatType -> call(
                member(formatImpl, "decode_float"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is FunkType.StringType -> call(
                member(formatImpl, "decode_string"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is FunkType.BooleanType -> call(
                member(formatImpl, "decode_bool"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is FunkType.NatType -> {
                // Decode as Int, then convert to Nat
                val decodeIntCall = call(member(formatImpl, "decode_int"), record("val" to valueExpr, "path" to pathExpr))
                // Wrap the conversion in a match to handle the Result
                match(
                    decodeIntCall,
                    matchCase(okPat("int_val"), ok(call(id("__native_int_to_nat"), record("n" to id("int_val"))))),
                    matchCase(errPat("e"), err(id("e")))
                )
            }
            is FunkType.FracType -> call(
                member(formatImpl, "decode_frac"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is FunkType.CharType -> {
                // Decode as String, then convert to Char (take first character)
                val decodeStrCall = call(member(formatImpl, "decode_string"), record("val" to valueExpr, "path" to pathExpr))
                match(
                    decodeStrCall,
                    matchCase(okPat("str_val"), ok(call(id("__native_string_head"), record("s" to id("str_val"))))),
                    matchCase(errPat("e"), err(id("e")))
                )
            }
            // For complex types, call Decode.decode and let ability resolution find the impl
            // This allows the SubstitutionApplier to resolve the correct impl by content hash
            is FunkType.RecordType, is FunkType.ListType, is FunkType.ArrayType, is FunkType.SetType,
            is FunkType.MapType, is FunkType.VariantType, is FunkType.TypeApplication -> {
                // Use Decode.decode ability call - SubstitutionApplier will resolve to correct impl
                // CRITICAL: Set inferredType on the arguments record so ability resolution works
                typedDecodeCall(type, valueExpr)
            }
            else -> {
                // For other types, use Decode.decode ability call
                typedDecodeCall(type, valueExpr)
            }
        }
    }

    /**
     * Create a typed Decode.decode call with inferredType set on the arguments.
     * This allows SubstitutionApplier to resolve the correct implementation.
     *
     * Note: For Decode, the target type comes from the return type (Result 'a DecodeError),
     * not from the argument. But we set the inferredType on the record so the return type
     * can be properly resolved.
     */
    private fun typedDecodeCall(targetType: FunkType, valueExpr: Expression): FunctionCall {
        // Create the arguments record with type information
        val argsRecord = RecordLiteral(
            entries = listOf(
                RecordFieldEntry("val", valueExpr),
                RecordFieldEntry("config", id("config"))
            ),
            // For Decode, the val field contains the serialized representation (e.g., JValue)
            // The targetType is what we want to decode TO, which affects the return type
            inferredType = FunkType.RecordType(
                fields = mapOf(
                    "val" to FunkType.VarType(TypeVar("repr", 0)),  // Serialized representation
                    "config" to FunkType.VarType(TypeVar("config", 0))
                )
            )
        )
        // Create the function call with the return type set
        // The return type is Result targetType DecodeError
        val decodeErrorType = FunkType.TypeApplication("DecodeError", emptyList())
        val resultType = FunkType.VariantType(
            constructors = mapOf(
                "Ok" to FunkType.RecordType(fields = mapOf("val" to targetType)),
                "Err" to FunkType.RecordType(fields = mapOf("error" to decodeErrorType))
            ),
            rowVar = null
        )
        return FunctionCall(
            function = member(id("Decode"), "decode"),
            arguments = argsRecord,
            inferredType = resultType
        )
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
                        member(encodeFormatImpl(formatType), "encode_variant"),
                        record(
                            "tag" to str(constructorName),
                            "payload" to none(),
                            "path" to id("path")
                        )
                    )
                )
            } else {
                // Has payload - need to handle based on payload type
                generateVariantCaseWithPayload(formatType, constructorName, payloadType)
            }
        }

        val body = match(id("val"), *cases.toTypedArray())
        return createEncodeImpl(formatType, variantType, body)
    }

    /**
     * Generate a match case for a variant constructor with a payload.
     * Handles record payloads by extracting all fields and reconstructing for encoding.
     */
    private fun generateVariantCaseWithPayload(formatType: FunkType, constructorName: String, payloadType: FunkType): MatchCase {
        return when (payloadType) {
            is FunkType.RecordType -> {
                // For record payloads, extract each field and reconstruct the record
                val fieldNames = payloadType.fields.keys.sorted()

                // Create pattern entries: field names mapped to variable patterns
                val patternEntries = fieldNames.map { fieldName ->
                    RecordPatternEntry(fieldName, VariablePattern("__payload_$fieldName"))
                }
                val recordPattern = RecordPattern(entries = patternEntries)

                // Reconstruct the record for encoding
                val reconstructedRecord = record(
                    *fieldNames.map { fieldName -> fieldName to id("__payload_$fieldName") }.toTypedArray()
                )

                // Use the proper impl name for encoding the payload record
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
                // For non-record payloads (like primitives), use the single-field pattern
                // This handles cases like `Some { val: 'a }` where payload is wrapped in a val field
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
     * Uses __native_list_fold_left to iterate and encode each element.
     */
    private fun generateListEncode(formatType: FunkType, listType: FunkType.ListType): ImplAbilityDeclaration {
        // Get the element type and generate the encode call for it
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
                "xs" to id("val"),
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
     * Generate encode for Array - convert to List first using native function.
     */
    private fun generateArrayEncode(formatType: FunkType, arrayType: FunkType.ArrayType): ImplAbilityDeclaration {
        // Use __native_array_to_list to convert array to list
        val toListCall = call(id("__native_array_to_list"), record("arr" to id("val")))

        // Create list type for encoding
        val listType = FunkType.ListType(arrayType.elementType)

        // Generate direct encode call for the list
        val body = generateDirectEncodeCall(formatType, listType, toListCall, "__array_as_list")

        return createEncodeImpl(formatType, arrayType, body)
    }

    /**
     * Generate encode for Set types.
     * Converts set to list, encodes each element, converts back to set.
     */
    private fun generateSetEncode(formatType: FunkType, setType: FunkType.SetType): ImplAbilityDeclaration {
        // Convert set to list using native function
        val toListCall = call(id("__native_set_to_list"), record("s" to id("val")))

        // Generate direct encode call for each element
        val elementType = setType.elementType
        val elementEncodeCall = generateDirectEncodeCall(formatType, elementType, id("elem"), "__set_elem")

        // Use same fold pattern as list encoding
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
     * Generate encode for SortedSet - convert to list and encode as list, then wrap as set.
     */
    private fun generateSortedSetEncode(formatType: FunkType, sortedSetType: FunkType.SortedSetType): ImplAbilityDeclaration {
        // Convert sorted set to list, encode as list
        val toListCall = call(id("__native_sorted_set_to_list"), record("s" to id("val")))

        // Generate direct encode call for each element
        val elementType = sortedSetType.elementType
        val elementEncodeCall = generateDirectEncodeCall(formatType, elementType, id("elem"), "__sorted_set_elem")

        // Use same fold pattern as list encoding
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

        // Encode as set (sorted sets serialize the same as regular sets)
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
     * Uses __native_map_entries to get list of entries, then encodes each key/value.
     */
    private fun generateMapEncode(formatType: FunkType, mapType: FunkType.MapType): ImplAbilityDeclaration {
        // Get entries as list using native function
        val entriesCall = call(id("__native_map_entries"), record("m" to id("val")))

        // Generate direct encode calls for key and value
        val keyType = mapType.keyType
        val valueType = mapType.valueType
        val keyEncodeCall = generateDirectEncodeCall(formatType, keyType, member(id("entry"), "key"), "__map_key")
        val valueEncodeCall = generateDirectEncodeCall(formatType, valueType, member(id("entry"), "value"), "__map_value")

        // Fold over entries, encoding each key and value
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
     * Generate encode for SortedMap - same pattern as Map but using sorted map native function.
     */
    private fun generateSortedMapEncode(formatType: FunkType, sortedMapType: FunkType.SortedMapType): ImplAbilityDeclaration {
        // Get entries as list using native function
        val entriesCall = call(id("__native_sorted_map_entries"), record("m" to id("val")))

        // Generate direct encode calls for key and value
        val keyType = sortedMapType.keyType
        val valueType = sortedMapType.valueType
        val keyEncodeCall = generateDirectEncodeCall(formatType, keyType, member(id("entry"), "key"), "__sorted_map_key")
        val valueEncodeCall = generateDirectEncodeCall(formatType, valueType, member(id("entry"), "value"), "__sorted_map_value")

        // Fold over entries, encoding each key and value
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
        // Get the inner type (e.g., Int for Option Int)
        val innerType = typeApp.args.firstOrNull() ?: FunkType.VarType(TypeVar("a", 0))

        // Generate the encode call for the inner value
        val innerEncodeCall = generateDirectEncodeCall(formatType, innerType, id("inner"), "__option_inner")

        val body = match(
            id("val"),
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
    private fun generateResultEncode(formatType: FunkType, typeApp: FunkType.TypeApplication): ImplAbilityDeclaration {
        // Get the Ok and Err types (Result 'ok 'err)
        val okType = typeApp.args.getOrNull(0) ?: FunkType.VarType(TypeVar("ok", 0))
        val errType = typeApp.args.getOrNull(1) ?: FunkType.VarType(TypeVar("err", 0))

        // Generate encode calls for Ok and Err values
        val okEncodeCall = generateDirectEncodeCall(formatType, okType, id("ok_val"), "__result_ok")
        val errEncodeCall = generateDirectEncodeCall(formatType, errType, id("err_val"), "__result_err")

        val body = match(
            id("val"),
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
            member(decodeFormatImpl(formatType), hookName),
            record("val" to id("val"), "path" to id("path"))
        )
        return createDecodeImpl(formatType, dataType, body)
    }

    /**
     * Generate decode for Nat - decode Int and validate.
     */
    private fun generateNatDecode(formatType: FunkType): ImplAbilityDeclaration {
        val body = match(
            call(
                member(decodeFormatImpl(formatType), "decode_int"),
                record("val" to id("val"), "path" to id("path"))
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
                member(decodeFormatImpl(formatType), "decode_string"),
                record("val" to id("val"), "path" to id("path"))
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

        val body = buildRecordDecodeBody(formatType, fields)
        return createDecodeImpl(formatType, recordType, body)
    }

    /**
     * Build the body for record decoding with Result handling.
     */
    private fun buildRecordDecodeBody(formatType: FunkType, fields: List<Map.Entry<String, FunkType>>): Expression {
        // Final expression: construct the record
        var resultExpr: Expression = ok(
            RecordLiteral(
                entries = fields.map { (fieldName, _) ->
                    RecordFieldEntry(fieldName, id("${fieldName}_val"))
                }
            )
        )

        // Wrap with match expressions for each field (in reverse order)
        for ((fieldName, fieldType) in fields.reversed()) {
            // First get the field repr, then decode it
            // The key is transformed (e.g., "userName" -> "user_name" for snake_case JSON)
            val getFieldCall = call(
                member(decodeFormatImpl(formatType), "decode_record_field"),
                record(
                    "val" to id("val"),
                    "key" to transformFieldNameForDecode(formatType, fieldName),
                    "path" to id("path")
                )
            )

            // Generate the decode call for this field's type directly using DecodeFormat hooks
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
     * For primitives, use DecodeFormat hooks directly.
     * For complex types, use Decode.decode (which will be resolved).
     */
    private fun generateFieldDecodeCall(formatType: FunkType, type: FunkType, valueExpr: Expression, fieldName: String): Expression {
        val pathExpr = appendPath(id("path"), fieldName)
        val formatImpl = decodeFormatImpl(formatType)

        // For Bool variant pattern
        if (isBoolVariant(type)) {
            return call(
                member(formatImpl, "decode_bool"),
                record("val" to valueExpr, "path" to pathExpr)
            )
        }

        return when (type) {
            // Primitives - call DecodeFormat hooks directly
            is FunkType.IntType -> call(
                member(formatImpl, "decode_int"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is FunkType.FloatType -> call(
                member(formatImpl, "decode_float"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is FunkType.StringType -> call(
                member(formatImpl, "decode_string"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is FunkType.BooleanType -> call(
                member(formatImpl, "decode_bool"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is FunkType.FracType -> call(
                member(formatImpl, "decode_frac"),
                record("val" to valueExpr, "path" to pathExpr)
            )
            is FunkType.NatType -> {
                // Decode as Int, then convert to Nat
                val decodeIntCall = call(member(formatImpl, "decode_int"), record("val" to valueExpr, "path" to pathExpr))
                match(
                    decodeIntCall,
                    matchCase(okPat("int_val"), ok(call(id("__native_int_to_nat"), record("n" to id("int_val"))))),
                    matchCase(errPat("e"), err(id("e")))
                )
            }
            is FunkType.CharType -> {
                // Decode as String, then convert to Char
                val decodeStrCall = call(member(formatImpl, "decode_string"), record("val" to valueExpr, "path" to pathExpr))
                match(
                    decodeStrCall,
                    matchCase(okPat("str_val"), ok(call(id("__native_string_head"), record("s" to id("str_val"))))),
                    matchCase(errPat("e"), err(id("e")))
                )
            }
            // For complex types, generate a typed Decode.decode call
            // SubstitutionApplier will resolve this to the correct impl using content hash
            is FunkType.RecordType, is FunkType.ListType, is FunkType.ArrayType, is FunkType.SetType,
            is FunkType.MapType, is FunkType.VariantType, is FunkType.TypeApplication -> {
                // Use typed ability call - SubstitutionApplier resolves by type
                typedDecodeCall(type, valueExpr)
            }
            else -> {
                // For unknown types, also use typed ability call
                typedDecodeCall(type, valueExpr)
            }
        }
    }

    /**
     * Generate decode for variant types.
     */
    private fun generateVariantDecode(formatType: FunkType, variantType: FunkType.VariantType): ImplAbilityDeclaration {
        val constructors = variantType.constructors.entries.sortedBy { it.key }

        // Use decode_variant and match on tag
        val body = match(
            call(
                member(decodeFormatImpl(formatType), "decode_variant"),
                record("val" to id("val"), "path" to id("path"))
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
    private fun buildVariantTagMatcher(formatType: FunkType, constructors: List<Map.Entry<String, FunkType?>>): Expression {
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
            // Determine how to construct the variant based on payload type
            val variantConstruction = when (payloadType) {
                is FunkType.RecordType -> {
                    // For record payloads, pass the decoded record directly
                    // e.g., Circle {radius = 5} - decoded_payload is already {radius = 5}
                    variant(constructorName, id("decoded_payload"))
                }
                else -> {
                    // For non-record payloads (like Option's Some {val: 'a}), wrap in {val: ...}
                    variant(constructorName, record("val" to id("decoded_payload")))
                }
            }

            // Use typed ability call - SubstitutionApplier will resolve to content hash
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

        return IfExpression(
            condition = call(
                member(id("string"), "eq"),
                record("a" to member(id("variant_info"), "tag"), "b" to str(constructorName))
            ),
            thenBranch = thenBranch,
            elseBranch = buildVariantTagMatcher(formatType, remaining)
        )
    }

    /**
     * Generate decode for List types.
     */
    private fun generateListDecode(formatType: FunkType, listType: FunkType.ListType): ImplAbilityDeclaration {
        val elementType = listType.elementType

        val body = match(
            call(
                member(decodeFormatImpl(formatType), "decode_list"),
                record("val" to id("val"), "path" to id("path"))
            ),
            matchCase(
                okPat("repr_list"),
                match(
                    call(
                        member(id("list"), "try_map_indexed"),
                        record(
                            "list" to id("repr_list"),
                            "f" to lambda(listOf("elem_repr", "idx"),
                                // Use typed ability call for element decoding
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
     * Generate decode for Array - decode as List then convert.
     */
    private fun generateArrayDecode(formatType: FunkType, arrayType: FunkType.ArrayType): ImplAbilityDeclaration {
        // Decode as List[ElementType] first, then convert to Array
        val listType = FunkType.ListType(arrayType.elementType)

        val body = match(
            // Use typed ability call for list decoding
            typedDecodeCall(listType, id("val")),
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
        val elementType = setType.elementType

        val body = match(
            call(
                member(decodeFormatImpl(formatType), "decode_set"),
                record("val" to id("val"), "path" to id("path"))
            ),
            matchCase(
                okPat("repr_set"),
                match(
                    call(
                        member(id("list"), "try_map_indexed"),
                        record(
                            "list" to call(member(id("set"), "to_list"), record("s" to id("repr_set"))),
                            "f" to lambda(listOf("elem_repr", "idx"),
                                // Use typed ability call for element decoding
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
    private fun generateSortedSetDecode(formatType: FunkType, sortedSetType: FunkType.SortedSetType): ImplAbilityDeclaration {
        // Decode as Set[ElementType] first, then convert to SortedSet
        val setType = FunkType.SetType(sortedSetType.elementType)

        val body = match(
            // Use typed ability call for set decoding
            typedDecodeCall(setType, id("val")),
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
        val keyType = mapType.keyType
        val valueType = mapType.valueType

        val body = match(
            call(
                member(decodeFormatImpl(formatType), "decode_map"),
                record("val" to id("val"), "path" to id("path"))
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
                                    // Use typed ability call for key decoding
                                    typedDecodeCall(keyType, member(id("entry"), "key")),
                                    matchCase(
                                        okPat("decoded_key"),
                                        match(
                                            // Use typed ability call for value decoding
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
    private fun generateSortedMapDecode(formatType: FunkType, sortedMapType: FunkType.SortedMapType): ImplAbilityDeclaration {
        // Decode as Map[K, V] first, then convert to SortedMap
        val mapType = FunkType.MapType(sortedMapType.keyType, sortedMapType.valueType)

        val body = match(
            // Use typed ability call for map decoding
            typedDecodeCall(mapType, id("val")),
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
        val innerType = typeApp.args.firstOrNull() ?: throw IllegalArgumentException("Option requires type argument")

        val body = match(
            call(
                member(decodeFormatImpl(formatType), "decode_option"),
                record("val" to id("val"), "path" to id("path"))
            ),
            matchCase(
                okPat("opt_repr"),
                match(
                    id("opt_repr"),
                    matchCase(nonePat(), ok(none())),
                    matchCase(
                        somePat("inner_repr"),
                        match(
                            // Use typed ability call for inner type decoding
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
    private fun generateResultDecode(formatType: FunkType, typeApp: FunkType.TypeApplication): ImplAbilityDeclaration {
        // Result[Ok, Err] has two type arguments
        val okType = typeApp.args.getOrNull(0) ?: throw IllegalArgumentException("Result requires Ok type argument")
        val errType = typeApp.args.getOrNull(1) ?: throw IllegalArgumentException("Result requires Err type argument")

        val body = match(
            call(
                member(decodeFormatImpl(formatType), "decode_variant"),
                record("val" to id("val"), "path" to id("path"))
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
                                // Use typed ability call for Ok type decoding
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
                                    // Use typed ability call for Err type decoding
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
     * Generates single `encode` method that takes config parameter.
     */
    private fun createEncodeImpl(formatType: FunkType, dataType: FunkType, body: Expression): ImplAbilityDeclaration {
        // Wrap body in a do block that creates an empty path
        // config is a parameter from the caller
        val encodeBody = doWithLet("path", call(id("__native_list_empty"), record()), body)

        return ImplAbilityDeclaration(
            abilityName = "Encode",
            targetTypes = listOf(funkTypeToAnnotation(formatType), funkTypeToAnnotation(dataType)),
            operations = listOf(
                ImplAbilityOperation(
                    name = "encode",
                    paramType = null,  // Inferred from ability declaration
                    returnType = null, // Inferred from ability declaration
                    implementation = lambda(listOf("val", "config"), encodeBody)
                )
            )
        )
    }

    /**
     * Create a Decode impl declaration.
     * Generates single `decode` method that takes config parameter.
     */
    private fun createDecodeImpl(formatType: FunkType, dataType: FunkType, body: Expression): ImplAbilityDeclaration {
        // Wrap body in a do block that creates an empty path
        // config is a parameter from the caller
        val decodeBody = doWithLet("path", call(id("__native_list_empty"), record()), body)

        return ImplAbilityDeclaration(
            abilityName = "Decode",
            targetTypes = listOf(funkTypeToAnnotation(formatType), funkTypeToAnnotation(dataType)),
            operations = listOf(
                ImplAbilityOperation(
                    name = "decode",
                    paramType = null,
                    returnType = null,
                    implementation = lambda(listOf("val", "config"), decodeBody)
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
            record("val" to id("val"), "buffer" to id("buffer"), "path" to id("path"))
        )
        return createStreamEncodeImpl(formatType, dataType, body)
    }

    private fun generateNatStreamEncode(formatType: FunkType): ImplAbilityDeclaration {
        val toIntCall = call(member(id("nat"), "to_int"), record("n" to id("val")))
        val body = call(
            member(id("StreamEncodeFormat"), "stream_int"),
            record("val" to toIntCall, "buffer" to id("buffer"), "path" to id("path"))
        )
        return createStreamEncodeImpl(formatType, FunkType.NatType, body)
    }

    private fun generateCharStreamEncode(formatType: FunkType): ImplAbilityDeclaration {
        val toStringCall = call(member(id("char"), "to_string"), record("c" to id("val")))
        val body = call(
            member(id("StreamEncodeFormat"), "stream_string"),
            record("val" to toStringCall, "buffer" to id("buffer"), "path" to id("path"))
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
                        "val" to member(id("val"), fieldName),
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
                                "val" to id("field_buf"),
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
                                "val" to id("payload_val"),
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

        val body = match(id("val"), *cases.toTypedArray())
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
                            "list" to id("val"),
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
                                                    "val" to id("elem"),
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
                                                            "val" to id("elem_buf"),
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
            id("val"),
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
                            "val" to id("inner"),
                            "buffer" to call(member(id("bytebuffer"), "new"), record()),
                            "path" to id("path")
                        )
                    ),
                    matchCase(
                        okPat("inner_buf"),
                        call(
                            member(id("StreamEncodeFormat"), "stream_some"),
                            record("val" to id("inner_buf"), "buffer" to id("buffer"), "path" to id("path"))
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
                        "val" to call(
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
                        "val" to RecordLiteral(entries = emptyList()),
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
            "val" to RecordLiteral(
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
                                "offset" to IntLiteral(0),
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
                        ok(record("val" to none(), "offset" to member(id("opt_result"), "offset")))
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
