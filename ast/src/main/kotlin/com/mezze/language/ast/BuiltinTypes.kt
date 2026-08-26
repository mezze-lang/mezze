package com.mezze.language.ast

/**
 * Built-in type constructors and utilities.
 */
object BuiltinTypes {

    const val OPTION = "Option"
    const val RESULT = "Result"
    const val LIST = "List"
    const val MAP = "Map"
    const val SET = "Set"
    const val SEQ = "Seq"
    const val SORTED_MAP = "SortedMap"
    const val SORTED_SET = "SortedSet"
    const val DEQUE = "Deque"
    const val NON_EMPTY_LIST = "NonEmptyList"
    const val BYTES = "Bytes"
    const val ITER = "Iter"

    val coreTypeNames = setOf(OPTION, RESULT, LIST, MAP, SET, SEQ, SORTED_MAP, SORTED_SET, DEQUE, NON_EMPTY_LIST, ITER)
    val primitiveTypeNames = PrimitiveTypes.allNames
    val allBuiltinTypeNames = coreTypeNames + primitiveTypeNames

    // Option constructors
    const val SOME = "Some"
    const val NONE = "None"

    // Result constructors
    const val OK = "Ok"
    const val ERR = "Err"

    // Bool type + constructors
    const val BOOL = "Bool"
    const val TRUE = "True"
    const val FALSE = "False"

    // List (cons-list) constructors
    const val CONS = "Cons"
    const val NIL = "Nil"

    /**
     * All variant constructor names that belong to types defined internally
     * (not via .mz `type` declarations). Used by validators that need to know
     * which constructor names are "declared" and so exempt from anonymous-
     * constructor rules.
     */
    val coreVariantConstructors = setOf(SOME, NONE, OK, ERR, TRUE, FALSE, CONS, NIL)

    /**
     * Each reserved builtin constructor mapped to the builtin type it belongs to.
     * These constructor names are RESERVED — a user variant may not declare them
     * (they are the built-in constructors of Bool/Option/Result/List). Single
     * source for "which builtin owns this constructor name", used by the
     * duplicate/reserved-constructor validator. Keys are exactly
     * [coreVariantConstructors].
     */
    val reservedConstructorOwner: Map<String, String> = mapOf(
        SOME to OPTION, NONE to OPTION,
        OK to RESULT, ERR to RESULT,
        TRUE to BOOL, FALSE to BOOL,
        CONS to LIST, NIL to LIST,
    )

    /** True iff [name] is a reserved builtin constructor (Some/None/Ok/Err/True/
     *  False/Cons/Nil). Single authority — prefer this over raw `name == "True"`. */
    fun isReservedConstructor(name: String): Boolean = name in coreVariantConstructors

    // Standard record field names for built-in types
    const val VAL_FIELD = "val"      // Used in Option.Some and Result.Ok
    const val ERROR_FIELD = "error"  // Used in Result.Err

    /**
     * Reserved prefix for opaque NATIVE primitive types (`__NativeInstant`,
     * `__NativePromise`, `__NativeMutInt`, …) — the runtime-handle payloads that
     * stdlib newtypes wrap (`newtype Promise 'a = __NativePromise`). They have no
     * `type` declaration and no content to hash: like `Int`/`Bytes`, each is a
     * compiler builtin identified by its reserved name alone.
     */
    const val NATIVE_TYPE_PREFIX = "__Native"

    fun isNativeType(typeName: String): Boolean = typeName.startsWith(NATIVE_TYPE_PREFIX)

    /** True for any name-carried builtin: a core/primitive type OR an opaque native handle. */
    fun isBuiltinTypeName(typeName: String): Boolean {
        return typeName in allBuiltinTypeNames || isNativeType(typeName)
    }

    /**
     * The canonical builtin constructor name for a STRUCTURAL container type
     * (`SeqType` -> "Seq", `MapType` -> "Map", …), or null for any other type.
     *
     * Single source of truth for the container-variant ↔ builtin-name mapping.
     * Impl-target matchers (`TypedASTCompiler.typeMatchesTarget`,
     * `MonoTypeOps.typeMatchesTarget`) bridge a structural `SeqType`/`MapType`/…
     * receiver to a `TypeApplication("Seq"/"Map"/…)` impl target through this —
     * so the name lives here once instead of being re-spelled in each matcher
     * (they must agree; a divergent copy silently mis-resolves impls). Note only
     * `NonEmptyList` has no structural variant (it is always a TypeApplication),
     * so it is intentionally absent.
     */
    fun builtinConstructorName(type: MezzeType): String? = when (type) {
        is MezzeType.SeqType -> SEQ
        is MezzeType.ListType -> LIST
        is MezzeType.SetType -> SET
        is MezzeType.MapType -> MAP
        is MezzeType.SortedSetType -> SORTED_SET
        is MezzeType.SortedMapType -> SORTED_MAP
        is MezzeType.BytesType -> BYTES
        else -> null
    }

    /**
     * Compute a stable integer tag for a variant constructor.
     *
     * The tag is derived from the constructor's simple name only. Cross-ADT
     * constructor collisions (two user ADTs both declaring `Foo`) are
     * prevented by three typechecker layers exercised in
     * `VariantTagCollisionSoundnessTest`:
     *
     *  1. Named-type duplicate-constructor rejection (type A / type B can't
     *     both declare `Ping`).
     *  2. Stdlib constructor protection (can't shadow `Some`/`Ok`/…).
     *  3. Row-poly closure at match sites (a match arm's row fixes the set
     *     of admissible constructors).
     *
     * The [typeHashHex] parameter is kept for source compatibility but is
     * intentionally ignored — construction and pattern sites do not always
     * see the same substituted `VariantType` (constructions at top-level
     * `let x = Foo` and patterns whose type is a fresh solver TyVar both
     * lose the ADT hash), which produced tag mismatches when the mixed
     * form was in use.
     */
    fun computeVariantTag(ctorName: String, typeHashHex: String?): Int {
        return ctorName.hashCode() and 0x7FFFFFFF
    }

    /** Delegates to PrimitiveTypes.isBoolVariant -- single canonical location. */
    fun isBoolVariant(type: MezzeType): Boolean = PrimitiveTypes.isBoolVariant(type)

    fun getBoolType(): MezzeType.VariantType {
        return MezzeType.VariantType(
            constructors = mapOf(TRUE to null, FALSE to null),
            rowVar = null
        )
    }

    fun isOptionVariant(type: MezzeType): Boolean {
        return type is MezzeType.VariantType &&
               type.constructors.size == 2 &&
               type.constructors.containsKey("Some") &&
               type.constructors.containsKey("None") &&
               type.constructors["None"] == null &&
               !type.isOpen
    }

    fun getOptionValueType(type: MezzeType.VariantType): MezzeType? {
        if (!isOptionVariant(type)) return null
        val someType = type.constructors["Some"] ?: return null
        return when (someType) {
            is MezzeType.RecordType -> someType.fields["val"]
            else -> someType
        }
    }

    fun getResultTypes(type: MezzeType.VariantType): Pair<MezzeType, MezzeType>? {
        if (type.constructors.size != 2 || type.isOpen) return null

        val okType = type.constructors["Ok"] ?: return null
        val errType = type.constructors["Err"] ?: return null

        val valueType = when (okType) {
            is MezzeType.RecordType -> okType.fields["val"]
            else -> null
        } ?: return null

        val errorType = when (errType) {
            is MezzeType.RecordType -> errType.fields["error"]
            else -> null
        } ?: return null

        return Pair(valueType, errorType)
    }
}
