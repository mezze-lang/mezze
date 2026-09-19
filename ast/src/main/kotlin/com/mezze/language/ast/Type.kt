@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.mezze.language.ast

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Type system representations for the Mezze language.
 *
 * Contains MezzeType (sealed interface for all type representations).
 */

/**
 * The identity of a nominal type — a CLOSED, TOTAL answer to "are these the same
 * type?" that unification can decide with one exhaustive `when` and no null
 * ambiguity and no name-string comparison.
 *
 * This replaces the loose `typeName: String?` + `typeHash: Hash?` pair (4
 * undeclared null-combinations) carried by [MezzeType.RecordType],
 * [MezzeType.VariantType], [MezzeType.NewtypeType], and
 * [MezzeType.TypeApplication].
 *
 * Identity rules (see Unification):
 *  - two [Declared] → same iff `hash == hash` (a `displayName` is NEVER consulted)
 *  - two [Builtin]  → same iff `kind == kind`
 *  - two [Anonymous] → structural (fall through to field/ctor unify)
 *  - a mixed [Declared]/[Anonymous] pair is NOT a blanket reject: for TRANSPARENT
 *    nominal types (records, and non-walled `type X = ...` variant aliases) it
 *    falls through to STRUCTURAL unify — that is what makes
 *    `type Wrapper = {n:Int}; f { x = {n=5} }` type-check. Only OPAQUE walls
 *    (newtype nodes, and `ascriptionWall` variants) reject on that mismatch.
 *    The wall test is gated on node kind / `ascriptionWall`, never on identity
 *    shape alone.
 */
@Serializable
sealed interface TypeIdentity {
    /**
     * A named type from a real declaration. [hash] is the content hash of the
     * declaration (module-distinct, rename-safe) and is the ONLY identity input.
     * [displayName] is metadata for printing/errors — DOCUMENTED as never an
     * identity input (so we need not thread a hash→name resolver through the
     * type printer).
     */
    @Serializable
    data class Declared(val hash: com.mezze.language.typedast.Hash, val displayName: String) : TypeIdentity

    /**
     * A name-carried builtin (`List`/`Map`/`Set`/`Seq`/`Option`/`Result`/`Bool`/…
     * as a `TypeApplication` or `VariantType` — the name-carried form, distinct
     * from the dedicated MezzeType nodes `ListType`/`MapType`/… which have no
     * identity field). There is exactly ONE of each builtin, so its reserved
     * [name] IS its canonical identity — no content to hash. [name] is always a
     * member of [BuiltinTypes.allBuiltinTypeNames] (the single source of truth).
     */
    @Serializable
    data class Builtin(val name: String) : TypeIdentity

    /** An inline type with no declaration (`{x:Int}`, `|A,B|`). Unifies structurally. */
    @Serializable
    data object Anonymous : TypeIdentity

    /** Name for DISPLAY/errors only — NEVER an identity input. */
    fun displayNameOrNull(): String? = when (this) {
        is Declared -> displayName
        is Builtin -> name
        Anonymous -> null
    }

    /**
     * THE canonical identity factory (S3.1). Every site that turns a
     * `(name, maybe-hash)` into a [TypeIdentity] funnels through here, so the
     * Declared/Builtin/Anonymous decision lives in exactly ONE place instead of
     * being open-coded at each producer. Precedence, unchanged from the sites it
     * replaces:
     *   1. a content hash present ⇒ [Declared] (a real declaration);
     *   2. else a reserved builtin name ⇒ [Builtin] (List/Map/Bool/… — no
     *      content to hash), gated by [allowBuiltin];
     *   3. else ⇒ [Anonymous] (an undeclared name — a checker error handled
     *      upstream, or a genuinely structural type).
     */
    companion object {
        fun derive(name: String, hash: com.mezze.language.typedast.Hash?, allowBuiltin: Boolean): TypeIdentity =
            when {
                hash != null -> Declared(hash, name)
                allowBuiltin && BuiltinTypes.isBuiltinTypeName(name) -> Builtin(name)
                else -> Anonymous
            }

        /**
         * A type that always has a content hash (a real declaration whose hash is
         * a pure function of the decl — variants, aliases). Same as `derive` with a
         * non-null hash, but expresses at the call site that the hash is mandatory.
         */
        fun declared(name: String, hash: com.mezze.language.typedast.Hash): Declared =
            Declared(hash, name)
    }
}

