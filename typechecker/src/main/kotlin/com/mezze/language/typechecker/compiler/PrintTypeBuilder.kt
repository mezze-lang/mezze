package com.mezze.language.typechecker.compiler

import com.mezze.language.ast.BuiltinTypes
import com.mezze.language.ast.MezzeType
import com.mezze.language.truffleir.PrintType

/**
 * Builds a [PrintType] — the static shape the Inspect printer renders a value
 * against — from the receiver's fully-substituted [MezzeType] (#394).
 *
 * The core is an EXHAUSTIVE `when` over the sealed [MezzeType] with NO `else`,
 * so adding a new MezzeType member fails compilation here until it is given a
 * rendering. There is no fallback/opaque PrintType: every reachable concrete
 * value type maps to exactly one constructor.
 *
 * A user-declared nominal type is carried at a use site as a
 * `TypeApplication(name, ...)` — the underlying newtype/record/variant body is
 * NOT on the type. [typeDefs] maps a declared type name to its resolved
 * underlying [MezzeType] (newtype→NewtypeType, record→RecordType,
 * variant→VariantType, alias→aliasedType) so those applications resolve to a
 * printable shape.
 *
 * VarType / EffectRow are not concrete printed values. Reaching one means the
 * receiver's type was never resolved (a solver/lowering bug), so we surface it
 * as an internal error rather than inventing a rendering.
 */
class PrintTypeBuilder(private val typeDefs: Map<String, MezzeType>) {

    class NotAConcreteValueType(type: MezzeType) : IllegalStateException(
        "PrintType: cannot render a value of non-concrete type $type — " +
            "the receiver's static type was not resolved before lowering (#394)."
    )

    /**
     * Nominal type names currently on the build stack. A recursive type
     * (`type Tree = | Leaf, Node { left: Tree, right: Tree } |`) would loop
     * forever as a flat PrintType tree; on re-entering a name already being
     * built we cut the recursion with [PrintType.Dynamic]. That inner position
     * renders via the runtime value-only path, which walks the ACTUAL value
     * and so handles arbitrarily deep recursive structures correctly.
     */
    private val building = HashSet<String>()

    /** Completed shapes for every named type built during this call, so a [PrintType.Ref]
     *  emitted at a cycle has something to resolve to. */
    private val defs = HashMap<String, PrintType>()

    /** Names a cycle actually referred back to. Only these need to travel with
     *  the result, so a non-recursive type's PrintType is byte-identical to before. */
    private val referenced = HashSet<String>()

    private inline fun guardedByName(name: String?, body: () -> PrintType): PrintType {
        if (name == null) return body()
        // Re-entering a name already on the stack is a CYCLE, not an unknown.
        // Emit a back-reference the printer can follow rather than cutting to
        // Dynamic, which renders from the runtime value and loses exactly the
        // distinctions PrintType exists to make (BUG 12).
        if (!building.add(name)) {
            referenced.add(name)
            return PrintType.Ref(name)
        }
        try {
            val built = body()
            defs[name] = built
            return built
        } finally {
            building.remove(name)
        }
    }

    /**
     * The shape [type] renders against. Wrapped in [PrintType.Recursive] only
     * when a cycle was encountered, so non-recursive shapes are unchanged.
     */
    fun build(type: MezzeType): PrintType {
        val root = buildShape(type)
        if (referenced.isEmpty()) return root
        return PrintType.Recursive(defs.filterKeys { it in referenced }, root)
    }

