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
            abilityConstraints.any { c -> c.typeArgs.any(predicate) || c.associatedTypeBindings.values.any(predicate) }
        is RecordType -> fields.values.any(predicate)
        is VariantType -> constructors.values.any { it != null && predicate(it) }
        is TypeApplication -> args.any(predicate)
        is ListType -> predicate(elementType)
        is MapType -> predicate(keyType) || predicate(valueType)
        is SetType -> predicate(elementType)
        is SortedMapType -> predicate(keyType) || predicate(valueType)
        is SortedSetType -> predicate(elementType)
        is SeqType -> predicate(elementType)
        is SuspendType -> predicate(valueType)
        is NewtypeType -> predicate(wrappedType)
        is VarType, is IntType, is FloatType, is StringType, is BoolType,
        is NatType, is FracType, is BytesType, is ByteBufferType,
        is CharType, is EffectRow -> false
    }

    fun transformChildren(f: (MezzeType) -> MezzeType): MezzeType = when (this) {
        is FunctionType -> copy(paramType = f(paramType), returnType = f(returnType),
            abilityConstraints = abilityConstraints.map { c -> c.copy(typeArgs = c.typeArgs.map(f), associatedTypeBindings = c.associatedTypeBindings.mapValues { (_, v) -> f(v) }) })
        is RecordType -> copy(fields = fields.mapValues { (_, v) -> f(v) })
        is VariantType -> copy(constructors = constructors.mapValues { (_, v) -> v?.let(f) })
        is TypeApplication -> copy(args = args.map(f))
        is ListType -> copy(elementType = f(elementType))
        is MapType -> copy(keyType = f(keyType), valueType = f(valueType))
        is SetType -> copy(elementType = f(elementType))
        is SortedMapType -> copy(keyType = f(keyType), valueType = f(valueType))
        is SortedSetType -> copy(elementType = f(elementType))
        is SeqType -> copy(elementType = f(elementType))
        is SuspendType -> copy(valueType = f(valueType))
        is NewtypeType -> copy(wrappedType = f(wrappedType))
        is VarType, is IntType, is FloatType, is StringType, is BoolType,
        is NatType, is FracType, is BytesType, is ByteBufferType,
        is CharType, is EffectRow -> this
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
    object NatType : MezzeType

    @Serializable
    object FracType : MezzeType

    // Character type
    @Serializable
    object CharType : MezzeType

    // Binary data type
    @Serializable
    object BytesType : MezzeType

    // Mutable byte buffer for streaming/building (internal mutation, functional API)
    @Serializable
    object ByteBufferType : MezzeType

    // Type variables (for inference)
    @Serializable
    data class VarType(val variable: TypeVar) : MezzeType

    // Record types
    @Serializable
    data class RecordType(
        val fields: Map<String, MezzeType>,
        val rowVar: RowVar? = null, // null = closed, non-null = open
        val typeName: String? = null, // Original type alias name (e.g., "Person" for `type Person = { ... }`)
        val typeHash: com.mezze.language.typedast.Hash? = null  // Content hash of the type declaration (for rename-safe identity)
    ) : MezzeType {
        val isOpen: Boolean get() = rowVar != null
        val isClosed: Boolean get() = rowVar == null

        override fun toString(): String = safeToString(
            typeName ?: "RecordType",
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
        val typeName: String? = null, // Original type alias name (e.g., "Person" for `type Person = | ... |`)
        // #194: The type-arg vector that completes the nominal identity for polymorphic
        // variants. For `type Tree 'a = | Leaf, Node { value: 'a, ... } |`, a use at
        // `Tree Int` is represented as VariantType(typeName="Tree", typeArgs=[IntType], ...)
        // with constructor payloads ALREADY substituted (so the body remains usable for
        // structural matching / ability lookups). Without this field the nominal name
        // had to be dropped at substitution time, which collapsed printed types to
        // their inlined variant bodies at outer positions (e.g. `match` scrutinees).
        val typeArgs: List<MezzeType> = emptyList(),
        val typeHash: com.mezze.language.typedast.Hash? = null,  // Content hash of the type declaration (for rename-safe identity)
        // A hard wall: foreign constructors reject regardless of scope.
        // Set true for constructs that behave like an author-written ascription:
        //   - An inline closed ascription (`let x: | A, B | = ...`)
        //   - An inferred match-scrutinee whose row var got pin-closed to the
        //     union of its match arms (a match implicitly ascribes the
        //     scrutinee to its covered arm set)
        // Alias-resolved shapes (`type Foo = | A, B |` used at a call site)
        // do NOT set this flag — their nominal identity lives on typeName +
        // typeHash, and unification's "walled" test consults those directly.
        val ascriptionWall: Boolean = false
    ) : MezzeType {
        val isOpen: Boolean get() = rowVar != null
        val isClosed: Boolean get() = rowVar == null

        override fun toString(): String = safeToString(
            typeName ?: "VariantType",
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
        // #278/F0: content hash of the DECLARATION (carries module context) for
        // rename-safe, module-distinct nominal identity — mirrors
        // VariantType.typeHash / TypeApplication.typeHash. A newtype is a nominal,
        // module-opaque type, so two newtypes are the same type only if they are
        // the same declaration. Unification keys on this hash when present (name is
        // display/fallback only), so two modules each declaring `newtype Token =
        // Token Str` no longer silently unify. `null` for builtins / pre-hash
        // construction; unification falls back to name only then.
        val typeHash: com.mezze.language.typedast.Hash? = null
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

    @Serializable
    data class ListType(val elementType: MezzeType) : MezzeType

    @Serializable
    data class MapType(val keyType: MezzeType, val valueType: MezzeType) : MezzeType

    @Serializable
    data class SetType(val elementType: MezzeType) : MezzeType

    @Serializable
    data class SortedMapType(val keyType: MezzeType, val valueType: MezzeType) : MezzeType

    @Serializable
    data class SortedSetType(val elementType: MezzeType) : MezzeType

    // Generic type application (e.g., Option Int, Result String Error, MyType 'a 'b)
    // This represents applying type arguments to a type constructor
    @Serializable
    data class TypeApplication(val constructor: String, val args: List<MezzeType>, val typeHash: com.mezze.language.typedast.Hash? = null) : MezzeType {
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
    data class SeqType(val elementType: MezzeType) : MezzeType

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

    // Suspended computation types (values with unhandled effects)
    // This represents a value of type valueType that has unhandled effects
    // Unlike FunctionType, this is not a function - it's an already-computed value
    // that cannot be used until its effects are handled
    @Serializable
    data class SuspendType(
        val valueType: MezzeType,
        val effects: EffectRow
    ) : MezzeType {
        override fun toString(): String {
            val effectList = effects.effects.take(3).joinToString(", ")
            val suffix = if (effects.effects.size > 3) ", ..." else ""
            return safeToString(
                "SuspendType",
                truncatedContent = { "${effects.effects.size} effects" },
                fullContent = { "$effectList$suffix" }
            )
        }
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