/**
 * Rewrite [f] over the type-valued children of an effect row — the associated
 * types bound in each [MezzeType.EffectInstance] (`Exception having 'error = 'a`).
 * Effect names, rowVar and flags are not types, so they pass through untouched.
 * Used by [MezzeType.transformChildren] so a substitution reaches type vars living
 * inside a function's effect row, not just its param/return positions.
 */
private fun mapEffectRowChildren(er: MezzeType.EffectRow, f: (MezzeType) -> MezzeType): MezzeType.EffectRow {
    if (er.effectInstances.none { it.associatedTypes.isNotEmpty() }) return er
    return er.copy(effectInstances = er.effectInstances.map { inst ->
        if (inst.associatedTypes.isEmpty()) inst
        else inst.copy(associatedTypes = inst.associatedTypes.mapValues { (_, t) -> f(t) })
    }.toSet())
}

/** True if any associated type in [er]'s effect instances satisfies [predicate]. */
private fun effectRowAnyChild(er: MezzeType.EffectRow, predicate: (MezzeType) -> Boolean): Boolean =
    er.effectInstances.any { inst -> inst.associatedTypes.values.any(predicate) }

@Serializable
@JsonClassDiscriminator("typeKind")
sealed interface MezzeType {
    companion object {
        // Thread-local to track nesting depth to prevent infinite recursion in toString()
        internal val toStringDepth = ThreadLocal.withInitial { 0 }
        internal const val MAX_DEPTH = 10
        internal const val TRUNCATE_DEPTH = 3

        /**
         * Helper for safe toString() implementations with depth tracking.
         * Prevents StackOverflowError and provides consistent truncation behavior.
         */
        internal inline fun safeToString(
            typeName: String,
            truncatedContent: () -> String,
            fullContent: () -> String
        ): String {
            val depth = toStringDepth.get()
            if (depth > MAX_DEPTH) {
                return "$typeName{<max depth>}"
            }
            return try {
                toStringDepth.set(depth + 1)
                if (depth > TRUNCATE_DEPTH) {
                    "$typeName{${truncatedContent()}}"
                } else {
                    "$typeName(${fullContent()})"
                }
            } catch (e: StackOverflowError) {
                "$typeName{<StackOverflowError>}"
            } finally {
                toStringDepth.set(depth)
                if (depth == 0) toStringDepth.remove()
            }
        }
    }

    fun anyChild(predicate: (MezzeType) -> Boolean): Boolean = when (this) {
        is FunctionType -> predicate(paramType) || predicate(returnType) ||
            abilityConstraints.any { c -> c.typeArgs.any(predicate) || c.associatedTypeBindings.values.any(predicate) } ||
            (effects?.let { effectRowAnyChild(it, predicate) } ?: false)
        is RecordType -> fields.values.any(predicate)
        // #194: an applied polymorphic variant carries its args in `typeArgs`
        // (`Tree Int` = VariantType(typeArgs=[Int])); those are structural children
        // too, so a var/predicate hit there must be seen (else isConcrete/
        // containsVarType/substitution silently skip them).
        is VariantType -> constructors.values.any { it != null && predicate(it) } ||
            typeArgs.any(predicate)
        is TypeApplication -> args.any(predicate)
        is ParametricBuiltin -> typeArgs.any(predicate)
        is NewtypeType -> predicate(wrappedType)
        // A standalone effect row's associated types are children too (a function's
        // effect row is reached via the FunctionType arm above).
        is EffectRow -> effectRowAnyChild(this, predicate)
        is VarType, is IntType, is FloatType, is StringType, is BoolType,
        is FracType, is BytesType,
        is CharType -> false
    }