    private fun buildShape(type: MezzeType): PrintType = when (type) {
        is MezzeType.IntType -> PrintType.Scalar(PrintType.ScalarKind.Int)
        is MezzeType.FloatType -> PrintType.Scalar(PrintType.ScalarKind.Float)
        is MezzeType.StringType -> PrintType.Scalar(PrintType.ScalarKind.Str)
        is MezzeType.BoolType -> PrintType.Scalar(PrintType.ScalarKind.Bool)
        is MezzeType.CharType -> PrintType.Scalar(PrintType.ScalarKind.Char)
        is MezzeType.BytesType -> PrintType.Scalar(PrintType.ScalarKind.Bytes)

        is MezzeType.FracType -> PrintType.Frac

        is MezzeType.NewtypeType -> guardedByName(type.name) {
            PrintType.Newtype(type.name, buildShape(type.wrappedType))
        }

        is MezzeType.RecordType -> guardedByName(type.identity.displayNameOrNull()) {
            PrintType.Record(type.fields.map { (name, ft) -> PrintType.Field(name, buildShape(ft)) })
        }

        is MezzeType.VariantType -> guardedByName(type.identity.displayNameOrNull()) {
            buildVariant(type)
        }


        is MezzeType.TypeApplication -> buildApplication(type)

        is MezzeType.FunctionType -> PrintType.Function

        // An unresolved type variable in a printed position is an ADT type
        // parameter that never manifests a runtime value along the reachable
        // arm (e.g. `Ok.val` when the value is always `Err`, or a phantom).
        // It is NOT a concrete type with a lost shape — render it dynamically
        // from the runtime value, which is correct for everything a VarType
        // can actually stand in for at runtime.
        is MezzeType.VarType -> PrintType.Dynamic

        is MezzeType.EffectRow -> throw NotAConcreteValueType(type)
    }

    private fun buildVariant(type: MezzeType.VariantType): PrintType {
        // Bool is represented as the two-constructor variant True|False. Render
        // it as the Bool scalar so nested Bools print True/False uniformly.
        if (BuiltinTypes.isBoolVariant(type)) {
            return PrintType.Scalar(PrintType.ScalarKind.Bool)
        }
        val arms = type.constructors.map { (ctor, payload) ->
            PrintType.Arm(ctor, payload?.let { buildShape(it) })
        }
        return PrintType.Variant(arms)
    }

    private fun buildApplication(type: MezzeType.TypeApplication): PrintType {
        val args = type.args
        return when (type.constructor) {
            BuiltinTypes.LIST -> PrintType.MzList(buildShape(args[0]))
            BuiltinTypes.SEQ -> PrintType.MzSeq(buildShape(args[0]))
            BuiltinTypes.SET -> PrintType.MzSet(buildShape(args[0]))
            BuiltinTypes.SORTED_SET -> PrintType.MzSortedSet(buildShape(args[0]))
            BuiltinTypes.DEQUE -> PrintType.MzDeque(buildShape(args[0]))
            BuiltinTypes.NON_EMPTY_LIST -> PrintType.MzNonEmptyList(buildShape(args[0]))
            BuiltinTypes.MAP -> PrintType.MzMap(buildShape(args[0]), buildShape(args[1]))
            BuiltinTypes.SORTED_MAP -> PrintType.MzSortedMap(buildShape(args[0]), buildShape(args[1]))
            BuiltinTypes.OPTION -> PrintType.Variant(
                listOf(
                    PrintType.Arm(
                        BuiltinTypes.SOME,
                        PrintType.Record(listOf(PrintType.Field(BuiltinTypes.VAL_FIELD, buildShape(args[0]))))
                    ),
                    PrintType.Arm(BuiltinTypes.NONE, null)
                )
            )
            BuiltinTypes.RESULT -> PrintType.Variant(
                listOf(
                    PrintType.Arm(
                        BuiltinTypes.OK,
                        PrintType.Record(listOf(PrintType.Field(BuiltinTypes.VAL_FIELD, buildShape(args[0]))))
                    ),
                    PrintType.Arm(
                        BuiltinTypes.ERR,
                        PrintType.Record(listOf(PrintType.Field(BuiltinTypes.ERROR_FIELD, buildShape(args[1]))))
                    )
                )
            )
            // Iter has no printable runtime value distinct from its element
            // stream; treat as a list of its element for rendering purposes.
            BuiltinTypes.ITER -> PrintType.MzList(buildShape(args[0]))
            // Bytes-as-application (no args): the Bytes scalar.
            BuiltinTypes.BYTES -> PrintType.Scalar(PrintType.ScalarKind.Bytes)
            // A user-declared nominal type (newtype/record/variant/alias): the
            // TypeApplication only carries the name, so resolve to the declared
            // underlying shape and render THAT. Substitute this application's
            // type args into the resolved body is not needed for the leaf
            // shapes we render (newtype underlying / record fields / variant
            // constructors) at monomorphized use sites.
            else -> {
                // Resolve a user nominal to its declared underlying shape; the
                // underlying's own nominal guard (Newtype/Record/Variant by
                // name) handles recursion.
                val underlying = typeDefs[type.constructor]
                    ?: throw NotAConcreteValueType(type)
                buildShape(underlying)
            }
        }
    }
}
