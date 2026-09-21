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

    /**
     * The constructors a SEQUENCE pattern (`[head, ...tail]`) can match against
     * in the name-carried representation. Counterpart to the `SeqType`/`ListType`
     * arms in [builtinConstructorName], for readers that meet a container as
     * `TypeApplication("Seq", [T])` — the dominant form in solved types — rather
     * than as a dedicated node.
     */
    val sequenceConstructors = setOf(SEQ, LIST)
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

    /**
     * Type names the COMPILER owns — the primitives, plus the core and collection
     * types it has a dedicated [MezzeType] representation for.
     *
     * No `.mz` file declares any of these; they are synthetic, which is what makes
     * them different in kind from a stdlib type like `Json` or `HttpError`. Those
     * are ordinary declarations, and shadowing one is ordinary module scoping, so
     * they are deliberately NOT here.
     *
     * A declaration under a reserved name cannot mean what it says. For `Int` the
     * compiler keeps its own meaning and the declaration was silently discarded —
     * the author got no diagnostic, just a later mismatch against a type they
     * thought they had defined. For `Option` the declaration WAS honoured while the
     * compiler went on applying `Option`'s rules (the `Some`/`None` constructors,
     * the container and codec machinery keyed on the type), so the two coexisted
     * and disagreed. Rejecting the declaration is the only reading that stays true.
     *
     * [LIST] used to be held out, because the builtin `List` could not be USED by
     * name — `{ l: List Int }` with a `match l is Nil -> …` failed to resolve the
     * constructor, so fixtures declared `type List 'a = | Cons …, Nil |` to work
     * around it and reserving the name would have taken away the workaround before
     * the thing it worked around was fixed. That resolution defect is fixed, the
     * workaround is gone from the fixtures, and `List` is reserved like its
     * siblings. The hold-out mattered: identity for these types is CARRIED BY THE
     * NAME (TypeIdentity.Builtin), which is only sound because the name is
     * reserved — a user `type List` would have been a second type answering to the
     * one name the compiler keys on.
     */
    val reservedTypeNames: Set<String> = PrimitiveTypes.allNames + coreTypeNames

    /** True iff [name] is a type name the compiler owns. Single authority. */
    fun isReservedTypeName(name: String): Boolean = name in reservedTypeNames

    // Standard record field names for built-in types
    const val VAL_FIELD = "val"      // Used in Option.Some and Result.Ok
    const val ERROR_FIELD = "error"  // Used in Result.Err

    /** Ability operation names the compiler itself must NAME. An operation's name
     *  is its identity within its ability, so these are constants rather than
     *  literals spelled at use sites. */
    const val HASH_METHOD = "hash"
    const val EQ_METHOD = "eq"

    /** The Bool ability method for logical negation: `b.not {}`. Single source so
     *  callers recognize the negation method here, not via raw `member == "not"`. */
    const val NOT_METHOD = "not"

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
    /**
     * The ELEMENT type of a sequence-like container, in EITHER representation —
     * the dedicated node (`SeqType`/`ListType`) or the name-carried
     * `TypeApplication("Seq"|"List", [elem])`.
     *
     * The reverse of [builtinConstructorName], and here for the same reason: a
     * consumer that knows only one representation silently disagrees with one
     * that knows the other. `DerivabilityRules` was exactly that — it had
     * `is SeqType` / `is ListType` arms and no TypeApplication arm, so
     * `let a: Seq P = …; a == b` was REJECTED ("implement: impl Eq for Seq P")
     * while the identical `let a: [P] = …` derived Eq fine.
     *
     * Deliberately limited to Seq/List, matching the only shapes the derive rule
     * already accepted — this closes a representation gap, it does not widen
     * which types derive.
     */
    fun sequenceElementType(type: MezzeType): MezzeType? = when (type) {
        is MezzeType.TypeApplication ->
            if (type.constructor in sequenceConstructors) type.args.firstOrNull() else null
        else -> null
    }

    fun builtinConstructorName(type: MezzeType): String? = when (type) {
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

    // Cons-list payload field names. `Cons { head, tail }` was spelled with raw
    // literals at three separate sites, which is two more than there should ever be.
    const val HEAD_FIELD = "head"
    const val TAIL_FIELD = "tail"

    /** `Cons`'s payload for element [elem]: `{ head: elem, tail: List elem }`. */
    fun consPayload(elem: MezzeType): MezzeType.RecordType =
        MezzeType.RecordType(mapOf(HEAD_FIELD to elem, TAIL_FIELD to listApp(elem)))

    /**
     * `List elem` in the one form a container has (B.1).
     *
     * Builtin identity, not Declared: `List` has no `ParsedTypeDecl` to hash. That
     * is not a second identity — unification vetoes only when BOTH sides carry a
     * hash and the hashes differ, so this meets a converter-built `List` on the
     * name and unifies, which is exactly what a builtin needs.
     */
    /**
     * `Seq elem` — the `[elem]` syntax — in the one form a container has (B.1).
     *
     * Same Builtin identity as [listApp], for the same reason. This is also what
     * makes `[Int]` and a written `Seq Int` the SAME type rather than two that
     * unification has to reconcile, which is the whole point of the exercise.
     */
    fun seqApp(elem: MezzeType): MezzeType.TypeApplication =
        MezzeType.TypeApplication(
            constructor = SEQ,
            args = listOf(elem),
            identity = TypeIdentity.derive(SEQ, null, allowBuiltin = true),
        )

    fun listApp(elem: MezzeType): MezzeType.TypeApplication =
        MezzeType.TypeApplication(
            constructor = LIST,
            args = listOf(elem),
            identity = TypeIdentity.derive(LIST, null, allowBuiltin = true),
        )

    /**
     * The builtin cons-list AS A VARIANT — the one description of `| Cons {…}, Nil |`.
     *
     * `List` has no `ParsedTypeDecl` (it is a compiler builtin with a dedicated
     * [MezzeType.ListType]), so a checker that needs its CONSTRUCTORS has nothing to
     * read them off. This is that, and it is deliberately a shape rather than a
     * declaration: registering a decl would give `List 'a` a Declared identity,
     * making it structurally derivable and colliding with the hand-written `Eq`/`Hash`
     * in `std/collections/list.mz`, which are load-bearing while a List is native at
     * runtime rather than an actual Cons/Nil chain.
     */
    fun listVariantShape(elem: MezzeType): MezzeType.VariantType =
        MezzeType.VariantType(
            constructors = mapOf(CONS to consPayload(elem), NIL to null),
            rowVar = null,
            typeArgs = listOf(elem),
            identity = TypeIdentity.Builtin(LIST),
        )

    /**
     * The element of the builtin LIST in EITHER representation — the dedicated
     * [MezzeType.ListType] or the name-carried `TypeApplication("List", [elem])`.
     *
     * Sibling of [sequenceElementType], and here for the same reason: the name↔builtin
     * mapping belongs in this one place, so callers ask a question about the TYPE
     * instead of comparing a constructor string themselves.
     */
    fun listElementType(type: MezzeType): MezzeType? = when (type) {
        is MezzeType.TypeApplication -> if (type.constructor == LIST) type.args.singleOrNull() else null
        else -> null
    }

    /** Delegates to PrimitiveTypes.isBoolVariant -- single canonical location. */
    fun isBoolVariant(type: MezzeType): Boolean = PrimitiveTypes.isBoolVariant(type)

    fun getBoolType(): MezzeType.VariantType {
        return MezzeType.VariantType(
            constructors = mapOf(TRUE to null, FALSE to null),
            rowVar = null
        )
    }

    /**
     * `Option value` as its variant shape — the sibling of [listVariantShape], and
     * here for the same reason: a BUILTIN's constructors are not in doubt, so
     * expanding one must not depend on a user registry having `Option` in it.
     * `Some`/`None` are reserved to Option exactly as `Cons`/`Nil` are to List.
     *
     * Builtin identity rather than Declared: this shape carries no content hash,
     * and unification vetoes only when BOTH sides carry one, so meeting the
     * stdlib's declared `Option` falls through to a structural comparison of the
     * arms — which is the intent.
     */
    fun optionVariantShape(value: MezzeType): MezzeType.VariantType =
        MezzeType.VariantType(
            constructors = mapOf(SOME to MezzeType.RecordType(mapOf(VAL_FIELD to value)), NONE to null),
            rowVar = null,
            typeArgs = listOf(value),
            identity = TypeIdentity.Builtin(OPTION),
        )

    /** `Result value error` as its variant shape. Sibling of [optionVariantShape]. */
    fun resultVariantShape(value: MezzeType, error: MezzeType): MezzeType.VariantType =
        MezzeType.VariantType(
            constructors = mapOf(
                OK to MezzeType.RecordType(mapOf(VAL_FIELD to value)),
                ERR to MezzeType.RecordType(mapOf(ERROR_FIELD to error)),
            ),
            rowVar = null,
            typeArgs = listOf(value, error),
            identity = TypeIdentity.Builtin(RESULT),
        )

    fun isOptionVariant(type: MezzeType): Boolean {
        return type is MezzeType.VariantType &&
               type.constructors.size == 2 &&
               type.constructors.containsKey(SOME) &&
               type.constructors.containsKey(NONE) &&
               type.constructors[NONE] == null &&
               !type.isOpen
    }

    fun getOptionValueType(type: MezzeType.VariantType): MezzeType? {
        if (!isOptionVariant(type)) return null
        val someType = type.constructors[SOME] ?: return null
        return when (someType) {
            is MezzeType.RecordType -> someType.fields[VAL_FIELD]
            else -> someType
        }
    }

    fun getResultTypes(type: MezzeType.VariantType): Pair<MezzeType, MezzeType>? {
        if (type.constructors.size != 2 || type.isOpen) return null

        val okType = type.constructors[OK] ?: return null
        val errType = type.constructors[ERR] ?: return null

        val valueType = when (okType) {
            is MezzeType.RecordType -> okType.fields[VAL_FIELD]
            else -> null
        } ?: return null

        val errorType = when (errType) {
            is MezzeType.RecordType -> errType.fields[ERROR_FIELD]
            else -> null
        } ?: return null

        return Pair(valueType, errorType)
    }
}