    fun transformChildren(f: (MezzeType) -> MezzeType): MezzeType = when (this) {
        is FunctionType -> copy(paramType = f(paramType), returnType = f(returnType),
            abilityConstraints = abilityConstraints.map { c -> c.copy(typeArgs = c.typeArgs.map(f), associatedTypeBindings = c.associatedTypeBindings.mapValues { (_, v) -> f(v) }) },
            effects = effects?.let { mapEffectRowChildren(it, f) })
        is RecordType -> copy(fields = fields.mapValues { (_, v) -> f(v) })
        // #194: rewrite `typeArgs` in step with the constructor payloads, else a
        // substitution leaves `typeArgs` stale (`['a]`) against already-substituted
        // payloads (`Int`) — an inconsistent applied variant.
        is VariantType -> copy(
            constructors = constructors.mapValues { (_, v) -> v?.let(f) },
            typeArgs = typeArgs.map(f)
        )
        is TypeApplication -> copy(args = args.map(f))
        is ParametricBuiltin -> rebuild(typeArgs.map(f))
        is NewtypeType -> copy(wrappedType = f(wrappedType))
        is EffectRow -> mapEffectRowChildren(this, f)
        is VarType, is IntType, is FloatType, is StringType, is BoolType,
        is FracType, is BytesType,
        is CharType -> this
    }

    // Primitive types
    @Serializable
    object IntType : MezzeType

    @Serializable
    object FloatType : MezzeType

    @Serializable
    object StringType : MezzeType

    @Serializable
    object BoolType : MezzeType

    // Enhanced numeric types
    @Serializable
    object FracType : MezzeType

    // Character type
    @Serializable
    object CharType : MezzeType

    // Binary data type
    @Serializable
    object BytesType : MezzeType

    // Type variables (for inference)
    @Serializable
    data class VarType(val variable: TypeVar) : MezzeType

    // Record types
    @Serializable
    data class RecordType(
        val fields: Map<String, MezzeType>,
        val rowVar: RowVar? = null, // null = closed, non-null = open
        val identity: TypeIdentity = TypeIdentity.Anonymous
    ) : MezzeType {
        val isOpen: Boolean get() = rowVar != null
        val isClosed: Boolean get() = rowVar == null

        override fun toString(): String = safeToString(
            identity.displayNameOrNull() ?: "RecordType",
            truncatedContent = { "${fields.size} fields" },
            // Show field names WITH their types (was names only, so `{x: Int}` and
            // `{x: Str}` printed identically). safeToString recurses/truncates the
            // field types past TRUNCATE_DEPTH.
            fullContent = {
                val shown = fields.entries.take(3).joinToString(", ") { (k, v) -> "$k: $v" }
                val more = if (fields.size > 3) ", ..." else ""
                val open = if (rowVar != null) ", ..." else ""
                "{$shown$more$open}"
            }
        )
    }

    // Variant types
    @Serializable
    data class VariantType(
        val constructors: Map<String, MezzeType?>, // null = no data, MezzeType = constructor data
        val rowVar: RowVar? = null, // null = closed, non-null = open
        // #194: The type-arg vector that completes the nominal identity for polymorphic
        // variants. For `type Tree 'a = | Leaf, Node { value: 'a, ... } |`, a use at
        // `Tree Int` is represented as VariantType(identity=Declared(..,"Tree"), typeArgs=[IntType], ...)
        // with constructor payloads ALREADY substituted (so the body remains usable for
        // structural matching / ability lookups). Without this field the nominal name
        // had to be dropped at substitution time, which collapsed printed types to
        // their inlined variant bodies at outer positions (e.g. `match` scrutinees).
        val typeArgs: List<MezzeType> = emptyList(),
        val identity: TypeIdentity = TypeIdentity.Anonymous,
        // A hard wall: foreign constructors reject regardless of scope.
        // Set true for constructs that behave like an author-written ascription:
        //   - An inline closed ascription (`let x: | A, B | = ...`)
        //   - An inferred match-scrutinee whose row var got pin-closed to the
        //     union of its match arms (a match implicitly ascribes the
        //     scrutinee to its covered arm set)
        // Alias-resolved shapes (`type Foo = | A, B |` used at a call site)
        // do NOT set this flag — their nominal identity lives on [identity],
        // and unification's "walled" test consults that directly.
        val ascriptionWall: Boolean = false
    ) : MezzeType {
        val isOpen: Boolean get() = rowVar != null
        val isClosed: Boolean get() = rowVar == null

        override fun toString(): String = safeToString(
            identity.displayNameOrNull() ?: "VariantType",
            truncatedContent = { "${constructors.size} constructors" },
            fullContent = { "${constructors.keys.take(3)}${if (constructors.size > 3) "..." else ""}" }
        )
    }

