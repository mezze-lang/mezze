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

    val coreTypeNames = setOf(OPTION, RESULT, LIST, MAP, SET, SEQ, SORTED_MAP, SORTED_SET, DEQUE, NON_EMPTY_LIST)
    val primitiveTypeNames = PrimitiveTypes.allNames
    val allBuiltinTypeNames = coreTypeNames + primitiveTypeNames

    // Option constructors
    const val SOME = "Some"
    const val NONE = "None"

    // Result constructors
    const val OK = "Ok"
    const val ERR = "Err"

    // Bool constructors
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

    // Standard record field names for built-in types
    const val VAL_FIELD = "val"      // Used in Option.Some and Result.Ok
    const val ERROR_FIELD = "error"  // Used in Result.Err

    fun isBuiltinTypeName(typeName: String): Boolean {
        return typeName in allBuiltinTypeNames
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