    // Newtype — compile-time wrapper, erased at runtime.
    // newtype UserId = Int → NewtypeType("UserId", "UserId", IntType)
    // newtype Point = Point {x: Float, y: Float} → NewtypeType("Point", "Point", RecordType({x: Float, y: Float}))
    // At runtime, UserId 42 is just 42. Point {x=1.0, y=2.0} is just {x=1.0, y=2.0}.
    // Does NOT auto-derive Eq/Ord/Codec — requires explicit impl.
    // Constructor is private to defining module (opaque type).
    @Serializable
    data class NewtypeType(
        val name: String,            // Type name: "UserId"
        val constructor: String,     // Constructor name: "UserId"
        val wrappedType: MezzeType,  // Inner type: IntType, RecordType, etc.
        // #278/F0: nominal identity of the DECLARATION (carries module context) for
        // rename-safe, module-distinct identity. A newtype is a nominal,
        // module-opaque type, so two newtypes are the same type only if they are
        // the same declaration. Unification keys on the Declared hash when present
        // (name is display/fallback only), so two modules each declaring `newtype
        // Token = Token Str` no longer silently unify. Anonymous for builtins /
        // pre-hash construction; unification falls back to name only then.
        val identity: TypeIdentity = TypeIdentity.Anonymous
    ) : MezzeType {
        override fun toString(): String = safeToString(
            name,
            truncatedContent = { "wraps ${wrappedType::class.simpleName}" },
            fullContent = { name }
        )
    }

    // Built-in core types (always available)
    // Note: Option and Result are now regular variant types, not special AST types
    // They are defined as built-in variants:
    //   Option 'a = Some { val: 'a } | None
    //   Result 'a 'e = Ok { val: 'a } | Err { error: 'e }

    /**
     * A builtin type constructor parameterized by type arguments — the collection
     * family (Seq/List/Set/SortedSet, keyed by one arg; Map/SortedMap, keyed by
     * two). Exposes its args positionally ([typeArgs]) and reconstructs itself from
     * new args ([rebuild]), so a structural pass folds the whole family through ONE
     * `is ParametricBuiltin` arm instead of six identical `is SeqType`/`is MapType`
     * copies. This is an ACCESSOR, not a demotion: each type stays a distinct
     * primitive `MezzeType` with its own hash tag and serialized identity — grouping
     * them here changes no runtime shape and no content hash. Only use it where the
     * per-collection arms are genuinely uniform (recurse on args, rebuild same
     * constructor); sites that special-case one collection keep their explicit arms.
     * TypeApplication/VariantType are deliberately NOT members — they carry a
     * nominal identity and are special-cased everywhere.
     */
    sealed interface ParametricBuiltin : MezzeType {
        /** Type arguments in constructor order (Map/SortedMap: [key, value]). */
        val typeArgs: List<MezzeType>
        /** Reconstruct this same constructor with [args] (same arity as [typeArgs]). */
        fun rebuild(args: List<MezzeType>): ParametricBuiltin
    }

    @Serializable
    data class ListType(val elementType: MezzeType) : ParametricBuiltin {
        override val typeArgs: List<MezzeType> get() = listOf(elementType)
        override fun rebuild(args: List<MezzeType>): ParametricBuiltin = ListType(args[0])
    }

    @Serializable
    data class MapType(val keyType: MezzeType, val valueType: MezzeType) : ParametricBuiltin {
        override val typeArgs: List<MezzeType> get() = listOf(keyType, valueType)
        override fun rebuild(args: List<MezzeType>): ParametricBuiltin = MapType(args[0], args[1])
    }

    @Serializable
    data class SetType(val elementType: MezzeType) : ParametricBuiltin {
        override val typeArgs: List<MezzeType> get() = listOf(elementType)
        override fun rebuild(args: List<MezzeType>): ParametricBuiltin = SetType(args[0])
    }

    // Generic type application (e.g., Option Int, Result String Error, MyType 'a 'b)
    // This represents applying type arguments to a type constructor
    @Serializable
    data class TypeApplication(val constructor: String, val args: List<MezzeType>, val identity: TypeIdentity = TypeIdentity.Anonymous) : MezzeType {
        init {
            // #312 (reference-everywhere): a name-carrying identity IS the type's
            // reference, so `constructor` must never disagree with it — the name is
            // one fact, held in `identity`, and `constructor` merely mirrors it.
            // This makes a name-compare on a Declared/Builtin type unable to diverge
            // from identity BY CONSTRUCTION (any producer that set a diverging name
            // fails loudly here instead of leaking a name the identity system can't
            // see). Anonymous is exempt: its transient undeclared-name case is never
            // read for identity (UnknownTypeValidator reports off the parse tree),
            // and its name is display-only.
            val idName = identity.displayNameOrNull()
            require(identity is TypeIdentity.Anonymous || idName == constructor) {
                "TypeApplication.constructor ('$constructor') disagrees with its identity name ('$idName'): " +
                    "the name is carried by identity; constructor must mirror it (#312)."
            }
        }
        override fun toString(): String = safeToString(
            "TypeApplication",
            truncatedContent = { "$constructor, ${args.size} args" },
            // Include the args at shallow depth — `constructor` alone dropped
            // them entirely, so `List Int` and `List Str` printed identically.
            // safeToString still truncates the args recursively past TRUNCATE_DEPTH.
            fullContent = { if (args.isEmpty()) constructor else "$constructor ${args.joinToString(" ")}" }
        )
    }

    // Seq types (for [] syntax)
    @Serializable
    data class SeqType(val elementType: MezzeType) : ParametricBuiltin {
        override val typeArgs: List<MezzeType> get() = listOf(elementType)
        override fun rebuild(args: List<MezzeType>): ParametricBuiltin = SeqType(args[0])
    }

    // Function types
    @Serializable
    data class FunctionType(
        val paramType: MezzeType,
        val returnType: MezzeType,
        val effects: EffectRow? = null,  // null = pure function, non-null = effectful function
        val abilityConstraints: List<FunctionAbilityConstraint> = emptyList()  // Ability constraints for this function
    ) : MezzeType {
        override fun toString(): String = safeToString(
            "FunctionType",
            truncatedContent = { "..." },
            fullContent = {
                val effectsStr = if (effects != null) {
                    val effectList = effects.effects.take(3).joinToString(", ")
                    val suffix = if (effects.effects.size > 3) ", ..." else ""
                    " <$effectList$suffix>"
                } else ""
                "${if (effects != null) "effectful" else "pure"}$effectsStr"
            }
        )
    }

    /**
     * An effect instance with its associated type bindings.
     * Example: Exception with 'error = String
     */
    @Serializable
    data class EffectInstance(
        val name: String,
        val associatedTypes: Map<String, MezzeType> = emptyMap()
    ) {
        override fun toString(): String {
            return if (associatedTypes.isEmpty()) {
                name
            } else {
                val bindings = associatedTypes.entries.joinToString(", ") { (k, v) ->
                    "'$k = $v"
                }
                "$name having $bindings"
            }
        }

    }

    // Effect rows
    @Serializable
    data class EffectRow(
        val effects: Set<String>,
        val effectInstances: Set<EffectInstance> = emptySet(), // Effects with associated type info
        val effectHashes: Map<String, com.mezze.language.typedast.Hash> = emptyMap(),  // effectName → effectDeclHash
        val rowVar: RowVar? = null, // null = closed, non-null = open
        val performed: Boolean = false, // true = effects have been performed via 'perform'
        // Effects declared on this callback row that are HANDLED by the
        // enclosing function (i.e., appear in the callback row but not in
        // the return row). Unification silently absorbs these from the
        // actual callback's row without propagating through the shared
        // row var. Example: in `spawn : {f: {} -> 'a <Exception, ...>} ->
        // Promise 'a <Async, ...>`, the callback row's `caughtByAncestor`
        // is `{Exception}` — spawn catches Exception at runtime.
        val caughtByAncestor: Set<String> = emptySet()
    ) : MezzeType {
        val isOpen: Boolean get() = rowVar != null
        val isClosed: Boolean get() = rowVar == null

        /** True if this is truly pure - no effects, not open, nothing performed */
        val isPure: Boolean get() = effects.isEmpty() && effectInstances.isEmpty() && rowVar == null && !performed

        /** Get all effect names (from both simple effects and effect instances) */
        val allEffectNames: Set<String> get() = effects + effectInstances.map { it.name }.toSet()

        /** Get associated types for an effect by name */
        fun getAssociatedTypes(effectName: String): Map<String, MezzeType>? {
            return effectInstances.find { it.name == effectName }?.associatedTypes
        }

        /** Create a new EffectRow with an effect instance added/updated */
        fun withEffectInstance(instance: EffectInstance): EffectRow {
            // Remove from simple effects if present, add to instances
            val newEffects = effects - instance.name
            val newInstances = effectInstances.filterNot { it.name == instance.name }.toSet() + instance
            return copy(effects = newEffects, effectInstances = newInstances)
        }

        override fun toString(): String {
            // Combine simple effects and effect instances for display
            val simpleEffects = effects.take(3).toList()
            val instanceStrs = effectInstances.take(3).map { it.toString() }
            val allEffects = simpleEffects + instanceStrs
            val effectList = allEffects.take(5).joinToString(", ")
            val totalCount = effects.size + effectInstances.size
            val suffix = when {
                performed && totalCount > 0 -> ", *"
                performed -> "*"
                totalCount > 5 -> ", ..."
                else -> ""
            }
            return safeToString(
                "EffectRow",
                truncatedContent = { if (performed) "$totalCount effects, *" else "$totalCount effects" },
                fullContent = { "$effectList$suffix" }
            )
        }

        companion object {
            /** Create an EffectRow from simple effect names (backward compatible) */
            fun simple(effects: Set<String>, rowVar: RowVar? = null, performed: Boolean = false, effectHashes: Map<String, com.mezze.language.typedast.Hash> = emptyMap()): EffectRow {
                return EffectRow(effects = effects, effectInstances = emptySet(), effectHashes = effectHashes, rowVar = rowVar, performed = performed)
            }

            /** Create an EffectRow with a single effect instance (with associated types) */
            fun withInstance(instance: EffectInstance, rowVar: RowVar? = null, performed: Boolean = false, effectHashes: Map<String, com.mezze.language.typedast.Hash> = emptyMap()): EffectRow {
                return EffectRow(effects = emptySet(), effectInstances = setOf(instance), effectHashes = effectHashes, rowVar = rowVar, performed = performed)
            }
        }
    }

    // Function-level ability constraints
    @Serializable
    data class FunctionAbilityConstraint(
        val abilityName: String,
        val typeArgs: List<MezzeType> = emptyList(),  // Type arguments for the ability
        val associatedTypeBindings: Map<String, MezzeType> = emptyMap(),  // Associated type bindings (e.g., 'item = Int)
        val abilityHash: com.mezze.language.typedast.Hash? = null  // Content hash of the ability declaration
    ) {
        /** True if this constraint has any associated type bindings specified */
        val hasAssociatedTypes: Boolean get() = associatedTypeBindings.isNotEmpty()
    }

}
/**
 * True iff this type still holds an unresolved type VARIABLE anywhere in it.
 *
 * The complement — "fully concrete" — is what several passes need before they may
 * treat a declared shape as this value's actual shape, or specialize on it. It was
 * written out separately in `AbilityChecker`, in `TypedASTCompiler` (twice, at
 * different depths) and inline at a handful of call sites; they disagree only by
 * accident, so it lives here on the type itself.
 *
 * Traversal goes through [MezzeType.anyChild], which is the one walker that knows
 * about every child position — including a variant's `typeArgs` and an effect
 * row's associated types, both of which a hand-rolled recursion has forgotten
 * before.
 */
/**
 * True iff this type IS, or contains, a variable the compiler minted because it
 * could not determine a type — see [TypeVar.isPlaceholder].
 *
 * Distinct from [holdsTypeVar] on purpose. A pass that defers to a variable
 * (unification, generalization) wants that one. A pass that would GUESS from a
 * variable — pick an impl, pick an operation, pick a representation — wants this
 * one, and must not guess when it answers true.
 */
fun MezzeType.holdsUnknownPlaceholder(): Boolean =
    (this is MezzeType.VarType && variable.isPlaceholder) || anyChild { it.holdsUnknownPlaceholder() }

fun MezzeType.holdsTypeVar(): Boolean =
    this is MezzeType.VarType || anyChild { it.holdsTypeVar() }
